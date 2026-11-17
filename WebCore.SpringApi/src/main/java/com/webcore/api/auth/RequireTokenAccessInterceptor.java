package com.webcore.api.auth;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RequireTokenAccessInterceptor implements HandlerInterceptor {
    private final AuthService authService;

    public RequireTokenAccessInterceptor(AuthService authService) { this.authService = authService; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        RequireTokenAccess required = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequireTokenAccess.class);
        if (required == null) required = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequireTokenAccess.class);
        if (required == null) return true;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new AccessDeniedException("A verified user or OAuth client token is required.");
        }
        Jwt jwt = jwtAuthentication.getToken();
        if (isAllowedUser(jwt, required) || isAllowedClient(jwtAuthentication, jwt, required)) return true;
        throw new AccessDeniedException("The token does not have a required role or OAuth scope.");
    }

    private boolean isAllowedUser(Jwt jwt, RequireTokenAccess required) {
        if (!jwt.hasClaim("payload")) return false;
        if (required.allowAnyUserJwt()) return true;
        Set<String> requiredRoles = normalized(required.userRoles());
        return !requiredRoles.isEmpty() && authService.tokenPayload(jwt).roles().stream()
                .map(AuthRepository.RoleRef::name).map(this::normalized).anyMatch(requiredRoles::contains);
    }

    private boolean isAllowedClient(JwtAuthenticationToken authentication, Jwt jwt, RequireTokenAccess required) {
        if (!jwt.hasClaim("client_id")) return false;
        Set<String> requiredScopes = normalized(required.clientScopes());
        return !requiredScopes.isEmpty() && authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).filter(authority -> authority.startsWith("SCOPE_"))
                .map(authority -> normalized(authority.substring("SCOPE_".length())))
                .anyMatch(requiredScopes::contains);
    }

    private Set<String> normalized(String[] values) {
        return Arrays.stream(values).map(this::normalized).filter(value -> !value.isBlank()).collect(Collectors.toSet());
    }

    private String normalized(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
