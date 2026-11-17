package com.webcore.api.core;

import com.webcore.api.cluster.ClusterCommandWorker;
import com.webcore.api.cluster.ClusterCoordinatorService;
import com.webcore.api.cluster.ClusterRuntimeProperties;
import com.webcore.api.runtime.RuntimeReloadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.Instant;

/** Opt-in direct-DB metadata invalidation. In cluster mode the elected endpoint creates a durable command. */
@Service
public class CoreMetadataInvalidationMonitor {
    private static final Logger log=LoggerFactory.getLogger(CoreMetadataInvalidationMonitor.class);
    private final CoreMetadataRepository repository; private final CoreMetadataService core; private final RuntimeReloadService reloads; private final ClusterCoordinatorService cluster; private final ClusterRuntimeProperties properties; private final ClusterCommandWorker worker; private final ObjectMapper json;
    private volatile String revision=""; private volatile Instant next=Instant.EPOCH;
    public CoreMetadataInvalidationMonitor(CoreMetadataRepository repository,CoreMetadataService core,RuntimeReloadService reloads,ClusterCoordinatorService cluster,ClusterRuntimeProperties properties,ClusterCommandWorker worker,ObjectMapper json){this.repository=repository;this.core=core;this.reloads=reloads;this.cluster=cluster;this.properties=properties;this.worker=worker;this.json=json;}
    @Scheduled(fixedDelay=1000) public void check() {
        if(!enabled()||Instant.now().isBefore(next)) return; next=Instant.now().plusSeconds(seconds());
        try { String nextRevision=hash(); if(revision.isBlank()){revision=nextRevision;return;} if(revision.equals(nextRevision)) { if(properties.enabled()) cluster.advanceMetadataInvalidation(); return; } revision=nextRevision;
            if(properties.enabled()) { if(isLeader()) cluster.requestMetadataInvalidation(nextRevision); }
            else reloads.reloadSystem("system:metadata-watch");
            log.info("core_metadata_changed revision={} mode={}",nextRevision,properties.enabled()?"cluster":"local");
        } catch(Exception ex) { log.warn("core_metadata_invalidation_check_failed: {}",ex.getMessage()); }
    }
    private boolean enabled(){return Boolean.parseBoolean(core.systemValue("RUNTIME_CACHE","INVALIDATION_ENABLED","false"));}
    private int seconds(){try{return Math.clamp(Integer.parseInt(core.systemValue("RUNTIME_CACHE","INVALIDATION_POLL_SECONDS","30")),5,300);}catch(NumberFormatException e){return 30;}}
    private boolean isLeader(){return cluster.endpoints().endpoints().stream().filter(ClusterCoordinatorService.EndpointState::isFresh).map(ClusterCoordinatorService.EndpointState::recordCode).sorted().findFirst().map(worker.recordCode()::equals).orElse(false);}
    private String hash() throws Exception { byte[] bytes=json.writeValueAsBytes(repository.revisionPayload()); byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder result=new StringBuilder();for(byte value:digest)result.append(String.format("%02x",value));return result.toString(); }
}
