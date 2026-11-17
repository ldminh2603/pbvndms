package com.webcore.api.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.webcore.api.database.CoreDataSourceResolver;
import com.webcore.api.runtime.RuntimeReloadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Durable per-node delivery worker. It uses the same ops tables as .NET and
 * deliberately polls as a lossless fallback; PostgreSQL NOTIFY/Redis only wake
 * this worker early and never replace the durable delivery record.
 */
@Service
public class ClusterCommandWorker {
    private static final Logger log = LoggerFactory.getLogger(ClusterCommandWorker.class);

    private final CoreDataSourceResolver coreSource;
    private final ClusterRuntimeProperties properties;
    private final RuntimeReloadService reloads;
    private final ClusterSettingsResolver settings;
    private final AtomicBoolean draining = new AtomicBoolean();
    private volatile Instant nextPollAt = Instant.EPOCH;

    public ClusterCommandWorker(
            CoreDataSourceResolver coreSource,
            ClusterRuntimeProperties properties,
            RuntimeReloadService reloads,
            ClusterSettingsResolver settings) {
        this.coreSource = coreSource;
        this.properties = properties;
        this.reloads = reloads;
        this.settings = settings;
    }

    private JdbcTemplate db() {
        return new JdbcTemplate(coreSource.resolve("DBCORE_ops"));
    }

    private ClusterSqlDialect dialect() {
        return new ClusterSqlDialect(coreSource.provider("DBCORE_ops"));
    }

    @Scheduled(fixedDelay = 500)
    public void poll() {
        if (!properties.enabled()) return;

        Instant now = Instant.now();
        if (now.isBefore(nextPollAt)) return;

        nextPollAt = now.plusSeconds(settings.resolve().pollFallbackSeconds());
        triggerDrain();
    }

    /** A reload may take longer than a delivery lease; its endpoint must remain fresh while it runs. */
    @Scheduled(fixedDelay = 5_000)
    public void heartbeatWhileBusy() {
        if (!properties.enabled()) return;
        try {
            heartbeat();
        } catch (Exception exception) {
            log.warn("cluster endpoint heartbeat failed instance={}", properties.instanceId(), exception);
        }
    }

    /** Called by LISTEN/NOTIFY or Redis; push wake-up bypasses the fallback poll interval. */
    public void wake() {
        if (!properties.enabled()) return;
        triggerDrain();
    }

    private void triggerDrain() {
        if (!draining.compareAndSet(false, true)) return;
        try {
            drain();
        } finally {
            draining.set(false);
        }
    }

