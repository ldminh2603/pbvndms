package com.webcore.api.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("webcore.password-reset")
public record PasswordResetProperties(String mailProvider, String baseUrl, Duration tokenTtl, Smtp smtp, Ses ses) {
    public record Smtp(String host, int port, String username, String password, String from, String replyTo, boolean starttlsEnabled) { }
    public record Ses(String region, String from, String replyTo, String accessKeyId, String secretAccessKey) { }
}
