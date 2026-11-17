package com.webcore.api.controller;

import java.time.Instant;
import java.util.List;

import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.AdminToolsResponses;
import com.webcore.api.runtime.RuntimeReloadService;
import com.webcore.api.runtime.StartupGate;
import com.webcore.api.runtime.ModuleRuntimeCache;
import com.webcore.api.runtime.OpenApiDocumentCache;
import com.webcore.api.runtime.RuntimeCacheMetrics;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime-admin compatibility endpoints consumed by the React header. */
@RestController
@RequestMapping("/api/admin/tools")
public class AdminToolsController {
    private static final String CONFIGURED_LOG_LEVEL = "Information";
    private static final int DEBUG_TIMEOUT_MINUTES = 30;
    private static final int TRACE_TIMEOUT_MINUTES = 15;
    private static final int ABSOLUTE_TIMEOUT_CAP_MINUTES = 480;
    private final AuthService auth;
    private final RuntimeReloadService reloads;
    private final StartupGate startupGate;
    private final ModuleRuntimeCache runtimeCache;
    private final OpenApiDocumentCache openApi;
    private final RuntimeCacheMetrics cacheMetrics;
    private volatile RuntimeLogLevelOverride runtimeLogLevelOverride;

    public AdminToolsController(AuthService auth, RuntimeReloadService reloads, StartupGate startupGate, ModuleRuntimeCache runtimeCache, OpenApiDocumentCache openApi, RuntimeCacheMetrics cacheMetrics) {
        this.auth = auth;
        this.reloads = reloads;
        this.startupGate = startupGate;
        this.runtimeCache=runtimeCache;
        this.openApi=openApi;
        this.cacheMetrics=cacheMetrics;
    }

    @GetMapping("/runtime-cache-policy")
    public AdminToolsResponses.RuntimeCachePolicyResponse runtimeCachePolicy(@AuthenticationPrincipal Jwt jwt) {
        requirePrivileged(jwt);
        return new AdminToolsResponses.RuntimeCachePolicyResponse("Runtime cache policy loaded.", true, true, 300, "/static/S004");
    }

    /** Privileged, redacted cache shape: never returns def_source config, credential, token or PEM values. */
    @GetMapping("/runtime-cache")
    public AdminToolsResponses.RuntimeCacheResponse runtimeCache(@AuthenticationPrincipal Jwt jwt) {
        requirePrivileged(jwt);
        return new AdminToolsResponses.RuntimeCacheResponse(Instant.now(), reloads.status(), runtimeCache.status(), openApi.status(), cacheMetrics.status());
    }

    /** Legacy Admin Panel route retained for the unchanged React client. */
    @GetMapping("/reload-core")
    public AdminToolsResponses.ReloadResponse reloadCore(@AuthenticationPrincipal Jwt jwt) {
        RuntimeReloadService.ReloadResult result = reloads.reloadSystem(requirePrivileged(jwt));
        return new AdminToolsResponses.ReloadResponse("Core reloaded successfully in " + result.elapsedMs() + " ms.", result.loadedAtUtc(), "manual", result.elapsedMs() / 1000D);
    }

    /** Legacy Admin Panel route retained for the unchanged React client. */
    @GetMapping("/reload-role")
    public AdminToolsResponses.ReloadResponse reloadRole(@AuthenticationPrincipal Jwt jwt) {
        RuntimeReloadService.ReloadResult result = reloads.reloadRole(requirePrivileged(jwt));
        return new AdminToolsResponses.ReloadResponse("Role reloaded successfully in " + result.elapsedMs() + " ms.", result.loadedAtUtc(), "manual", result.elapsedMs() / 1000D);
    }

