package com.webcore.api.runtime;

import com.webcore.api.core.CoreMetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Documents are preloaded only from the OpenAPI descriptor held in def_source.config. */
@Service
public class OpenApiDocumentCache {
    private static final Logger log=LoggerFactory.getLogger(OpenApiDocumentCache.class);
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final CoreMetadataService core;
    private final RuntimeCacheMetrics metrics;
    public OpenApiDocumentCache(CoreMetadataService core, RuntimeCacheMetrics metrics) { this.core=core; this.metrics=metrics; }
    public static Map<String,Document> prepare(CoreMetadataService.Snapshot core, ObjectMapper json) {
        Map<String,Document> next=new LinkedHashMap<>();
        for(CoreMetadataService.Source source:core.sources().values()) {
            Map<String,Object> descriptor=map(source.config(),"openapi"); String url=text(descriptor,"doc_url"); if(url.isBlank()) continue;
            try { HttpResponse<String> response=HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(number(descriptor,"timeout_seconds",30))).header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString()); if(response.statusCode()<200||response.statusCode()>=300) throw new IllegalStateException("HTTP "+response.statusCode()); next.put(key(source.sourceName()),new Document(source.sourceName(),url,"ready",response.statusCode(),json.readValue(response.body(),Object.class),Instant.now(),null)); }
            catch(Exception ex) { next.put(key(source.sourceName()),new Document(source.sourceName(),url,"unhealthy",null,null,Instant.now(),ex.getMessage())); log.warn("openapi_preload_failed source={} message={}",source.sourceName(),ex.getMessage()); }
        }
        return Map.copyOf(next);
    }
    public Document document(String sourceName) { Document value=core.snapshot().openApiDocuments().get(key(sourceName)); if(value==null)metrics.miss("openapi-document");else metrics.hit("openapi-document"); return value; }
    public Status status() { Map<String,Document> value=core.snapshot().openApiDocuments(); long ready=value.values().stream().filter(item->"ready".equals(item.status())).count(); return new Status(value.size(), ready, value.size()-ready, value.values().stream().map(item -> new SourceStatus(item.sourceName(), item.status(), item.httpStatus() == null ? "" : item.httpStatus(), item.loadedAtUtc().toString(), item.error() == null ? "" : item.error())).toList()); }
    public int clear() { return core.snapshot().openApiDocuments().size(); }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Map<String,Object> source,String key){Object value=source.get(key);return value instanceof Map<?,?> raw?(Map<String,Object>)raw:Map.of();}
    private static String text(Map<String,Object> source,String key){Object value=source.get(key);return value==null?"":String.valueOf(value).trim();}
    private static int number(Map<String,Object> source,String key,int fallback){Object value=source.get(key);return value instanceof Number number?Math.clamp(number.intValue(),1,120):fallback;}
    private static String key(String value){return value==null?"":value.trim().toLowerCase(Locale.ROOT);}
    public record Document(String sourceName,String url,String status,Integer httpStatus,Object document,Instant loadedAtUtc,String error) { }
    public record Status(int total, long ready, long unhealthy, java.util.List<SourceStatus> sources) { }
    public record SourceStatus(String sourceName, String status, Object httpStatus, String loadedAtUtc, String error) { }
}
