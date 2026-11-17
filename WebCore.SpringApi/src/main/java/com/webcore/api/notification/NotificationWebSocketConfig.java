package com.webcore.api.notification;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class NotificationWebSocketConfig implements WebSocketConfigurer {
    private final NotificationWebSocketHandler handler;
    private final List<String> allowedOrigins;

    public NotificationWebSocketConfig(NotificationWebSocketHandler handler,
                                       @Value("${webcore.cors.allowed-origins}") List<String> allowedOrigins) {
        this.handler = handler; this.allowedOrigins = allowedOrigins;
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/notification/ws").setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }
}
