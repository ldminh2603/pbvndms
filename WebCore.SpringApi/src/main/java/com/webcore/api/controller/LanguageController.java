package com.webcore.api.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.webcore.api.core.CoreMetadataService;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/api/language")
public class LanguageController {

    
    private final CoreMetadataService core;

    public LanguageController(CoreMetadataService core) {
        this.core = core;
    }

    /**
     * Returns only the requested language dictionary.
     *
     * Examples:
     *
     * Accept-Language: EN
     * {
     *   "EN": {
     *     "...": "..."
     *   }
     * }
     *
     * If a configured language has no DEFLANG entries yet:
     *
     * Accept-Language: MY
     * {
     *   "MY": {}
     * }
     *
     * No ObjectMapper dependency is required here.
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(
                    value = "Accept-Language",
                    defaultValue = ""
            ) String acceptLanguage) {

        String lang = resolveLanguageId(acceptLanguage);

        if (lang.isBlank()) {
            return ResponseEntity
                    .badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of());
        }

        Map<String, String> values = core.snapshot()
                .languages()
                .get(lang);

        /*
         * Some project-level JSON inclusion settings can serialize:
         *
         *     Map.of("MY", Map.of())
         *
         * as:
         *
         *     {}
         *
         * For the empty-language case, return the tiny JSON payload directly
         * so the language id is preserved as {"MY":{}}.
         */
        if (values == null || values.isEmpty()) {
            String json = "{\"" + escapeJson(lang) + "\":{}}";

            return ResponseEntity
                    .ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .cacheControl(CacheControl.noStore())
                    .body(json);
        }

        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        result.put(lang, values);

        return ResponseEntity
                .ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    /**
     * Returns only DEFLANG entries used by one app-menu component.
     *
     * <p>For example, component {@code USER_MANAGER} only receives keys
     * beginning with {@code APP_MENU$USER_MANAGER.}, including its
     * {@code .Icon} keys.</p>
     */
    @GetMapping("/menu/{component}")
    public ResponseEntity<?> appMenuDictionary(
            @PathVariable String component,
            @RequestHeader(value = "Accept-Language", defaultValue = "") String acceptLanguage) {

        String lang = resolveLanguageId(acceptLanguage);
        if (lang.isBlank()) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of());
        }

        String normalizedComponent = component == null
                ? ""
                : component.trim().toUpperCase(Locale.ROOT);
        if (normalizedComponent.isBlank()) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of());
        }

        String prefix = "APP_MENU$" + normalizedComponent + ".";
        Map<String, String> allValues = core.snapshot().languages().get(lang);
        Map<String, String> values = new LinkedHashMap<>();

        if (allValues != null) {
            allValues.forEach((key, value) -> {
                if (key != null && key.startsWith(prefix)) {
                    values.put(key, value);
                }
            });
        }

        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        result.put(lang, values);
        return ResponseEntity
                .ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    /**
     * Supports:
     *   Accept-Language: VN
     *   Accept-Language: EN
     *   Accept-Language: MY
     * and standard forms such as:
     *   Accept-Language: en-US,en;q=0.9
     *
     * No language id is hard-coded.
     */
    private static String resolveLanguageId(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return "";
        }

        String first = acceptLanguage.split(",", 2)[0].trim();

        int qualitySeparator = first.indexOf(';');
        if (qualitySeparator >= 0) {
            first = first.substring(0, qualitySeparator).trim();
        }

        return first.toUpperCase(Locale.ROOT);
    }

    /** DEFCODE(SYSTEM/LANGUAGE) drives the language selector. */
    @GetMapping("/options")
    public List<Map<String, String>> options() {

        List<Map<String, String>> result = new ArrayList<>();

        for (Map<String, Object> code : core.snapshot()
                .systemMetadata()
                .getOrDefault("DEFCODE", List.of())) {

            if (!"SYSTEM".equalsIgnoreCase(value(code, "CDTYPE"))
                    || !"LANGUAGE".equalsIgnoreCase(value(code, "CDNAME"))) {
                continue;
            }

            String language = value(code, "CDVALUE");

            if (language.isBlank()) {
                continue;
            }

            Map<String, String> item = new LinkedHashMap<>();

            item.put("value", language);
            item.put(
                    "text",
                    value(code, "CDDESC").isBlank()
                            ? language
                            : value(code, "CDDESC")
            );
            item.put(
                    "position",
                    value(code, "CDPOSITION")
            );

            result.add(item);
        }

        result.sort(
                Comparator
                        .comparingDouble(
                                (Map<String, String> item) ->
                                        number(item.get("position"))
                        )
                        .thenComparing(
                                item -> item.get("value"),
                                String.CASE_INSENSITIVE_ORDER
                        )
        );

        result.forEach(item -> item.remove("position"));

        return List.copyOf(result);
    }

    private static String value(
            Map<String, Object> row,
            String name) {

        if (row == null || name == null) {
            return "";
        }

        Object raw = row.get(name);

        if (raw == null) {
            raw = row.get(name.toLowerCase(Locale.ROOT));
        }

        if (raw == null) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey();

                if (key != null && key.equalsIgnoreCase(name)) {
                    raw = entry.getValue();
                    break;
                }
            }
        }

        return raw == null
                ? ""
                : String.valueOf(raw).trim();
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value);
        } catch (RuntimeException ignored) {
            return Double.MAX_VALUE;
        }
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
