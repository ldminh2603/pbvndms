package com.webcore.api.cluster;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Polls due auto-sync jobs. DB leases make execution safe across cluster nodes. */
@Service
public class RuntimeCommandAutoSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(RuntimeCommandAutoSyncWorker.class);
    private final RuntimeCommandAutoSyncService autoSync;
    private final ClusterRuntimeProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    public RuntimeCommandAutoSyncWorker(
            RuntimeCommandAutoSyncService autoSync,
            ClusterRuntimeProperties properties) {
        this.autoSync = autoSync;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 10_000)
    public void runDueJobs() {
        if (!properties.enabled() || !running.compareAndSet(false, true)) return;
        try {
            autoSync.runDueJobs();
        } catch (Exception exception) {
            log.warn("Auto sync poll failed: {}", exception.getMessage());
        } finally {
            running.set(false);
        }
    }
}
