package com.webcore.api.auth;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class OAuthAuthorizationServerConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        http
                .securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, customizer -> { })
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
        return http.build();
    }

    @Bean
    OAuth2AuthorizationService authorizationService(RegisteredClientRepository clients) {
        return new InMemoryOAuth2AuthorizationService();
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(OAuthProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.issuer()).build();
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> oauthAccessTokenCustomizer(AuthSystemSettingsService settingsService) {
        return context -> {
            if (org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims().audience(List.of(settingsService.load().audience()));
                context.getClaims().claim("client_id", context.getRegisteredClient().getClientId());
            }
        };
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(AuthSystemSettingsService settingsService) throws Exception {
        AuthSystemSettingsService.Settings settings = settingsService.load();
        RSAPrivateKey privateKey = RsaPemKeys.readPrivateKey(settings.privateKeyPem());
        RSAPublicKey publicKey = RsaPemKeys.publicKeyFromPrivateKey(privateKey);
        RSAKey key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("webcore-rsa").build();
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }
}
