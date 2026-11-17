package com.webcore.api.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.webcore.api.runtime.ModuleRuntimeCache;
import com.webcore.api.runtime.OpenApiDocumentCache;
import com.webcore.api.runtime.RuntimeCacheMetrics;
import com.webcore.api.runtime.RuntimeReloadService;
import com.webcore.api.runtime.StartupGate;

/** Typed HTTP contracts for the runtime-cache administration endpoints. */
public final class AdminToolsResponses {
    private AdminToolsResponses() { }

    public record RuntimeCachePolicyResponse(String message, boolean allowLocalManualReload,
            boolean requireCurrentTargetsOnApply, int preparedCommandTtlSeconds, String clusterManagementPath) { }

    public record RuntimeCacheResponse(Instant generatedAtUtc, RuntimeReloadService.Status core,
            ModuleRuntimeCache.Status module, OpenApiDocumentCache.Status openApi,
            Map<String, RuntimeCacheMetrics.CacheMetrics> metrics) { }

    public record ReloadResponse(String message, Instant reloadedAtUtc, String source, double loadDurationSeconds) { }

    public record DataSourcesResponse(Instant generatedAtUtc, Instant coreLoadedAt,
            DataSourceSummary summary, List<StartupGate.SourceStatus> sources) { }

    public record DataSourceSummary(int total, long healthy, long unhealthy, long skipped) { }

    public record LogLevelResponse(String message, String configuredMinimumLevel, String effectiveMinimumLevel,
            String runtimeOverrideMinimumLevel, String runtimeOverrideSetAtUtc, String runtimeOverrideExpiresAtUtc,
            String runtimeOverrideSource, boolean runtimeOverrideActive,
            boolean debugEnabled, boolean traceEnabled, int debugTimeoutMinutes, int traceTimeoutMinutes,
            int absoluteTimeoutCapMinutes) { }
}
