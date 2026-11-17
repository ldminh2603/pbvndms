package com.webcore.api.runtime;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.webcore.api.core.CoreMetadataService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** Business connections are created from cached DEF_SOURCE or DEFMOD.APPID -> DEFAPPS.CONSTR metadata. */
@Service
public class SourceDataSourceRegistry {
    private final CoreMetadataService core;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HikariDataSource> applicationSources = new ConcurrentHashMap<>();
    public SourceDataSourceRegistry(CoreMetadataService core, ObjectMapper json) { this.core = core; this.json = json; }

    public DataSource resolve(String sourceName) {
        CoreMetadataService.Source source = core.source(sourceName);
        if (source == null) throw new IllegalStateException("Data source is not configured: " + sourceName);
        String key = source.sourceName().toLowerCase(Locale.ROOT);
        return dataSources.computeIfAbsent(key, ignored -> create(source));
    }
    public String provider(String sourceName) { CoreMetadataService.Source source=core.source(sourceName); if(source==null)throw new IllegalStateException("Data source is not configured: "+sourceName); return value(source.config(),"data_source_type").toUpperCase(Locale.ROOT); }
    /** Opens a business connection declared by DEFAPPS.CONSTR, identified only by DEFMOD.APPID. */
    public DataSource resolveApplication(String appId, String constr) {
        String key = appId == null ? "" : appId.trim().toLowerCase(Locale.ROOT);
        if (key.isBlank() || constr == null || constr.isBlank()) throw new IllegalStateException("DEFAPPS.CONSTR is required for the module application.");
        return applicationSources.computeIfAbsent(key, ignored -> createApplication(appId, constr));
    }
    public String applicationProvider(String constr) { return value(applicationConfig(constr), "data_source_type").toUpperCase(Locale.ROOT); }
    public int clear() { int count=dataSources.size()+applicationSources.size(); dataSources.values().forEach(HikariDataSource::close); applicationSources.values().forEach(HikariDataSource::close); dataSources.clear(); applicationSources.clear(); return count; }
    public ProbeStatus probe(String sourceName) {
        long started = System.nanoTime();
        try (Connection connection = resolve(sourceName).getConnection(); var statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return ProbeStatus.healthy((System.nanoTime() - started) / 1_000_000D);
        } catch (Exception exception) { return ProbeStatus.unhealthy(exception.getMessage(), (System.nanoTime() - started) / 1_000_000D); }
    }
    /** Opens a short-lived pool from an immutable def_source entry while a Core snapshot is prepared. */
    public static HikariDataSource open(CoreMetadataService.Source source) { return create(source); }
    private HikariDataSource createApplication(String appId, String constr) {
        return create(new CoreMetadataService.Source("APPID_" + appId, "SQL", applicationConfig(constr)));
    }
    private Map<String,Object> applicationConfig(String constr) {
        String value = constr == null ? "" : constr.trim();
        if (value.isBlank()) throw new IllegalStateException("DEFAPPS.CONSTR is required for the module application.");
        if (value.startsWith("{")) {
            try { return json.readValue(value, new TypeReference<Map<String,Object>>() {}); }
            catch (Exception exception) { throw new IllegalStateException("DEFAPPS.CONSTR JSON is invalid.", exception); }
        }
        // Older DEFAPPS rows store the provider connection string directly. Keep
        // APPID -> DEFAPPS.CONSTR as the only source of truth and normalize it to
        // the same shape used by a JSON CONSTR row.
        return Map.of("data_source_type", providerFromConnection(value), "data_source_connection", value);
    }
    private static String providerFromConnection(String connection) {
        Map<String,String> values = pairs(connection);
        if (values.containsKey("host") && values.containsKey("database")) return "POSTGRES";
        if (values.containsKey("initial catalog") || values.containsKey("integrated security") || values.containsKey("trusted_connection")) return "SQLSERVER";
        if (values.containsKey("user id") && values.containsKey("data source")) return "ORACLE";
        throw new IllegalStateException("Cannot determine provider from raw DEFAPPS.CONSTR. Use a JSON CONSTR with data_source_type/data_source_connection.");
    }
    private static HikariDataSource create(CoreMetadataService.Source source) {
        String provider = value(source.config(), "data_source_type");
        String connection = value(source.config(), "data_source_connection");
        if (connection.isBlank()) throw new IllegalStateException("Data source connection is missing: " + source.sourceName());
        HikariConfig config = new HikariConfig();
        config.setPoolName("source-" + source.sourceName());
        config.setJdbcUrl(jdbcUrl(provider, connection));
        config.setDriverClassName(driver(provider));
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(0);
        if ("ORACLE".equals(normalizeProvider(provider))) {
            Map<String,String> values = pairs(connection);
            String username = values.getOrDefault("user id", values.getOrDefault("username", ""));
            String password = values.getOrDefault("password", "");
            if (username.isBlank() || password.isBlank()) {
                throw new IllegalStateException("Oracle def_source requires User Id and Password: " + source.sourceName());
            }
            config.setUsername(username);
            config.setPassword(password);
            String schema = value(source.config(), "default_schema").replaceAll("[^A-Za-z0-9_$#]", "");
            if (!schema.isBlank()) config.setConnectionInitSql("ALTER SESSION SET CURRENT_SCHEMA=" + schema);
        }
        return new HikariDataSource(config);
    }
    private static String driver(String provider) { return switch (normalizeProvider(provider)) { case "POSTGRES" -> "org.postgresql.Driver"; case "SQLSERVER" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver"; case "ORACLE" -> "oracle.jdbc.OracleDriver"; default -> throw new IllegalStateException("Unsupported def_source type: " + provider); }; }
    private static String jdbcUrl(String provider, String connection) {
        if (connection.startsWith("jdbc:")) return connection;
        if ("SQLSERVER".equals(normalizeProvider(provider))) return "jdbc:sqlserver:" + connection;
        if ("ORACLE".equalsIgnoreCase(provider)) {
            Map<String,String> p=pairs(connection);
            String dataSource=p.getOrDefault("data source", "").trim();
            if (!dataSource.isBlank()) return dataSource.startsWith("//") ? "jdbc:oracle:thin:@"+dataSource : "jdbc:oracle:thin:@//"+dataSource;
            String host=p.getOrDefault("host",""); String port=p.getOrDefault("port","1521"); String service=p.getOrDefault("service name",p.getOrDefault("service",""));
            if(host.isBlank()||service.isBlank())throw new IllegalStateException("Oracle def_source requires Data Source or Host and Service Name.");
            return "jdbc:oracle:thin:@//"+host+":"+port+"/"+service;
        }
        if (!"POSTGRES".equalsIgnoreCase(provider)) throw new IllegalStateException("Unsupported def_source type: "+provider);
        Map<String, String> pairs = pairs(connection);
        String host = pairs.get("host"); String database = pairs.get("database");
        if (host == null || database == null) throw new IllegalStateException("Postgres def_source requires Host and Database.");
        StringBuilder url = new StringBuilder("jdbc:postgresql://").append(host).append(':').append(pairs.getOrDefault("port", "5432")).append('/').append(database);
        Map<String,String> query = new LinkedHashMap<>(); copy(pairs, query, "username", "user"); copy(pairs, query, "password", "password"); copy(pairs, query, "search path", "currentSchema"); copy(pairs, query, "ssl mode", "sslmode");
        if (!query.isEmpty()) { url.append('?'); boolean first = true; for (var item : query.entrySet()) { if (!first) url.append('&'); first = false; url.append(URLEncoder.encode(item.getKey(), StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(item.getValue(), StandardCharsets.UTF_8)); } }
        return url.toString();
    }
    private static Map<String,String> pairs(String connection){Map<String,String> pairs=new LinkedHashMap<>();for(String part:connection.split(";")){int i=part.indexOf('=');if(i>0)pairs.put(part.substring(0,i).trim().toLowerCase(Locale.ROOT),part.substring(i+1).trim());}return pairs;}
    private static String normalizeProvider(String provider) { String value=provider==null?"":provider.trim().toUpperCase(Locale.ROOT); return "MSSQLSERVER".equals(value)||"MSSQL".equals(value)?"SQLSERVER":value; }
    private static void copy(Map<String,String> from, Map<String,String> to, String name, String target) { if (from.containsKey(name)) to.put(target, from.get(name)); }
    private static void copy(Map<String,String> from, StringBuilder url, String name, String target) { if(from.containsKey(name))url.append(';').append(target).append('=').append(from.get(name)); }
    private static String value(Map<String,Object> values, String name) { Object value = values.get(name); return value == null ? "" : String.valueOf(value).trim(); }
    public record ProbeStatus(boolean healthy, String message, double elapsedMs) { static ProbeStatus healthy(double elapsedMs) { return new ProbeStatus(true, "Connection probe passed.", elapsedMs); } static ProbeStatus unhealthy(String message, double elapsedMs) { return new ProbeStatus(false, message == null ? "Connection probe failed." : message, elapsedMs); } }
}
