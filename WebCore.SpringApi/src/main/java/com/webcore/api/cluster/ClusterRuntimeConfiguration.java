package com.webcore.api.cluster;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ClusterRuntimeProperties.class)
public class ClusterRuntimeConfiguration {
    /** Keeps heartbeats alive while another scheduled task performs a long reload. */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("webcore-scheduled-");
        return scheduler;
    }
}
