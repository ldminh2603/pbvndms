package com.webcore.api.cluster;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.webcore.api.database.CoreDataSourceResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC repository for ops.cluster_auto_sync_job and ops.cluster_auto_sync_job_run. */
@Repository
public class RuntimeCommandAutoSyncRepository {
    private final CoreDataSourceResolver source;

    public RuntimeCommandAutoSyncRepository(CoreDataSourceResolver source) {
        this.source = source;
    }

    private JdbcTemplate db() {
        return new JdbcTemplate(source.resolve("DBCORE_ops"));
    }

    private ClusterSqlDialect dialect() {
        return new ClusterSqlDialect(source.provider("DBCORE_ops"));
    }

    public Overview overview(int historyLimit) {
        return new Overview(jobs(), recentRuns(Math.max(1, Math.min(historyLimit, 500))));
    }

    public List<Job> jobs() {
        return db().query("""
                SELECT job_name,command_type,enabled,interval_seconds,next_run_at,
                       lease_owner,lease_until,last_started_at,last_started_command_id,
                       last_completed_at,last_completed_command_id,last_status,last_message,updated_at
                FROM cluster_auto_sync_job
                ORDER BY job_name
                """, (rs, ignored) -> new Job(rs.getString("job_name"), rs.getString("command_type"), rs.getBoolean("enabled"),
                rs.getInt("interval_seconds"), rs.getTimestamp("next_run_at"), rs.getString("lease_owner"), rs.getTimestamp("lease_until"),
                rs.getTimestamp("last_started_at"), longValue(rs.getObject("last_started_command_id")), rs.getTimestamp("last_completed_at"),
                longValue(rs.getObject("last_completed_command_id")), rs.getString("last_status"), rs.getString("last_message"), rs.getTimestamp("updated_at")));
    }

    public List<Run> recentRuns(int historyLimit) {
        String select = """
                SELECT r.id,r.job_name,r.command_type,r.run_kind,r.scheduled_for,r.lease_owner,
                       r.status,r.command_id,r.payload_hash,r.started_at,r.finished_at,
                       r.duration_seconds,r.skip_reason,r.error_message,r.result_message,r.created_at
                FROM cluster_auto_sync_job_run r
                ORDER BY r.id DESC
                """;
        String sql = dialect().oracle()
                ? "SELECT * FROM (" + select + ") WHERE ROWNUM <= ?"
                : dialect().postgres()
                    ? select + " LIMIT ?"
                    : select.replace("SELECT ", "SELECT TOP (?) ");

        List<Run> runs = db().query(sql, (rs, ignored) -> {
            long runId = rs.getLong("id");
            Long commandId = longValue(rs.getObject("command_id"));
            DeliverySummary summary = deliverySummary(commandId);
            return new Run(runId, rs.getString("job_name"), rs.getString("command_type"), rs.getString("run_kind"),
                    rs.getTimestamp("scheduled_for"), rs.getString("lease_owner"), rs.getString("status"), commandId,
                    rs.getString("payload_hash"), summary.targetCount(), summary.preparedCount(), summary.succeededCount(),
                    summary.failedCount(), summary.pendingCount(), rs.getTimestamp("started_at"), rs.getTimestamp("finished_at"),
                    rs.getObject("duration_seconds"), rs.getString("skip_reason"), rs.getString("error_message"),
                    rs.getString("result_message"), rs.getTimestamp("created_at"));
        }, historyLimit);
        return runs;
    }

