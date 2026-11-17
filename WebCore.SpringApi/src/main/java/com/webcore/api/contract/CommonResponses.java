package com.webcore.api.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.webcore.api.auth.AuthRepository;
import com.webcore.api.auth.AuthService;
import com.webcore.api.notification.NotificationService;
import com.webcore.api.runtime.StartupGate;

/** Reusable, fixed-shape HTTP response contracts. */
public final class CommonResponses {
    private CommonResponses() { }
    public record MessageResponse(String message) { }
    public record ErrorResponse(String errorCode, String errorMessage) { }
    public record LoginResponse(AuthRepository.AuthUser user) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuthMeResponse(int version, String id, String email, String ip, boolean allowPublic, String type,
                                 boolean isPrivilegedAccount, String displayName, List<AuthRepository.RoleRef> roles,
                                 List<String> components, Object setting, String avatarUrl, Map<String, Object> userConfig,
                                 int lockScreenTimeoutSeconds) {
        public static AuthMeResponse from(AuthService.CurrentUser value, Map<String, Object> userConfig,
                                          int lockScreenTimeoutSeconds) {
            return new AuthMeResponse(value.version(), value.id(), value.email(), value.ip(), value.allowPublic(), value.type(),
                    value.isPrivilegedAccount(), value.displayName(), value.roles(), value.components(), value.setting(),
                    value.avatarUrl(), userConfig, lockScreenTimeoutSeconds);
        }
    }
    public record LiveResponse(String status) { }
    public record ReadyResponse(String status, Instant checkedAtUtc, List<StartupGate.SourceStatus> sources) { }
    public record RecentNotificationsResponse(List<NotificationService.Item> items, int unreadCount) { }
    public record ReadNotificationResponse(String id, Instant readAt) { }
    public record DismissNotificationResponse(String id, Instant dismissedAt) { }
    public record ReadAllNotificationsResponse(Instant readAt, int updatedCount) { }
    public record ChatSettingsResponse(int clientHistoryMaxMessages, int contextHistoryMaxMessages,
            int summaryTriggerMessageCount, int summaryPruneMessageCount, int summaryMaxChars,
            int maxUserMessageChars, int maxAssistantMessageChars) { }
}
