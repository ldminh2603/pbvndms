package com.webcore.api.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.webcore.api.database.CoreDataSourceResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Service;

/** Durable S004 control plane. The database remains authoritative; signals only wake workers. */
@Service
public class ClusterCoordinatorService {
    private final CoreDataSourceResolver source;
    private final ClusterRuntimeProperties properties;
    private final StringRedisTemplate redis;
    private final ClusterSettingsResolver settings;

    public ClusterCoordinatorService(
            CoreDataSourceResolver source,
            ClusterRuntimeProperties properties,
            StringRedisTemplate redis,
            ClusterSettingsResolver settings) {
        this.source = source;
        this.properties = properties;
        this.redis = redis;
        this.settings = settings;
    }

    private JdbcTemplate db() {
        return new JdbcTemplate(source.resolve("DBCORE_ops"));
    }

    private ClusterSqlDialect dialect() {
        return new ClusterSqlDialect(source.provider("DBCORE_ops"));
    }

    private String resolveServiceName(String targetServiceName) {
        return targetServiceName == null || targetServiceName.isBlank()
                ? properties.serviceName()
                : targetServiceName.trim();
    }

    public EndpointOverview endpoints() {
        return endpoints(null);
    }

    public EndpointOverview endpoints(String targetServiceName) {
        String serviceName = resolveServiceName(targetServiceName);
        ClusterSettingsResolver.Settings config = settings.resolve();
        Instant freshnessCutoff = Instant.now().minusSeconds(config.deliveryLeaseSeconds());

        List<EndpointState> rows = db().query("""
                SELECT record_code, environment, service_name, platform_type, instance_id, host_name, status, health_status,
                       last_seen_at, updated_at, command_channel_mode, command_signal_provider,
                       command_signal_channel, command_signal_result_channel, command_signal_last_error
                FROM runtime_endpoint
                WHERE environment=? AND service_name=?
                ORDER BY last_seen_at DESC
        """, (rs, ignored) -> {
            var lastSeenAt = rs.getTimestamp("last_seen_at");
            boolean fresh = "active".equalsIgnoreCase(rs.getString("status"))
                    && "healthy".equalsIgnoreCase(rs.getString("health_status"))
                    && lastSeenAt != null
                    && lastSeenAt.toInstant().isAfter(freshnessCutoff);
            return new EndpointState(rs.getString("record_code"), rs.getString("environment"), rs.getString("service_name"),
                    rs.getString("platform_type"), rs.getString("instance_id"), rs.getString("host_name"), rs.getString("status"), rs.getString("health_status"),
                    lastSeenAt, rs.getTimestamp("updated_at"), rs.getString("command_channel_mode"), rs.getString("command_signal_provider"),
                    rs.getString("command_signal_channel"), rs.getString("command_signal_result_channel"), rs.getString("command_signal_last_error"),
                    fresh, fresh && serviceName.equals(properties.serviceName()));
        }, properties.environment(), serviceName);

        return new EndpointOverview(rows, coordination());
    }

    /** Reconciles stale heartbeat-backed endpoints before returning the current view. */
    public EndpointOverview refreshEndpoints(String targetServiceName) {
        String serviceName = resolveServiceName(targetServiceName);
        Instant cutoff = Instant.now().minusSeconds(settings.resolve().deliveryLeaseSeconds());

        // Keep this intentionally conservative: stale endpoints cease being active targets,
        // but health_status is left untouched because deployments may use provider-specific values.
        db().update("""
                UPDATE runtime_endpoint
                SET status='inactive',updated_at=CURRENT_TIMESTAMP
                WHERE environment=? AND service_name=? AND status='active'
                  AND (last_seen_at IS NULL OR last_seen_at<=?)
                """, properties.environment(), serviceName, ClusterSqlDialect.timestamp(cutoff));

        return endpoints(serviceName);
    }

    public CommandState reloadSystem(
            String requestedBy,
            String targetServiceName,
            List<String> targetRecordCodes) {
        return rollout("reload-system", requestedBy, targetServiceName, targetRecordCodes);
    }

    public CommandState reloadRole(
            String requestedBy,
            String targetServiceName,
            List<String> targetRecordCodes) {
        return rollout("reload-role", requestedBy, targetServiceName, targetRecordCodes);
    }