    private DeliverySummary deliverySummary(Long commandId) {
        if (commandId == null) {
            return new DeliverySummary(0, 0, 0, 0, 0);
        }
        return db().queryForObject("""
                SELECT COUNT(*) AS target_count,
                       SUM(CASE WHEN delivery_status IN ('prepared','applying','succeeded') THEN 1 ELSE 0 END) AS prepared_count,
                       SUM(CASE WHEN delivery_status='succeeded' THEN 1 ELSE 0 END) AS succeeded_count,
                       SUM(CASE WHEN delivery_status IN ('failed','aborted','expired','skipped','rollback_pending') THEN 1 ELSE 0 END) AS failed_count,
                       SUM(CASE WHEN delivery_status IN ('pending','leased','running') THEN 1 ELSE 0 END) AS pending_count
                FROM runtime_command_delivery
                WHERE command_id=?
                """, (rs, ignored) -> new DeliverySummary(rs.getInt("target_count"), rs.getInt("prepared_count"),
                        rs.getInt("succeeded_count"), rs.getInt("failed_count"), rs.getInt("pending_count")), commandId);
    }

    public Job updateJob(String jobName, Boolean enabled, Integer intervalSeconds) {
        int changed = db().update("""
                UPDATE cluster_auto_sync_job
                SET enabled=COALESCE(?,enabled),
                    interval_seconds=COALESCE(?,interval_seconds),
                    next_run_at=CASE WHEN ? IS NULL THEN next_run_at ELSE CURRENT_TIMESTAMP END,
                    updated_at=CURRENT_TIMESTAMP
                WHERE job_name=?
                """, enabled, intervalSeconds, intervalSeconds, jobName);
        if (changed == 0) return null;
        if (intervalSeconds != null) {
            db().update("UPDATE cluster_auto_sync_job SET next_run_at=? WHERE job_name=?",
                    Timestamp.from(Instant.now().plusSeconds(intervalSeconds)), jobName);
        }
        return job(jobName);
    }

    public Job tryClaimJobNow(String jobName, String leaseOwner, int leaseSeconds) {
        Instant now = Instant.now();
        int changed = db().update("""
                UPDATE cluster_auto_sync_job
                SET lease_owner=?,lease_until=?,last_started_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                WHERE job_name=? AND (lease_until IS NULL OR lease_until<=?)
                """, leaseOwner, Timestamp.from(now.plusSeconds(Math.max(10, leaseSeconds))), jobName, Timestamp.from(now));
        return changed == 0 ? null : job(jobName);
    }

    public List<Job> claimDueJobs(String leaseOwner, int leaseSeconds, int maxJobs) {
        int limit = Math.max(1, Math.min(maxJobs, 10));
        Instant now = Instant.now();
        String base = "SELECT job_name FROM cluster_auto_sync_job WHERE enabled=" + trueLiteral()
                + " AND next_run_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_run_at,job_name";
        String sql = dialect().oracle()
                ? "SELECT * FROM (" + base + ") WHERE ROWNUM <= " + limit
                : dialect().postgres()
                    ? base + " LIMIT " + limit
                    : base.replace("SELECT ", "SELECT TOP " + limit + " ");

        List<String> names = db().queryForList(sql, String.class, Timestamp.from(now), Timestamp.from(now));
        List<Job> claimed = new java.util.ArrayList<>();
        for (String name : names) {
            int changed = db().update("""
                    UPDATE cluster_auto_sync_job
                    SET lease_owner=?,lease_until=?,last_started_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                    WHERE job_name=? AND enabled=""" + trueLiteral() + " AND (lease_until IS NULL OR lease_until<=?)",
                    leaseOwner, Timestamp.from(now.plusSeconds(Math.max(10, leaseSeconds))), name, Timestamp.from(now));
            if (changed > 0) claimed.add(job(name));
        }
        return claimed;
    }

