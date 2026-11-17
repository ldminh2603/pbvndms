package com.webcore.api.runtime;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Base64;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.webcore.api.auth.AuthService;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.userconfig.UserConfigRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Executes a MODMAINTAIN.CommitHandler using cached DEFMODFLD and DEFAPPS metadata. */
@Service
public class MaintainCommitService {
    private final CoreMetadataService core;
    private final SourceDataSourceRegistry sources;
    private final UserConfigRepository userConfig;
    private final StoreParameterBinder parameterBinder;
    private final Map<String, MaintainJavaHandler> javaHandlers;

    public MaintainCommitService(CoreMetadataService core, SourceDataSourceRegistry sources, UserConfigRepository userConfig,
                                 StoreParameterBinder parameterBinder, List<MaintainJavaHandler> javaHandlers) {
        this.core = core;
        this.sources = sources;
        this.userConfig = userConfig;
        this.parameterBinder = parameterBinder;
        this.javaHandlers = javaHandlers.stream().collect(Collectors.toUnmodifiableMap(
                handler -> handler.name().trim().toUpperCase(Locale.ROOT), Function.identity(),
                (first, duplicate) -> { throw new IllegalStateException("Duplicate JAVA CommitHandler: " + first.name()); }));
    }

    public Map<String, Object> commit(String moduleId, String subModule, Map<String, Object> request,
                                      AuthService.TokenPayload user) {
        String mode = requiredSubModule(subModule);
        String buttonName = text(request, "buttonName");
        if (buttonName.startsWith("BTN_")) {
            core.authorize(user, moduleId, "View", null);
            core.authorize(user, moduleId, "View", buttonName);
        } else {
        String action = "MAD".equals(mode) ? "Add" : "Edit";
        core.authorize(user, moduleId, action, null);
        }

        Map<String, Object> form = core.runtimeFormInfo(moduleId, mode);
        return commitAuthorized(moduleId, mode, form, request, user);
    }

    /**
     * Executes a Type-02 form after its caller has authorized the business
     * module. Legacy Search uses this to avoid exposing technical 01Lxx/02Lxx
     * module ids as policy keys.
     */
    public Map<String, Object> commitAuthorized(String moduleId, String subModule, Map<String, Object> form,
                                                Map<String, Object> request, AuthService.TokenPayload user) {
        String mode = requiredSubModule(subModule);
        if (form.isEmpty()) throw new IllegalArgumentException("Maintain form is not configured: " + moduleId + "/" + mode);
        if (!"02".equals(text(form, "ModuleType"))) throw new IllegalArgumentException("CommitHandler is available only for ModType 02.");

        Map<String, Object> maintain = map(form.get("Maintain"));
        String handler = text(maintain, "CommitHandler");
        if (handler.isBlank()) throw new IllegalStateException("MODMAINTAIN.CommitHandler is not configured for " + moduleId + "/" + mode + ".");
        if (isJavaHandler(handler)) {
            Map<String, Object> result = new LinkedHashMap<>(javaHandler(handler).commit(moduleId, mode, form, request, user));
            successMessage(form, text(maintain, "ShowSuccess"), user).ifPresent(message -> result.putIfAbsent("message", message));
            return Map.copyOf(result);
        }
        if (!handler.matches("[A-Za-z_][A-Za-z0-9_$#]*(\\.[A-Za-z_][A-Za-z0-9_$#]*){0,2}")) {
            throw new IllegalArgumentException("MODMAINTAIN.CommitHandler contains an invalid routine name.");
        }

        return execute(moduleId, mode, form, handler, text(maintain, "ShowSuccess"), request, user);
    }

