package com.webcore.api.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/** Redacted cache telemetry. Values are process-local and reset when the node restarts. */
@Service
public class RuntimeCacheMetrics {
    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counters> counters = new ConcurrentHashMap<>();

    public RuntimeCacheMetrics(MeterRegistry registry) { this.registry = registry; }

    public void hit(String cache) { record(cache, "hit"); }
    public void miss(String cache) { record(cache, "miss"); }
    public Timer.Sample warmupStart() { return Timer.start(registry); }
    public void warmup(String cache, Timer.Sample sample) {
        sample.stop(Timer.builder("webcore.cache.warmup").description("Core cache prepare duration").tag("cache", cache).register(registry));
        counters.computeIfAbsent(cache, ignored -> new Counters()).warmups.incrementAndGet();
    }
    public Map<String,CacheMetrics> status() {
        Map<String,CacheMetrics> values = new LinkedHashMap<>();
        counters.forEach((name, value) -> values.put(name, new CacheMetrics(value.hits.get(), value.misses.get(), value.warmups.get())));
        return Map.copyOf(values);
    }
    private void record(String cache, String outcome) {
        Counters value = counters.computeIfAbsent(cache, ignored -> new Counters());
        if ("hit".equals(outcome)) value.hits.incrementAndGet(); else value.misses.incrementAndGet();
        Counter.builder("webcore.cache.access").description("Runtime cache access").tag("cache", cache).tag("outcome", outcome).register(registry).increment();
    }
    private static final class Counters { private final AtomicLong hits = new AtomicLong(); private final AtomicLong misses = new AtomicLong(); private final AtomicLong warmups = new AtomicLong(); }
    public record CacheMetrics(long hits, long misses, long warmups) { }
}
