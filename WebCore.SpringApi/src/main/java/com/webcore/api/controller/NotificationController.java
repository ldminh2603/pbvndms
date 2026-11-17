package com.webcore.api.controller;

import java.time.Instant;
import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.CommonResponses;
import com.webcore.api.notification.NotificationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notification")
public class NotificationController {
    private final AuthService auth; private final NotificationService notifications;
    public NotificationController(AuthService auth, NotificationService notifications) { this.auth=auth; this.notifications=notifications; }
    @GetMapping("/recent") public CommonResponses.RecentNotificationsResponse recent(@RequestParam(defaultValue="5") int limit,@AuthenticationPrincipal Jwt jwt){var value=notifications.recent(email(jwt),limit); return new CommonResponses.RecentNotificationsResponse(value.items(), value.unreadCount());}
    @GetMapping public NotificationService.Response list(@RequestParam(defaultValue="all") String category,@RequestParam(defaultValue="false") boolean unreadOnly,@RequestParam(defaultValue="0") int skip,@RequestParam(defaultValue="20") int take,@AuthenticationPrincipal Jwt jwt){return notifications.list(email(jwt),category,unreadOnly,skip,take);}
    @PostMapping("/{id}/read") public CommonResponses.ReadNotificationResponse read(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){return new CommonResponses.ReadNotificationResponse(id, notifications.read(email(jwt),id));}
    @PostMapping("/{id}/dismiss") public CommonResponses.DismissNotificationResponse dismiss(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){return new CommonResponses.DismissNotificationResponse(id, notifications.dismiss(email(jwt),id));}
    @PostMapping("/read-all") public CommonResponses.ReadAllNotificationsResponse readAll(@RequestBody(required=false) Map<String,Object> request,@AuthenticationPrincipal Jwt jwt){String scope=request==null?"all":String.valueOf(request.getOrDefault("scope","all")); Instant now=Instant.now(); return new CommonResponses.ReadAllNotificationsResponse(now, notifications.readAll(email(jwt),scope));}
    @PostMapping("/{id}/action") public NotificationService.ActionResult action(@PathVariable String id,@RequestBody(required=false) Map<String,Object> request,@AuthenticationPrincipal Jwt jwt){return notifications.action(email(jwt),id,request==null?"view":String.valueOf(request.getOrDefault("action","view")));}
    private String email(Jwt jwt){return auth.tokenPayload(jwt).email();}
}
