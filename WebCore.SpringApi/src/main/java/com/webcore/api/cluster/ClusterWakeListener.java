package com.webcore.api.cluster;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.webcore.api.database.CoreDataSourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

/** Real push wake-up. Durable DB delivery polling remains enabled if either listener disconnects. */
@Service
public final class ClusterWakeListener implements SmartLifecycle, MessageListener {
    private static final Logger log = LoggerFactory.getLogger(ClusterWakeListener.class);
    private final CoreDataSourceResolver source; private final ClusterRuntimeProperties properties; private final ClusterCommandWorker worker; private final ClusterSettingsResolver settings;
    private final RedisMessageListenerContainer redis; private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("cluster-pg-listener-", 0).factory());
    private volatile boolean running;
    public ClusterWakeListener(CoreDataSourceResolver source, ClusterRuntimeProperties properties, ClusterCommandWorker worker, RedisMessageListenerContainer redis, ClusterSettingsResolver settings) { this.source=source; this.properties=properties; this.worker=worker; this.redis=redis; this.settings=settings; }
    @Override public void start() { if (!properties.enabled() || running) return; running=true; if ("POSTGRES".equals(source.provider("DBCORE_ops"))) executor.submit(this::listenPostgres); else { redis.addMessageListener(this, new ChannelTopic(settings.resolve().channel())); redis.start(); } }
    private void listenPostgres() {
        while (running) try (Connection connection=source.resolve("DBCORE_ops").getConnection(); Statement statement=connection.createStatement()) {
            statement.execute("LISTEN " + settings.resolve().channel().replaceAll("[^A-Za-z0-9_]", ""));
            Object pg = connection.unwrap(Class.forName("org.postgresql.PGConnection")); Method notices=pg.getClass().getMethod("getNotifications");
            while (running) { statement.execute("SELECT 1"); Object array=notices.invoke(pg); if (array != null && Array.getLength(array)>0) worker.wake(); Thread.sleep(150); }
        } catch (Exception failure) { log.warn("PostgreSQL cluster listener disconnected; durable poll remains active: {}", failure.getMessage()); try { Thread.sleep(settings.resolve().connectRetryDelaySeconds()*1000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; } }
    }
    @Override public void onMessage(Message message, byte[] pattern) { if (running) worker.wake(); }
    @Override public void stop() { running=false; try { redis.removeMessageListener(this); } catch (Exception ignored) {} executor.shutdownNow(); }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 50; }
}
