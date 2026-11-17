package com.webcore.api.notification;

import com.webcore.api.auth.AuthService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** WebSocket is receive-only: the authenticated browser cannot publish notification events. */
@Component
public class NotificationWebSocketHandler extends TextWebSocketHandler {
    private static final String EMAIL = "notification.email";
    private final AuthService auth;
    private final NotificationSocketHub sockets;

    public NotificationWebSocketHandler(AuthService auth, NotificationSocketHub sockets) { this.auth = auth; this.sockets = sockets; }

    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!(session.getPrincipal() instanceof JwtAuthenticationToken principal)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Authentication is required."));
            return;
        }
        String email = auth.tokenPayload(principal.getToken()).email();
        session.getAttributes().put(EMAIL, email);
        sockets.add(email, session);
    }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object email = session.getAttributes().get(EMAIL);
        if (email != null) sockets.remove(String.valueOf(email), session.getId());
    }
}
