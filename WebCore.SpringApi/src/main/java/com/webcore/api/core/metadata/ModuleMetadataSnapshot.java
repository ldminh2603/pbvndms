package com.webcore.api.core.metadata;

import java.util.Map;

import com.webcore.api.contract.ModuleFormInfoResponse;

/** Immutable, atomically published module metadata snapshot kept in JVM RAM. */
public record ModuleMetadataSnapshot(
        Map<ModuleKey, ModuleFormInfo> forms,
        Map<ModuleLocalizationKey, ModuleLocalization> localizations) {

    public ModuleMetadataSnapshot {
        forms = Map.copyOf(forms);
        localizations = Map.copyOf(localizations);
    }

    public static ModuleMetadataSnapshot empty() {
        return new ModuleMetadataSnapshot(Map.of(), Map.of());
    }

    public ModuleFormInfoResponse find(String moduleId, String subModule) {
        ModuleFormInfo value = forms.get(new ModuleKey(moduleId, subModule));
        return value == null ? null : value.response();
    }

    public Map<String, Object> findRuntime(String moduleId, String subModule) {
        ModuleFormInfo value = forms.get(new ModuleKey(moduleId, subModule));
        return value == null ? null : value.runtimeContract();
    }

    public Map<String, Object> findLocalization(String moduleId, String subModule, String languageId) {
        ModuleLocalization value = localizations.get(new ModuleLocalizationKey(moduleId, subModule, languageId));
        return value == null ? null : value.contract();
    }
}
