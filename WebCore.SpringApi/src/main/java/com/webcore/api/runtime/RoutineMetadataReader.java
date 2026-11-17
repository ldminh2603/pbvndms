package com.webcore.api.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.webcore.api.core.CoreMetadataService;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Routine catalog metadata belongs to the immutable Core snapshot.  A request therefore
 * sees the old Core and old routine signatures, or the new pair, never a mixed version.
 */
@Service
public class RoutineMetadataReader {
    private static final Logger log = LoggerFactory.getLogger(RoutineMetadataReader.class);
    private final CoreMetadataService core;
    private final RuntimeCacheMetrics metrics;

    public RoutineMetadataReader(CoreMetadataService core, RuntimeCacheMetrics metrics) { this.core = core; this.metrics = metrics; }

    public List<Parameter> parameters(String source, String reference) {
        Routine routine = required(source, reference);
        return routine.parameters();
    }

    public Kind kind(String source, String reference) { return required(source, reference).kind(); }

    /** Compatibility result for reload telemetry; snapshot replacement has no mutable entries to clear. */
    public int clear() { return core.snapshot().routineMetadata().routines().size(); }

    private Routine required(String source, String reference) {
        Routine value = core.snapshot().routineMetadata().routines().get(key(source, reference));
        if (value == null) { metrics.miss("routine-metadata"); throw new IllegalStateException("Routine metadata was not prepared for " + source + ":" + reference + ". Reload Core after updating def_mod."); }
        metrics.hit("routine-metadata");
        return value;
    }

