package com.webcore.api.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "webcore.notification")
public record NotificationProperties(String redisKeyPrefix, boolean realtimeEnabled, String redisChannel) { }
