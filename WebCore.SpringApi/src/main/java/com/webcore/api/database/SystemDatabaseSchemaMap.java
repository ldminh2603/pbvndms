package com.webcore.api.database;

/** Logical schema names shared with .NET SystemDatabaseSchemaMap. */
public record SystemDatabaseSchemaMap(String core, String auth, String ops, String messaging) {
    public static SystemDatabaseSchemaMap forProvider(String provider) {
        String value = provider == null ? "" : provider.trim().toUpperCase();
        return "ORACLE".equals(value)
                ? new SystemDatabaseSchemaMap("WEBCORE_CORE", "WEBCORE_AUTH", "WEBCORE_OPS", "WEBCORE_MESSAGING")
                : new SystemDatabaseSchemaMap("core", "auth", "ops", "messaging");
    }
    public String table(String logicalSchema, String table) {
        String schema = switch (logicalSchema.toLowerCase()) {
            case "core" -> core; case "auth" -> auth; case "ops" -> ops; case "messaging" -> messaging;
            default -> throw new IllegalArgumentException("Unsupported logical schema: " + logicalSchema);
        };
        return schema + "." + table;
    }
}
