package com.webcore.api.controller;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.ModuleSearchResponse;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.runtime.ModuleRuntimeCache;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/module")
public class ModuleSearchController {
    private final AuthService auth;
    private final CoreMetadataService core;
    private final ModuleRuntimeCache cache;

    public ModuleSearchController(AuthService auth, CoreMetadataService core, ModuleRuntimeCache cache) {
        this.auth = auth;
        this.core = core;
        this.cache = cache;
    }

    /** Permission projection is also available for Legacy-only modules that have no Core runtime Module record. */
    @GetMapping("/{moduleId}/permission")
    public CoreMetadataService.UiPermission permission(@PathVariable String moduleId, @AuthenticationPrincipal Jwt jwt) {
        return core.uiPermission(auth.tokenPayload(jwt), moduleId);
    }

    @GetMapping("/search")
    public ModuleSearchResponse search(
            @RequestParam String component,
            @RequestParam(name = "q") String query,
            @RequestParam(required = false) String lang,
            @RequestParam(defaultValue = "5") int limitPerSection,
            @AuthenticationPrincipal Jwt jwt) {
        String normalizedQuery = normalize(query);
        String language = lang == null ? "" : lang.trim().toUpperCase(Locale.ROOT);
        int limit = Math.clamp(limitPerSection, 1, 5);
        AuthService.TokenPayload payload = auth.tokenPayload(jwt);
        if (normalizedQuery.length() < 3 || core.visibleMenus(component, payload).isEmpty()) {
            return response(component, query, language, List.of());
        }

        var allowed = payload.isPrivilegedAccount() ? null : core.accessibleModules(payload.roles());
        List<ModuleSearchResponse.ModuleSearchItem> results = new ArrayList<>();
        var visibleMenus = core.visibleMenus(component, payload).stream()
                .map(menu -> menu.menuId().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        for (ModuleRuntimeCache.SearchDocument document : cache.search(component)) {
            if (!visibleMenus.contains(document.menuId().toLowerCase(Locale.ROOT))
                    || (allowed != null && !allowed.contains(document.moduleId().toLowerCase(Locale.ROOT)))
                    || !document.matches(normalizedQuery)) {
                continue;
            }
            int score = document.score(normalizedQuery);
            results.add(new ModuleSearchResponse.ModuleSearchItem(document.moduleId(), document.menuId(), document.param(),
                    document.moduleName(), document.display(language), document.icon(), score >= 90 ? "module" : "name",
                    score >= 90 ? "moduleId" : "name", score));
        }
        results.sort(Comparator.comparingInt(value -> -value.score()));
        return response(component, query, language, results.stream().limit(limit).toList());
    }

    private static ModuleSearchResponse response(String component, String query, String lang, List<ModuleSearchResponse.ModuleSearchItem> results) {
        return new ModuleSearchResponse(component.trim(), query.trim(), lang,
                results.size() == 1 && results.getFirst().score() == 100 ? results.getFirst() : null,
                results.isEmpty() ? List.of() : List.of(new ModuleSearchResponse.Section("modules", "Modules", results)), results.size());
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
