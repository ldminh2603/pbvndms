package com.webcore.api.userconfig;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.database.CoreDataSourceResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserConfigRepository {
    private final ObjectMapper objectMapper;
    private final CoreMetadataService core;
    private final CoreDataSourceResolver coreSource;

    public UserConfigRepository(ObjectMapper objectMapper, CoreMetadataService core, CoreDataSourceResolver coreSource) {
        this.objectMapper = objectMapper;
        this.core = core;
        this.coreSource = coreSource;
    }
    private JdbcTemplate coreDb() { return new JdbcTemplate(coreSource.resolve()); }

    public UserConfigState loadOrEmpty(String email) {
        String rawConfig = coreDb().query(
                "SELECT config FROM user_config WHERE user_email = ?",
                statement -> statement.setString(1, normalizeEmail(email)),
                resultSet -> resultSet.next() ? resultSet.getString(1) : "{}");
        try {
            return state(objectMapper.readValue(rawConfig, new TypeReference<>() { }));
        } catch (Exception exception) {
            return state(Map.of());
        }
    }

    /**
     * Resolves the user's language once from the Core snapshot.  A saved user
     * choice always wins; profile setting is used only for users without a
     * choice, then the first DEFCODE(SYSTEM/LANGUAGE) row becomes the default.
     * The resolved value is persisted so every subsequent browser/tab gets the
     * same language without relying on localStorage.
     */
    public UserConfigState loadOrDefaultLanguage(String email, Object profileSetting, String modifiedBy) {
        String normalizedEmail = normalizeEmail(email);
        Map<String, Object> current = new LinkedHashMap<>(loadOrEmpty(normalizedEmail).values());
        String saved = configuredLanguage(current.get("language"));
        if (saved != null) {
            if (!saved.equals(current.get("language"))) {
                current.put("language", saved);
                write(normalizedEmail, current, normalizedEmailOrSystem(modifiedBy, normalizedEmail));
            }
            return state(current);
        }

        String resolved = configuredLanguage(languageFromProfileSetting(profileSetting));
        if (resolved == null) resolved = defaultLanguage();
        if (resolved != null) {
            current.put("language", resolved);
            write(normalizedEmail, current, normalizedEmailOrSystem(modifiedBy, normalizedEmail));
        }
        return state(current);
    }

    public SaveResult save(String email, String key, JsonNode requestValue, String modifiedBy) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedKey = normalizeKey(key);
        Map<String, Object> current = new LinkedHashMap<>(loadOrEmpty(normalizedEmail).values());
        if ("language".equals(normalizedKey)) {
            String language = languageValue(requestValue);
            current.put(normalizedKey, language);
            Instant now = Instant.now();
            write(normalizedEmail, current, normalizedEmailOrSystem(modifiedBy, normalizedEmail));
            return new SaveResult(normalizedKey, Map.of("language", language), now);
        }
        Map<String, Object> normalizedValue = new LinkedHashMap<>();
        Map<String, Object> request = objectValue(requestValue);
        if (request.get("value") instanceof Map<?, ?> nested) {
            nested.forEach((name, value) -> normalizedValue.put(String.valueOf(name), value));
        } else {
            normalizedValue.putAll(request);
        }
        validateValue(normalizedKey, normalizedValue);
        Instant now = Instant.now();
        normalizedValue.put("version", 1);
        normalizedValue.put("updatedBy", normalizedEmailOrSystem(modifiedBy, normalizedEmail));
        normalizedValue.put("updatedAt", now.toString());
        current.put(normalizedKey, normalizedValue);
        write(normalizedEmail, current, normalizedEmailOrSystem(modifiedBy, normalizedEmail));
        return new SaveResult(normalizedKey, normalizedValue, now);
    }

    public ClearResult clear(String email, String key, String modifiedBy) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedKey = normalizeKey(key);
        Map<String, Object> current = new LinkedHashMap<>(loadOrEmpty(normalizedEmail).values());
        current.remove(normalizedKey);
        Instant now = Instant.now();
        write(normalizedEmail, current, normalizedEmailOrSystem(modifiedBy, normalizedEmail));
        return new ClearResult(normalizedKey, now);
    }

    private void write(String email, Map<String, Object> config, String modifiedBy) {
        try {
            String json = objectMapper.writeValueAsString(config);
            coreDb().update(upsertSql(), email, json, modifiedBy, modifiedBy);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot save user config.", exception);
        }
    }

    private String upsertSql() {
        return switch (coreSource.provider()) {
            case "POSTGRES" -> """
                    INSERT INTO user_config (user_email, config, created_by, created_date, modified_by, modified_date)
                    VALUES (?, CAST(? AS jsonb), ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (user_email) DO UPDATE SET config = EXCLUDED.config, modified_by = EXCLUDED.modified_by, modified_date = CURRENT_TIMESTAMP
                    """;
            case "SQLSERVER" -> """
                    MERGE user_config AS target USING (VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP))
                    AS source(user_email, config, created_by, created_date, modified_by, modified_date)
                    ON target.user_email = source.user_email
                    WHEN MATCHED THEN UPDATE SET config = source.config, modified_by = source.modified_by, modified_date = source.modified_date
                    WHEN NOT MATCHED THEN INSERT (user_email, config, created_by, created_date, modified_by, modified_date)
                    VALUES (source.user_email, source.config, source.created_by, source.created_date, source.modified_by, source.modified_date);
                    """;
            case "ORACLE" -> """
                    MERGE INTO user_config target
                    USING (SELECT ? user_email, ? config, ? created_by, CURRENT_TIMESTAMP created_date, ? modified_by, CURRENT_TIMESTAMP modified_date FROM dual) source
                    ON (target.user_email = source.user_email)
                    WHEN MATCHED THEN UPDATE SET target.config = source.config, target.modified_by = source.modified_by, target.modified_date = source.modified_date
                    WHEN NOT MATCHED THEN INSERT (user_email, config, created_by, created_date, modified_by, modified_date)
                    VALUES (source.user_email, source.config, source.created_by, source.created_date, source.modified_by, source.modified_date)
                    """;
            default -> throw new IllegalStateException("Unsupported DBCORE provider for user_config: " + coreSource.provider());
        };
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank() || email.length() > 320 || !email.contains("@")) {
            throw new IllegalArgumentException("Authenticated user email is invalid.");
        }
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizeKey(String key) {
        String normalized = key == null ? "" : key.trim();
        if ("language".equalsIgnoreCase(normalized)) return "language";
        if (normalized.length() > 128 || !(normalized.startsWith("module_grid_layout:") || normalized.startsWith("module_grid_row_conditions:"))) {
            throw new IllegalArgumentException("User config key is not supported: " + normalized);
        }
        String moduleId = normalized.substring(normalized.indexOf(':') + 1).trim().toUpperCase(java.util.Locale.ROOT);
        if (moduleId.length() < 2 || (moduleId.charAt(0) != 'L' && moduleId.charAt(0) != 'F')) {
            throw new IllegalArgumentException("User grid layout only supports List-family modules.");
        }
        if (core.module(moduleId) == null) throw new IllegalArgumentException("Module not found: " + moduleId);
        return normalized.substring(0, normalized.indexOf(':') + 1) + moduleId;
    }

    /**
     * Language is an exact DEFCODE(SYSTEM, LANGUAGE).CDVALUE.  It is stored as
     * the scalar code so a newly opened browser tab can hydrate it without any
     * locale aliases or UI-specific conversion.
     */
    private String languageValue(JsonNode requestValue) {
        Object value = requestValue != null && requestValue.isObject() && requestValue.get("value") != null
                ? objectMapper.convertValue(requestValue.get("value"), Object.class)
                : requestValue == null ? null : objectMapper.convertValue(requestValue, Object.class);
        String language = configuredLanguage(value);
        if (language == null) {
            throw new IllegalArgumentException("Language must be a configured language code.");
        }
        return language;
    }

    private Object languageFromProfileSetting(Object setting) {
        if (setting instanceof Map<?, ?> values) return values.get("language");
        if (!(setting instanceof String raw) || raw.isBlank()) return null;
        try {
            Map<String, Object> values = objectMapper.readValue(raw, new TypeReference<>() { });
            return values.get("language");
        } catch (Exception ignored) {
            return null;
        }
    }

    private String configuredLanguage(Object value) {
        if (!(value instanceof String raw) || raw.isBlank() || raw.trim().length() > 64) return null;
        String requested = raw.trim();
        for (Map<String, Object> row : languageRows()) {
            if (!isLanguageRow(row)) continue;
            String configured = value(row, "CDVALUE");
            if (configured.equalsIgnoreCase(requested)) return configured;
        }
        return null;
    }

    private String defaultLanguage() {
        Map<String, Object> defaultRow = null;
        for (Map<String, Object> row : languageRows()) {
            if (!isLanguageRow(row)) continue;
            if (defaultRow == null || compareLanguageRows(row, defaultRow) < 0) defaultRow = row;
        }
        return defaultRow == null ? null : value(defaultRow, "CDVALUE");
    }

    /** Reads only the already-published Core snapshot; no request-time DEFCODE query or temporary list. */
    private List<Map<String, Object>> languageRows() {
        return core.snapshot().systemMetadata().getOrDefault("DEFCODE", List.of());
    }

    private static boolean isLanguageRow(Map<String, Object> row) {
        return "SYSTEM".equalsIgnoreCase(value(row, "CDTYPE"))
                && "LANGUAGE".equalsIgnoreCase(value(row, "CDNAME"))
                && !value(row, "CDVALUE").isBlank();
    }

    private static int compareLanguageRows(Map<String, Object> left, Map<String, Object> right) {
        int position = Double.compare(position(value(left, "CDPOSITION")), position(value(right, "CDPOSITION")));
        return position != 0 ? position : value(left, "CDVALUE").compareToIgnoreCase(value(right, "CDVALUE"));
    }

    private static String value(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (value == null) value = row.get(name.toLowerCase(Locale.ROOT));
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double position(String value) {
        try { return Double.parseDouble(value); }
        catch (RuntimeException ignored) { return Double.MAX_VALUE; }
    }

    @SuppressWarnings("unchecked")
    private void validateValue(String key, Map<String, Object> value) {
        if (key.startsWith("module_grid_layout:")) {
            Object gridState = value.get("gridState");
            if (!(gridState instanceof Map<?, ?>)) throw new IllegalArgumentException("gridState is required.");
            return;
        }
        Object conditionsValue = value.get("conditions");
        if (!(conditionsValue instanceof java.util.List<?> conditions) || conditions.size() > 50) throw new IllegalArgumentException("conditions must contain at most 50 items.");
        String moduleId = key.substring(key.indexOf(':') + 1);
        java.util.Set<String> allowedFields = configuredGridFields(core.module(moduleId).configUi());
        for (Object condition : conditions) {
            if (!(condition instanceof Map<?, ?> row)) throw new IllegalArgumentException("Row condition must be a JSON object.");
            Object fieldValue = row.get("field");
            String field = fieldValue == null ? "" : String.valueOf(fieldValue).trim();
            if (field.isBlank() || !allowedFields.contains(field.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Row condition field is not configured in grid columns: " + field);
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<String> configuredGridFields(Map<String, Object> configUi) {
        java.util.Set<String> fields = new java.util.HashSet<>();
        Object grid = configUi.get("GridConfig");
        if (!(grid instanceof Map<?, ?> gridMap)) return fields;
        Object columns = gridMap.get("Columns");
        if (!(columns instanceof java.util.List<?> list)) return fields;
        for (Object column : list) if (column instanceof Map<?, ?> map && map.get("dataField") != null) fields.add(String.valueOf(map.get("dataField")).trim().toLowerCase(java.util.Locale.ROOT));
        return fields;
    }

    private static String normalizedEmailOrSystem(String modifiedBy, String fallback) {
        return modifiedBy == null || modifiedBy.isBlank() ? fallback : modifiedBy.trim();
    }

    private UserConfigState state(Map<String, Object> values) {
        Map<String, Object> immutable = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        String language = configuredLanguage(immutable.get("language"));
        return new UserConfigState(language, immutable);
    }

    private Map<String, Object> objectValue(JsonNode value) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException("User config payload must be a JSON object.");
        return objectMapper.convertValue(value, new TypeReference<>() { });
    }

    public record UserConfigState(String language, Map<String, Object> values) { }
    public record SaveResult(String key, Map<String, Object> value, Instant updatedAtUtc) { }
    public record ClearResult(String key, Instant updatedAtUtc) { }
}
