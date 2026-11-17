package com.webcore.api.config;

import java.security.interfaces.RSAPublicKey;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import com.webcore.api.auth.AuthSystemSettingsService;
import com.webcore.api.auth.OAuthProperties;
import com.webcore.api.auth.DomainSessionValidationFilter;
import com.webcore.api.auth.RsaPemKeys;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, CookieBearerTokenResolver cookieBearerTokenResolver,
             AllowAnonymousAuthorizationManager allowAnonymousAuthorizationManager,
            DomainSessionValidationFilter domainSessionValidationFilter,
            @Value("${WEBCORE_PROMETHEUS_PUBLIC:false}") boolean prometheusPublic) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/health/**", "/actuator/health/**", "/api/auth/login", "/api/auth/refresh").permitAll();
                    authorize.requestMatchers("/api/oauth-test/documents")
                            .access(allowAnonymousAuthorizationManager);
                    if (prometheusPublic) {
                        authorize.requestMatchers("/actuator/prometheus").permitAll();
                    }
                    
                    authorize
                        .anyRequest()
                        .access(allowAnonymousAuthorizationManager);
                })
                .oauth2ResourceServer(oauth2 -> oauth2.bearerTokenResolver(cookieBearerTokenResolver).jwt(Customizer.withDefaults()))
                .addFilterAfter(domainSessionValidationFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(AuthSystemSettingsService authSystemSettingsService, OAuthProperties oauthProperties) throws Exception {
        AuthSystemSettingsService.Settings settings = authSystemSettingsService.load();
        {
            // Keep validating existing WebCore user JWTs against the configured
            // AUTH_SYSTEM public key, as before OAuth support was added.
            RSAPublicKey rsaPublicKey = RsaPemKeys.readPublicKey(settings.publicKeyPem());
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaPublicKey).build();
            OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<java.util.List<String>>(
                    "aud", audiences -> audiences != null && audiences.contains(settings.audience()));
            OAuth2TokenValidator<Jwt> issuerValidator = jwt -> {
                // Legacy WebCore user tokens use the non-URL issuer "web-core".
                // Jwt#getIssuer() in Spring Security 7 requires a URL and
                // throws for that legacy token, so read the signed raw claim.
                String tokenIssuer = jwt.getClaimAsString("iss");
                if (settings.issuer().equals(tokenIssuer) || oauthProperties.issuer().equals(tokenIssuer)) {
                    return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
                }
                logger.warn("Rejected JWT issuer '{}'; allowed WebCore issuer '{}' and OAuth issuer '{}'.",
                        tokenIssuer, settings.issuer(), oauthProperties.issuer());
                return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                        new org.springframework.security.oauth2.core.OAuth2Error("invalid_token", "Unexpected token issuer.", null));
            };
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
            return decoder;
        }
    }
}
