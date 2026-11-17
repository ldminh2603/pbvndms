package com.webcore.api.auth;

public record ApiSecurityRule(
        long id,
        String pathPattern,
        String httpMethod,
        boolean allowAnonymous,
        boolean active
) {
}