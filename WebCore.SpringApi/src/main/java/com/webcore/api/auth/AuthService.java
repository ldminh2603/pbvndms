package com.webcore.api.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AuthRepository authRepository;
    private final JwtEncoder jwtEncoder;
    private final AuthProperties properties;
    private final AuthSystemSettingsService authSystemSettingsService;
    private final DomainAuthenticationService domainAuthenticationService;
    private final AuthSecurityAuditService securityAudit;
    private final ObjectMapper objectMapper;

    public AuthService(AuthRepository authRepository, JwtEncoder jwtEncoder, AuthProperties properties,
                       AuthSystemSettingsService authSystemSettingsService,
                       DomainAuthenticationService domainAuthenticationService, AuthSecurityAuditService securityAudit, ObjectMapper objectMapper) {
        this.authRepository = authRepository;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.authSystemSettingsService = authSystemSettingsService;
        this.domainAuthenticationService = domainAuthenticationService;
        this.securityAudit = securityAudit;
        this.objectMapper = objectMapper;
    }

    public LoginResult login(String email, String password, String remoteIp) {
        AuthRepository.UserAuthContext context;
        String domainPasswordLastSet = null;
        String principal = domainAuthenticationService.isEnabled()
                ? domainAuthenticationService.canonicalPrincipal(email)
                : email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        securityAudit.assertNotLocked(principal, remoteIp);
        try {
            if (domainAuthenticationService.isEnabled()) {
                domainAuthenticationService.verifyCredentials(principal, password);
                domainPasswordLastSet = domainAuthenticationService.passwordLastSetFresh(principal);
                context = authRepository.loadByEmail(principal, "domain");
            } else {
                context = authRepository.loadForLogin(principal, password, "local");
            }
            validateAssignments(context);
        } catch (AuthRepository.InvalidCredentialsException exception) {
            securityAudit.loginFailed(principal, remoteIp);
            throw exception;
        }
        securityAudit.loginSucceeded(principal, remoteIp);
        return new LoginResult(context.user(), createAccessToken(context, null, domainPasswordLastSet), createRefreshToken(context, domainPasswordLastSet));
    }

    public boolean isDomainLoginEnabled() {
        return domainAuthenticationService.isEnabled();
    }

    public void logout(String email, String remoteIp) { securityAudit.logout(email, remoteIp); }

    public String refresh(Jwt refreshJwt) {
        // A new browser tab can legitimately have no refresh cookie yet (or an
        // expired/cleared cookie).  Treat that exactly as an unauthenticated
        // request instead of dereferencing a null principal and returning 500.
        if (refreshJwt == null) {
            throw new AuthRepository.InvalidCredentialsException();
        }
        if (!"refresh".equals(refreshJwt.getClaimAsString("type"))) {
            throw new AuthRepository.InvalidCredentialsException();
        }
        TokenPayload payload = readPayload(refreshJwt);
        assertDomainSessionCurrent(payload);
        AuthRepository.UserAuthContext context = authRepository.loadByEmail(payload.email(), payload.type());
        validateAssignments(context);
        return createAccessToken(context, payload, payload.domainPasswordLastSet());
    }

    public CurrentUser currentUser(Jwt accessJwt) {
        TokenPayload payload = tokenPayload(accessJwt);

        // The signed token intentionally contains authorization claims only.
        // Read the persisted profile setting for a fresh browser tab so the
        // client can restore its exact DEFLANG language id.
        AuthRepository.AuthUser persistedUser = authRepository.loadByEmail(payload.email(), payload.type()).user();
        String avatarUrl = persistedUser.avatarUrl() == null || persistedUser.avatarUrl().isBlank()
                ? null : persistedUser.avatarUrl().trim();
        return new CurrentUser(payload.version(), payload.id(), payload.email(), payload.ip(), payload.allowPublic(), payload.type(),
                payload.isPrivilegedAccount(), payload.displayName(), payload.roles(), payload.components(), persistedUser.setting(), avatarUrl);
    }

    /** Parsed, verified access-token payload for authorization-aware API endpoints. */
    public TokenPayload tokenPayload(Jwt jwt) {
        return readPayload(jwt);
    }

    public void assertDomainSessionCurrent(Jwt jwt) {
        // Only WebCore browser/user JWTs have the signed payload that carries
        // the domain-password state. OAuth access tokens represent a client
        // application and must not enter user-session validation.
        if (jwt.getClaimAsString("payload") == null) {
            return;
        }
        assertDomainSessionCurrent(readPayload(jwt));
    }

    private void assertDomainSessionCurrent(TokenPayload payload) {
        if (!domainAuthenticationService.isEnabled() || !"domain".equalsIgnoreCase(payload.type())) {
            return;
        }

        if (payload.domainPasswordLastSet() == null || payload.domainPasswordLastSet().isBlank()
                || !payload.domainPasswordLastSet().equals(domainAuthenticationService.passwordLastSet(payload.email()))) {
            throw new AuthRepository.InvalidCredentialsException();
        }
    }

    private String createAccessToken(AuthRepository.UserAuthContext context, TokenPayload previousPayload, String domainPasswordLastSet) {
        TokenPayload payload = new TokenPayload(
                previousPayload == null ? 1 : previousPayload.version(), String.valueOf(context.user().id()), context.user().email(),
                previousPayload == null ? "" : previousPayload.ip(), context.user().allowPublic(), context.user().type(),
                context.user().isPrivilegedAccount(), context.user().displayName(), context.roles(), context.components(), domainPasswordLastSet);
        return encode(payload, false, authSystemSettingsService.load().accessTokenTtl());
    }

    private String createRefreshToken(AuthRepository.UserAuthContext context, String domainPasswordLastSet) {
        TokenPayload payload = new TokenPayload(1, String.valueOf(context.user().id()), context.user().email(), "", false,
                context.user().type(), false, null, List.of(), List.of(), domainPasswordLastSet);
        return encode(payload, true, authSystemSettingsService.load().refreshTokenTtl());
    }

    private String encode(TokenPayload payload, boolean refresh, java.time.Duration ttl) {
        try {
            Instant now = Instant.now();
            AuthSystemSettingsService.Settings settings = authSystemSettingsService.load();
            JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(settings.issuer()).audience(List.of(settings.audience()))
                    .subject(payload.id()).id(UUID.randomUUID().toString()).issuedAt(now).notBefore(now).expiresAt(now.plus(ttl))
                    .claim("payload", objectMapper.writeValueAsString(payload));
            if (refresh) {
                claims.claim("type", "refresh");
            } else {
                claims.claim("email", payload.email());
            }
            return jwtEncoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create token.", exception);
        }
    }

    private TokenPayload readPayload(Jwt jwt) {
        try {
            return objectMapper.readValue(jwt.getClaimAsString("payload"), new TypeReference<>() {});
        } catch (Exception exception) {
            throw new AuthRepository.InvalidCredentialsException();
        }
    }

    private void validateAssignments(AuthRepository.UserAuthContext context) {
        if (!context.user().isPrivilegedAccount() && context.roles().isEmpty()) {
            throw new AuthConfigurationException("AUTH_ROLE_NOT_ASSIGNED", "No role has been assigned. Please contact the administrator.");
        }
        if (!context.user().isPrivilegedAccount() && context.components().isEmpty()) {
            throw new AuthConfigurationException("AUTH_COMPONENT_NOT_ASSIGNED", "No component has been assigned. Please contact the administrator.");
        }
    }

    public record TokenPayload(int version, String id, String email, String ip, boolean allowPublic, String type,
                               boolean isPrivilegedAccount, String displayName, List<AuthRepository.RoleRef> roles, List<String> components,
                               String domainPasswordLastSet) { }
    public record CurrentUser(int version, String id, String email, String ip, boolean allowPublic, String type,
                              boolean isPrivilegedAccount, String displayName, List<AuthRepository.RoleRef> roles,
                              List<String> components, Object setting, String avatarUrl) { }
    public record LoginResult(AuthRepository.AuthUser user, String accessToken, String refreshToken) { }
    public static final class AuthConfigurationException extends RuntimeException {
        private final String code;

        public AuthConfigurationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }
}