    /** Executes only MODMAINTAIN (ModType 02) after the maintain form is resolved. */
    private Map<String, Object> execute(String moduleId, String subModule, Map<String, Object> form,
                                       String handler, String showSuccess, Map<String, Object> request,
                                       AuthService.TokenPayload user) {
        String mode = subModule == null ? "" : subModule.trim().toUpperCase(Locale.ROOT);
        if (form == null || form.isEmpty()) throw new IllegalArgumentException("Target module form is not configured.");
        if (handler == null || handler.isBlank()) throw new IllegalStateException("CommitHandler is not configured for " + moduleId + "/" + mode + ".");
        if (!handler.matches("[A-Za-z_][A-Za-z0-9_$#]*(\\.[A-Za-z_][A-Za-z0-9_$#]*){0,2}")) {
            throw new IllegalArgumentException("CommitHandler contains an invalid routine name.");
        }

        String appId = text(form, "ApplicationId");
        String constr = application(appId).constr();
        DataSource dataSource = sources.resolveApplication(appId, constr);
        String provider = sources.applicationProvider(constr);
        StoreParameterBinder.BoundParameters bound = parameterBinder.bind(
                moduleId, mode, handler, new JdbcTemplate(dataSource), provider, request, user);

        String call = call(handler, bound.definitions(), provider);
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (CallableStatement statement = connection.prepareCall(call)) {
                for (int index = 0; index < bound.arguments().size(); index++) {
                    bind(statement, index + 1, bound.arguments().get(index), bound.definitions().get(index).type());
                }
                statement.execute();
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw new IllegalStateException("CommitHandler failed: " + handler + ". " + databaseMessage(exception), exception);
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot execute CommitHandler: " + handler, exception);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commitHandler", handler);
        successMessage(form, showSuccess, user).ifPresent(message -> result.put("message", message));
        return Map.copyOf(result);
    }

    /**
     * MODMAINTAIN controls whether a successful commit is announced.  Both
     * MODMAINTAIN and DEFLANG are read from the immutable Core snapshot; the
     * only per-request lookup is the user's persisted language choice.
     */
    private java.util.Optional<String> successMessage(Map<String, Object> form, String showSuccess,
                                                      AuthService.TokenPayload user) {
        if (!"Y".equalsIgnoreCase(showSuccess)) return java.util.Optional.empty();
        String languageId = userConfig.loadOrDefaultLanguage(user.email(), Map.of(), user.email()).language();
        String moduleName = text(form, "ModuleName");
        String message = languageValue(moduleName + ".Status[Success]", languageId);
        if (message.isBlank()) message = languageValue("GLOBAL.Status[Success]", languageId);
        return message.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(message);
    }

    private String languageValue(String languageName, String languageId) {
        for (com.webcore.api.core.metadata.RuntimeMetadataRows.DefLanguage row : core.languageDefinitions()) {
            if (!"A".equalsIgnoreCase(row.status())) continue;
            if (same(languageName, row.langname()) && same(languageId, row.langid())) {
                return row.langvalue() == null ? "" : row.langvalue().trim();
            }
        }
        return "";
    }

    private com.webcore.api.core.metadata.RuntimeMetadataRows.DefApplication application(String appId) {
        com.webcore.api.core.metadata.RuntimeMetadataRows.DefApplication application = core.application(appId);
        if (application != null) return application;
        throw new IllegalStateException("DEFAPPS is not configured for ApplicationId: " + appId);
    }


    /**
     * PostgreSQL resolves CALL overloads from argument types. JDBC strings are
     * sent as varchar, which does not necessarily resolve to a text parameter.
     * Types are read from pg_proc by StoreParameterBinder, so this is generic
     * for every metadata-defined MODMAINTAIN handler.
     */
    private static String call(String handler, List<RoutineMetadataReader.Parameter> definitions, String provider) {
        String arguments;
        if ("POSTGRES".equalsIgnoreCase(provider)) {
            arguments = definitions.stream()
                    .map(parameter -> postgresPlaceholder(parameter.type()))
                    .collect(java.util.stream.Collectors.joining(","));
        } else {
            arguments = String.join(",", java.util.Collections.nCopies(definitions.size(), "?"));
        }
        return "POSTGRES".equalsIgnoreCase(provider) ? "CALL " + handler + "(" + arguments + ")" : "{call " + handler + "(" + arguments + ")}";
    }

