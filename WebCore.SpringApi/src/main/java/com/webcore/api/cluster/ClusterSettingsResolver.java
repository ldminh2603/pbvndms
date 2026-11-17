package com.webcore.api.cluster;

import com.webcore.api.core.CoreMetadataService;
import org.springframework.stereotype.Service;

/** .NET-compatible, snapshot-backed CLUSTER_COMMAND/CLUSTER_SIGNAL settings. */
@Service
public class ClusterSettingsResolver {
    private final CoreMetadataService core;
    public ClusterSettingsResolver(CoreMetadataService core) { this.core = core; }
    public Settings resolve() {
        return new Settings(
                integer("CLUSTER_COMMAND", "PREPARED_COMMAND_TTL_SECONDS", 300, 30, 3600),
                integer("CLUSTER_COMMAND", "DELIVERY_LEASE_SECONDS", 30, 5, 600),
                integer("CLUSTER_SIGNAL", "POLL_FALLBACK_SECONDS", 2, 1, 300),
                integer("CLUSTER_SIGNAL", "CONNECT_RETRY_COUNT", 3, 0, 100),
                integer("CLUSTER_SIGNAL", "CONNECT_RETRY_DELAY_SECONDS", 2, 1, 60),
                bool("CLUSTER_COMMAND", "REQUIRE_CURRENT_TARGETS_ON_APPLY", true),
                bool("CLUSTER_COMMAND", "AUTO_SYNC_ENABLED", true),
                integer("CLUSTER_COMMAND", "AUTO_SYNC_POLL_SECONDS", 10, 2, 300),
                integer("CLUSTER_COMMAND", "AUTO_SYNC_LEASE_SECONDS", 120, 10, 600),
                text("CLUSTER_SIGNAL", "CHANNEL", "runtime_command"));
    }
    private String text(String group, String name, String fallback) { return core.systemValue(group, name, fallback); }
    private boolean bool(String group, String name, boolean fallback) { String value=text(group,name,String.valueOf(fallback)); return "1".equals(value) || Boolean.parseBoolean(value); }
    private int integer(String group,String name,int fallback,int min,int max) { try { return Math.clamp(Integer.parseInt(text(group,name,String.valueOf(fallback))),min,max); } catch (NumberFormatException ignored) { return fallback; } }
    public record Settings(int preparedCommandTtlSeconds, int deliveryLeaseSeconds, int pollFallbackSeconds, int connectRetryCount, int connectRetryDelaySeconds, boolean requireCurrentTargetsOnApply, boolean autoSyncEnabled, int autoSyncPollSeconds, int autoSyncLeaseSeconds, String channel) { }
}
