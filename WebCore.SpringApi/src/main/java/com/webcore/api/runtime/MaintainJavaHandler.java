package com.webcore.api.runtime;

import java.util.Map;

import com.webcore.api.auth.AuthService;

/**
 * Extension point for a MODMAINTAIN.CommitHandler prefixed with {@code JAVA:}.
 * Database routine handlers continue through {@link MaintainCommitService}'s
 * normal stored-procedure path.
 */
public interface MaintainJavaHandler {
    /** Identifier after the {@code JAVA:} prefix in MODMAINTAIN.CommitHandler. */
    String name();

    Map<String, Object> commit(String moduleId, String subModule, Map<String, Object> form,
                               Map<String, Object> request, AuthService.TokenPayload user);
}
