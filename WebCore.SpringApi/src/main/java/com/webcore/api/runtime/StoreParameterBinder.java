package com.webcore.api.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.core.metadata.RuntimeMetadataRows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The single parameter-binding path for metadata Store calls.
 *
 * <p>MODMAINTAIN (type 02) and MODEXECPROC (type 01) resolve their own
 * handler metadata, then both call this binder.  Parameters are matched only
 * by {@code DEFMODFLD.PARAMNAME} for the target module/submodule; standard
 * {@code SESSIONINFO_*} parameters are supplied from the authenticated user.
 * No module, datasource, or procedure-specific alias is maintained here.</p>
 */
@Service
public class StoreParameterBinder {
    private final CoreMetadataService core;
    private final ObjectMapper json;

    public StoreParameterBinder(CoreMetadataService core, ObjectMapper json) {
        this.core = core;
        this.json = json;
    }

    public BoundParameters bind(String moduleId, String subModule, String handler,
                                JdbcTemplate database, String provider,
                                Map<String, Object> request, AuthService.TokenPayload user) {
        List<RoutineMetadataReader.Parameter> inputs = RoutineMetadataReader.loadParameters(database, provider, handler)
                .stream().filter(RoutineMetadataReader.Parameter::input).toList();
        if (inputs.isEmpty()) throw new IllegalStateException("CommitHandler has no input parameters: " + handler);

        Map<String, Object> values = map(request.get("values"));
        if (values.isEmpty()) values = request;
        List<RuntimeMetadataRows.DefModuleField> fields = fields(moduleId, subModule);
        List<Object> arguments = new ArrayList<>(inputs.size());
        for (RoutineMetadataReader.Parameter parameter : inputs) {
            RuntimeMetadataRows.DefModuleField field = matchingField(fields, parameter.name());
            Object sessionValue = systemValue(parameter.name(), user);
            if (field == null && sessionValue == null) {
                throw new IllegalStateException("No DEFMODFLD.PARAMNAME matches Store parameter: " + parameter.name());
            }
            arguments.add(field == null ? sessionValue : valueFor(field, values, user));
        }
        return new BoundParameters(inputs, arguments);
    }

    private List<RuntimeMetadataRows.DefModuleField> fields(String moduleId, String subModule) {
        List<RuntimeMetadataRows.DefModuleField> result = new ArrayList<>();
        for (RuntimeMetadataRows.DefModuleField row : core.moduleFields()) {
            if (!same(row.modid(), moduleId)) continue;
            String fieldSubModule = row.submod();
            if (same(fieldSubModule, "ALL") || same(fieldSubModule, subModule)) result.add(row);
        }
        return result;
    }

    private static RuntimeMetadataRows.DefModuleField matchingField(List<RuntimeMetadataRows.DefModuleField> fields, String parameterName) {
        String parameter = comparableParameter(parameterName);
        for (RuntimeMetadataRows.DefModuleField field : fields) {
            if (comparableParameter(field.paramname()).equals(parameter)) return field;
        }
        return null;
    }

    private Object valueFor(RuntimeMetadataRows.DefModuleField field, Map<String, Object> values, AuthService.TokenPayload user) {
        String parameterName = field.paramname();
        if (isSessionInfo(parameterName)) return sessionValue(parameterName, user);
        // CCB editors persist CDVALUE under FLDNAME; captions are never sent to Store.
        Object value = lookup(values, field.fldname());
        // CCC is a multi-select code list. PostgreSQL jsonb does not accept a
        // Java/legacy array containing null entries (for example [null,"pdf"]).
        // Normalize it here as the final, metadata-driven boundary before JDBC.
        return "CCC".equalsIgnoreCase(field.ctrltype()) ? jsonArray(value) : value;
    }

    private String jsonArray(Object value) {
        try {
            // PostgreSQL jsonb can be posted back by the legacy client as a
            // driver wrapper such as {"type":"jsonb","value":"[...]"}.
            // Read its payload, rather than treating the wrapper itself as an
            // empty multi-select.
            if (value instanceof Map<?, ?> wrapper) {
                Object nested = null;
                for (Map.Entry<?, ?> entry : wrapper.entrySet()) {
                    if (entry.getKey() != null && "value".equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                        nested = entry.getValue();
                        break;
                    }
                }
                return jsonArray(nested);
            }
            List<?> source = value instanceof String text
                    ? json.readValue(text, new TypeReference<List<Object>>() { })
                    : value instanceof List<?> list ? list : List.of();
            List<String> result = new ArrayList<>();
            for (Object item : source) {
                if (item == null) continue;
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) result.add(text);
            }
            return json.writeValueAsString(result);
        } catch (Exception ignored) {
            // The procedure reports its regular required-field validation for
            // an invalid/empty selection instead of a low-level JSON error.
            return "[]";
        }
    }

    private static Object systemValue(String parameterName, AuthService.TokenPayload user) {
        return switch (neutralName(parameterName).toUpperCase(Locale.ROOT)) {
            case "SESSIONINFO_USERNAME", "SESSIONINFO_EMAIL" -> user.email();
            case "SESSIONINFO_USERID" -> user.id();
            case "SESSIONINFO_DISPLAYNAME" -> user.displayName();
            case "SESSIONINFO_IP" -> user.ip();
            default -> null;
        };
    }

    private static Object sessionValue(String token, AuthService.TokenPayload user) {
        Object value = systemValue(token, user);
        if (value == null) throw new IllegalArgumentException("Unsupported DEFMODFLD session parameter: " + token);
        return value;
    }

    private static boolean isSessionInfo(String parameterName) {
        return comparableParameter(parameterName).startsWith("SESSIONINFO_");
    }

    public record BoundParameters(List<RoutineMetadataReader.Parameter> definitions, List<Object> arguments) {
        public BoundParameters {
            definitions = List.copyOf(definitions);
            // Optional metadata fields are represented by null and are later
            // bound as SQL NULL by MaintainCommitService. List.copyOf rejects
            // null elements, so preserve the immutable boundary without
            // discarding valid optional Store parameters.
            arguments = java.util.Collections.unmodifiableList(new ArrayList<>(arguments));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? new LinkedHashMap<>((Map<String, Object>) source) : new LinkedHashMap<>();
    }
    private static Object lookup(Map<String, Object> values, String name) { for (var entry : values.entrySet()) if (same(entry.getKey(), name)) return entry.getValue(); return null; }
    private static String neutralName(String value) { return value == null ? "" : value.trim().replaceFirst("^[:@]", ""); }
    private static String comparableParameter(String value) { return neutralName(value).toUpperCase(Locale.ROOT); }
    private static String text(Map<String, Object> values, String name) { Object value = lookup(values, name); return value == null ? "" : String.valueOf(value).trim(); }
    private static boolean same(String left, String right) { return left != null && right != null && left.trim().equalsIgnoreCase(right.trim()); }
}
