package com.webcore.api.runtime;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.webcore.api.core.CoreMetadataService;
import org.springframework.stereotype.Service;

/** Resolves Legacy Search CCB LISTSOURCE routines that return a cursor/result set. */
@Service
public class LegacyCcbListSourceService {
    private static final Pattern ROUTINE = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)(?:\\.([A-Za-z_][A-Za-z0-9_]*))?\\((.*)\\)$");
    private static final Pattern FIELD_REFERENCE = Pattern.compile("^:([A-Za-z_][A-Za-z0-9_]*)$");
    private static final List<String> FIELD_SECTIONS = List.of(
            "ParameterFields", "CommonFields", "LayoutFields", "ConditionFields", "SearchFacetFields",
            "ColumnFields", "GroupFields", "ImportFields", "SystemFields");
    private final CoreMetadataService core;
    private final SourceDataSourceRegistry sources;

    public LegacyCcbListSourceService(CoreMetadataService core, SourceDataSourceRegistry sources) {
        this.core = core;
        this.sources = sources;
    }

    /** Returns a request-local copy; the immutable Core metadata snapshot is never mutated. */
    public Map<String, Object> enrich(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        String appId = text(source, "ApplicationId");
        if (appId.isBlank()) return source;
        var application = core.application(appId);
        if (application == null) throw new IllegalStateException("DEFAPPS is not configured for ApplicationId: " + appId);

        Map<String, Object> result = new LinkedHashMap<>(source);
        DataSource dataSource = sources.resolveApplication(appId, application.constr());
        for (String section : FIELD_SECTIONS) {
            Object raw = source.get(section);
            if (!(raw instanceof List<?> rows)) continue;
            List<Map<String, Object>> fields = new ArrayList<>(rows.size());
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> map)) continue;
                Map<String, Object> field = new LinkedHashMap<>((Map<String, Object>) map);
                String listSource = text(field, "ListSource");
                if ("CCB".equalsIgnoreCase(text(field, "ControlType")) && !listSource.isBlank() && !listSource.startsWith(":")) {
                    Routine routine = routine(listSource);
                    if (routine.references().isEmpty()) field.put("ListSourceItems", execute(dataSource, routine, List.of()));
                }
                fields.add(java.util.Collections.unmodifiableMap(field));
            }
            result.put(section, List.copyOf(fields));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    /** Resolves one configured CCB using values currently entered in the form. */
    public List<Map<String, Object>> items(Map<String, Object> form, String fieldId, Map<String, Object> values) {
        Map<String, Object> field = field(form, fieldId);
        if (field == null || !"CCB".equalsIgnoreCase(text(field, "ControlType"))) throw new IllegalArgumentException("CCB field was not found: " + fieldId);
        String listSource = text(field, "ListSource");
        // A blank source (for example a BIT/BOOL CCB) and a static code-list
        // are not procedure calls. They may be requested by an older client
        // bundle, but must never be parsed as a routine.
        if (listSource.isBlank() || listSource.startsWith(":")) return list(field.get("ListSourceItems"));
        Routine routine = routine(listSource);
        if (routine.references().isEmpty()) return list(field.get("ListSourceItems"));
        List<Object> arguments = new ArrayList<>(routine.references().size());
        for (String reference : routine.references()) {
            Map<String, Object> sourceField = field(form, reference);
            if (sourceField == null) throw new IllegalArgumentException("CCB LISTSOURCE references an unknown field: " + reference);
            Object value = lookup(values, text(sourceField, "FieldID"), text(sourceField, "FieldName"), text(sourceField, "ParameterName"));
            if (value == null || String.valueOf(value).trim().isEmpty()) return List.of();
            arguments.add(value);
        }
        String appId = text(form, "ApplicationId");
        var application = core.application(appId);
        if (application == null) throw new IllegalStateException("DEFAPPS is not configured for ApplicationId: " + appId);
        return execute(sources.resolveApplication(appId, application.constr()), routine, arguments);
    }

    /** Resolves a dependent CFU source. Unlike a CCB it returns its first raw configuration row. */
    public Map<String, Object> fileConfig(Map<String, Object> form, String fieldId, Map<String, Object> values) {
        Map<String, Object> field = field(form, fieldId);
        if (field == null || !"CFU".equalsIgnoreCase(text(field, "ControlType"))) throw new IllegalArgumentException("CFU field was not found: " + fieldId);
        String listSource = text(field, "ListSource");
        if (listSource.isBlank()) return Map.of();
        Routine routine = routine(listSource);
        List<Object> arguments = arguments(form, routine, values);
        if (arguments == null) return Map.of();
        String appId = text(form, "ApplicationId");
        var application = core.application(appId);
        if (application == null) throw new IllegalStateException("DEFAPPS is not configured for ApplicationId: " + appId);
        List<Map<String, Object>> rows = executeRaw(sources.resolveApplication(appId, application.constr()), routine, arguments);
        if (rows.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        rows.getFirst().forEach((key, value) -> result.put(key, value instanceof Number || value instanceof Boolean ? value : value == null ? null : String.valueOf(value)));
        return Map.copyOf(result);
    }

    private static Routine routine(String configuredRoutine) {
        Matcher matcher = ROUTINE.matcher(configuredRoutine == null ? "" : configuredRoutine.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("CCB LISTSOURCE '" + configuredRoutine + "' must end in (), or use field references such as docsvc.sp_doc_type_lstbyid(:C01).");
        String name = matcher.group(2) == null ? matcher.group(1) : matcher.group(1) + "." + matcher.group(2);
        String rawArguments = matcher.group(3).trim();
        if (rawArguments.isBlank()) return new Routine(name, List.of());
        List<String> references = new ArrayList<>();
        for (String raw : rawArguments.split(",")) {
            Matcher reference = FIELD_REFERENCE.matcher(raw.trim());
            if (!reference.matches()) throw new IllegalArgumentException("CCB LISTSOURCE arguments must be field references such as :C01.");
            references.add(reference.group(1));
        }
        return new Routine(name, List.copyOf(references));
    }

    private static List<Map<String, Object>> execute(DataSource dataSource, Routine routine, List<Object> arguments) {
        return normalize(executeRaw(dataSource, routine, arguments));
    }

    private static List<Map<String, Object>> executeRaw(DataSource dataSource, Routine routine, List<Object> arguments) {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<Map<String, Object>> rows;
                String placeholders = arguments.isEmpty() ? "" : "?,".repeat(arguments.size());
                try (CallableStatement statement = connection.prepareCall("CALL " + routine.name() + "(" + placeholders + "CAST(? AS refcursor))")) {
                // Legacy CCB procedures conventionally expose an INOUT REFCURSOR.
                // Supplying a cursor name works for the PostgreSQL default-parameter form
                // and lets the JDBC driver return the cursor directly when it supports it.
                int cursorIndex = 1;
                for (Object argument : arguments) statement.setObject(cursorIndex++, argument);
                statement.setString(cursorIndex, "p_cursor");
                statement.registerOutParameter(cursorIndex, Types.REF_CURSOR);
                boolean hasResult = statement.execute();
                rows = readResults(statement, hasResult);
                Object cursorResult = statement.getObject(cursorIndex);
                if (cursorResult instanceof ResultSet cursorRows) rows.addAll(read(cursorRows));
                else if (rows.isEmpty() && cursorResult != null) rows = List.of(Map.of("cursor", cursorResult));
                }
                if (isCursorNameResult(rows)) {
                String cursor = String.valueOf(rows.getFirst().values().iterator().next());
                if (!cursor.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalStateException("CCB LISTSOURCE returned an invalid cursor name.");
                try (var fetch = connection.createStatement(); ResultSet cursorRows = fetch.executeQuery("FETCH ALL FROM \"" + cursor + "\"")) {
                    rows = read(cursorRows);
                }
                }
                connection.commit();
                return List.copyOf(rows);
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("CCB LISTSOURCE execution failed: " + routine.name() + ". " + exception.getMessage(), exception);
        }
    }

    private static List<Object> arguments(Map<String, Object> form, Routine routine, Map<String, Object> values) {
        List<Object> arguments = new ArrayList<>(routine.references().size());
        for (String reference : routine.references()) {
            Map<String, Object> sourceField = field(form, reference);
            if (sourceField == null) throw new IllegalArgumentException("LISTSOURCE references an unknown field: " + reference);
            Object value = lookup(values, text(sourceField, "FieldID"), text(sourceField, "FieldName"), text(sourceField, "ParameterName"));
            if (value == null || String.valueOf(value).trim().isEmpty()) return null;
            arguments.add(value);
        }
        return arguments;
    }

    private static List<Map<String, Object>> readResults(CallableStatement statement, boolean hasResult) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        while (true) {
            if (hasResult) rows.addAll(read(statement.getResultSet()));
            else if (statement.getUpdateCount() == -1) break;
            hasResult = statement.getMoreResults();
        }
        return rows;
    }

    private static boolean isCursorNameResult(List<Map<String, Object>> rows) {
        return rows.size() == 1 && rows.getFirst().size() == 1 && !has(rows.getFirst(), "value") && !has(rows.getFirst(), "text");
    }

    private static List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String value = value(row, "value");
            String caption = value(row, "text");
            if (value.isBlank()) throw new IllegalStateException("CCB LISTSOURCE result must contain a non-empty value column.");
            if (caption.isBlank()) caption = value;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("Value", value);
            item.put("Text", caption);
            item.put("CodeValueName", caption);
            String image = value(row, "image");
            if (image.isBlank()) image = value(row, "images");
            item.put("Image", image);
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> read(ResultSet resultSet) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (resultSet == null) return rows;
        var metadata = resultSet.getMetaData();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= metadata.getColumnCount(); column++) row.put(metadata.getColumnLabel(column), resultSet.getObject(column));
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> field(Map<String, Object> form, String reference) {
        for (String section : FIELD_SECTIONS) for (Map<String, Object> field : list(form.get(section)))
            if (same(reference, text(field, "FieldID")) || same(reference, text(field, "FieldName")) || same(reference, text(field, "ParameterName"))) return field;
        return null;
    }
    private static Object lookup(Map<String, Object> values, String... names) {
        for (String name : names) for (Map.Entry<String, Object> entry : values.entrySet()) if (same(name, entry.getKey())) return entry.getValue();
        return null;
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> list(Object value) {
        if (!(value instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) if (row instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        return result;
    }
    private static boolean same(String left, String right) { return left != null && left.equalsIgnoreCase(right == null ? "" : right); }
    private record Routine(String name, List<String> references) { }

    private static boolean has(Map<String, Object> row, String name) { return row.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name)); }
    private static String value(Map<String, Object> row, String name) { return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).filter(value -> value != null).map(String::valueOf).map(String::trim).findFirst().orElse(""); }
    private static String text(Map<String, Object> row, String name) { Object value = row.get(name); return value == null ? "" : String.valueOf(value).trim(); }
}
