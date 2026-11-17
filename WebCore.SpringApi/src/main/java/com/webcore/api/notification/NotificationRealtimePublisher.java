package com.webcore.api.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Publishes lightweight wake-up payloads; notification data itself remains durable in DBCORE_messaging. */
@Service
public class NotificationRealtimePublisher {
    private static final Logger log = LoggerFactory.getLogger(NotificationRealtimePublisher.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final NotificationProperties properties;

    public NotificationRealtimePublisher(StringRedisTemplate redis, ObjectMapper json, NotificationProperties properties) {
        this.redis = redis; this.json = json; this.properties = properties;
    }

    public void publish(String recipientEmail, NotificationService.Item item) {
        if (!properties.realtimeEnabled()) return;
        try {
            redis.convertAndSend(channel(), json.writeValueAsString(new Event("notification", recipientEmail, item)));
        } catch (Exception failure) {
            // The DB inbox has already committed, so Redis downtime may delay only the live push.
            log.warn("Notification {} is durable but could not be pushed in real time: {}", item.id(), failure.getMessage());
        }
    }

    private String channel() {
        return properties.redisChannel() == null || properties.redisChannel().isBlank()
                ? "webcore:notification:events" : properties.redisChannel().trim();
    }

    public record Event(String type, String recipientEmail, NotificationService.Item item) { }
}
