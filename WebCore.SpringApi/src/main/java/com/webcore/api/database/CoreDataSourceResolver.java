package com.webcore.api.database;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Bootstrap reads def_source once; all Core runtime reads use DBCORE_<database>. */
@Service
public class CoreDataSourceResolver {
    private final JdbcTemplate bootstrap;
    private final SystemDatabaseProperties properties;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String,HikariDataSource> resolved = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,String> resolvedProvider = new ConcurrentHashMap<>();

    public CoreDataSourceResolver(JdbcTemplate bootstrap, SystemDatabaseProperties properties, ObjectMapper json) {
        this.bootstrap = bootstrap; this.properties = properties; this.json = json;
    }

    public DataSource resolve() {
        return resolve("DBCORE_web_core");
    }
    public DataSource resolve(String sourceName) { String key=sourceName.trim().toLowerCase(Locale.ROOT); return resolved.computeIfAbsent(key, ignored -> load(sourceName)); }

    public void clear() { clearExcept(); }

    /**
     * Closes cached Core datasource pools while retaining explicitly named durable
     * control-plane pools. Cluster command delivery uses DBCORE_ops throughout a
     * prepare/apply cycle, so closing it during a metadata reload would terminate
     * in-flight coordinator queries.
     */
    public void clearExcept(String... retainedSourceNames) {
        java.util.Set<String> retained = java.util.Arrays.stream(retainedSourceNames == null ? new String[0] : retainedSourceNames)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        resolved.forEach((key, dataSource) -> {
            if (!retained.contains(key) && resolved.remove(key, dataSource)) {
                resolvedProvider.remove(key);
                dataSource.close();
            }
        });
    }

    public String provider() { return provider("DBCORE_web_core"); }
    public String provider(String sourceName) { resolve(sourceName); return resolvedProvider.get(sourceName.trim().toLowerCase(Locale.ROOT)); }

    private HikariDataSource load(String sourceName) {
        // Logical Core source is provider-independent. ORCLPDB1/MSSQL database
        // names are physical endpoints, never def_source identities.
        Map<String, Object> row = bootstrap.query("SELECT source_name, config FROM def_source WHERE lower(source_name)=lower(?)", rs -> {
            if (!rs.next()) return null;
            return Map.of("source_name", rs.getString("source_name"), "config", rs.getString("config"));
        }, sourceName);
        if (row == null) throw new IllegalStateException("Core runtime source is missing from def_source: " + sourceName);
        Map<String, Object> config;
        try { config = json.readValue(String.valueOf(row.get("config")), new TypeReference<>() {}); }
        catch (Exception ex) { throw new IllegalStateException("Invalid def_source config: " + sourceName, ex); }
        String provider = normalize(value(config, "data_source_type")); String connection = value(config, "data_source_connection");
        if (provider.isBlank() || connection.isBlank()) throw new IllegalStateException("DBCORE source requires data_source_type and data_source_connection: " + sourceName);
        HikariConfig pool = new HikariConfig(); pool.setPoolName("core-" + sourceName); pool.setMaximumPoolSize(10); pool.setMinimumIdle(0); pool.setDriverClassName(driver(provider)); pool.setJdbcUrl(url(provider, connection));
        String schema = value(config, "default_schema");
        if ("ORACLE".equals(normalize(provider)) && !schema.isBlank()) pool.setConnectionInitSql("ALTER SESSION SET CURRENT_SCHEMA=" + schema.replaceAll("[^A-Za-z0-9_$#]", ""));
        Map<String,String> pairs = pairs(connection); if ("ORACLE".equals(normalize(provider))) { pool.setUsername(pairs.getOrDefault("user id", pairs.getOrDefault("username", ""))); pool.setPassword(pairs.getOrDefault("password", "")); }
        resolvedProvider.put(sourceName.trim().toLowerCase(Locale.ROOT), provider);
        return new HikariDataSource(pool);
    }

    private static String databaseName(String connection) { Map<String,String> values=pairs(connection); String database=values.get("database"); if (database != null && !database.isBlank()) return database.trim(); String source=values.getOrDefault("data source", ""); int slash=source.lastIndexOf('/'); return (slash >= 0 ? source.substring(slash+1) : source).trim(); }
    private static String driver(String provider) { return switch(normalize(provider)) { case "POSTGRES" -> "org.postgresql.Driver"; case "SQLSERVER" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver"; case "ORACLE" -> "oracle.jdbc.OracleDriver"; default -> throw new IllegalStateException("Unsupported DBCORE provider: " + provider); }; }
    private static String url(String provider,String connection) { if(connection.startsWith("jdbc:"))return connection; Map<String,String> p=pairs(connection); if("POSTGRES".equals(normalize(provider))) { String host=p.get("host"),db=p.get("database"); if(host==null||db==null)throw new IllegalStateException("Postgres DBCORE source requires Host and Database."); String query=""; Map<String,String> q=new LinkedHashMap<>(); copy(p,q,"username","user");copy(p,q,"password","password");copy(p,q,"search path","currentSchema"); if(!q.isEmpty())query="?"+q.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).reduce((a,b)->a+"&"+b).orElse(""); return "jdbc:postgresql://"+host+":"+p.getOrDefault("port","5432")+"/"+db+query; } if("ORACLE".equals(normalize(provider))) { String source=p.getOrDefault("data source",""); return source.startsWith("//")?"jdbc:oracle:thin:@"+source:"jdbc:oracle:thin:@//"+source; } return "jdbc:sqlserver:"+connection; }
    private static Map<String,String> pairs(String value){Map<String,String> out=new LinkedHashMap<>();for(String part:value.split(";")){int at=part.indexOf('=');if(at>0)out.put(part.substring(0,at).trim().toLowerCase(Locale.ROOT),part.substring(at+1).trim());}return out;}
    private static String normalize(String value){String result=value==null?"":value.trim().toUpperCase(Locale.ROOT);return "MSSQLSERVER".equals(result)||"MSSQL".equals(result)?"SQLSERVER":result;}
    private static void copy(Map<String,String> from,Map<String,String> to,String fromKey,String toKey){if(from.containsKey(fromKey))to.put(toKey,from.get(fromKey));}
    private static String value(Map<String,Object> values,String name){Object value=values.get(name);return value==null?"":String.valueOf(value).trim();}
}
