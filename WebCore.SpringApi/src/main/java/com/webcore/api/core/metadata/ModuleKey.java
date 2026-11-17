package com.webcore.api.core.metadata;

import java.util.Locale;

/** Canonical lookup key for a module/submodule pair. */
public record ModuleKey(String moduleId, String subModule) {
    public ModuleKey {
        moduleId = normalize(moduleId);
        subModule = normalize(subModule);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
