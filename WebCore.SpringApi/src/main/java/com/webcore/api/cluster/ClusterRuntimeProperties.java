package com.webcore.api.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("webcore.cluster")
public record ClusterRuntimeProperties(boolean enabled, String environment, String serviceName, String instanceId, String hostName,
                                       long pollIntervalMs, String testFailApplyInstanceId) {
    public boolean failApplyForCurrentInstance() {
        return testFailApplyInstanceId != null && !testFailApplyInstanceId.isBlank()
                && testFailApplyInstanceId.trim().equals(instanceId);
    }
}