    @GetMapping("/data-sources")
    public AdminToolsResponses.DataSourcesResponse dataSources(@AuthenticationPrincipal Jwt jwt) {
        requirePrivileged(jwt);
        List<StartupGate.SourceStatus> sources = startupGate.state().sources();
        long healthy = sources.stream().filter(StartupGate.SourceStatus::isHealthy).count();
        long unhealthy = sources.stream().filter(source -> "unhealthy".equalsIgnoreCase(source.status())).count();
        long skipped = sources.stream().filter(source -> "skipped".equalsIgnoreCase(source.status())).count();
        return new AdminToolsResponses.DataSourcesResponse(Instant.now(), reloads.status().loadedAtUtc(),
                new AdminToolsResponses.DataSourceSummary(sources.size(), healthy, unhealthy, skipped), sources);
    }

    @GetMapping("/log-level")
    public AdminToolsResponses.LogLevelResponse logLevel(@AuthenticationPrincipal Jwt jwt) {
        requirePrivileged(jwt);
        return logLevelState("Runtime log level loaded.");
    }

    @PutMapping("/log-level")
    public AdminToolsResponses.LogLevelResponse updateLogLevel(@RequestBody(required = false) LogLevelUpdateRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        requirePrivileged(jwt);
        String requested = request == null || request.mode() == null ? "" : request.mode().trim();
        if (!requested.equals("Information") && !requested.equals("Debug") && !requested.equals("Trace")) {
            throw new IllegalArgumentException("mode must be Information, Debug, or Trace.");
        }
        if (requested.equals(CONFIGURED_LOG_LEVEL)) {
            runtimeLogLevelOverride = null;
        } else {
            Instant setAt = Instant.now();
            int timeoutMinutes = requested.equals("Debug") ? DEBUG_TIMEOUT_MINUTES : TRACE_TIMEOUT_MINUTES;
            runtimeLogLevelOverride = new RuntimeLogLevelOverride(
                    requested,
                    setAt,
                    setAt.plusSeconds(timeoutMinutes * 60L),
                    "admin-ui");
        }
        return logLevelState("Runtime log level updated.");
    }

    private AdminToolsResponses.LogLevelResponse logLevelState(String message) {
        RuntimeLogLevelOverride runtimeOverride = currentRuntimeLogLevelOverride();
        String effectiveLevel = runtimeOverride == null ? CONFIGURED_LOG_LEVEL : runtimeOverride.minimumLevel();
        boolean debug = runtimeOverride != null
                && (runtimeOverride.minimumLevel().equals("Debug") || runtimeOverride.minimumLevel().equals("Trace"));
        boolean trace = runtimeOverride != null && runtimeOverride.minimumLevel().equals("Trace");
        return new AdminToolsResponses.LogLevelResponse(message, CONFIGURED_LOG_LEVEL, effectiveLevel,
                runtimeOverride == null ? null : runtimeOverride.minimumLevel(),
                runtimeOverride == null ? null : runtimeOverride.setAtUtc().toString(),
                runtimeOverride == null ? null : runtimeOverride.expiresAtUtc().toString(),
                runtimeOverride == null ? null : runtimeOverride.source(),
                runtimeOverride != null, debug, trace, DEBUG_TIMEOUT_MINUTES, TRACE_TIMEOUT_MINUTES,
                ABSOLUTE_TIMEOUT_CAP_MINUTES);
    }

    private RuntimeLogLevelOverride currentRuntimeLogLevelOverride() {
        RuntimeLogLevelOverride runtimeOverride = runtimeLogLevelOverride;
        if (runtimeOverride != null && !Instant.now().isBefore(runtimeOverride.expiresAtUtc())) {
            runtimeLogLevelOverride = null;
            return null;
        }
        return runtimeOverride;
    }

    private String requirePrivileged(Jwt jwt) {
        AuthService.TokenPayload payload = auth.tokenPayload(jwt);
        if (!payload.isPrivilegedAccount()) {
            throw new SecurityException("ADMIN_ACCESS_DENIED");
        }
        return payload.email();
    }

    public record LogLevelUpdateRequest(String mode) { }

    private record RuntimeLogLevelOverride(String minimumLevel, Instant setAtUtc, Instant expiresAtUtc, String source) { }
}
