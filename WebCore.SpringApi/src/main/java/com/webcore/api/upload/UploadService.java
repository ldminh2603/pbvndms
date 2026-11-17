package com.webcore.api.upload;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.UploadResponses;
import com.webcore.api.core.CoreMetadataService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Config-driven S3-compatible upload runtime. Module storage values can use
 * {{def_source.<sourceName>.<property>}} expressions; no source identity is hard-coded.
 */
@Service
public class UploadService {
    private static final Pattern SOURCE_TOKEN = Pattern.compile("\\{\\{def_source\\.([^.}]+)\\.([^}]+)}}", Pattern.CASE_INSENSITIVE);
    private final CoreMetadataService core;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public UploadService(CoreMetadataService core) { this.core = core; }

    /** UI bootstrap for ClientNew; storage credentials remain server-side. */
    public UploadResponses.UiResponse ui(String moduleId, AuthService.TokenPayload actor) {
        if (moduleId == null || !moduleId.trim().toUpperCase(Locale.ROOT).startsWith("U")) throw new UploadException(HttpStatus.NOT_FOUND, "UPLOAD_MODULE_NOT_FOUND", "Upload module was not found.");
        CoreMetadataService.Module module = core.module(moduleId);
        if (module == null) throw new UploadException(HttpStatus.NOT_FOUND, "UPLOAD_MODULE_NOT_FOUND", "Upload module was not found.");
        core.authorize(actor, module.modId(), "View", null);
        return new UploadResponses.UiResponse(module.modId(), module.modName(), module.configUi(),
                module.language(), module.metadata(), core.uiPermission(actor, module.modId()));
    }

    public UploadResponses.ListFilesResponse list(String moduleId, String path, AuthService.TokenPayload actor) {
        Context ctx = context(moduleId, actor, "View", null);
        String relative = path(path, false);
        // S3 list uses a delimiter.  A child directory must end in '/' or the
        // provider returns that directory itself as a common prefix instead of
        // its contained files (for example test/a1 versus test/a1/).
        String prefixValue = join(ctx.storage.prefix(), relative);
        if (!relative.isBlank() && !prefixValue.endsWith("/")) prefixValue += "/";
        final String prefix = prefixValue;
        var response = ctx.s3.listObjectsV2(ListObjectsV2Request.builder().bucket(ctx.storage.bucket()).prefix(prefix).delimiter("/").build());
        List<UploadResponses.FileItem> items = new ArrayList<>();
        response.commonPrefixes().forEach(folder -> items.add(item(relativePath(ctx.storage.prefix(), folder.prefix()).replaceAll("/$", ""), true, 0, null, null)));
        response.contents().stream().filter(object -> !object.key().equals(prefix) && !object.key().endsWith("/"))
                .forEach(object -> items.add(item(relativePath(ctx.storage.prefix(), object.key()), false, object.size(), object.lastModified(), object.eTag())));
        items.sort(Comparator.comparing((UploadResponses.FileItem value) -> !value.isDirectory()).thenComparing(UploadResponses.FileItem::name, String.CASE_INSENSITIVE_ORDER));
        return new UploadResponses.ListFilesResponse(relative, items);
    }

    public UploadResponses.CreateFolderResponse createFolder(String moduleId, CreateFolderRequest request, AuthService.TokenPayload actor) {
        Context ctx = context(moduleId, actor, null, "Upload");
        String parent = path(request.parentPath(), false); String name = segment(request.name());
        String relative = parent.isBlank() ? name : parent + "/" + name;
        String key = join(ctx.storage.prefix(), relative) + "/";
        ctx.s3.putObject(PutObjectRequest.builder().bucket(ctx.storage.bucket()).key(key).contentType("application/x-directory").build(), RequestBody.empty());
        return new UploadResponses.CreateFolderResponse(item(relative, true, 0, Instant.now(), null));
    }