    /**
     * Synchronous one-shot rollout used by the stable S004 reload endpoints.
     * Manual prepare/apply/abort endpoints remain available for interactive control.
     */
    private CommandState rollout(
            String commandType,
            String requestedBy,
            String targetServiceName,
            List<String> targetRecordCodes) {

        CommandState created = prepare(
                commandType,
                targetServiceName,
                targetRecordCodes,
                requestedBy);

        long commandId = created.commandId();
        long waitSeconds = rolloutWaitSeconds();

        CommandState prepared = awaitPrepared(commandId, waitSeconds);
        if (!"prepared".equalsIgnoreCase(prepared.status())) {
            return prepared;
        }

        try {
            CommandState applying = apply(commandId, requestedBy);
            if (applying == null) {
                throw new IllegalStateException("Cluster command disappeared before apply: " + commandId);
            }
        } catch (RuntimeException failure) {
            CommandState current = command(commandId);
            if (current != null && !"completed".equalsIgnoreCase(current.phase())) {
                abort(commandId, "Apply failed: " + safeMessage(failure));
                return awaitCompleted(commandId, waitSeconds);
            }
            throw failure;
        }

        return awaitCompleted(commandId, waitSeconds);
    }

    private CommandState awaitPrepared(long commandId, long waitSeconds) {
        Instant deadline = Instant.now().plusSeconds(waitSeconds);

        while (Instant.now().isBefore(deadline)) {
            CommandState current = requireCommand(commandId);
            String status = current.status();
            String phase = current.phase();

            if ("prepare".equalsIgnoreCase(phase) && "prepared".equalsIgnoreCase(status)) {
                return current;
            }

            if ("abort".equalsIgnoreCase(phase) || "completed".equalsIgnoreCase(phase)) {
                return "completed".equalsIgnoreCase(phase)
                        ? current
                        : awaitCompleted(commandId, waitSeconds);
            }

            sleepForRollout();
        }

        abort(commandId, "Prepare timeout");
        return awaitCompleted(commandId, waitSeconds);
    }

    private CommandState awaitCompleted(long commandId, long waitSeconds) {
        Instant deadline = Instant.now().plusSeconds(waitSeconds);

        while (Instant.now().isBefore(deadline)) {
            CommandState current = requireCommand(commandId);
            if ("completed".equalsIgnoreCase(current.phase())) {
                return current;
            }
            sleepForRollout();
        }

        CommandState current = requireCommand(commandId);
        if (!"abort".equalsIgnoreCase(current.phase())
                && !"completed".equalsIgnoreCase(current.phase())) {
            abort(commandId, "Rollout timeout");
        }

        // Do not wait forever on an HTTP request. The durable workers continue the abort
        // in the background; return the authoritative command state at the deadline.
        return requireCommand(commandId);
    }

    private long rolloutWaitSeconds() {
        ClusterSettingsResolver.Settings config = settings.resolve();
        return Math.max(30L, Math.max(
                config.preparedCommandTtlSeconds(),
                config.deliveryLeaseSeconds() * 3L));
    }

