package com.webcore.api.dms;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.webcore.api.auth.AuthService;
import com.webcore.api.runtime.MaintainJavaHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Uploads the 02L45 CFU payload through the normal docsvc S3/WORM pipeline. */
@Service
public class Dms02L45S3DocumentUploadHandler implements MaintainJavaHandler {
    private static final String HANDLER = "DMS_02L45_S3_DOCUMENT_UPLOAD";
    private final DocsvcReadService documents;

    public Dms02L45S3DocumentUploadHandler(DocsvcReadService documents) { this.documents = documents; }

    @Override public String name() { return HANDLER; }

    @Override
    public Map<String, Object> commit(String moduleId, String subModule, Map<String, Object> form,
                                      Map<String, Object> request, AuthService.TokenPayload user) {
        Map<String, Object> values = map(request.get("values"));
        String caseId = required(values, "CASE_ID");
        String categoryCode = required(values, "CATEGORY_CODE");
        String typeCode = required(values, "TYPE_CODE");
        String referenceText = optional(values, "REFERENCE_TEXT");
        MultipartFile file = decodeCfu(required(values, "FILEINFO"));

        return upload(caseId, categoryCode, typeCode, referenceText, file, null, user);
    }

    /**
     * Shared entry point for any transport.  The Legacy commit adapter supplies
     * a decoded CFU file; a multipart controller can supply its request part.
     */
    public Map<String, Object> upload(String caseId, String categoryCode, String typeCode,
                                      String referenceText, MultipartFile file, String idempotencyKey,
                                      AuthService.TokenPayload user) {
        requireValue(caseId, "CASE_ID");
        requireValue(categoryCode, "CATEGORY_CODE");
        requireValue(typeCode, "TYPE_CODE");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("FILEINFO is required.");
        String resolvedIdempotencyKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString() : idempotencyKey.trim();
        DocsvcReadService.DocumentRow document = documents.uploadForCase(caseId, categoryCode, typeCode,
                referenceText, file, resolvedIdempotencyKey, user.email());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commitHandler", "JAVA:" + HANDLER);
        result.put("documentId", document.id());
        result.put("caseId", caseId);
        result.put("version", document.version());
        result.put("fileName", document.name());
        result.put("idempotencyKey", resolvedIdempotencyKey);
        return Map.copyOf(result);
    }

    /** CFU wire format: [one-byte UTF-8 filename length][filename][file bytes]. */
    private static MultipartFile decodeCfu(String value) {
        final byte[] payload;
        try { payload = Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("FILEINFO must be a Base64 CFU payload.", error); }
        if (payload.length < 2) throw new IllegalArgumentException("FILEINFO CFU payload has no file content.");
        int nameLength = Byte.toUnsignedInt(payload[0]);
        if (nameLength == 0 || payload.length <= nameLength + 1) throw new IllegalArgumentException("FILEINFO CFU payload is invalid.");
        String name = new String(payload, 1, nameLength, StandardCharsets.UTF_8).trim();
        if (name.isBlank()) throw new IllegalArgumentException("FILEINFO filename is required.");
        byte[] content = java.util.Arrays.copyOfRange(payload, nameLength + 1, payload.length);
        return new CfuMultipartFile(name, content);
    }

    private static String required(Map<String, Object> values, String name) {
        String value = optional(values, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " is required.");
        return value;
    }
    private static void requireValue(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required.");
    }
    private static String optional(Map<String, Object> values, String name) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey().trim().equalsIgnoreCase(name)) return entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim();
        }
        return "";
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? new LinkedHashMap<>((Map<String, Object>) source) : Map.of();
    }

    private record CfuMultipartFile(String originalFilename, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() {
            try { return Files.probeContentType(Path.of(originalFilename)); }
            catch (IOException ignored) { return null; }
        }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), bytes); }
        @Override public void transferTo(Path destination) throws IOException { Files.write(destination, bytes); }
    }
}