    public long upsertRunStarted(Job job, String runKind, Instant scheduledFor, String leaseOwner) {
        String jobName = job.jobName();
        String commandType = job.commandType();
        String normalizedKind = "manual".equalsIgnoreCase(runKind) ? "manual" : "scheduled";
        Timestamp scheduled = Timestamp.from(scheduledFor);

        int changed = db().update("""
                UPDATE cluster_auto_sync_job_run
                SET lease_owner=?,status='running',started_at=CURRENT_TIMESTAMP,finished_at=NULL,
                    duration_seconds=NULL,skip_reason=NULL,error_message=NULL,result_message=NULL
                WHERE job_name=? AND scheduled_for=? AND run_kind=?
                """, leaseOwner, jobName, scheduled, normalizedKind);

        if (changed == 0) {
            db().update("""
                    INSERT INTO cluster_auto_sync_job_run(
                        job_name,command_type,run_kind,scheduled_for,lease_owner,status,started_at,created_at)
                    VALUES(?,?,?,?,?,'running',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, jobName, commandType, normalizedKind, scheduled, leaseOwner);
        }

        Long id = db().queryForObject("""
                SELECT id FROM cluster_auto_sync_job_run
                WHERE job_name=? AND scheduled_for=? AND run_kind=?
                """, Long.class, jobName, scheduled, normalizedKind);
        if (id == null) throw new IllegalStateException("Auto sync run was created but could not be read.");
        return id;
    }

    public void completeRun(long runId, String jobName, String leaseOwner, String status,
                            boolean advanceSchedule, Long commandId, Long startedCommandId,
                            String payloadHash, Double durationSeconds, Integer nextRunDelaySeconds,
                            String skipReason, String errorMessage, String resultMessage) {
        db().update("""
                UPDATE cluster_auto_sync_job_run
                SET status=?,command_id=?,payload_hash=?,finished_at=CURRENT_TIMESTAMP,
                    duration_seconds=?,skip_reason=?,error_message=?,result_message=?
                WHERE id=?
                """, status, commandId, payloadHash, durationSeconds, skipReason, errorMessage, resultMessage, runId);

        Job job = job(jobName);
        if (job == null) return;
        int interval = job.intervalSeconds();
        int nextDelay = nextRunDelaySeconds == null ? interval : Math.max(1, nextRunDelaySeconds);
        Timestamp next = advanceSchedule ? Timestamp.from(Instant.now().plusSeconds(nextDelay)) : job.nextRunAt();
        db().update("""
                UPDATE cluster_auto_sync_job
                SET lease_owner=CASE WHEN lease_owner=? THEN NULL ELSE lease_owner END,
                    lease_until=CASE WHEN lease_owner=? THEN NULL ELSE lease_until END,
                    next_run_at=?,
                    last_started_command_id=COALESCE(?,last_started_command_id),
                    last_completed_at=CURRENT_TIMESTAMP,
                    last_completed_command_id=COALESCE(?,last_completed_command_id),
                    last_status=?,last_message=?,updated_at=CURRENT_TIMESTAMP
                WHERE job_name=?
                """, leaseOwner, leaseOwner, next, startedCommandId, startedCommandId, status, resultMessage, jobName);
    }

    public Job job(String jobName) {
        List<Job> rows = jobs().stream()
                .filter(row -> jobName.equalsIgnoreCase(row.jobName()))
                .toList();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String trueLiteral() {
        return dialect().oracle() ? "1" : dialect().postgres() ? "TRUE" : "1";
    }
    private static Long longValue(Object value) { return value instanceof Number number ? number.longValue() : null; }
    public record Overview(List<Job> jobs, List<Run> runs) { }
    public record Job(String jobName, String commandType, boolean enabled, int intervalSeconds, Timestamp nextRunAt,
                      String leaseOwner, Timestamp leaseUntil, Timestamp lastStartedAt, Long lastStartedCommandId,
                      Timestamp lastCompletedAt, Long lastCompletedCommandId, String lastStatus, String lastMessage, Timestamp updatedAt) { }
    public record Run(long runId, String jobName, String commandType, String runKind, Timestamp scheduledFor, String leaseOwner,
                      String status, Long commandId, String payloadHash, int targetCount, int preparedCount, int succeededCount,
                      int failedCount, int pendingCount, Timestamp startedAt, Timestamp finishedAt, Object durationSeconds,
                      String skipReason, String errorMessage, String resultMessage, Timestamp createdAt) { }
    private record DeliverySummary(int targetCount, int preparedCount, int succeededCount, int failedCount, int pendingCount) { }
}
