package com.webcore.api.core.metadata;

import java.util.Locale;

/** Canonical lookup key for one localized form payload. */
public record ModuleLocalizationKey(String moduleId, String subModule, String languageId) {
    public ModuleLocalizationKey {
        moduleId = normalize(moduleId);
        subModule = normalize(subModule);
        languageId = normalize(languageId);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
