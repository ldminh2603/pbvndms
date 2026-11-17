package com.webcore.api.runtime;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.database.CoreDataSourceResolver;
import com.webcore.api.cluster.ClusterRuntimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Local-node implementation of the .NET Core and Role reload handlers. */
@Service
public class RuntimeReloadService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeReloadService.class);
    private final ReentrantLock reloadLock = new ReentrantLock(true);
    private final CoreMetadataService core;
    private final CoreDataSourceResolver coreSource;
    private final SourceDataSourceRegistry sources;
    private final RoutineMetadataReader routines;
    private final StartupGate startupGate;
    private final ClusterRuntimeProperties cluster;
    private final ConcurrentHashMap<Long, StagedSnapshot> staged = new ConcurrentHashMap<>();

    public RuntimeReloadService(CoreMetadataService core, CoreDataSourceResolver coreSource, SourceDataSourceRegistry sources,
                                RoutineMetadataReader routines, StartupGate startupGate, ClusterRuntimeProperties cluster) {
        this.core = core;
        this.coreSource = coreSource;
        this.sources = sources;
        this.routines = routines;
        this.startupGate = startupGate;
        this.cluster = cluster;
    }

    public ReloadResult reloadSystem(String requestedBy) { return execute("reload-system", requestedBy, true); }
    public ReloadResult reloadRole(String requestedBy) { return execute("reload-role", requestedBy, false); }

    /** Cluster prepare: build but never expose a new snapshot to request threads. */
    public StageResult prepare(long commandId, String commandType) {
        if (!reloadLock.tryLock()) throw new IllegalStateException("A runtime reload is already in progress.");
        try {
            boolean full = "reload-system".equals(commandType);
            // A cluster reload builds its candidate snapshot during PREPARE.  Clear
            // cached Core datasource pools first, otherwise that snapshot can be
            // built from the old source configuration and then faithfully published
            // during APPLY.  Keep DBCORE_ops alive for the durable command itself.
            if (full) coreSource.clearExcept("DBCORE_ops");
            CoreMetadataService.Snapshot candidate = full ? core.prepareCore() : core.prepareRoles();
            String hash = snapshotHash(candidate);
            staged.put(commandId, new StagedSnapshot(commandType, candidate, null, hash));
            return new StageResult(hash, candidate.loadedAtUtc(), full ? "Core snapshot staged." : "Role snapshot staged.");
        } finally { reloadLock.unlock(); }
    }

    /** Cluster apply: atomically swaps the staged snapshot and retains the old one for abort. */
    public ReloadResult applyStaged(long commandId, String requestedBy) {
        if (!reloadLock.tryLock()) throw new IllegalStateException("A runtime reload is already in progress.");
        Instant started=Instant.now();
        try {
            StagedSnapshot stage=staged.get(commandId);
            if(stage==null) throw new IllegalStateException("No staged runtime snapshot for command " + commandId);
            if (cluster.failApplyForCurrentInstance()) {
                throw new IllegalStateException("Cluster test fault injection: apply failed for instance " + cluster.instanceId());
            }
            boolean full="reload-system".equals(stage.commandType()); int pools=0,routinesCleared=0;
            CoreMetadataService.Snapshot previous=core.snapshot();
            if(full) { coreSource.clearExcept("DBCORE_ops"); pools=sources.clear(); routinesCleared=routines.clear(); }
            CoreMetadataService.Snapshot active=core.publish(stage.snapshot());
            staged.put(commandId,new StagedSnapshot(stage.commandType(),stage.snapshot(),previous,stage.hash()));
            if(full) { startupGate.refresh(); }
            long elapsed=Duration.between(started,Instant.now()).toMillis();
            return new ReloadResult("succeeded",stage.commandType(),requestedBy,started,active.loadedAtUtc(),elapsed,core.componentCount(),active.menusByComponent().values().stream().mapToInt(java.util.List::size).sum(),active.modules().size(),active.sources().size(),active.roles().size(),pools,routinesCleared,"Staged snapshot applied; rollback snapshot retained until cluster finalize.");
        } finally { reloadLock.unlock(); }
    }

    /** Abort rolls a node back only when that node had already applied the staged snapshot. */
    public boolean abortStaged(long commandId) {
        if (!reloadLock.tryLock()) throw new IllegalStateException("A runtime reload is already in progress.");
        try { StagedSnapshot stage=staged.remove(commandId); if(stage==null) return false; if(stage.previous()!=null) { core.publish(stage.previous()); if("reload-system".equals(stage.commandType())) startupGate.refresh(); return true; } return false; }
        finally { reloadLock.unlock(); }
    }

    /**
     * Releases the temporary rollback pair after the durable coordinator has
     * confirmed that every target applied the command. Keeping it beyond that
     * point would retain both the active candidate and the prior Core snapshot
     * in this JVM indefinitely.
     */
    public boolean finalizeStaged(long commandId) {
        if (!reloadLock.tryLock()) return false;
        try {
            StagedSnapshot stage = staged.remove(commandId);
            if (stage == null) return false;
            log.info("runtime_reload_finalized commandId={} command={} releasedRollbackSnapshot=true",
                    commandId, stage.commandType());
            return true;
        } finally {
            reloadLock.unlock();
        }
    }

    /** Only used by the durable worker to reconcile this node's in-memory staging. */
    public Set<Long> stagedCommandIds() {
        return Set.copyOf(staged.keySet());
    }

    private ReloadResult execute(String command, String requestedBy, boolean fullReload) {
        if (!reloadLock.tryLock()) throw new IllegalStateException("A runtime reload is already in progress.");
        Instant startedAt = Instant.now();
        try {
            int clearedPools = 0;
            int clearedRoutineEntries = 0;
            if (fullReload) {
                // DBCORE_ops is the durable cluster control plane. Keep its pool
                // alive while a Core reload is applied so concurrent Role/Core
                // delivery queries are not cut off mid-command.
                coreSource.clearExcept("DBCORE_ops");
                clearedPools = sources.clear();
                clearedRoutineEntries = routines.clear();
            }
            CoreMetadataService.Snapshot snapshot = fullReload ? core.reload() : core.reloadRoles();
            if (fullReload) { startupGate.refresh(); }
            long elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
            ReloadResult result = new ReloadResult("succeeded", command, requestedBy, startedAt, snapshot.loadedAtUtc(), elapsedMs,
                    core.componentCount(), snapshot.menusByComponent().values().stream().mapToInt(java.util.List::size).sum(),
                    snapshot.modules().size(), snapshot.sources().size(), snapshot.roles().size(), clearedPools, clearedRoutineEntries,
                    fullReload ? "Core metadata, datasource pools, and routine metadata reloaded." : "Role policy metadata reloaded.");
            log.info("runtime_reload command={} requestedBy={} elapsedMs={} modules={} sources={} roles={} clearedPools={} clearedRoutineEntries={}",
                    command, requestedBy, elapsedMs, result.moduleCount(), result.sourceCount(), result.roleCount(), clearedPools, clearedRoutineEntries);
            return result;
        } catch (RuntimeException exception) {
            log.error("runtime_reload_failed command={} requestedBy={}", command, requestedBy, exception);
            throw exception;
        } finally {
            reloadLock.unlock();
        }
    }

    public Status status() {
        CoreMetadataService.Snapshot snapshot = core.snapshot();
        return new Status(cluster.enabled() ? "cluster" : "local", reloadLock.isLocked(), snapshot.loadedAtUtc(),
                snapshot.modules().size(), snapshot.sources().size(), snapshot.roles().size(),
                cluster.enabled() ? "ops.runtime_command prepare/apply" : "local reload", snapshot.systemValues().size());
    }

    /** Stable full SHA-256 contract used by runtime_command.payload_hash. */
    private static String snapshotHash(CoreMetadataService.Snapshot snapshot) {
        String payload = snapshot.modules().toString() + snapshot.sources() + snapshot.roles();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot calculate staged snapshot hash.", exception);
        }
    }

    public record ReloadResult(String status, String command, String requestedBy, Instant startedAtUtc, Instant loadedAtUtc,
                               long elapsedMs, int componentCount, int menuCount, int moduleCount, int sourceCount, int roleCount,
                               int clearedDataSourcePools, int clearedRoutineMetadataEntries, String message) { }
    public record Status(String nodeMode, boolean reloadInProgress, Instant loadedAtUtc, int moduleCount,
            int sourceCount, int roleCount, String cacheInvalidation, int systemValueCount) { }
    public record StageResult(String hash, Instant loadedAtUtc, String message) { }
    private record StagedSnapshot(String commandType, CoreMetadataService.Snapshot snapshot, CoreMetadataService.Snapshot previous, String hash) { }
}
