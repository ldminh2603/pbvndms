package com.webcore.api.core.metadata;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

import com.webcore.api.contract.ModuleFormInfoResponse;

/**
 * Immutable runtime form metadata.  The payload keeps the .NET/PascalCase
 * FormInfo contract while the cache itself is strongly typed by ModuleKey.
 */
public record ModuleFormInfo(ModuleFormInfoResponse response, Map<String, Object> runtimeContract) {
    public ModuleFormInfo {
        runtimeContract = Collections.unmodifiableMap(new LinkedHashMap<>(runtimeContract));
    }
}
