package com.webcore.api.contract;

import java.util.List;

/** Typed search result contract for the module launcher. */
public record ModuleSearchResponse(String component, String query, String lang,
        ModuleSearchItem directModule, List<Section> sections, int total) {
    public record Section(String key, String title, List<ModuleSearchItem> results) { }
    public record ModuleSearchItem(String moduleId, String menuId, String param, String moduleName,
            String displayName, String icon, String matchType, String matchField, int score) { }
}
