package com.webcore.api.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Java equivalent of the .NET RuntimeCommandAutoSyncService. */
@Service
public class RuntimeCommandAutoSyncService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeCommandAutoSyncService.class);
    private static final int DEFAULT_HISTORY_LIMIT = 25;
    private static final int MAX_JOBS_PER_CYCLE = 1;
    private static final int SYSTEM_RELOAD_BLOCKED_RETRY_SECONDS = 30;

    private final RuntimeCommandAutoSyncRepository repository;
    private final ClusterCoordinatorService coordinator;
    private final ClusterCommandWorker worker;
    private final ClusterSettingsResolver settings;

    public RuntimeCommandAutoSyncService(
            RuntimeCommandAutoSyncRepository repository,
            ClusterCoordinatorService coordinator,
            ClusterCommandWorker worker,
            ClusterSettingsResolver settings) {
        this.repository = repository;
        this.coordinator = coordinator;
        this.worker = worker;
        this.settings = settings;
    }

    public RuntimeCommandAutoSyncRepository.Overview getOverview() {
        return repository.overview(DEFAULT_HISTORY_LIMIT);
    }

    public RuntimeCommandAutoSyncRepository.Overview updateJob(String jobName, Boolean enabled, Integer intervalSeconds) {
        String normalized = normalizeJobName(jobName);
        Integer normalizedInterval = intervalSeconds == null ? null : Math.max(30, Math.min(intervalSeconds, 86_400));
        RuntimeCommandAutoSyncRepository.Job updated = repository.updateJob(normalized, enabled, normalizedInterval);
        if (updated == null) throw new IllegalStateException("Auto sync job '" + jobName + "' was not found.");
        return getOverview();
    }

    public RuntimeCommandAutoSyncRepository.Overview runJobNow(String jobName, String createdBy, Integer intervalSeconds) {
        String normalized = normalizeJobName(jobName);
        RuntimeCommandAutoSyncRepository.Job configuredJob = repository.job(normalized);
        if (configuredJob == null) {
            throw new IllegalStateException("Auto sync job '" + jobName + "' was not found.");
        }
        ClusterCoordinatorService.CommandState active = coordinator.latestActionableForService(null);
        if (active != null) throw new ClusterCoordinatorService.ActiveCommandConflictException(active);
        if (intervalSeconds != null) {
            int normalizedInterval = Math.max(30, Math.min(intervalSeconds, 86_400));
            if (repository.updateJob(normalized, null, normalizedInterval) == null) {
                throw new IllegalStateException("Auto sync job '" + jobName + "' was not found.");
            }
        }
        RuntimeCommandAutoSyncRepository.Job job = repository.tryClaimJobNow(
                normalized, worker.recordCode(), resolveLeaseSeconds());
        if (job == null) {
            throw new IllegalStateException("Auto sync job '" + jobName + "' is already running; wait for its current run to finish.");
        }

        // A manual run has the same durable prepare/apply lifecycle as a scheduled
        // run, but must not hold the browser request open for the prepare TTL.
        // Insert the run row before starting the background work so both Core and
        // Role display an authoritative `running` row in the first HTTP response.
        Instant scheduledFor = Instant.now();
        long runId = repository.upsertRunStarted(job, "manual", scheduledFor, worker.recordCode());
        CompletableFuture.runAsync(() -> {
            try {
                executeClaimedJob(job, "manual", false, normalizeCreatedBy(createdBy), runId, scheduledFor);
            } catch (Throwable failure) {
                // executeClaimedJob closes expected failures into the run history.
                // This guard is only for failures before that work can begin.
                log.error("Manual auto sync job '{}' stopped unexpectedly.", normalized, failure);
            }
        });
        return getOverview();
    }

    public void runDueJobs() {
        if (!settings.resolve().autoSyncEnabled()) return;
        List<RuntimeCommandAutoSyncRepository.Job> claimed = repository.claimDueJobs(
                worker.recordCode(), resolveLeaseSeconds(), MAX_JOBS_PER_CYCLE);
        for (RuntimeCommandAutoSyncRepository.Job job : claimed) {
            executeClaimedJob(job, "scheduled", true, "system:auto-sync", null, null);
        }
    }

    private void executeClaimedJob(RuntimeCommandAutoSyncRepository.Job job, String runKind,
                                   boolean advanceSchedule, String createdBy, Long existingRunId, Instant existingScheduledFor) {
        String jobName = job.jobName();
        String commandType = job.commandType();
        Instant scheduledFor = existingScheduledFor != null
                ? existingScheduledFor
                : "manual".equalsIgnoreCase(runKind) ? Instant.now() : toInstant(job.nextRunAt());
        long runId = existingRunId != null
                ? existingRunId
                : repository.upsertRunStarted(job, runKind, scheduledFor, worker.recordCode());
        Instant started = Instant.now();

        try {
            ClusterCoordinatorService.CommandState active = coordinator.latestActionableForService(null);
            if (active != null) {
                Integer retryDelay = blockedRetryDelaySeconds(job, advanceSchedule);
                String message = "Skipped because runtime command " + active.commandId() + " is still "
                        + active.phase() + "/" + active.status() + ".";
                if (retryDelay != null) {
                    message += " reload-system will retry in " + retryDelay + " seconds.";
                }
                complete(runId, jobName, "skipped", advanceSchedule,
                        active.commandId(), active.payloadHash(), started,
                        retryDelay, "active_command_exists", null, message);
                return;
            }

            ClusterCoordinatorService.CommandState result = switch (commandType) {
                case "reload-role" -> coordinator.reloadRole(createdBy, null, List.of());
                case "reload-system" -> coordinator.reloadSystem(createdBy, null, List.of());
                default -> throw new IllegalStateException("Unsupported auto sync command type '" + commandType + "'.");
            };

            String resultStatus = result.status();
            String runStatus = "succeeded".equalsIgnoreCase(resultStatus)
                    ? "succeeded"
                    : "no_targets".equalsIgnoreCase(resultStatus) ? "skipped" : "failed";
            String message = result.notes() == null ? "" : result.notes();
            if (message.isBlank()) message = commandType + " finished with status " + resultStatus + ".";

            complete(runId, jobName, runStatus, advanceSchedule,
                    result.commandId(), result.payloadHash(), started,
                    null, "skipped".equals(runStatus) ? resultStatus : null,
                    "failed".equals(runStatus) ? message : null,
                    message);
        } catch (IllegalStateException exception) {
            complete(runId, jobName, "skipped", advanceSchedule,
                    null, null, started, null, "not_runnable", null, safeMessage(exception));
        } catch (Throwable exception) {
            log.error("Auto sync job '{}' failed.", jobName, exception);
            complete(runId, jobName, "failed", advanceSchedule,
                    null, null, started, null, null, safeMessage(exception), safeMessage(exception));
        }
    }

    private void complete(long runId, String jobName, String status, boolean advanceSchedule,
                          Long commandId, String payloadHash, Instant started,
                          Integer nextRunDelaySeconds, String skipReason, String errorMessage, String resultMessage) {
        double seconds = Math.round(Duration.between(started, Instant.now()).toMillis() / 1000.0 * 1000.0) / 1000.0;
        Long startedCommandId = "active_command_exists".equals(skipReason) ? null : commandId;
        repository.completeRun(runId, jobName, worker.recordCode(), status, advanceSchedule,
                commandId, startedCommandId, blankToNull(payloadHash), seconds, nextRunDelaySeconds,
                skipReason, errorMessage, resultMessage);
    }

    private Integer blockedRetryDelaySeconds(RuntimeCommandAutoSyncRepository.Job job, boolean advanceSchedule) {
        return advanceSchedule && "reload-system".equals(job.commandType())
                ? SYSTEM_RELOAD_BLOCKED_RETRY_SECONDS
                : null;
    }

    private int resolveLeaseSeconds() {
        return Math.max(30, settings.resolve().deliveryLeaseSeconds() * 3);
    }

    private static String normalizeJobName(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Auto sync job name is required.");
        return value.trim();
    }

    private static String normalizeCreatedBy(String value) {
        return value == null || value.isBlank() ? "system:auto-sync" : value.trim();
    }

    private static Instant toInstant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof Instant instant) return instant;
        return Instant.now();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
