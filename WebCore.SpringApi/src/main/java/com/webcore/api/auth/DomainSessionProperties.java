package com.webcore.api.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("webcore.domain-session")
public record DomainSessionProperties(String serviceUsername, String servicePassword, String baseDn) { }
