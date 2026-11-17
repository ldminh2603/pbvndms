package com.webcore.api.core.metadata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable localized labels/captions for one module/submodule/language. */
public record ModuleLocalization(Map<String, Object> contract) {
    public ModuleLocalization {
        contract = Collections.unmodifiableMap(new LinkedHashMap<>(contract));
    }
}
