package com.webcore.api.dms;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.webcore.api.core.CoreMetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** DMS file bytes are held in S3/MinIO; credentials are resolved solely from named core.def_source records. */
@Service
public class DmsObjectStorageService {
    private static final Logger log = LoggerFactory.getLogger(DmsObjectStorageService.class);
    private static final String PREFIX = "TRD";
    private static final String DMS_WORM_SOURCE = "s3_dmsworm";
    private static final String QUARANTINE_SOURCE = "s3_quarantine";
    private final CoreMetadataService core;

    public DmsObjectStorageService(CoreMetadataService core) { this.core = core; }

    /** Stores infected bytes outside the normal document bucket; never returns a previewable object. */
    public String quarantine(byte[] content, String fileName) {
        S3Source source = sourceConfig(QUARANTINE_SOURCE);
        String quarantineBucket = bucket(source, QUARANTINE_SOURCE);
        String endpoint = source.endpoint();
        String access = source.accessKey();
        String secret = source.secretKey();
        if (endpoint.isBlank() || access.isBlank() || secret.isBlank()) throw new IllegalStateException("core.def_source source_name '" + QUARANTINE_SOURCE + "' has incomplete s3_utility configuration.");
        String region = source.region();
        String key = "QUARANTINE/" + UUID.randomUUID() + "_" + safeName(fileName);
        try (S3Client s3 = S3Client.builder().region(Region.of(region.isBlank() ? "us-east-1" : region))
                .endpointOverride(URI.create(endpoint)).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret))).build()) {
            s3.putObject(PutObjectRequest.builder().bucket(quarantineBucket).key(key).contentType("application/octet-stream").build(), RequestBody.fromBytes(content));
            return key;
        } catch (Exception error) { throw new IllegalStateException("Unable to quarantine infected file in bucket " + quarantineBucket + ".", error); }
    }

    public StoredObject upload(String documentCode, String versionNo, MultipartFile file) { return upload(documentCode, versionNo, file, null, null); }

    public StoredObject upload(String documentCode, String categoryCode, String typeCode, String versionNo,
                               MultipartFile file, OffsetDateTime retainUntil, String wormMode) {
        return upload(documentCode, categoryCode, typeCode, versionNo, file, retainUntil, wormMode, true);
    }

    /** Applies S3 Object Lock when the docsvc retention policy requires WORM. */
    public StoredObject upload(String documentCode, String versionNo, MultipartFile file, OffsetDateTime retainUntil, String wormMode) {
        return upload(documentCode, null, null, versionNo, file, retainUntil, wormMode, false);
    }

    private StoredObject upload(String documentCode, String categoryCode, String typeCode, String versionNo,
                                MultipartFile file, OffsetDateTime retainUntil, String wormMode, boolean includeDocumentClassification) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("A version file is required.");
        if (file.getSize() > 20L * 1024 * 1024) throw new IllegalArgumentException("Version file must not exceed 20 MB.");
        String name = safeName(file.getOriginalFilename());
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
        if (!(extension.equals(".pdf") || extension.equals(".doc") || extension.equals(".docx") || extension.equals(".xls") || extension.equals(".xlsx"))) {
            throw new IllegalArgumentException("Only PDF, DOC, DOCX, XLS and XLSX files are supported.");
        }
        S3Source source = sourceConfig(DMS_WORM_SOURCE);
        String documentBucket = bucket(source, DMS_WORM_SOURCE);
        String endpoint = source.endpoint();
        String access = source.accessKey();
        String secret = source.secretKey();
        if (endpoint.isBlank() || access.isBlank() || secret.isBlank()) {
            String missing = (endpoint.isBlank() ? " endpoint" : "") + (access.isBlank() ? " access key" : "") + (secret.isBlank() ? " secret key" : "");
            throw new IllegalStateException("core.def_source source_name '" + DMS_WORM_SOURCE + "' is missing:" + missing + ".");
        }
        String key = includeDocumentClassification
                ? classifiedKeyPrefix(documentCode, categoryCode, typeCode, versionNo)
                : PREFIX + "/" + segment(documentCode) + "/" + segment(versionNo) + "/";
        key += UUID.randomUUID() + "_" + name;
        try (S3Client s3 = S3Client.builder()
                .region(Region.of(source.region().isBlank() ? "us-east-1" : source.region()))
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                .build()) {
            PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(documentBucket).key(key).contentType(contentType(name, file.getContentType()));
            if (retainUntil != null && wormMode != null && !wormMode.isBlank()) request.objectLockMode(wormMode).objectLockRetainUntilDate(retainUntil.toInstant());
            s3.putObject(request.build(), RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (Exception error) {
            // Surface the storage provider's safe diagnostic (for example
            // NoSuchBucket/ObjectLockConfigurationNotFound) without exposing credentials.
            throw new IllegalStateException("Unable to upload document version to " + documentBucket + "/" + PREFIX + ": " + error.getMessage(), error);
        }
        return new StoredObject(documentBucket, key, name, file.getSize());
    }

    public String viewUrl(String objectKey, String fileName) {
        if (blank(objectKey) || !objectKey.startsWith(PREFIX + "/")) throw new IllegalArgumentException("This document version has no uploaded file.");
        S3Source source = sourceConfig(DMS_WORM_SOURCE);
        String documentBucket = bucket(source, DMS_WORM_SOURCE);
        String endpoint = source.endpoint();
        String access = source.accessKey();
        String secret = source.secretKey();
        if (endpoint.isBlank() || access.isBlank() || secret.isBlank()) throw new IllegalStateException("core.def_source source_name '" + DMS_WORM_SOURCE + "' has incomplete s3_utility configuration.");
        String region = source.region();
        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(region.isBlank() ? "us-east-1" : region))
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                .build()) {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(10))
                    .getObjectRequest(GetObjectRequest.builder().bucket(documentBucket).key(objectKey)
                            .responseContentDisposition("inline; filename=\"" + safeName(fileName).replace("\"", "") + "\"").build())
                    .build()).url().toString();
        }
    }

    public StoredContent read(String objectKey, String fileName) {
        if (blank(objectKey) || !objectKey.startsWith(PREFIX + "/")) throw new IllegalArgumentException("This document version has no uploaded file.");
        S3Source source = sourceConfig(DMS_WORM_SOURCE);
        String documentBucket = bucket(source, DMS_WORM_SOURCE);
        String endpoint = source.endpoint();
        String access = source.accessKey();
        String secret = source.secretKey();
        if (endpoint.isBlank() || access.isBlank() || secret.isBlank()) throw new IllegalStateException("core.def_source source_name '" + DMS_WORM_SOURCE + "' has incomplete s3_utility configuration.");
        String region = source.region();
        try (S3Client s3 = S3Client.builder().region(Region.of(region.isBlank() ? "us-east-1" : region))
                .endpointOverride(URI.create(endpoint)).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret))).build()) {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(documentBucket).key(objectKey).build());
            return new StoredContent(object.asByteArray(), contentType(fileName, object.response().contentType()), safeName(fileName));
        }
    }

    /** Reads an existing PBBDMS object by its persisted bucket/key; used only by the read adapter. */
    public StoredContent readStored(String bucket, String objectKey, String fileName) {
        if (blank(bucket) || blank(objectKey)) throw new IllegalArgumentException("This document has no stored object.");
        long startedAt = System.nanoTime();
        S3Source source = sourceConfig(DMS_WORM_SOURCE);
        long sourceConfigMs = elapsedMillis(startedAt);
        String endpoint = source.endpoint();
        String access = source.accessKey();
        String secret = source.secretKey();
        if (endpoint.isBlank() || access.isBlank() || secret.isBlank()) throw new IllegalStateException("core.def_source source_name '" + DMS_WORM_SOURCE + "' has incomplete s3_utility configuration.");
        String region = source.region();
        S3Client s3 = S3Client.builder().region(Region.of(region.isBlank() ? "us-east-1" : region)).endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret))).build();
        long clientBuildMs = elapsedMillis(startedAt) - sourceConfigMs;
        try (s3) {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
            byte[] bytes = object.asByteArray();
            log.info("docsvc_s3_read_timing source={} bucket={} sourceConfigMs={} clientBuildMs={} getObjectMs={} totalMs={} bytes={}",
                    DMS_WORM_SOURCE, bucket, sourceConfigMs, clientBuildMs, elapsedMillis(startedAt) - sourceConfigMs - clientBuildMs,
                    elapsedMillis(startedAt), bytes.length);
            return new StoredContent(bytes, contentType(fileName, object.response().contentType()), safeName(fileName));
        } catch (RuntimeException exception) {
            log.warn("docsvc_s3_read_failed source={} bucket={} sourceConfigMs={} clientBuildMs={} totalMs={} errorType={}",
                    DMS_WORM_SOURCE, bucket, sourceConfigMs, clientBuildMs, elapsedMillis(startedAt), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private static long elapsedMillis(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }

    /** Best-effort cleanup for an S3 object whose database transaction did not commit. WORM may reject this by design. */
    public void deleteOrphanQuietly(String bucket, String objectKey) {
        if (blank(bucket) || blank(objectKey)) return;
        try (S3Client s3 = client(sourceConfig(DMS_WORM_SOURCE))) { s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build()); }
        catch (Exception ignored) { /* Object Lock can forbid deletion; no successful document points to it. */ }
    }

    private S3Source sourceConfig(String sourceName) {
        CoreMetadataService.Source source = core.source(sourceName);
        if (source == null) throw new IllegalStateException("core.def_source source_name '" + sourceName + "' was not found.");
        return new S3Source(first(source.config(), "bucket_name", "bucket", "bucketName"),
                first(source.config(), "endpoint", "endpointUrl", "s3Endpoint", "serviceUrl", "url"),
                first(source.config(), "accessKeyId", "accessKey", "accessKeyIdValue", "username", "user"),
                first(source.config(), "secretAccessKey", "secretKey", "secretAccessKeyValue", "password"),
                first(source.config(), "region"));
    }
    private S3Client client(S3Source source) {
        String endpoint = source.endpoint();
        String access = source.accessKey(); String secret = source.secretKey();
        if (endpoint.isBlank() || access.isBlank() || secret.isBlank()) throw new IllegalStateException("core.def_source source configuration is incomplete.");
        String region = source.region();
        return S3Client.builder().region(Region.of(region.isBlank() ? "us-east-1" : region)).endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret))).build();
    }
    private static String bucket(S3Source source, String sourceName) {
        String value = source.bucket();
        if (value.isBlank()) throw new IllegalStateException("core.def_source source_name '" + sourceName + "' is missing s3_utility.bucket_name.");
        return value;
    }
    static String classifiedKeyPrefix(String objectRef, String categoryCode, String typeCode, String versionNo) {
        return PREFIX + "/" + segment(objectRef) + "/" + segment(categoryCode) + "/" + segment(typeCode) + "/" + segment(versionNo) + "/";
    }
    private static String first(Map<String, Object> config, String... names) {
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            for (String name : names) {
                if (normalize(entry.getKey()).equals(normalize(name)) && entry.getValue() != null && !String.valueOf(entry.getValue()).trim().isBlank()) return String.valueOf(entry.getValue()).trim();
            }
            if (entry.getValue() instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) nested;
                String found = first(values, names);
                if (!found.isBlank()) return found;
            }
        }
        return "";
    }
    private static String normalize(String value) { return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String contentType(String fileName, String supplied) {
        String name = safeName(fileName).toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".doc")) return "application/msword";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".xls")) return "application/vnd.ms-excel";
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return blank(supplied) ? "application/octet-stream" : supplied;
    }
    private static String segment(String value) { return safeName(value).replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String safeName(String value) { String name = value == null ? "" : value.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1).trim(); return name.isBlank() ? "file" : name; }
    public record StoredObject(String bucket, String key, String fileName, long sizeBytes) { }
    public record StoredContent(byte[] bytes, String contentType, String fileName) { }
    private record S3Source(String bucket, String endpoint, String accessKey, String secretKey, String region) { }
}
