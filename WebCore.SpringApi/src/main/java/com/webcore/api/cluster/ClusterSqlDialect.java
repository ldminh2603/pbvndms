package com.webcore.api.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;

/** Provider SQL is isolated here; object resolution remains controlled by DBCORE_ops. */
final class ClusterSqlDialect {
    private final String provider;

    ClusterSqlDialect(String provider) {
        this.provider = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
    }

    boolean postgres() { return "POSTGRES".equals(provider); }
    boolean oracle() { return "ORACLE".equals(provider); }

    long nextId(Connection connection, String sequence) throws Exception {
        String sql = postgres()
                ? "SELECT nextval('" + sequence.toLowerCase(Locale.ROOT) + "')"
                : oracle()
                    ? "SELECT " + sequence.toUpperCase(Locale.ROOT) + ".NEXTVAL FROM DUAL"
                    : "SELECT NEXT VALUE FOR " + sequence.toLowerCase(Locale.ROOT);

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("Could not allocate cluster command id.");
            }
            return rows.getLong(1);
        }
    }

    String freshEndpointsSql() {
        return """
                SELECT record_code
                FROM runtime_endpoint
                WHERE environment=?
                  AND service_name=?
                  AND status='active'
                  AND health_status='healthy'
                  AND last_seen_at>?
                ORDER BY record_code
                """;
    }

    /** Serializes command creation for every reload type targeting one service. */
    String targetServiceLockSql() {
        String where = "environment=? AND service_name=?";
        if (postgres() || oracle()) {
            return "SELECT record_code FROM runtime_endpoint WHERE " + where + " FOR UPDATE";
        }
        return "SELECT record_code FROM runtime_endpoint WITH (UPDLOCK,HOLDLOCK,ROWLOCK) WHERE " + where;
    }

    /**
     * Bind parameters:
     *  1 target record code
     *  2 prepare lease cutoff
     *  3 apply lease cutoff
     *  4 abort lease cutoff
     */
    String claimSql() {
        String predicate = """
                d.target_record_code=? AND (
                    (c.phase='prepare'
                     AND c.status='running'
                     AND d.delivery_status IN ('pending','running')
                     AND (d.delivery_status='pending' OR d.lease_expires_at IS NULL OR d.lease_expires_at<?))
                    OR
                    (c.phase='apply'
                     AND c.status='applying'
                     AND d.delivery_status IN ('pending','prepared','running','applying')
                     AND (d.delivery_status IN ('pending','prepared') OR d.lease_expires_at IS NULL OR d.lease_expires_at<?))
                    OR
                    (c.phase='abort'
                     AND c.status IN ('aborted','failed','expired')
                     AND d.delivery_status<>'aborted'
                     AND (d.delivery_status NOT IN ('running','applying') OR d.lease_expires_at IS NULL OR d.lease_expires_at<?))
                )
                """;

        if (postgres()) {
            return "SELECT d.id,d.command_id,c.command_type,c.phase " +
                    "FROM runtime_command_delivery d " +
                    "JOIN runtime_command c ON c.id=d.command_id " +
                    "WHERE " + predicate +
                    " ORDER BY c.created_at,d.id FOR UPDATE SKIP LOCKED LIMIT 1";
        }

        if (oracle()) {
            return "SELECT * FROM (SELECT d.id,d.command_id,c.command_type,c.phase " +
                    "FROM runtime_command_delivery d " +
                    "JOIN runtime_command c ON c.id=d.command_id " +
                    "WHERE " + predicate +
                    " ORDER BY c.created_at,d.id) WHERE ROWNUM=1 FOR UPDATE SKIP LOCKED";
        }

        return "SELECT TOP 1 d.id,d.command_id,c.command_type,c.phase " +
                "FROM runtime_command_delivery d WITH (UPDLOCK,READPAST,ROWLOCK) " +
                "JOIN runtime_command c ON c.id=d.command_id " +
                "WHERE " + predicate +
                " ORDER BY c.created_at,d.id";
    }

    void heartbeat(
            Connection connection,
            String recordCode,
            String environment,
            String service,
            String instance,
            String hostName) throws Exception {

        String sql = postgres()
                ? "INSERT INTO runtime_endpoint(record_code,environment,service_name,platform_type,instance_id,host_name,protocol,listen_port,status,registered_at,last_seen_at,updated_at,health_status) VALUES(?,?,?,'other',?,?,'http',8080,'active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'healthy') ON CONFLICT(record_code) DO UPDATE SET status='active',host_name=EXCLUDED.host_name,last_seen_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,health_status='healthy'"
                : oracle()
                    ? "MERGE INTO runtime_endpoint t USING (SELECT ? record_code,? environment,? service_name,? instance_id,? host_name FROM dual) s ON (t.record_code=s.record_code) WHEN MATCHED THEN UPDATE SET t.status='active',t.host_name=s.host_name,t.last_seen_at=CURRENT_TIMESTAMP,t.updated_at=CURRENT_TIMESTAMP,t.health_status='healthy' WHEN NOT MATCHED THEN INSERT(record_code,environment,service_name,platform_type,instance_id,host_name,protocol,listen_port,status,registered_at,last_seen_at,updated_at,health_status) VALUES(s.record_code,s.environment,s.service_name,'other',s.instance_id,s.host_name,'http',8080,'active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'healthy')"
                    : "MERGE runtime_endpoint WITH (HOLDLOCK) AS t USING (SELECT ? AS record_code,? AS environment,? AS service_name,? AS instance_id,? AS host_name) AS s ON t.record_code=s.record_code WHEN MATCHED THEN UPDATE SET status='active',host_name=s.host_name,last_seen_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,health_status='healthy' WHEN NOT MATCHED THEN INSERT(record_code,environment,service_name,platform_type,instance_id,host_name,protocol,listen_port,status,registered_at,last_seen_at,updated_at,health_status) VALUES(s.record_code,s.environment,s.service_name,'other',s.instance_id,s.host_name,'http',8080,'active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'healthy');";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, recordCode);
            statement.setString(2, environment);
            statement.setString(3, service);
            statement.setString(4, instance);
            statement.setString(5, hostName);
            statement.executeUpdate();
        }
    }

    static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
