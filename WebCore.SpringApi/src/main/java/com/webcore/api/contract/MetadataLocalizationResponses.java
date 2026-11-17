package com.webcore.api.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/** Stable localization envelopes; form keys remain metadata-defined. */
public final class MetadataLocalizationResponses {
    private MetadataLocalizationResponses() { }

    public record FormLocalizationResponse(@JsonValue Map<String, Object> values) {
        public FormLocalizationResponse {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values == null ? Map.of() : values));
        }
    }

    public record MenuLocalizationResponse(@JsonProperty("Menus") Map<String, MenuText> menus) {
        public MenuLocalizationResponse {
            menus = Collections.unmodifiableMap(new LinkedHashMap<>(menus == null ? Map.of() : menus));
        }
    }

    public record MenuText(@JsonProperty("Caption") String caption, @JsonProperty("Icon") String icon) { }
}
