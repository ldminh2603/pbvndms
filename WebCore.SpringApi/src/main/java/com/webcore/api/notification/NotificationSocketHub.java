package com.webcore.api.notification;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.stereotype.Service;

/** Holds only local, authenticated WebSocket sessions. Redis distributes events to every node. */
@Service
public class NotificationSocketHub {
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    public void add(String email, WebSocketSession session) {
        sessionsByUser.computeIfAbsent(normalize(email), ignored -> new ConcurrentHashMap<>())
                .put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 10_000, 512 * 1024));
    }

    public void remove(String email, String sessionId) {
        ConcurrentHashMap<String, WebSocketSession> sessions = sessionsByUser.get(normalize(email));
        if (sessions == null) return;
        sessions.remove(sessionId);
        if (sessions.isEmpty()) sessionsByUser.remove(normalize(email), sessions);
    }

    public void send(String email, String payload) {
        ConcurrentHashMap<String, WebSocketSession> sessions = sessionsByUser.get(normalize(email));
        if (sessions == null) return;
        Set<String> ids = Set.copyOf(sessions.keySet());
        for (String id : ids) {
            WebSocketSession session = sessions.get(id);
            if (session == null || !session.isOpen()) { sessions.remove(id); continue; }
            try { session.sendMessage(new TextMessage(payload)); }
            catch (IOException failure) { sessions.remove(id); try { session.close(); } catch (IOException ignored) { } }
        }
    }

    private static String normalize(String email) { return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT); }
}
