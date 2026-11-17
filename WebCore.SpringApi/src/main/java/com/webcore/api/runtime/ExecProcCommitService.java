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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import com.webcore.api.auth.AuthService;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.userconfig.UserConfigRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Executes MODEXECPROC (ModType 01) independently from MODMAINTAIN. */
@Service
public class ExecProcCommitService {
    private final CoreMetadataService core;
    private final SourceDataSourceRegistry sources;
    private final UserConfigRepository userConfig;
    private final StoreParameterBinder parameterBinder;

    public ExecProcCommitService(CoreMetadataService core, SourceDataSourceRegistry sources,
                                 UserConfigRepository userConfig, StoreParameterBinder parameterBinder) {
        this.core = core;
        this.sources = sources;
        this.userConfig = userConfig;
        this.parameterBinder = parameterBinder;
    }

    public Map<String, Object> execute(String moduleId, String subModule, Map<String, Object> form,
                                       String handler, String showSuccess, Map<String, Object> request,
                                       AuthService.TokenPayload user) {
        String mode = subModule == null ? "" : subModule.trim().toUpperCase(Locale.ROOT);
        if (form == null || form.isEmpty()) throw new IllegalArgumentException("ExecProc module is not configured.");
        if (!"01".equals(text(form, "ModuleType"))) throw new IllegalArgumentException("ExecProc handler is available only for ModType 01.");
        if (handler == null || handler.isBlank()) throw new IllegalStateException("MODEXECPROC.CommitHandler is not configured for " + moduleId + "/" + mode + ".");
        if (!handler.matches("[A-Za-z_][A-Za-z0-9_$#]*(\\.[A-Za-z_][A-Za-z0-9_$#]*){0,2}")) throw new IllegalArgumentException("MODEXECPROC.CommitHandler contains an invalid routine name.");

        String appId = text(form, "ApplicationId");
        String constr = application(appId).constr();
        DataSource dataSource = sources.resolveApplication(appId, constr);
        String provider = sources.applicationProvider(constr);
        StoreParameterBinder.BoundParameters bound = parameterBinder.bind(moduleId, mode, handler,
                new JdbcTemplate(dataSource), provider, request, user);

        String call = call(handler, bound.arguments().size(), provider);
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (CallableStatement statement = connection.prepareCall(call)) {
                for (int index = 0; index < bound.arguments().size(); index++) bind(statement, index + 1, bound.arguments().get(index), bound.definitions().get(index).type());
                statement.execute();
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw new IllegalStateException("MODEXECPROC.CommitHandler failed: " + handler, exception);
            } finally { connection.setAutoCommit(originalAutoCommit); }
        } catch (IllegalStateException exception) { throw exception;
        } catch (Exception exception) { throw new IllegalStateException("Cannot execute MODEXECPROC.CommitHandler: " + handler, exception); }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commitHandler", handler);
        successMessage(form, showSuccess, user).ifPresent(message -> result.put("message", message));
        return Map.copyOf(result);
    }

    private java.util.Optional<String> successMessage(Map<String, Object> form, String showSuccess, AuthService.TokenPayload user) {
        if (!"Y".equalsIgnoreCase(showSuccess)) return java.util.Optional.empty();
        String languageId = userConfig.loadOrDefaultLanguage(user.email(), Map.of(), user.email()).language();
        String message = languageValue(text(form, "ModuleName") + ".Status[Success]", languageId);
        if (message.isBlank()) message = languageValue("GLOBAL.Status[Success]", languageId);
        return message.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(message);
    }
    private String languageValue(String languageName, String languageId) {
        for (com.webcore.api.core.metadata.RuntimeMetadataRows.DefLanguage row : core.languageDefinitions()) if ("A".equalsIgnoreCase(row.status()) && same(languageName, row.langname()) && same(languageId, row.langid())) return row.langvalue() == null ? "" : row.langvalue().trim();
        return "";
    }
    private com.webcore.api.core.metadata.RuntimeMetadataRows.DefApplication application(String appId) {
        com.webcore.api.core.metadata.RuntimeMetadataRows.DefApplication application = core.application(appId);
        if (application != null) return application;
        throw new IllegalStateException("DEFAPPS is not configured for ApplicationId: " + appId);
    }
    private static String call(String handler, int count, String provider) { String arguments = String.join(",", Collections.nCopies(count, "?")); return "POSTGRES".equalsIgnoreCase(provider) ? "CALL " + handler + "(" + arguments + ")" : "{call " + handler + "(" + arguments + ")}"; }
    private static void bind(CallableStatement statement, int index, Object value, String type) throws Exception {
        if (value == null || (value instanceof String text && text.isBlank())) { statement.setNull(index, sqlType(type)); return; }
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("TIMESTAMP") || normalized.contains("DATETIME")) { statement.setTimestamp(index, timestamp(value)); return; }
        if (normalized.equals("DATE")) { statement.setDate(index, date(value)); return; }
        if (normalized.contains("NUMBER") || normalized.contains("DECIMAL") || normalized.contains("NUMERIC")) { statement.setBigDecimal(index, new BigDecimal(String.valueOf(value))); return; }
        if (normalized.contains("INT")) { statement.setLong(index, Long.parseLong(String.valueOf(value))); return; }
        if (normalized.contains("BOOL") || normalized.equals("BIT")) { statement.setBoolean(index, value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value))); return; }
        statement.setObject(index, value);
    }
    private static int sqlType(String type) { String normalized = type == null ? "" : type.toUpperCase(Locale.ROOT); if (normalized.contains("TIMESTAMP") || normalized.contains("DATETIME")) return Types.TIMESTAMP; if (normalized.equals("DATE")) return Types.DATE; if (normalized.contains("NUMBER") || normalized.contains("DECIMAL") || normalized.contains("NUMERIC")) return Types.NUMERIC; if (normalized.contains("INT")) return Types.BIGINT; if (normalized.contains("CHAR") || normalized.contains("TEXT") || normalized.contains("CLOB")) return Types.VARCHAR; return Types.NULL; }
    private static Date date(Object value) { return value instanceof Date date ? date : Date.valueOf(LocalDate.parse(String.valueOf(value).substring(0, 10))); }
    private static Timestamp timestamp(Object value) { if (value instanceof Timestamp timestamp) return timestamp; String text = String.valueOf(value); try { return Timestamp.from(OffsetDateTime.parse(text).toInstant()); } catch (Exception ignored) { return Timestamp.valueOf(LocalDateTime.parse(text.replace(' ', 'T'))); } }
    private static String text(Map<String, Object> values, String name) { for (var entry : values.entrySet()) if (same(entry.getKey(), name)) return entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim(); return ""; }
    private static boolean same(String left, String right) { return left != null && right != null && left.trim().equalsIgnoreCase(right.trim()); }
}