    private void drain() {
        heartbeat();
        reconcileInterruptedCommands();
        reconcileAbortingCommands();
        reconcileCompletedCommands();

        // Prepared commands must expire too. Do not mark completed yet: each target
        // still has to discard/rollback its staged snapshot through the abort phase.
        db().update("""
                UPDATE runtime_command
                SET status='expired',
                    phase='abort',
                    notes=COALESCE(notes,'Prepare expired')
                WHERE phase='prepare'
                  AND status IN ('running','prepared')
                  AND expires_at IS NOT NULL
                  AND expires_at<CURRENT_TIMESTAMP
                """);

        // Expiry changes a command to abort after the first reconciliation pass.
        // Run it again so an already-stale target cannot leave the command blocking
        // a new reload until the next poll cycle.
        reconcileAbortingCommands();
        reconcileCompletedCommands();

        while (true) {
            Work work = claim();
            if (work == null) return;

            try {
                if ("prepare".equals(work.phase())) {
                    RuntimeReloadService.StageResult stage = reloads.prepare(work.commandId(), work.commandType());
                    db().update("""
                            UPDATE runtime_command_delivery
                            SET delivery_status='prepared',
                                prepared_at=CURRENT_TIMESTAMP,
                                prepared_hash=?,
                                prepared_revision=?,
                                result_message=?,
                                lease_expires_at=NULL,
                                updated_at=CURRENT_TIMESTAMP
                            WHERE id=?
                            """,
                            stage.hash(),
                            String.valueOf(stage.loadedAtUtc()),
                            stage.message(),
                            work.deliveryId());

                    db().update("""
                            UPDATE runtime_command
                            SET status='prepared', prepared_at=CURRENT_TIMESTAMP,
                                payload_hash=COALESCE(payload_hash,?)
                            WHERE id=?
                              AND phase='prepare'
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM runtime_command_delivery
                                  WHERE command_id=?
                                  AND delivery_status<>'prepared'
                              )
                            """, stage.hash(), work.commandId(), work.commandId());

                } else if ("apply".equals(work.phase())) {
                    RuntimeReloadService.ReloadResult applied =
                            reloads.applyStaged(work.commandId(), "cluster:" + properties.instanceId());

                    db().update("""
                            UPDATE runtime_command_delivery
                            SET delivery_status='succeeded',
                                completed_at=CURRENT_TIMESTAMP,
                                finished_at=CURRENT_TIMESTAMP,
                                result_message=?,
                                lease_expires_at=NULL,
                                updated_at=CURRENT_TIMESTAMP
                            WHERE id=?
                            """, applied.message(), work.deliveryId());

                    db().update("""
                            UPDATE runtime_command
                            SET status='succeeded',
                                phase='completed',
                                completed_at=CURRENT_TIMESTAMP
                            WHERE id=?
                              AND phase='apply'
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM runtime_command_delivery
                                  WHERE command_id=?
                                    AND delivery_status<>'succeeded'
                              )
                            """, work.commandId(), work.commandId());

                } else if ("abort".equals(work.phase())) {
                    boolean rolledBack = reloads.abortStaged(work.commandId());
                    db().update("""
                            UPDATE runtime_command_delivery
                            SET delivery_status='aborted',
                                finished_at=CURRENT_TIMESTAMP,
                                result_message=?,
                                lease_expires_at=NULL,
                                updated_at=CURRENT_TIMESTAMP
                            WHERE id=?
                            """,
                            rolledBack
                                    ? "Aborted; prior runtime snapshot restored."
                                    : "Aborted before apply; staged snapshot discarded.",
                            work.deliveryId());

                    // Preserve the reason status (aborted/failed/expired), but only mark
                    // the command completed after every delivery has finished aborting.
                    db().update("""
                            UPDATE runtime_command
                            SET phase='completed', completed_at=CURRENT_TIMESTAMP
                            WHERE id=?
                              AND phase='abort'
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM runtime_command_delivery
                                  WHERE command_id=?
                                    AND delivery_status<>'aborted'
                              )
                            """, work.commandId(), work.commandId());

                } else {
                    throw new IllegalStateException("Unsupported command phase: " + work.phase());
                }
            } catch (Exception exception) {
                db().update("""
                        UPDATE runtime_command_delivery
                        SET delivery_status='failed',
                            finished_at=CURRENT_TIMESTAMP,
                            last_error=?,
                            lease_expires_at=NULL,
                            updated_at=CURRENT_TIMESTAMP
                        WHERE id=?
                        """, exception.getMessage(), work.deliveryId());

                // Abort is not complete yet; other targets still need to discard/rollback.
                db().update("""
                        UPDATE runtime_command
                        SET status='failed', phase='abort', completed_at=NULL
                        WHERE id=? AND phase<>'completed'
                        """, work.commandId());

                log.error("cluster command failed commandId={} deliveryId={}",
                        work.commandId(), work.deliveryId(), exception);
            }
        }
    }

    /**
     * A Kubernetes rollout can replace a target while it is preparing or applying.
     * The replacement pod cannot safely inherit that pod's staged snapshot, so the
     * command must abort as a whole. Continuing with only the surviving targets
     * would make the reload non-atomic, while leaving the old delivery pending
     * would block the cluster until the prepare TTL expires.
     */
    private void reconcileInterruptedCommands() {
        Instant cutoff = Instant.now().minusSeconds(settings.resolve().deliveryLeaseSeconds());
        List<Long> interruptedCommandIds = db().queryForList("""
                SELECT DISTINCT c.id
                FROM runtime_command c
                JOIN runtime_command_delivery d ON d.command_id=c.id
                WHERE c.phase IN ('prepare','apply')
                  AND c.status IN ('running','prepared','applying')
                  AND (
                      d.delivery_status IN ('pending','prepared')
                      OR (
                          d.delivery_status IN ('running','applying')
                          AND (d.lease_expires_at IS NULL OR d.lease_expires_at<CURRENT_TIMESTAMP)
                      )
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM runtime_endpoint e
                      WHERE e.record_code=d.target_record_code
                        AND e.environment=?
                        AND e.service_name=c.target_service_name
                        AND e.status='active'
                        AND e.health_status='healthy'
                        AND e.last_seen_at>?
                  )
                """, Long.class, properties.environment(), ClusterSqlDialect.timestamp(cutoff));

        for (Long commandId : interruptedCommandIds) {
            db().update("""
                    UPDATE runtime_command
                    SET phase='abort',
                        status='aborted',
                        completed_at=NULL,
                        notes='Aborted because a target endpoint was replaced or became stale during reload.'
                    WHERE id=?
                      AND phase IN ('prepare','apply')
                      AND status IN ('running','prepared','applying')
                    """, commandId);
        }
    }