    private CommandState requireCommand(long commandId) {
        CommandState current = command(commandId);
        if (current == null) {
            throw new IllegalStateException("Cluster command not found: " + commandId);
        }
        return current;
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    private static void sleepForRollout() {
        try {
            Thread.sleep(100L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cluster rollout interrupted.", interrupted);
        }
    }

    public CommandState prepare(String type, List<String> requestedTargets, String requestedBy) {
        return prepare(type, null, requestedTargets, requestedBy);
    }

    public CommandState prepare(
            String type,
            String targetServiceName,
            List<String> requestedTargets,
            String requestedBy) {

        validate(type);
        String serviceName = resolveServiceName(targetServiceName);
        ClusterSettingsResolver.Settings config = settings.resolve();
        List<String> eligibleTargets = db().queryForList(
                dialect().freshEndpointsSql(),
                String.class,
                properties.environment(),
                serviceName,
                ClusterSqlDialect.timestamp(Instant.now().minusSeconds(config.deliveryLeaseSeconds())));
        List<String> targets = requestedTargets == null || requestedTargets.isEmpty()
                ? eligibleTargets
                : requestedTargets.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();

        if (!eligibleTargets.containsAll(targets)) {
            throw new IllegalStateException("Every requested target must be active, healthy, and fresh.");
        }

        if (targets.isEmpty()) {
            throw new IllegalStateException("No active runtime endpoint is available for this rollout.");
        }

        long id;
        try (Connection connection = source.resolve("DBCORE_ops").getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockTargetService(connection, serviceName);
                assertNoActiveCommand(serviceName);
                id = dialect().nextId(connection, "runtime_command_id_seq");

                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO runtime_command(
                            id,command_type,target_service_name,coordination_mode,phase,status,
                            created_by,created_at,started_at,expires_at,notes)
                        VALUES(?,?,?,'prepare_apply','prepare','running',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)
                        """)) {
                    insert.setLong(1, id);
                    insert.setString(2, type);
                    insert.setString(3, serviceName);
                    insert.setString(4, requestedBy);
                    insert.setTimestamp(5, ClusterSqlDialect.timestamp(
                            Instant.now().plusSeconds(config.preparedCommandTtlSeconds())));
                    insert.setString(6, "Prepared by Spring S004 coordinator");
                    insert.executeUpdate();
                }

                for (String target : targets) {
                    try (PreparedStatement delivery = connection.prepareStatement("""
                            INSERT INTO runtime_command_delivery(
                                id,command_id,target_record_code,delivery_status,attempt_count,created_at,updated_at)
                            VALUES(?,?,?,'pending',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                            """)) {
                        delivery.setLong(1, dialect().nextId(connection, "runtime_command_delivery_id_seq"));
                        delivery.setLong(2, id);
                        delivery.setString(3, target);
                        delivery.executeUpdate();
                    }
                }

                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create cluster command.", failure);
        }

        signal(id, "prepare");
        return command(id);
    }

    public CommandState apply(long id, String requestedBy) {
        ClusterSettingsResolver.Settings config = settings.resolve();
        CommandState existing = command(id);
        if (existing == null) return null;

        String targetServiceName = existing.targetServiceName();

        Integer unresolved = db().queryForObject("""
                SELECT COUNT(*)
                FROM runtime_command_delivery
                WHERE command_id=? AND delivery_status<>'prepared'
                """, Integer.class, id);

        if (unresolved == null || unresolved != 0) {
            throw new IllegalStateException("Apply barrier is closed: every target must prepare successfully.");
        }

        if (config.requireCurrentTargetsOnApply()) {
            Integer missing = db().queryForObject("""
                    SELECT COUNT(*)
                    FROM runtime_command_delivery d
                    WHERE d.command_id=?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM runtime_endpoint e
                          WHERE e.record_code=d.target_record_code
                            AND e.environment=?
                            AND e.service_name=?
                            AND e.status='active'
                            AND e.health_status='healthy'
                            AND e.last_seen_at>?
                      )
                    """,
                    Integer.class,
                    id,
                    properties.environment(),
                    targetServiceName,
                    ClusterSqlDialect.timestamp(
                            Instant.now().minusSeconds(config.deliveryLeaseSeconds())));

            if (missing != null && missing > 0) {
                throw new IllegalStateException(
                        "Apply barrier is closed: one or more prepared targets are no longer current.");
            }
        }

        int changed = db().update("""
                UPDATE runtime_command
                SET phase='apply',status='applying',apply_started_at=CURRENT_TIMESTAMP,notes=?
                WHERE id=? AND phase='prepare' AND status IN ('running','prepared')
                """, "Apply requested by " + requestedBy, id);

        if (changed == 0) {
            throw new IllegalStateException("Command is not ready to apply.");
        }

        db().update("""
                UPDATE runtime_command_delivery
                SET delivery_status='pending', lease_expires_at=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE command_id=? AND delivery_status='prepared'
                """, id);

        signal(id, "apply");
        return command(id);
    }

    public CommandState abort(long id, String reason) {
        if (command(id) == null) return null;

        // Do not mark completed here. Workers must first discard/rollback every target.
        int changed = db().update("""
                UPDATE runtime_command
                SET phase='abort',status='aborted',completed_at=NULL,notes=?
                WHERE id=? AND phase<>'completed'
                """, reason == null || reason.isBlank() ? "Aborted" : reason, id);

        if (changed == 0) {
            return command(id);
        }

        // Keep current delivery states/leases. Abort claim respects an active lease so
        // it cannot overlap a prepare/apply that is still executing on the same node.
        signal(id, "abort");
        return command(id);
    }

    public CommandState command(long id) {
        List<CommandState> commands = db().query("""
                SELECT id AS "commandId",
                       command_type AS "commandType",
                       target_service_name AS "targetServiceName",
                       coordination_mode AS "coordinationMode",
                       phase,status,payload_hash AS "payloadHash",
                       desired_revision AS "desiredRevision",
                       created_by AS "createdBy",created_at AS "createdAt",
                       started_at AS "startedAt",prepared_at AS "preparedAt",
                       apply_started_at AS "applyStartedAt",completed_at AS "completedAt",
                       expires_at AS "expiresAt",last_signal_provider AS "lastSignalProvider",
                       last_signal_channel AS "lastSignalChannel",last_signal_status AS "lastSignalStatus",
                       last_signal_at AS "lastSignalAt",last_signal_error AS "lastSignalError",notes
                FROM runtime_command
                WHERE id=?
                """, (rs, ignored) -> commandState(rs, List.of()), id);

        if (commands.isEmpty()) return null;

        CommandState result = commands.getFirst();
        List<DeliveryState> deliveries = db().query("""
                SELECT id AS "deliveryId",target_record_code AS "targetRecordCode",
                       delivery_status AS "deliveryStatus",attempt_count AS "attemptCount",
                       started_at AS "startedAt",prepared_at AS "preparedAt",
                       apply_wake_via AS "applyWakeVia",apply_wake_channel AS "applyWakeChannel",
                       apply_wake_at AS "applyWakeAt",completed_at AS "completedAt",
                       finished_at AS "finishedAt",updated_at AS "updatedAt",
                       prepared_hash AS "preparedHash",prepared_revision AS "preparedRevision",
                       last_error AS "lastError",result_message AS "resultMessage"
                FROM runtime_command_delivery
                WHERE command_id=?
                ORDER BY id
                """, (rs, ignored) -> deliveryState(rs), id);

        return new CommandState(result.commandId(), result.commandType(), result.targetServiceName(), result.coordinationMode(),
                result.phase(), result.status(), result.payloadHash(), result.desiredRevision(), result.createdBy(), result.createdAt(),
                result.startedAt(), result.preparedAt(), result.applyStartedAt(), result.completedAt(), result.expiresAt(),
                result.lastSignalProvider(), result.lastSignalChannel(), result.lastSignalStatus(), result.lastSignalAt(),
                result.lastSignalError(), result.notes(), deliveries);
    }

    public CommandState latest(String type) {
        return latest(type, null);
    }

    public CommandState latest(String type, String targetServiceName) {
        validate(type);
        String serviceName = resolveServiceName(targetServiceName);

        String latest = dialect().oracle()
                ? "SELECT id FROM (SELECT id FROM runtime_command WHERE command_type=? AND target_service_name=? ORDER BY id DESC) WHERE ROWNUM=1"
                : dialect().postgres()
                    ? "SELECT id FROM runtime_command WHERE command_type=? AND target_service_name=? ORDER BY id DESC LIMIT 1"
                    : "SELECT TOP 1 id FROM runtime_command WHERE command_type=? AND target_service_name=? ORDER BY id DESC";

        List<Long> ids = db().queryForList(latest, Long.class, type, serviceName);
        return ids.isEmpty() ? null : command(ids.getFirst());
    }

    /** Matches .NET GetLatestActionableCommandAsync: terminal commands are ignored. */
    public CommandState latestActionable(String type, String targetServiceName) {
        validate(type);
        String serviceName = resolveServiceName(targetServiceName);
        String where = "command_type=? AND target_service_name=? "
                + "AND phase IN ('prepare','apply','abort')";
        String sql = dialect().oracle()
                ? "SELECT id FROM (SELECT id FROM runtime_command WHERE " + where + " ORDER BY id DESC) WHERE ROWNUM=1"
                : dialect().postgres()
                    ? "SELECT id FROM runtime_command WHERE " + where + " ORDER BY id DESC LIMIT 1"
                    : "SELECT TOP 1 id FROM runtime_command WHERE " + where + " ORDER BY id DESC";
        List<Long> ids = db().queryForList(sql, Long.class, type, serviceName);
        return ids.isEmpty() ? null : command(ids.getFirst());
    }

    /** Returns a blocking command regardless of reload type for the same target service. */
    public CommandState latestActionableForService(String targetServiceName) {
        String serviceName = resolveServiceName(targetServiceName);
        String where = "target_service_name=? AND phase IN ('prepare','apply','abort')";
        String sql = dialect().oracle()
                ? "SELECT id FROM (SELECT id FROM runtime_command WHERE " + where + " ORDER BY id DESC) WHERE ROWNUM=1"
                : dialect().postgres()
                    ? "SELECT id FROM runtime_command WHERE " + where + " ORDER BY id DESC LIMIT 1"
                    : "SELECT TOP 1 id FROM runtime_command WHERE " + where + " ORDER BY id DESC";
        List<Long> ids = db().queryForList(sql, Long.class, serviceName);
        return ids.isEmpty() ? null : command(ids.getFirst());
    }

    private void lockTargetService(Connection connection, String serviceName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(dialect().targetServiceLockSql())) {
            statement.setString(1, properties.environment());
            statement.setString(2, serviceName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    // Fetch every matching endpoint so its transaction lock is acquired.
                }
            }
        }
    }

    private void assertNoActiveCommand(String serviceName) {
        CommandState active = latestActionableForService(serviceName);
        if (active != null) throw new ActiveCommandConflictException(active);
    }

    /** Called only by the elected metadata monitor leader. Normal requests still use S004 prepare/apply. */
    public void requestMetadataInvalidation(String revision) {
        List<Long> active = db().queryForList("""
                SELECT id
                FROM runtime_command
                WHERE command_type='reload-system'
                  AND target_service_name=?
                  AND phase IN ('prepare','apply','abort')
                  AND status IN ('running','prepared','applying','failed','aborted','expired')
                """, Long.class, properties.serviceName());

        if (active.isEmpty()) {
            prepare("reload-system", properties.serviceName(), List.of(),
                    "system:metadata-watch:" + revision);
        }
    }

    /** Advances automatic metadata commands only after the normal prepare barrier has succeeded. */
    public void advanceMetadataInvalidation() {
        List<MetadataCommandState> commands = db().query("""
                SELECT id,phase,status,created_by
                FROM runtime_command
                WHERE created_by LIKE 'system:metadata-watch:%'
                  AND phase IN ('prepare','apply')
                ORDER BY id
                """, (rs, ignored) -> new MetadataCommandState(rs.getLong("id"), rs.getString("phase"), rs.getString("status")));

        for (MetadataCommandState command : commands) {
            if ("prepare".equalsIgnoreCase(command.phase())
                    && "prepared".equalsIgnoreCase(command.status())) {
                apply(command.commandId(), "system:metadata-watch");
            }
        }
    }

    public void signal(long id, String phase) {
        String channel = settings.resolve().channel();
        String payload = id + ":" + phase + ":" + Instant.now();

        try {
            if ("POSTGRES".equals(source.provider("DBCORE_ops"))) {
                db().execute("SELECT pg_notify(?, ?)",
                        (PreparedStatementCallback<Void>) statement -> {
                            statement.setString(1, channel);
                            statement.setString(2, payload);
                            statement.execute();
                            return null;
                        });

                db().update("""
                        UPDATE runtime_command
                        SET last_signal_provider='postgres',last_signal_channel=?,
                            last_signal_status='published',last_signal_at=CURRENT_TIMESTAMP,
                            last_signal_error=NULL
                        WHERE id=?
                        """, channel, id);
            } else {
                redis.convertAndSend(channel, payload);
                db().update("""
                        UPDATE runtime_command
                        SET last_signal_provider='redis',last_signal_channel=?,
                            last_signal_status='published',last_signal_at=CURRENT_TIMESTAMP,
                            last_signal_error=NULL
                        WHERE id=?
                        """, channel, id);
            }
        } catch (Exception failure) {
            db().update("""
                    UPDATE runtime_command
                    SET last_signal_status='failed',last_signal_error=?,last_signal_at=CURRENT_TIMESTAMP
                    WHERE id=?
                    """, failure.getMessage(), id);
        }
    }

    private static CommandState commandState(ResultSet rs, List<DeliveryState> deliveries) throws SQLException {
        return new CommandState(rs.getLong("commandId"), rs.getString("commandType"), rs.getString("targetServiceName"),
                rs.getString("coordinationMode"), rs.getString("phase"), rs.getString("status"), rs.getString("payloadHash"),
                rs.getString("desiredRevision"), rs.getString("createdBy"), rs.getTimestamp("createdAt"),
                rs.getTimestamp("startedAt"), rs.getTimestamp("preparedAt"), rs.getTimestamp("applyStartedAt"),
                rs.getTimestamp("completedAt"), rs.getTimestamp("expiresAt"), rs.getString("lastSignalProvider"),
                rs.getString("lastSignalChannel"), rs.getString("lastSignalStatus"), rs.getTimestamp("lastSignalAt"),
                rs.getString("lastSignalError"), rs.getString("notes"), deliveries);
    }

    private static DeliveryState deliveryState(ResultSet rs) throws SQLException {
        return new DeliveryState(rs.getLong("deliveryId"), rs.getString("targetRecordCode"), rs.getString("deliveryStatus"),
                rs.getInt("attemptCount"), rs.getTimestamp("startedAt"), rs.getTimestamp("preparedAt"),
                rs.getString("applyWakeVia"), rs.getString("applyWakeChannel"), rs.getTimestamp("applyWakeAt"),
                rs.getTimestamp("completedAt"), rs.getTimestamp("finishedAt"), rs.getTimestamp("updatedAt"),
                rs.getString("preparedHash"), rs.getString("preparedRevision"), rs.getString("lastError"),
                rs.getString("resultMessage"));
    }

    private CoordinationState coordination() {
        ClusterSettingsResolver.Settings config = settings.resolve();
        boolean clusterEnabled = properties.enabled();
        boolean postgres = "POSTGRES".equals(source.provider("DBCORE_ops"));
        return new CoordinationState("ops.runtime_command", "database", "ops.runtime_command_delivery", clusterEnabled,
                clusterEnabled ? (postgres ? "postgres" : "redis") : "disabled", clusterEnabled
                        ? (postgres ? "LISTEN/NOTIFY + poll" : "Redis Pub/Sub + poll")
                        : "disabled", clusterEnabled ? "" : "WEBCORE_CLUSTER_ENABLED=false", config.channel(),
                config.pollFallbackSeconds(), true, config.preparedCommandTtlSeconds(), config.deliveryLeaseSeconds(),
                config.requireCurrentTargetsOnApply(), config.autoSyncEnabled());
    }

    private static void validate(String type) {
        if (!"reload-system".equals(type) && !"reload-role".equals(type)) {
            throw new IllegalArgumentException("Unsupported runtime command.");
        }
    }
    public record EndpointOverview(List<EndpointState> endpoints, CoordinationState coordination) { }
    public record EndpointState(String recordCode, String environment, String serviceName, String platformType, String instanceId, String hostName,
                                String status, String healthStatus, Object lastSeenAt, Object updatedAt, String commandChannelMode,
                                String commandSignalProvider, String commandSignalChannel, String commandSignalResultChannel,
                                String commandSignalLastError, boolean isFresh, boolean isDefaultTarget) { }
    public record CoordinationState(String controlPlane, String commandStore, String deliveryStore, boolean signalEnabled,
                                    String signalProvider, String signalTransport, String disabledReason, String signalChannel,
                                    Object pollFallbackSeconds, boolean signalProviderImplemented, Object preparedCommandTtlSeconds,
                                    Object deliveryLeaseSeconds, boolean requireCurrentTargetsOnApply, boolean autoSyncEnabled) { }
    public record CommandState(long commandId, String commandType, String targetServiceName, String coordinationMode,
                               String phase, String status, String payloadHash, String desiredRevision, String createdBy,
                               Timestamp createdAt, Timestamp startedAt, Timestamp preparedAt, Timestamp applyStartedAt,
                               Timestamp completedAt, Timestamp expiresAt, String lastSignalProvider, String lastSignalChannel,
                               String lastSignalStatus, Timestamp lastSignalAt, String lastSignalError, String notes,
                               List<DeliveryState> deliveries) { }
    public record DeliveryState(long deliveryId, String targetRecordCode, String deliveryStatus, int attemptCount,
                                Timestamp startedAt, Timestamp preparedAt, String applyWakeVia, String applyWakeChannel,
                                Timestamp applyWakeAt, Timestamp completedAt, Timestamp finishedAt, Timestamp updatedAt,
                                String preparedHash, String preparedRevision, String lastError, String resultMessage) { }
    /** A command remains active until its phase moves from prepare/apply/abort to completed. */
    public static final class ActiveCommandConflictException extends IllegalStateException {
        private final CommandState activeCommand;

        public ActiveCommandConflictException(CommandState activeCommand) {
            super("Runtime command " + activeCommand.commandId() + " is still "
                    + activeCommand.phase() + "/" + activeCommand.status() + ".");
            this.activeCommand = activeCommand;
        }

        public CommandState activeCommand() {
            return activeCommand;
        }
    }
    private record MetadataCommandState(long commandId, String phase, String status) { }
}
