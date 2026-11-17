package com.webcore.api.database;

import javax.sql.DataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SystemDatabaseProperties.class)
public class SystemDatabaseConfiguration {

    @Bean
    DataSource dataSource(SystemDatabaseProperties properties) {
        if (properties.connectionString() == null || properties.connectionString().isBlank()) {
            throw new IllegalStateException("Database.ConnectionString is required.");
        }

        HikariConfig configuration = new HikariConfig();
        configuration.setMaximumPoolSize(5);
        configuration.setMinimumIdle(0);
        configuration.setJdbcUrl(toJdbcUrl(properties.provider(), properties.connectionString()));
        configuration.setDriverClassName(driverClassName(properties.provider()));
        applyCredentials(configuration, properties.provider(), properties.connectionString());
        return new HikariDataSource(configuration);
    }

    private String driverClassName(String provider) {
        return switch (normalizeProvider(provider)) {
            case "POSTGRES" -> "org.postgresql.Driver";
            case "SQLSERVER" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "ORACLE" -> "oracle.jdbc.OracleDriver";
            default -> throw new IllegalStateException("Unsupported Database.Provider: " + provider);
        };
    }

    private String toJdbcUrl(String provider, String connectionString) {
        String trimmed = connectionString.trim();
        if (trimmed.startsWith("jdbc:")) {
            return trimmed;
        }
        return switch (normalizeProvider(provider)) {
            case "POSTGRES" -> postgresJdbcUrl(parseConnectionString(trimmed));
            case "SQLSERVER" -> "jdbc:sqlserver:" + trimmed;
            case "ORACLE" -> oracleJdbcUrl(parseConnectionString(trimmed));
            default -> throw new IllegalStateException("Unsupported Database.Provider: " + provider);
        };
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "" : provider.trim().replace("_", "").replace("-", "").toUpperCase();
    }

    private Map<String, String> parseConnectionString(String connectionString) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : connectionString.split(";")) {
            int separator = part.indexOf('=');
            if (separator > 0) {
                values.put(part.substring(0, separator).trim().toLowerCase(), part.substring(separator + 1).trim());
            }
        }
        return values;
    }

    private String postgresJdbcUrl(Map<String, String> values) {
        String host = required(values, "host");
        String database = required(values, "database");
        String port = values.getOrDefault("port", "5432");
        Map<String, String> parameters = new LinkedHashMap<>();
        copyIfPresent(values, parameters, "username", "user");
        copyIfPresent(values, parameters, "password", "password");
        copyIfPresent(values, parameters, "search path", "currentSchema");
        copyIfPresent(values, parameters, "ssl mode", "sslmode");
        String query = parameters.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .map(value -> "?" + value)
                .orElse("");
        return "jdbc:postgresql://" + host + ":" + port + "/" + database + query;
    }

    private String oracleJdbcUrl(Map<String, String> values) {
        String dataSource = values.getOrDefault("data source", "").trim();
        if (dataSource.isBlank()) throw new IllegalStateException("Database.ConnectionString is missing Data Source.");
        if (dataSource.startsWith("jdbc:")) return dataSource;
        if (dataSource.startsWith("//")) return "jdbc:oracle:thin:@" + dataSource;
        return "jdbc:oracle:thin:@//" + dataSource;
    }

    private void applyCredentials(HikariConfig configuration, String provider, String connectionString) {
        if (!"ORACLE".equals(normalizeProvider(provider)) || connectionString.trim().startsWith("jdbc:")) return;
        Map<String, String> values = parseConnectionString(connectionString);
        String username = values.getOrDefault("user id", values.getOrDefault("username", ""));
        String password = values.getOrDefault("password", "");
        if (username.isBlank() || password.isBlank()) throw new IllegalStateException("Oracle Database.ConnectionString requires User Id and Password.");
        configuration.setUsername(username);
        configuration.setPassword(password);
    }

    private String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Database.ConnectionString is missing " + key + ".");
        }
        return value;
    }

    private void copyIfPresent(Map<String, String> source, Map<String, String> target, String sourceKey, String targetKey) {
        String value = source.get(sourceKey);
        if (value != null && !value.isBlank()) {
            target.put(targetKey, value);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