    /** Builds all declared Store/Procedure/Function signatures before the Core snapshot is published. */
    public static Snapshot prepare(CoreMetadataService.Snapshot core) {
        Map<String, List<RoutineRef>> bySource = new LinkedHashMap<>();
        for (CoreMetadataService.Module module : core.modules().values()) {
            for (Map<String, Object> action : actions(module.config())) {
                String method = ModuleActionExecutor.text(action, "actionMethod").toUpperCase(Locale.ROOT);
                String source = ModuleActionExecutor.text(action, "source");
                String reference = ModuleActionExecutor.text(action, "function");
                if (reference.isBlank()) reference = ModuleActionExecutor.text(action, "action");
                // Only descriptors executable by ModuleActionExecutor are prepared.  Other
                // metadata sections (notification, static adapters, etc.) may have their
                // own source-like fields and must not make Core startup depend on them.
                if (!isRoutine(method) || source.isBlank() || reference.isBlank() || !core.sources().containsKey(source.trim().toLowerCase(Locale.ROOT))) continue;
                bySource.computeIfAbsent(source.trim().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                        .add(new RoutineRef(source, reference));
            }
        }

        Map<String, Routine> result = new LinkedHashMap<>();
        for (List<RoutineRef> references : bySource.values()) {
            long startedAt = System.nanoTime();
            RoutineRef first = references.getFirst();
            CoreMetadataService.Source source = core.sources().get(first.source().trim().toLowerCase(Locale.ROOT));
            if (source == null) continue;
            String provider = value(source.config(), "data_source_type").toUpperCase(Locale.ROOT);
            try (HikariDataSource dataSource = SourceDataSourceRegistry.open(source)) {
                JdbcTemplate db = new JdbcTemplate(dataSource);
                for (RoutineRef reference : references) {
                    String key = key(reference.source(), reference.reference());
                    if (!result.containsKey(key)) result.put(key, new Routine(loadKind(db, provider, reference.reference()), loadParameters(db, provider, reference.reference())));
                }
                log.info("routine_metadata_prepare_timing source={} routines={} elapsedMs={}", source.sourceName(), references.size(), elapsedMillis(startedAt));
            } catch (RuntimeException exception) {
                log.warn("routine_metadata_prepare_failed source={} routines={} elapsedMs={} errorType={}", source.sourceName(), references.size(), elapsedMillis(startedAt), exception.getClass().getSimpleName());
                throw new IllegalStateException("Cannot prepare routine metadata from def_source " + source.sourceName(), exception);
            }
        }
        return new Snapshot(Map.copyOf(result));
    }

    private static boolean isRoutine(String method) { return "STORE".equals(method) || "PROCEDURE".equals(method) || "FUNCTION".equals(method) || "FUNCTIONTABLE".equals(method); }
    private static Kind loadKind(JdbcTemplate db, String provider, String reference) {
        if (!"POSTGRES".equals(normalize(provider))) return Kind.PROCEDURE;
        String[] part = parts(reference);
        String value = db.query("SELECT p.prokind FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE lower(n.nspname)=lower(?) AND lower(p.proname)=lower(?) ORDER BY p.oid DESC LIMIT 1", rs -> rs.next() ? rs.getString(1) : null, part[0], part[1]);
        return "f".equals(value) ? Kind.FUNCTION : Kind.PROCEDURE;
    }
    /**
     * Reads a routine signature from the application database.  Maintain forms use
     * this for their configured MODMAINTAIN.CommitHandler; the handler itself and
     * the application connection both come from the immutable Core metadata.
     */
    static List<Parameter> loadParameters(JdbcTemplate db, String provider, String reference) {
        String[] part = parts(reference);
        return switch (normalize(provider)) {
            case "POSTGRES" -> postgresParameters(db, reference, part);
            case "SQLSERVER" -> db.query("SELECT p.name,TYPE_NAME(p.user_type_id),p.parameter_id,CASE WHEN p.is_output=1 THEN 'INOUT' ELSE 'IN' END FROM sys.parameters p JOIN sys.objects o ON p.object_id=o.object_id JOIN sys.schemas s ON o.schema_id=s.schema_id WHERE s.name=? AND o.name=? ORDER BY p.parameter_id", (rs,n) -> new Parameter(rs.getString(1).replaceFirst("^@", ""),rs.getString(2),rs.getInt(3),rs.getString(4)), part[0], part[1]);
            case "ORACLE" -> db.query("SELECT argument_name,data_type,position,in_out FROM all_arguments WHERE owner=? AND object_name=? AND data_level=0 ORDER BY position", (rs,n) -> new Parameter(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getString(4)), part[0].toUpperCase(Locale.ROOT), part[1].toUpperCase(Locale.ROOT));
            default -> throw new IllegalArgumentException("Unsupported routine provider: " + provider);
        };
    }
    /**
     * An unqualified handler is resolved by PostgreSQL through the connection
     * Search Path configured in DEFAPPS.CONSTR.  The catalog lookup must use
     * that same path; defaulting it to public would describe a different
     * routine than the one CALL executes.
     */
    private static List<Parameter> postgresParameters(JdbcTemplate db, String reference, String[] part) {
        // specific_name in information_schema is an implementation generated
        // identifier (for example sp_groups_del_29170).  It is not the name
        // configured by MODEXECPROC/ MODMAINTAIN and can therefore cause an
        // empty signature for a perfectly valid PostgreSQL procedure.  Read
        // pg_proc directly, which is also what CALL resolves against.
        String query = """
                SELECT COALESCE(p.proargnames[a.ordinality + COALESCE(array_lower(p.proargnames, 1), 1) - 1], 'arg' || a.ordinality::text) AS parameter_name,
                       t.typname AS type_name,
                       a.ordinality AS ordinal_position,
                       CASE COALESCE(p.proargmodes[a.ordinality + COALESCE(array_lower(p.proargmodes, 1), 1) - 1], 'i'::\"char\")
                           WHEN 'o' THEN 'OUT'
                           WHEN 'b' THEN 'INOUT'
                           WHEN 't' THEN 'OUT'
                           ELSE 'IN'
                       END AS parameter_mode
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                CROSS JOIN LATERAL unnest(COALESCE(p.proallargtypes, p.proargtypes::oid[]))
                    WITH ORDINALITY AS a(type_oid, ordinality)
                JOIN pg_type t ON t.oid = a.type_oid
                """;
        var mapper = (org.springframework.jdbc.core.RowMapper<Parameter>) (rs, n) -> new Parameter(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4));
        if (reference.contains(".")) {
            return db.query(query + "WHERE lower(n.nspname)=lower(?) AND lower(p.proname)=lower(?) ORDER BY p.oid DESC, a.ordinality", mapper, part[0], part[1]);
        }
        return db.query(query + "WHERE n.nspname = ANY(current_schemas(false)) AND lower(p.proname)=lower(?) ORDER BY array_position(current_schemas(false), n.nspname), p.oid DESC, a.ordinality", mapper, part[1]);
    }
    private static List<Map<String,Object>> actions(Map<String,Object> config) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (Object value : ModuleActionExecutor.object(config, "Actions").values()) if (value instanceof Map<?,?> map) result.add(cast(map));
        for (Object value : ModuleActionExecutor.object(config, "Operations").values()) if (value instanceof Map<?,?> map) result.add(cast(map));
        collect(config.get("Ui"), result);
        return result;
    }
    @SuppressWarnings("unchecked") private static void collect(Object value, List<Map<String,Object>> result) {
        if (value instanceof Map<?,?> map) { Map<String,Object> item = (Map<String,Object>) map; if (item.containsKey("actionMethod")) result.add(item); for (Object child : item.values()) collect(child, result); }
        else if (value instanceof Iterable<?> values) for (Object child : values) collect(child, result);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Map<?,?> value) { return (Map<String,Object>) value; }
    private static String[] parts(String reference) { String[] part = reference.split("\\."); return part.length > 1 ? new String[]{part[part.length - 2], part[part.length - 1]} : new String[]{"public", part[0]}; }
    private static String key(String source, String reference) { return source.trim().toLowerCase(Locale.ROOT) + ":" + reference.trim().toLowerCase(Locale.ROOT); }
    private static String normalize(String provider) { String value = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT); return "MSSQL".equals(value) || "MSSQLSERVER".equals(value) ? "SQLSERVER" : value; }
    private static String value(Map<String,Object> values, String name) { Object value = values.get(name); return value == null ? "" : String.valueOf(value).trim(); }
    private static long elapsedMillis(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }

    public record Snapshot(Map<String,Routine> routines) { public static Snapshot empty() { return new Snapshot(Map.of()); } }
    public record Routine(Kind kind, List<Parameter> parameters) { public Routine { parameters = List.copyOf(parameters); } }
    public record Parameter(String name,String type,int position,String mode) { public boolean input() { return mode == null || mode.toUpperCase(Locale.ROOT).contains("IN"); } public boolean output() { return mode != null && mode.toUpperCase(Locale.ROOT).contains("OUT"); } }
    public enum Kind { FUNCTION, PROCEDURE }
    private record RoutineRef(String source, String reference) { }
}
