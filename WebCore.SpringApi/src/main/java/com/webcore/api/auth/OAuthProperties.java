package com.webcore.api.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OAuth issuer must be a stable, absolute URL; the legacy WebCore JWT issuer need not be. */
@ConfigurationProperties(prefix = "webcore.oauth")
public record OAuthProperties(String issuer) {
}