    /**
     * Finishes durable aborts even when a Kubernetes pod was replaced while a
     * command was in flight. A disappeared pod has lost its in-memory staged
     * snapshot, so there is nothing left on that target to roll back. Keeping
     * its old delivery pending would otherwise lock Core Data, Role/Permission,
     * and Auto Sync forever.
     */
    private void reconcileAbortingCommands() {
        Instant cutoff = Instant.now().minusSeconds(settings.resolve().deliveryLeaseSeconds());

        List<Long> staleDeliveryIds = db().queryForList("""
                SELECT d.id
                FROM runtime_command_delivery d
                JOIN runtime_command c ON c.id=d.command_id
                WHERE c.phase='abort'
                  AND c.status IN ('aborted','failed','expired')
                  AND d.delivery_status<>'aborted'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM runtime_endpoint e
                      WHERE e.record_code=d.target_record_code
                        AND e.environment=?
                        AND e.service_name=c.target_service_name
                        AND e.status='active'
                        AND e.last_seen_at>?
                  )
                """, Long.class, properties.environment(), ClusterSqlDialect.timestamp(cutoff));

        for (Long deliveryId : staleDeliveryIds) {
            db().update("""
                    UPDATE runtime_command_delivery
                    SET delivery_status='aborted',
                        finished_at=COALESCE(finished_at,CURRENT_TIMESTAMP),
                        lease_expires_at=NULL,
                        result_message=COALESCE(result_message,'Target endpoint is no longer active; abort cleanup completed.'),
                        updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND delivery_status<>'aborted'
                    """, deliveryId);
        }

        // A completion check must not depend on the last target's worker. It also
        // repairs legacy commands whose deliveries were already all aborted.
        db().update("""
                UPDATE runtime_command
                SET phase='completed',completed_at=COALESCE(completed_at,CURRENT_TIMESTAMP)
                WHERE phase='abort'
                  AND status IN ('aborted','failed','expired')
                  AND NOT EXISTS (
                      SELECT 1
                      FROM runtime_command_delivery d
                      WHERE d.command_id=runtime_command.id
                        AND d.delivery_status<>'aborted'
                  )
                """);
    }

    /**
     * A staged snapshot is needed only while a command can still abort. Once the
     * durable command is completed/succeeded, every target has applied and the
     * prior snapshot must be released from this JVM. This is intentionally
     * per-node: the map is local process state, while runtime_command is the
     * cluster-wide finalization barrier.
     */
    private void reconcileCompletedCommands() {
        for (Long commandId : reloads.stagedCommandIds()) {
            Boolean completed = db().query("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM runtime_command c
                        JOIN runtime_command_delivery d ON d.command_id=c.id
                        WHERE c.id=?
                          AND c.phase='completed'
                          AND c.status='succeeded'
                          AND d.target_record_code=?
                          AND d.delivery_status='succeeded'
                    )
                    """, rows -> rows.next() && rows.getBoolean(1), commandId, recordCode());
            if (Boolean.TRUE.equals(completed) && reloads.finalizeStaged(commandId)) {
                log.info("cluster command finalized commandId={} instance={}", commandId, properties.instanceId());
            }
        }
    }

    private void heartbeat() {
        String record = recordCode();
        try (Connection connection = coreSource.resolve("DBCORE_ops").getConnection()) {
            dialect().heartbeat(
                    connection,
                    record,
                    properties.environment(),
                    properties.serviceName(),
                    properties.instanceId(),
                    properties.hostName() == null || properties.hostName().isBlank()
                            ? properties.instanceId()
                            : properties.hostName());
        } catch (Exception exception) {
            throw new IllegalStateException("Cluster endpoint heartbeat failed.", exception);
        }
    }

    private Work claim() {
        Instant now = Instant.now();
        try (Connection connection = coreSource.resolve("DBCORE_ops").getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement select = connection.prepareStatement(dialect().claimSql())) {
                select.setString(1, recordCode());
                select.setTimestamp(2, ClusterSqlDialect.timestamp(now));
                select.setTimestamp(3, ClusterSqlDialect.timestamp(now));
                select.setTimestamp(4, ClusterSqlDialect.timestamp(now));

                try (ResultSet rows = select.executeQuery()) {
                    if (!rows.next()) {
                        connection.commit();
                        return null;
                    }

                    Work work = new Work(
                            rows.getLong(1),
                            rows.getLong(2),
                            rows.getString(3),
                            rows.getString(4));

                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE runtime_command_delivery
                            SET delivery_status=?,
                                started_at=COALESCE(started_at,CURRENT_TIMESTAMP),
                                attempt_count=attempt_count+1,
                                leased_by_record_code=?,
                                leased_at=CURRENT_TIMESTAMP,
                                lease_expires_at=?,
                                updated_at=CURRENT_TIMESTAMP
                            WHERE id=?
                            """)) {
                        update.setString(1, "apply".equals(work.phase()) ? "applying" : "running");
                        update.setString(2, recordCode());
                        update.setTimestamp(3, ClusterSqlDialect.timestamp(
                                now.plusSeconds(settings.resolve().deliveryLeaseSeconds())));
                        update.setLong(4, work.deliveryId());
                        update.executeUpdate();
                    }

                    connection.commit();
                    return work;
                }
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Cluster delivery claim failed.", failure);
        }
    }

    public String recordCode() {
        return properties.environment() + "|" + properties.serviceName() + "|java|" + properties.instanceId();
    }

    public record Work(long deliveryId, long commandId, String commandType, String phase) { }
}