    private static String postgresPlaceholder(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z_][a-z0-9_]*") ? "?::" + normalized : "?";
    }

    private static void bind(CallableStatement statement, int index, Object value, String type) throws Exception {
        if (value == null || (value instanceof String text && text.isBlank())) { statement.setNull(index, sqlType(type)); return; }
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("TIMESTAMP") || normalized.contains("DATETIME")) { statement.setTimestamp(index, timestamp(value)); return; }
        if (normalized.equals("DATE")) { statement.setDate(index, date(value)); return; }
        if (normalized.contains("NUMBER") || normalized.contains("DECIMAL") || normalized.contains("NUMERIC")) { statement.setBigDecimal(index, new BigDecimal(String.valueOf(value))); return; }
        if (normalized.contains("INT")) { statement.setLong(index, Long.parseLong(String.valueOf(value))); return; }
        if (normalized.contains("BOOL") || normalized.equals("BIT")) { statement.setBoolean(index, value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value))); return; }
        // PostgreSQL receives the placeholder as ?::jsonb. Binding JSON with
        // setObject(String) can be inferred as an incompatible JDBC type;
        // send its JSON text explicitly so the PostgreSQL cast is deterministic.
        if (normalized.equals("JSON") || normalized.equals("JSONB")) { statement.setString(index, String.valueOf(value)); return; }
        if (normalized.equals("BYTEA") || normalized.contains("BINARY") || normalized.contains("BLOB")) {
            if (value instanceof byte[] bytes) { statement.setBytes(index, bytes); return; }
            if (value instanceof String encoded) { statement.setBytes(index, Base64.getDecoder().decode(encoded)); return; }
            throw new IllegalArgumentException("Binary Store parameter must be a Base64 CFU payload.");
        }
        statement.setObject(index, value);
    }

    private static String databaseMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static int sqlType(String type) {
        String normalized = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (normalized.contains("TIMESTAMP") || normalized.contains("DATETIME")) return Types.TIMESTAMP;
        if (normalized.equals("DATE")) return Types.DATE;
        if (normalized.contains("NUMBER") || normalized.contains("DECIMAL") || normalized.contains("NUMERIC")) return Types.NUMERIC;
        if (normalized.contains("INT")) return Types.BIGINT;
        if (normalized.contains("CHAR") || normalized.contains("TEXT") || normalized.contains("CLOB")) return Types.VARCHAR;
        return Types.NULL;
    }

    private static Date date(Object value) { return value instanceof Date date ? date : Date.valueOf(LocalDate.parse(String.valueOf(value).substring(0, 10))); }
    private static Timestamp timestamp(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp;
        String text = String.valueOf(value);
        try { return Timestamp.from(OffsetDateTime.parse(text).toInstant()); }
        catch (Exception ignored) { return Timestamp.valueOf(LocalDateTime.parse(text.replace(' ', 'T'))); }
    }
    private static String requiredSubModule(String value) {
        String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!"MAD".equals(result) && !"MED".equals(result)) throw new IllegalArgumentException("Only MAD and MED can execute CommitHandler.");
        return result;
    }
    private static boolean isJavaHandler(String handler) { return handler.regionMatches(true, 0, "JAVA:", 0, 5); }
    private MaintainJavaHandler javaHandler(String handler) {
        String name = handler.substring(5).trim().toUpperCase(Locale.ROOT);
        if (name.isBlank()) throw new IllegalArgumentException("JAVA CommitHandler name is required.");
        MaintainJavaHandler result = javaHandlers.get(name);
        if (result == null) throw new IllegalArgumentException("Unknown JAVA CommitHandler: " + name);
        return result;
    }
    private static Object lookup(Map<String, Object> values, String name) { for (var entry : values.entrySet()) if (same(entry.getKey(), name)) return entry.getValue(); return null; }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return value instanceof Map<?, ?> source ? new LinkedHashMap<>((Map<String, Object>) source) : new LinkedHashMap<>(); }
    private static String text(Map<String, Object> values, String name) { Object value = lookup(values, name); return value == null ? "" : String.valueOf(value).trim(); }
    private static boolean same(String left, String right) { return left != null && right != null && left.trim().equalsIgnoreCase(right.trim()); }
}
