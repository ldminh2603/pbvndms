package com.webcore.api.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "webcore.auth-cookie")
public record AuthCookieProperties(boolean secure, String sameSite, String path) { }
