package com.webcore.api.contract;

import java.util.List;

import com.webcore.api.cluster.RuntimeCommandAutoSyncRepository;
import com.webcore.api.cluster.ClusterCoordinatorService;

/** Converts JDBC maps to stable nested S004 response records at the HTTP boundary. */
public final class ClusterResponses {
    private ClusterResponses() { }
    public record EndpointResponse(List<Endpoint> endpoints, Coordination coordination) { }
    public record Endpoint(String recordCode, String environment, String serviceName, String platformType, String instanceId, String hostName, String status, String healthStatus, Object lastSeenAt, Object updatedAt, String commandChannelMode, String commandSignalProvider, String commandSignalChannel, String commandSignalResultChannel, String commandSignalLastError, boolean isFresh, boolean isDefaultTarget) { }
    public record Coordination(String controlPlane, String commandStore, String deliveryStore, boolean signalEnabled, String signalProvider, String signalTransport, String disabledReason, String signalChannel, Object pollFallbackSeconds, boolean signalProviderImplemented, Object preparedCommandTtlSeconds, Object deliveryLeaseSeconds, boolean requireCurrentTargetsOnApply, boolean autoSyncEnabled) { }
    public record CommandResponse(Object commandId, String commandType, String targetServiceName, String coordinationMode, String phase, String status, Object payloadHash, Object desiredRevision, String createdBy, Object createdAt, Object startedAt, Object preparedAt, Object applyStartedAt, Object completedAt, Object expiresAt, Object lastSignalProvider, Object lastSignalChannel, Object lastSignalStatus, Object lastSignalAt, Object lastSignalError, Object notes, List<Delivery> deliveries) { }
    public record Delivery(Object deliveryId, String targetRecordCode, String deliveryStatus, Object attemptCount, Object startedAt, Object preparedAt, Object applyWakeVia, Object applyWakeChannel, Object applyWakeAt, Object completedAt, Object finishedAt, Object updatedAt, Object preparedHash, Object preparedRevision, Object lastError, Object resultMessage) { }
    public record AutoSyncOverviewResponse(List<AutoSyncJob> jobs, List<AutoSyncRun> runs) { }
    public record AutoSyncJob(String jobName, String commandType, boolean enabled, Object intervalSeconds, Object nextRunAt, Object leaseOwner, Object leaseUntil, Object lastStartedAt, Object lastStartedCommandId, Object lastCompletedAt, Object lastCompletedCommandId, Object lastStatus, Object lastMessage, Object updatedAt) { }
    public record AutoSyncRun(Object runId, String jobName, String commandType, String runKind, Object scheduledFor, Object leaseOwner, String status, Object commandId, Object payloadHash, Object targetCount, Object preparedCount, Object succeededCount, Object failedCount, Object pendingCount, Object startedAt, Object finishedAt, Object durationSeconds, Object skipReason, Object errorMessage, Object resultMessage, Object createdAt) { }
    public static EndpointResponse endpoints(ClusterCoordinatorService.EndpointOverview value) {
        return new EndpointResponse(value.endpoints().stream().map(item -> new Endpoint(item.recordCode(), item.environment(), item.serviceName(),
                item.platformType(), item.instanceId(), item.hostName(), item.status(), item.healthStatus(), item.lastSeenAt(), item.updatedAt(),
                item.commandChannelMode(), item.commandSignalProvider(), item.commandSignalChannel(), item.commandSignalResultChannel(),
                item.commandSignalLastError(), item.isFresh(), item.isDefaultTarget())).toList(),
                new Coordination(value.coordination().controlPlane(), value.coordination().commandStore(), value.coordination().deliveryStore(),
                value.coordination().signalEnabled(), value.coordination().signalProvider(), value.coordination().signalTransport(),
                value.coordination().disabledReason(), value.coordination().signalChannel(), value.coordination().pollFallbackSeconds(),
                value.coordination().signalProviderImplemented(), value.coordination().preparedCommandTtlSeconds(),
                value.coordination().deliveryLeaseSeconds(), value.coordination().requireCurrentTargetsOnApply(), value.coordination().autoSyncEnabled()));
    }
    public static CommandResponse command(ClusterCoordinatorService.CommandState value) {
        return new CommandResponse(value.commandId(), value.commandType(), value.targetServiceName(), value.coordinationMode(),
                value.phase(), value.status(), value.payloadHash(), value.desiredRevision(), value.createdBy(), value.createdAt(),
                value.startedAt(), value.preparedAt(), value.applyStartedAt(), value.completedAt(), value.expiresAt(),
                value.lastSignalProvider(), value.lastSignalChannel(), value.lastSignalStatus(), value.lastSignalAt(),
                value.lastSignalError(), value.notes(), value.deliveries().stream().map(item -> new Delivery(item.deliveryId(),
                item.targetRecordCode(), item.deliveryStatus(), item.attemptCount(), item.startedAt(), item.preparedAt(),
                item.applyWakeVia(), item.applyWakeChannel(), item.applyWakeAt(), item.completedAt(), item.finishedAt(),
                item.updatedAt(), item.preparedHash(), item.preparedRevision(), item.lastError(), item.resultMessage())).toList());
    }
    public static AutoSyncOverviewResponse overview(RuntimeCommandAutoSyncRepository.Overview value) {
        return new AutoSyncOverviewResponse(value.jobs().stream().map(job -> new AutoSyncJob(job.jobName(), job.commandType(), job.enabled(),
                job.intervalSeconds(), job.nextRunAt(), job.leaseOwner(), job.leaseUntil(), job.lastStartedAt(), job.lastStartedCommandId(),
                job.lastCompletedAt(), job.lastCompletedCommandId(), job.lastStatus(), job.lastMessage(), job.updatedAt())).toList(),
                value.runs().stream().map(run -> new AutoSyncRun(run.runId(), run.jobName(), run.commandType(), run.runKind(), run.scheduledFor(),
                run.leaseOwner(), run.status(), run.commandId(), run.payloadHash(), run.targetCount(), run.preparedCount(), run.succeededCount(),
                run.failedCount(), run.pendingCount(), run.startedAt(), run.finishedAt(), run.durationSeconds(), run.skipReason(), run.errorMessage(),
                run.resultMessage(), run.createdAt())).toList());
    }
}
