package com.webcore.api.runtime;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.webcore.api.core.CoreMetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/** Readiness is false until the core snapshot and all configured business sources have been probed. */
@Service
@Order(1)
public class StartupGate implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupGate.class);
    private final CoreMetadataService core; private final SourceDataSourceRegistry sources;
    private final AtomicReference<State> state = new AtomicReference<>(new State(false, Instant.EPOCH, List.of()));
    public StartupGate(CoreMetadataService core, SourceDataSourceRegistry sources) { this.core = core; this.sources = sources; }
    @Override public void run(ApplicationArguments args) { refresh(); }
    public State refresh() {
        List<SourceStatus> statuses = core.snapshot().sources().values().stream().map(source -> {
            if (!isSqlSource(source)) {
                return new SourceStatus(source.sourceName(), source.type(), source.type(), "skipped", false, false,
                        "startup-probe", 30, "Non-SQL source is not probed during core startup.", null, null,
                        Instant.now(), 0D);
            }
            SourceDataSourceRegistry.ProbeStatus probe = sources.probe(source.sourceName());
            log.info("core_source_probe_timing source={} healthy={} elapsedMs={}", source.sourceName(), probe.healthy(), probe.elapsedMs());
            return new SourceStatus(source.sourceName(), source.type(), source.type(), probe.healthy() ? "healthy" : "unhealthy", probe.healthy(), false, "sql-connection-probe", 30, probe.message(), probe.healthy() ? null : "SOURCE_PROBE_FAILED", probe.healthy() ? null : probe.message(), Instant.now(), probe.elapsedMs());
        }).toList();
        // .NET records source probe failures for Admin Tools but does not make optional
        // API/Config sources (or non-critical SQL sources) fail the startup gate.
        State current = new State(!core.snapshot().loadedAtUtc().equals(Instant.EPOCH), Instant.now(), statuses); state.set(current); return current;
    }

    private static boolean isSqlSource(CoreMetadataService.Source source) {
        String type = source.type() == null ? "" : source.type().trim();
        if (!"SQL".equalsIgnoreCase(type)) return false;
        Object provider = source.config().get("data_source_type");
        return provider != null && switch (String.valueOf(provider).trim().toUpperCase()) {
            case "POSTGRES", "SQLSERVER", "ORACLE" -> true;
            default -> false;
        };
    }
    public State state() { return state.get(); }
    public record State(boolean ready, Instant checkedAtUtc, List<SourceStatus> sources) { }
    public record SourceStatus(String sourceName, String dataSourceType, String sourceKind, String status, boolean isHealthy, boolean isCritical, String phase, int phaseCode, String message, String errorCode, String errorMessage, Instant checkedAtUtc, double elapsedMs) { }
}
