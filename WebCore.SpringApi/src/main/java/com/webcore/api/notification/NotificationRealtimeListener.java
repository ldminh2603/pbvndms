package com.webcore.api.notification;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Receives every Redis notification event, then fans it out only to sessions local to this node. */
@Service
public final class NotificationRealtimeListener implements SmartLifecycle, MessageListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationRealtimeListener.class);
    private final RedisMessageListenerContainer redis;
    private final ObjectMapper json;
    private final NotificationSocketHub sockets;
    private final NotificationProperties properties;
    private volatile boolean running;

    public NotificationRealtimeListener(RedisMessageListenerContainer redis, ObjectMapper json, NotificationSocketHub sockets,
                                        NotificationProperties properties) {
        this.redis = redis; this.json = json; this.sockets = sockets; this.properties = properties;
    }

    @Override public void start() {
        if (!properties.realtimeEnabled() || running) return;
        redis.addMessageListener(this, new ChannelTopic(channel()));
        running = true;
    }
    @Override public void onMessage(Message message, byte[] pattern) {
        try {
            NotificationRealtimePublisher.Event event = json.readValue(message.getBody(), NotificationRealtimePublisher.Event.class);
            if ("notification".equals(event.type()) && event.item() != null) {
                sockets.send(event.recipientEmail(), json.writeValueAsString(event));
            }
        } catch (Exception failure) { log.warn("Ignoring invalid Redis notification event: {}", failure.getMessage()); }
    }
    @Override public void stop() { running = false; try { redis.removeMessageListener(this, new ChannelTopic(channel())); } catch (Exception ignored) { } }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 40; }
    private String channel() { return properties.redisChannel() == null || properties.redisChannel().isBlank() ? "webcore:notification:events" : properties.redisChannel().trim(); }
}
