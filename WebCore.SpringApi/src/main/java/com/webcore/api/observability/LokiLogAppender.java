package com.webcore.api.observability;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/** Sends structured application logs to Loki without logging failures recursively. */
public class LokiLogAppender extends AppenderBase<ILoggingEvent> {
    private boolean enabled;
    private String endpoint;
    private String service = "webcore-spring-api";
    private String environment = "development";
    private ExecutorService sender;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public void setService(String service) { this.service = service; }
    public void setEnvironment(String environment) { this.environment = environment; }

    @Override
    public void start() {
        if (!enabled || endpoint == null || endpoint.isBlank()) return;
        sender = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("loki-log-sender-", 0).factory());
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!isStarted()) return;
        event.prepareForDeferredProcessing();
        sender.submit(() -> send(event));
    }

    @Override
    public void stop() {
        if (sender != null) sender.shutdown();
        super.stop();
    }

    private void send(ILoggingEvent event) {
        try {
            long timestampNs = event.getTimeStamp() * 1_000_000L;
            String level = event.getLevel().toString().toLowerCase();
            String line = "{\"timestamp\":\"" + event.getInstant() + "\",\"level\":\"" + level
                    + "\",\"logger\":\"" + escape(event.getLoggerName()) + "\",\"message\":\""
                    + escape(event.getFormattedMessage()) + "\"}";
            String payload = "{\"streams\":[{\"stream\":{\"service\":\"" + escape(service)
                    + "\",\"environment\":\"" + escape(environment) + "\",\"level\":\"" + level
                    + "\"},\"values\":[[\"" + timestampNs + "\",\"" + escape(line) + "\"]]}]}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Loki is best-effort and must never interrupt application logging.
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
