package com.webcore.api.database;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "database")
public record SystemDatabaseProperties(String provider, String connectionString) {
}
