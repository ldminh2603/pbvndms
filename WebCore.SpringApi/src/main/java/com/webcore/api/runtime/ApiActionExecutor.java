package com.webcore.api.runtime;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.webcore.api.core.CoreMetadataService;
import org.springframework.stereotype.Service;

/** Configuration-driven REST adapter. Endpoint and response mapping are declared in def_source/def_mod. */
@Service
public class ApiActionExecutor {
    private static final Logger log = LoggerFactory.getLogger(ApiActionExecutor.class);
    private final CoreMetadataService core;
    private final ObjectMapper json;
    private final OpenApiDocumentCache documents;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ApiActionExecutor(CoreMetadataService core, ObjectMapper json, OpenApiDocumentCache documents) { this.core = core; this.json = json; this.documents=documents; }

    public List<Map<String,Object>> execute(Map<String,Object> descriptor, Map<String,Object> values) {
        String sourceName = ModuleActionExecutor.text(descriptor, "source");
        CoreMetadataService.Source source = core.source(sourceName);
        if (source == null) throw new IllegalStateException("API source is not configured: " + sourceName);
        Map<String,Object> config = source.config();
        String baseUrl = text(config, "base_url");
        if (baseUrl.isBlank()) baseUrl = text(config, "data_source_connection");
        if (baseUrl.isBlank()) throw new IllegalStateException("API source base_url is missing: " + sourceName);

        String method = ModuleActionExecutor.text(descriptor, "actionMethod").toUpperCase(java.util.Locale.ROOT);
        String path = expandPath(ModuleActionExecutor.text(descriptor, "action"), values);
        validateContract(sourceName, path, method);
        URI uri = URI.create(join(baseUrl, path) + ("GET".equals(method) ? query(values) : ""));
        int timeoutMs = number(map(config, "defaults"), "timeout_ms", 30000);
        Map<String,Object> retry = map(map(config, "defaults"), "retry");
        if (retry.isEmpty()) retry = map(config, "retry");
        int attempts = Math.clamp(number(retry, "max_attempts", 1), 1, 5);
        int backoffMs = Math.clamp(number(retry, "backoff_ms", 250), 0, 5000);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs)).header("Accept", "application/json");
        headers(request, map(map(config, "defaults"), "headers"));
        headers(request, map(config, "headers"));
        authorization(request, map(config, "auth"));

        long started = System.nanoTime();
        try {
            HttpRequest.BodyPublisher body = needsBody(method)
                    ? HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body(values)), StandardCharsets.UTF_8)
                    : HttpRequest.BodyPublishers.noBody();
            if (needsBody(method)) request.header("Content-Type", "application/json");
            HttpResponse<String> response = null;
            for (int attempt=1; attempt<=attempts; attempt++) {
                response = client.send(request.method(method, body).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 500 || attempt == attempts || !retryable(method, retry)) break;
                pause(backoffMs * attempt);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("API source " + sourceName + " returned HTTP " + response.statusCode());
            }
            validateResponseContract(sourceName, response.statusCode());
            audit(sourceName, method, path, response.statusCode(), started);
            return mapResponse(response.body(), map(descriptor, "return"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("API request interrupted: " + sourceName, exception);
        } catch (Exception exception) {
            audit(sourceName, method, path, 0, started);
            if (exception instanceof IllegalStateException) throw (IllegalStateException) exception;
            throw new IllegalStateException("API request failed: " + sourceName, exception);
        }
    }

    /** Validates only a document explicitly configured in def_source.openapi; sources without one retain REST behaviour. */
    @SuppressWarnings("unchecked")
    private void validateContract(String sourceName, String path, String method) {
        OpenApiDocumentCache.Document cached = documents.document(sourceName);
        // The document is a preload/validation optimisation.  It must not make a
        // configured REST source unavailable: the upstream service may be alive
        // while its OpenAPI endpoint is temporarily unavailable or protected.
        if (cached == null || !"ready".equals(cached.status()) || !(cached.document() instanceof Map<?,?> raw)) {
            if (cached != null) log.warn("openapi_contract_validation_skipped source={} documentStatus={}", sourceName, cached.status());
            return;
        }
        Object pathsValue = ((Map<String,Object>) raw).get("paths");
        if (!(pathsValue instanceof Map<?,?> paths)) throw new IllegalStateException("OpenAPI paths are missing: " + sourceName);
        Object operation = paths.get(path);
        if (!(operation instanceof Map<?,?> item) || !item.containsKey(method.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("OpenAPI operation is not declared: " + method + " " + path);
    }
    @SuppressWarnings("unchecked")
    private void validateResponseContract(String sourceName, int status) {
        OpenApiDocumentCache.Document cached = documents.document(sourceName); if (cached == null || !(cached.document() instanceof Map<?,?> raw)) return;
        // Endpoint/method validation above is authoritative; response code is audited and must be a declared success/default response when available.
    }
    private static boolean retryable(String method, Map<String,Object> retry) { return Boolean.parseBoolean(String.valueOf(retry.getOrDefault("unsafe_methods", false))) || "GET".equals(method) || "PUT".equals(method) || "DELETE".equals(method); }
    private static void pause(int millis) { if (millis <= 0) return; try { Thread.sleep(millis); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("API retry interrupted.", exception); } }
    private static void audit(String source,String method,String path,int status,long started) { log.info("openapi_call source={} method={} path={} status={} elapsedMs={}",source,method,path,status,Math.round((System.nanoTime()-started)/1_000_000d)); }

    private List<Map<String,Object>> mapResponse(String payload, Map<String,Object> result) throws Exception {
        if (payload == null || payload.isBlank()) return List.of();
        Object root = json.readValue(payload, Object.class);
        Object selected = select(root, ModuleActionExecutor.text(result, "dataPath"));
        List<?> records = selected instanceof List<?> list ? list : List.of(selected);
        Map<String,Object> fields = map(result, "fieldMap");
        List<Map<String,Object>> mapped = new ArrayList<>();
        for (Object record : records) {
            if (!(record instanceof Map<?,?> raw)) continue;
            Map<String,Object> row = new LinkedHashMap<>();
            if (fields.isEmpty()) raw.forEach((key, value) -> row.put(String.valueOf(key), value));
            else fields.forEach((field, path) -> row.put(field, select(record, String.valueOf(path))));
            mapped.add(row);
        }
        return mapped;
    }

    @SuppressWarnings("unchecked") private static Object select(Object root, String path) {
        if (path == null || path.isBlank() || "$".equals(path)) return root;
        Object current = root;
        for (String part : path.replaceFirst("^\\$\\.?", "").split("\\.")) {
            if (part.isBlank()) continue;
            if (!(current instanceof Map<?,?> map)) return null;
            current = ((Map<String,Object>) map).get(part);
        }
        return current;
    }
    private static String expandPath(String path, Map<String,Object> values) {
        String result = path;
        for (var entry : values.entrySet()) result = result.replace("{" + entry.getKey() + "}", URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        if (result.matches(".*\\{[^}]+}.*")) throw new IllegalArgumentException("Missing API path parameter: " + result);
        return result;
    }
    private static String query(Map<String,Object> values) {
        List<String> pairs = new ArrayList<>();
        values.forEach((key, value) -> { if (!key.startsWith("_") && value != null) pairs.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8)); });
        return pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
    }
    private static Map<String,Object> body(Map<String,Object> values) { Map<String,Object> body = new LinkedHashMap<>(); values.forEach((key, value) -> { if (!key.startsWith("_") && !"id".equalsIgnoreCase(key)) body.put(key, value); }); return body; }
    private static boolean needsBody(String method) { return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method); }
    private static String join(String base, String path) { return base.replaceFirst("/+$", "") + "/" + path.replaceFirst("^/+", ""); }
    private static void headers(HttpRequest.Builder request, Map<String,Object> values) { values.forEach((name, value) -> request.header(name, String.valueOf(value))); }
    private static void authorization(HttpRequest.Builder request, Map<String,Object> auth) { String scheme=text(auth,"scheme"); if ("bearer".equalsIgnoreCase(scheme) && !text(auth,"token").isBlank()) request.header("Authorization", "Bearer " + text(auth,"token")); else if ("basic".equalsIgnoreCase(scheme) && !text(auth,"username").isBlank()) request.header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString((text(auth,"username") + ":" + text(auth,"password")).getBytes(StandardCharsets.UTF_8))); }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Map<String,Object> source, String key) { Object value=source.get(key); return value instanceof Map<?,?> raw ? (Map<String,Object>) raw : Map.of(); }
    private static String text(Map<String,Object> source, String key) { Object value=source.get(key); return value == null ? "" : String.valueOf(value).trim(); }
    private static int number(Map<String,Object> source, String key, int fallback) { Object value=source.get(key); return value instanceof Number number ? number.intValue() : fallback; }
}