    public UploadResponses.InitResponse init(String moduleId, UploadInitRequest request, AuthService.TokenPayload actor) {
        Context ctx = context(moduleId, actor, null, "Upload");
        String directory = path(request.targetDirectoryPath(), false);
        List<UploadInitFile> files = request.files() == null ? List.of() : request.files();
        if (files.isEmpty()) throw error("VALIDATION_FAILED", "No files provided.");
        int maximum = ctx.validation.maxFileCount();
        if (files.size() > maximum) throw error("VALIDATION_FAILED", "File count exceeds configured maximum.");
        int concurrent = ctx.validation.maxConcurrentUploads();
        cleanupSessions();
        if (concurrent > 0 && sessions.values().stream().filter(s -> s.moduleId.equalsIgnoreCase(moduleId) && s.userId.equals(actor.email()) && s.expiresAt.isAfter(Instant.now()) && s.status.equals("active")).count() >= concurrent)
            throw new UploadException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many concurrent uploads.");
        for (UploadInitFile file : files) validate(file, ctx.validation);
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        int expires = ctx.storage.directUploadExpiresInSeconds();
        Instant expiresAt = Instant.now().plusSeconds(expires);
        List<UploadResponses.PresignedUploadFile> results = new ArrayList<>(); List<String> objectKeys = new ArrayList<>();
        for (UploadInitFile file : files) {
            String key = join(ctx.storage.prefix(), directory.isBlank() ? segment(file.clientFileName()) : directory + "/" + segment(file.clientFileName()));
            String tag = "module_id=" + enc(moduleId) + "&upload_id=" + enc(uploadId) + "&uploaded_by=" + enc(actor.email());
            PutObjectRequest put = PutObjectRequest.builder().bucket(ctx.storage.bucket()).key(key).contentType(blank(file.contentType()) ? "application/octet-stream" : file.contentType()).tagging(tag).build();
            PresignedPutObjectRequest signed = ctx.presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(Duration.ofSeconds(expires)).putObjectRequest(put).build());
            Map<String,String> headers = new LinkedHashMap<>();
            signed.httpRequest().headers().forEach((name, values) -> { if (!name.equalsIgnoreCase("host") && !values.isEmpty()) headers.put(name, values.getFirst()); });
            results.add(new UploadResponses.PresignedUploadFile(file.clientFileName(), relativePath(ctx.storage.prefix(), key), key, key, "presigned-put", signed.url().toString(), headers));
            objectKeys.add(key);
        }
        sessions.put(uploadId, new Session(uploadId, moduleId, actor.email(), ctx, objectKeys, expiresAt, "active"));
        return new UploadResponses.InitResponse(uploadId, ctx.workflow.mode(), expiresAt.toString(), results);
    }

    public UploadResponses.CompleteResponse complete(String moduleId, UploadCompleteRequest request, AuthService.TokenPayload actor) {
        Context ctx = context(moduleId, actor, null, "Upload"); Session session = session(moduleId, request.uploadId(), actor);
        List<UploadResponses.CompletedUploadFile> files = new ArrayList<>();
        for (UploadCompleteFile file : safe(request.files())) {
            if (!session.objectKeys.contains(file.uploadObjectKey())) throw error("UPLOAD_SESSION_INVALID", "Upload object is not part of this session.");
            var object = ctx.s3.headObject(HeadObjectRequest.builder().bucket(ctx.storage.bucket()).key(file.uploadObjectKey()).build());
            files.add(new UploadResponses.CompletedUploadFile(file.clientFileName(), file.uploadObjectKey(), file.uploadObjectKey(), object.contentLength(), "completed"));
        }
        if (files.isEmpty()) throw error("VALIDATION_FAILED", "No completed files provided.");
        session.status = "completed";
        return new UploadResponses.CompleteResponse(session.id, ctx.workflow.mode(), files);
    }

    public UploadResponses.AbortResponse abort(String moduleId, UploadAbortRequest request, AuthService.TokenPayload actor) {
        Context ctx = context(moduleId, actor, null, "Upload"); Session session = session(moduleId, request.uploadId(), actor); List<String> deleted = new ArrayList<>();
        for (String key : session.objectKeys) try { ctx.s3.deleteObject(DeleteObjectRequest.builder().bucket(ctx.storage.bucket()).key(key).build()); deleted.add(key); } catch (S3Exception ignored) { }
        session.status = "aborted";
        return new UploadResponses.AbortResponse(session.id, true, deleted, request.reason() == null ? "" : request.reason());
    }

    public UploadResponses.DeleteResponse delete(String moduleId, DeleteFilesRequest request, AuthService.TokenPayload actor) {
        Context ctx = context(moduleId, actor, null, "Delete"); if (!request.confirmed()) throw error("VALIDATION_FAILED", "Delete must be confirmed.");
        List<UploadResponses.DeleteItem> deleted = new ArrayList<>(), failed = new ArrayList<>(); int count = 0;
        for (DeleteFileItem item : safe(request.items())) try { count += deletePath(ctx, path(item.path(), item.isDirectory()), item.isDirectory()); deleted.add(new UploadResponses.DeleteItem(item.path(), item.isDirectory())); } catch (Exception ex) { failed.add(new UploadResponses.DeleteItem(item.path(), item.isDirectory())); }
        return new UploadResponses.DeleteResponse(deleted, count, failed);
    }

    public UploadResponses.RenameResponse rename(String moduleId, RenameItemRequest request, AuthService.TokenPayload actor) {
        Context ctx = context(moduleId, actor, null, "Rename"); String oldPath = path(request.path(), request.isDirectory()); String parent = parent(oldPath); String newPath = parent.isBlank() ? segment(request.newName()) : parent + "/" + segment(request.newName());
        copyPath(ctx, oldPath, newPath, request.isDirectory(), false); deletePath(ctx, oldPath, request.isDirectory());
        return new UploadResponses.RenameResponse(item(newPath, request.isDirectory(), 0, Instant.now(), null));
    }

    public UploadResponses.MoveResponse move(String moduleId, MoveItemRequest request, AuthService.TokenPayload actor) {
        TransferResult result = transfer(moduleId, request, actor, false);
        return new UploadResponses.MoveResponse(result.item(), result.copiedObjectCount(), true);
    }

    public UploadResponses.CopyResponse copy(String moduleId, MoveItemRequest request, AuthService.TokenPayload actor) {
        TransferResult result = transfer(moduleId, request, actor, true);
        return new UploadResponses.CopyResponse(result.item(), result.copiedObjectCount());
    }

    private TransferResult transfer(String moduleId, MoveItemRequest request, AuthService.TokenPayload actor, boolean copy) {
        Context ctx = context(moduleId, actor, null, copy ? "Copy" : "Move"); String source = path(request.sourcePath(), request.isDirectory()); String target = path(request.destinationParentPath(), false); String destination = target.isBlank() ? name(source) : target + "/" + name(source);
        int copied = copyPath(ctx, source, destination, request.isDirectory(), request.overwrite()); if (!copy) deletePath(ctx, source, request.isDirectory());
        UploadResponses.FileItem resultItem = item(destination, request.isDirectory(), 0, Instant.now(), null);
        return new TransferResult(resultItem, copied);
    }

    public UploadResponses.DownloadResponse download(String moduleId, String path, AuthService.TokenPayload actor) {
        Context ctx = context(moduleId, actor, null, "Download"); String relative = path(path, false); int ttl = ctx.storage.directUploadExpiresInSeconds();
        GetObjectRequest get = GetObjectRequest.builder().bucket(ctx.storage.bucket()).key(join(ctx.storage.prefix(), relative)).responseContentDisposition("attachment; filename=\"" + name(relative).replace("\"", "") + "\"").build();
        PresignedGetObjectRequest signed = ctx.presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofSeconds(ttl)).getObjectRequest(get).build());
        return new UploadResponses.DownloadResponse(relative, signed.url().toString(), Instant.now().plusSeconds(ttl).toString());
    }

    public UploadResponses.DownloadResponse template(String moduleId, AuthService.TokenPayload actor) {
        Context ctx = context(moduleId, actor, "View", null); String templatePath = templateSettings(ctx.module.config()).path();
        if (templatePath.isBlank()) throw new UploadException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_CONFIGURED", "No upload template is configured.");
        return download(moduleId, templatePath, actor);
    }

    private Context context(String moduleId, AuthService.TokenPayload actor, String action, String operation) {
        if (moduleId == null || !moduleId.trim().toUpperCase(Locale.ROOT).startsWith("U")) throw new UploadException(HttpStatus.NOT_FOUND, "UPLOAD_MODULE_NOT_FOUND", "Upload module was not found.");
        CoreMetadataService.Module module = core.module(moduleId); if (module == null) throw new UploadException(HttpStatus.NOT_FOUND, "UPLOAD_MODULE_NOT_FOUND", "Upload module was not found.");
        core.authorize(actor, module.modId(), action == null ? "View" : action, operation);
        Map<String,Object> storage = object(module.config(), "storage"); if (!"s3".equalsIgnoreCase(string(storage.get("provider"), ""))) throw new UploadException(HttpStatus.BAD_REQUEST, "UPLOAD_STORAGE_UNSUPPORTED", "Module storage provider must be s3.");
        Map<String,Object> resolved = resolveTemplates(storage); String bucket = string(resolved.get("bucket"), ""); if (bucket.isBlank()) throw error("UPLOAD_STORAGE_INVALID", "Storage bucket is missing.");
        SourceSettings sourceConfig = sourceConfig(storage); String region = string(resolved.get("region"), sourceConfig.region()); String endpoint = sourceConfig.endpoint(); String access = sourceConfig.accessKey(); String secret = sourceConfig.secretKey();
        var clientBuilder = S3Client.builder().region(Region.of(region)); var presignerBuilder = S3Presigner.builder().region(Region.of(region)); if (!endpoint.isBlank()) { URI uri = URI.create(endpoint); clientBuilder.endpointOverride(uri).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()); presignerBuilder.endpointOverride(uri).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()); }
        if (!access.isBlank() && !secret.isBlank()) { var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)); clientBuilder.credentialsProvider(credentials); presignerBuilder.credentialsProvider(credentials); }
        return new Context(module, new StorageSettings(bucket, normalizePrefix(string(resolved.get("prefix"), "")),
                number(object(storage, "directUpload").get("expiresInSeconds"), 900)), validationSettings(object(module.config(), "validation")),
                workflowSettings(object(module.config(), "workflow")), clientBuilder.build(), presignerBuilder.build());
    }

    private SourceSettings sourceConfig(Map<String,Object> storage) {
        String explicit = string(storage.get("source"), "");
        CoreMetadataService.Source source = explicit.isBlank() ? null : core.source(explicit);
        if (source == null) { Matcher matcher = SOURCE_TOKEN.matcher(storage.toString()); if (matcher.find()) source = core.source(matcher.group(1)); }
        Map<String,Object> config = source == null ? Map.of() : source.config();
        return new SourceSettings(string(config.get("region"), "us-east-1"), first(config, "endpoint", "serviceUrl", "service_url", "url"),
                first(config, "accessKeyId", "access_key_id", "accessKey", "access_key"), first(config, "secretAccessKey", "secret_access_key", "secretKey", "secret_key"));
    }
    private Map<String,Object> resolveTemplates(Map<String,Object> source) { Map<String,Object> result = new LinkedHashMap<>(); source.forEach((k,v) -> result.put(k, resolveValue(v))); return result; }
    private Object resolveValue(Object value) { if (!(value instanceof String text)) return value; Matcher matcher = SOURCE_TOKEN.matcher(text); StringBuffer result = new StringBuffer(); while (matcher.find()) { CoreMetadataService.Source source = core.source(matcher.group(1)); Object resolved = nested(source == null ? Map.of() : source.config(), matcher.group(2)); matcher.appendReplacement(result, Matcher.quoteReplacement(resolved == null ? "" : String.valueOf(resolved))); } matcher.appendTail(result); return result.toString(); }
    private static Object nested(Map<String,Object> source, String dotted) { Object current = source; for (String part : dotted.split("\\.")) { if (!(current instanceof Map<?,?> map)) return null; current = map.get(part); } return current; }
    private Session session(String moduleId, String id, AuthService.TokenPayload actor) { Session session = sessions.get(id); if (session == null || !session.moduleId.equalsIgnoreCase(moduleId) || !session.userId.equals(actor.email()) || !session.status.equals("active") || session.expiresAt.isBefore(Instant.now())) throw error("UPLOAD_SESSION_INVALID", "Upload session is invalid or expired."); return session; }
    private void cleanupSessions() { sessions.values().removeIf(session -> session.expiresAt.plus(Duration.ofDays(1)).isBefore(Instant.now())); }
    private static void validate(UploadInitFile file, ValidationSettings validation) { if (file == null || blank(file.clientFileName()) || file.fileSize() <= 0) throw error("VALIDATION_FAILED", "File name and positive size are required."); if (file.fileSize() > validation.maxFileSizeBytes()) throw error("FILE_TOO_LARGE", "File size exceeds configured maximum."); if (!validation.allowedExtensions().isEmpty()) { String ext = file.clientFileName().contains(".") ? file.clientFileName().substring(file.clientFileName().lastIndexOf('.')).toLowerCase(Locale.ROOT) : ""; if (validation.allowedExtensions().stream().noneMatch(item -> item.equalsIgnoreCase(ext))) throw error("INVALID_EXTENSION", "File extension is not allowed."); } segment(file.clientFileName()); }
    private static int deletePath(Context ctx, String relative, boolean directory) { List<String> keys = directory ? ctx.s3.listObjectsV2(ListObjectsV2Request.builder().bucket(ctx.storage.bucket()).prefix(join(ctx.storage.prefix(), relative) + "/").build()).contents().stream().map(v -> v.key()).toList() : List.of(join(ctx.storage.prefix(), relative)); keys.forEach(key -> ctx.s3.deleteObject(DeleteObjectRequest.builder().bucket(ctx.storage.bucket()).key(key).build())); return keys.size(); }
    private static int copyPath(Context ctx, String source, String destination, boolean directory, boolean overwrite) { String from = join(ctx.storage.prefix(), source); String to = join(ctx.storage.prefix(), destination); List<String> keys = directory ? ctx.s3.listObjectsV2(ListObjectsV2Request.builder().bucket(ctx.storage.bucket()).prefix(from + "/").build()).contents().stream().map(v -> v.key()).toList() : List.of(from); if (keys.isEmpty()) throw error("FILE_NOT_FOUND", "Source item was not found."); for (String key : keys) { String destinationKey = directory ? to + key.substring(from.length()) : to; if (!overwrite) try { ctx.s3.headObject(HeadObjectRequest.builder().bucket(ctx.storage.bucket()).key(destinationKey).build()); throw error("DUPLICATE_PATH", "Destination already exists."); } catch (NoSuchKeyException ignored) { } catch (S3Exception error) { if (error.statusCode() != 404) throw error; } ctx.s3.copyObject(CopyObjectRequest.builder().copySource(enc(ctx.storage.bucket() + "/" + key)).bucket(ctx.storage.bucket()).key(destinationKey).build()); } return keys.size(); }
    private static UploadResponses.FileItem item(String relative, boolean directory, long size, Instant modified, String eTag) { return new UploadResponses.FileItem(relative, name(relative), relative, parent(relative), directory, directory, size, modified == null ? null : modified.toString(), eTag); }
    private static String path(String value, boolean directory) { String p = value == null ? "" : value.trim().replace('\\', '/').replaceAll("/+", "/").replaceAll("^/+|/+$", ""); if (p.equals(".") || p.contains("../") || p.startsWith("..")) throw error("INVALID_PATH", "Path escapes the upload root."); return p; }
    private static String segment(String value) { String name = path(value, false); if (name.isBlank() || name.contains("/")) throw error("INVALID_PATH", "File or folder name is invalid."); return name; }
    private static String normalizePrefix(String value) { String p = path(value, true); return p.isBlank() ? "" : p + "/"; }
    private static String join(String prefix, String path) { return prefix + path; }
    private static String relativePath(String prefix, String key) { return key.startsWith(prefix) ? key.substring(prefix.length()) : key; }
    private static String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    private static String name(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? path : path.substring(slash + 1); }
    private static String enc(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static Map<String,Object> object(Map<String,Object> value, String name) { Object result = value.get(name); return result instanceof Map<?,?> map ? cast(map) : Map.of(); }
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Map<?,?> value) { Map<String,Object> result = new LinkedHashMap<>(); value.forEach((k,v) -> result.put(String.valueOf(k), v)); return result; }
    private static String first(Map<String,Object> values, String... names) { for (String name : names) { String result = string(values.get(name), ""); if (!result.isBlank()) return result; } Map<String,Object> nested = object(values, "s3_utility"); for (String name : names) { String result = string(nested.get(name), ""); if (!result.isBlank()) return result; } return ""; }
    private static String string(Object value, String fallback) { return value == null ? fallback : String.valueOf(value).trim(); }
    private static int number(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; } }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static <T> List<T> safe(List<T> value) { return value == null ? List.of() : value; }
    private static UploadException error(String code, String message) { return new UploadException(HttpStatus.BAD_REQUEST, code, message); }

    private static ValidationSettings validationSettings(Map<String,Object> value) { Object extensions = value.get("allowedExtensions"); return new ValidationSettings(number(value.get("maxFileCount"), 1), number(value.get("maxConcurrentUploads"), 0), number(value.get("maxFileSizeBytes"), 10 * 1024 * 1024), extensions instanceof Collection<?> list ? list.stream().map(String::valueOf).toList() : List.of()); }
    private static WorkflowSettings workflowSettings(Map<String,Object> value) { return new WorkflowSettings(string(value.get("mode"), "direct")); }
    private static TemplateSettings templateSettings(Map<String,Object> value) { return new TemplateSettings(string(object(value, "template").get("path"), "")); }
    private record Context(CoreMetadataService.Module module, StorageSettings storage, ValidationSettings validation, WorkflowSettings workflow, S3Client s3, S3Presigner presigner) { }
    private record StorageSettings(String bucket, String prefix, int directUploadExpiresInSeconds) { }
    private record SourceSettings(String region, String endpoint, String accessKey, String secretKey) { }
    private record ValidationSettings(int maxFileCount, int maxConcurrentUploads, int maxFileSizeBytes, List<String> allowedExtensions) { }
    private record WorkflowSettings(String mode) { }
    private record TemplateSettings(String path) { }
    private record TransferResult(UploadResponses.FileItem item, int copiedObjectCount) { }
    private static final class Session { final String id,moduleId,userId; final Context context; final List<String> objectKeys; final Instant expiresAt; volatile String status; Session(String id,String moduleId,String userId,Context context,List<String> objectKeys,Instant expiresAt,String status){this.id=id;this.moduleId=moduleId;this.userId=userId;this.context=context;this.objectKeys=List.copyOf(objectKeys);this.expiresAt=expiresAt;this.status=status;} }
    public record CreateFolderRequest(String parentPath, String name) { }
    public record UploadInitFile(String clientFileName, long fileSize, String contentType, String checksumSha256) { }
    public record UploadInitRequest(String targetDirectoryPath, List<UploadInitFile> files) { }
    public record UploadCompleteFile(String clientFileName, String uploadObjectKey, String eTag) { }
    public record UploadCompleteRequest(String uploadId, List<UploadCompleteFile> files) { }
    public record UploadAbortRequest(String uploadId, String reason) { }
    public record DeleteFileItem(String path, boolean isDirectory) { }
    public record DeleteFilesRequest(List<DeleteFileItem> items, boolean confirmed) { }
    public record RenameItemRequest(String path, boolean isDirectory, String newName) { }
    public record MoveItemRequest(String sourcePath, String destinationParentPath, boolean isDirectory, boolean overwrite) { }
}
