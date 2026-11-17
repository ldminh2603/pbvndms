package com.webcore.api.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, OAuthProperties.class, PasswordResetProperties.class, DomainSessionProperties.class})
public class AuthConfiguration {
}
