package com.webcore.api.controller;

import java.util.List;
import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.cluster.ClusterCoordinatorService;
import com.webcore.api.cluster.RuntimeCommandAutoSyncService;
import com.webcore.api.contract.ClusterResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Both paths are intentional: /api/cluster is the service API and S004 is React's stable .NET contract. */
@RestController
@RequestMapping({"/api/cluster", "/api/module/S004/cluster"})
public class ClusterController {
    private final AuthService auth;
    private final ClusterCoordinatorService coordinator;
    private final RuntimeCommandAutoSyncService autoSync;

    public ClusterController(
            AuthService auth,
            ClusterCoordinatorService coordinator,
            RuntimeCommandAutoSyncService autoSync) {
        this.auth = auth;
        this.coordinator = coordinator;
        this.autoSync = autoSync;
    }

    @PostMapping("/reload-system")
    public ResponseEntity<?> reloadSystem(
            @RequestBody(required = false) TargetRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            ClusterCoordinatorService.CommandState result = coordinator.reloadSystem(
                    privilegedEmail(jwt),
                    request == null ? null : request.targetServiceName(),
                    request == null ? List.of() : request.targetRecordCodes());
            return rolloutResponse(result);
        } catch (ClusterCoordinatorService.ActiveCommandConflictException exception) {
            return activeCommandConflict(exception);
        } catch (IllegalStateException exception) {
            if (isNoTargets(exception)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "status", "no_targets",
                                "message", exception.getMessage()));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("errorMessage", exception.getMessage()));
        }
    }

    @PostMapping("/reload-role")
    public ResponseEntity<?> reloadRole(
            @RequestBody(required = false) TargetRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            ClusterCoordinatorService.CommandState result = coordinator.reloadRole(
                    privilegedEmail(jwt),
                    request == null ? null : request.targetServiceName(),
                    request == null ? List.of() : request.targetRecordCodes());
            return rolloutResponse(result);
        } catch (ClusterCoordinatorService.ActiveCommandConflictException exception) {
            return activeCommandConflict(exception);
        } catch (IllegalStateException exception) {
            if (isNoTargets(exception)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "status", "no_targets",
                                "message", exception.getMessage()));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("errorMessage", exception.getMessage()));
        }
    }

    @GetMapping("/endpoints")
    public ClusterResponses.EndpointResponse endpoints(
            @RequestParam(required = false) String targetServiceName,
            @AuthenticationPrincipal Jwt jwt) {
        privilegedEmail(jwt);
        return ClusterResponses.endpoints(coordinator.endpoints(targetServiceName));
    }

    @PostMapping("/endpoints/refresh")
    public ClusterResponses.EndpointResponse refreshEndpoints(
            @RequestBody(required = false) EndpointRefreshRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        privilegedEmail(jwt);
        return ClusterResponses.endpoints(coordinator.refreshEndpoints(request == null ? null : request.targetServiceName()));
    }

    @PostMapping("/commands/reload-system/prepare")
    public ResponseEntity<?> prepareSystem(
            @RequestBody(required = false) TargetRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            return ResponseEntity.ok(ClusterResponses.command(coordinator.prepare(
                    "reload-system",
                    request == null ? null : request.targetServiceName(),
                    request == null ? List.of() : request.targetRecordCodes(),
                    privilegedEmail(jwt))));
        } catch (ClusterCoordinatorService.ActiveCommandConflictException exception) {
            return activeCommandConflict(exception);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", exception.getMessage()));
        }
    }

    @PostMapping("/commands/reload-role/prepare")
    public ResponseEntity<?> prepareRole(
            @RequestBody(required = false) TargetRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            return ResponseEntity.ok(ClusterResponses.command(coordinator.prepare(
                    "reload-role",
                    request == null ? null : request.targetServiceName(),
                    request == null ? List.of() : request.targetRecordCodes(),
                    privilegedEmail(jwt))));
        } catch (ClusterCoordinatorService.ActiveCommandConflictException exception) {
            return activeCommandConflict(exception);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", exception.getMessage()));
        }
    }

    @GetMapping("/commands/{id}")
    public ResponseEntity<?> command(
            @PathVariable long id,
            @AuthenticationPrincipal Jwt jwt) {
        privilegedEmail(jwt);
        ClusterCoordinatorService.CommandState status = coordinator.command(id);
        return status == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(ClusterResponses.command(status));
    }

    @GetMapping("/commands/latest")
    public ResponseEntity<?> latest(
            @RequestParam String commandType,
            @RequestParam(required = false) String targetServiceName,
            @AuthenticationPrincipal Jwt jwt) {
        privilegedEmail(jwt);
        try {
            // .NET returns Ok(null) when no actionable command exists.
            ClusterCoordinatorService.CommandState status = coordinator.latestActionable(commandType, targetServiceName);
            return ResponseEntity.ok(status == null ? null : ClusterResponses.command(status));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", exception.getMessage()));
        }
    }

    @PostMapping("/commands/{id}/apply")
    public ResponseEntity<?> apply(
            @PathVariable long id,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            ClusterCoordinatorService.CommandState status = coordinator.apply(id, privilegedEmail(jwt));
            return status == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(ClusterResponses.command(status));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("errorMessage", exception.getMessage()));
        }
    }

    @PostMapping("/commands/{id}/abort")
    public ResponseEntity<?> abort(
            @PathVariable long id,
            @RequestBody(required = false) AbortRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        privilegedEmail(jwt);
        ClusterCoordinatorService.CommandState status = coordinator.abort(id, request == null ? null : request.reason());
        return status == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(ClusterResponses.command(status));
    }

    @GetMapping("/auto-sync")
    public ClusterResponses.AutoSyncOverviewResponse autoSyncOverview(@AuthenticationPrincipal Jwt jwt) {
        privilegedEmail(jwt);
        return ClusterResponses.overview(autoSync.getOverview());
    }

    @PostMapping("/auto-sync/jobs/{jobName}")
    public ResponseEntity<?> updateAutoSyncJob(
            @PathVariable String jobName,
            @RequestBody(required = false) AutoSyncJobUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        privilegedEmail(jwt);
        try {
            return ResponseEntity.ok(ClusterResponses.overview(autoSync.updateJob(
                    jobName,
                    request == null ? null : request.enabled(),
                    request == null ? null : request.intervalSeconds())));
        } catch (IllegalStateException exception) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", exception.getMessage()));
        }
    }

    @PostMapping("/auto-sync/jobs/{jobName}/run")
    public ResponseEntity<?> runAutoSyncJobNow(
            @PathVariable String jobName,
            @RequestBody(required = false) AutoSyncJobRunRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            return ResponseEntity.ok(ClusterResponses.overview(autoSync.runJobNow(
                    jobName, privilegedEmail(jwt), request == null ? null : request.intervalSeconds())));
        } catch (ClusterCoordinatorService.ActiveCommandConflictException exception) {
            return activeCommandConflict(exception);
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("errorMessage", exception.getMessage()));
        }
    }

    private ResponseEntity<?> rolloutResponse(ClusterCoordinatorService.CommandState result) {
        String status = result == null ? "" : result.status();

        return switch (status.toLowerCase()) {
            case "succeeded" -> ResponseEntity.ok(ClusterResponses.command(result));
            case "aborted" -> ResponseEntity.status(HttpStatus.CONFLICT).body(ClusterResponses.command(result));
            case "failed", "expired" ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ClusterResponses.command(result));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ClusterResponses.command(result));
        };
    }

    private ResponseEntity<?> activeCommandConflict(ClusterCoordinatorService.ActiveCommandConflictException exception) {
        ClusterCoordinatorService.CommandState active = exception.activeCommand();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "errorMessage", exception.getMessage(),
                "commandId", active.commandId(),
                "commandType", active.commandType(),
                "phase", active.phase(),
                "status", active.status()));
    }

    private static boolean isNoTargets(IllegalStateException exception) {
        return exception.getMessage() != null
                && exception.getMessage().contains("No active runtime endpoint");
    }

    private String privilegedEmail(Jwt jwt) {
        AuthService.TokenPayload payload = auth.tokenPayload(jwt);
        if (!payload.isPrivilegedAccount()) {
            throw new SecurityException("ADMIN_ACCESS_DENIED");
        }
        return payload.email();
    }

    public record TargetRequest(String targetServiceName, List<String> targetRecordCodes) { }
    public record AbortRequest(String reason) { }
    public record EndpointRefreshRequest(String targetServiceName) { }
    public record AutoSyncJobUpdateRequest(Boolean enabled, Integer intervalSeconds) { }
    public record AutoSyncJobRunRequest(Integer intervalSeconds) { }
}
