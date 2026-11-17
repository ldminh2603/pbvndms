package com.webcore.api.controller;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.webcore.api.auth.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Privileged log-file reader compatible with the legacy WebCore client contract. */
@RestController
@RequestMapping("/api/admin/logs")
public class AdminLogsController {
    private static final int DEFAULT_MAX_LINES = 4_000;
    private static final int MAX_ALLOWED_LINES = 20_000;
    private static final int DEFAULT_MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ALLOWED_BYTES = 5 * 1024 * 1024;

    private final AuthService auth;
    private final Path logDirectory;

    public AdminLogsController(AuthService auth, @Value("${WEBCORE_LOG_DIRECTORY:Logs}") String configuredDirectory) {
        this.auth = auth;
        this.logDirectory = Path.of(configuredDirectory).toAbsolutePath().normalize();
    }

    @GetMapping("/files")
    public FilesResponse files(@AuthenticationPrincipal Jwt jwt) throws IOException {
        requirePrivileged(jwt);
        List<LogFile> files = availableFiles();
        String defaultFileId = files.stream().filter(file -> file.kind().equals("info")).findFirst()
                .or(() -> files.stream().findFirst()).map(LogFile::id).orElse(null);
        return new FilesResponse(files.stream().map(this::listItem).toList(), defaultFileId == null ? "" : defaultFileId);
    }

    @GetMapping("/content")
    public ResponseEntity<?> content(@RequestParam String fileId,
                                     @RequestParam(defaultValue = "4000") int maxLines,
                                     @RequestParam(defaultValue = "2097152") int maxBytes,
                                     @AuthenticationPrincipal Jwt jwt) throws IOException {
        requirePrivileged(jwt);
        if (maxLines < 1 || maxLines > MAX_ALLOWED_LINES) return badRequest("maxLines must be between 1 and 20000.");
        if (maxBytes < 1 || maxBytes > MAX_ALLOWED_BYTES) return badRequest("maxBytes must be between 1 and 5242880.");
        LogFile file = findFile(fileId);
        if (file == null) return ResponseEntity.notFound().build();
        Tail tail = readTail(file.path(), maxLines, maxBytes);
        return ResponseEntity.ok(new ContentResponse(file.id(), file.name(), file.name(), file.kind(), file.sizeBytes(),
                file.lastModifiedUtc().toString(), tail.content(), tail.lineCount(), tail.loadedBytes(), tail.truncated()));
    }

    @GetMapping("/download")
    public ResponseEntity<?> download(@RequestParam String fileId, @AuthenticationPrincipal Jwt jwt) throws IOException {
        requirePrivileged(jwt);
        LogFile file = findFile(fileId);
        if (file == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.name().replace("\"", "") + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(new FileSystemResource(file.path()));
    }

    private List<LogFile> availableFiles() throws IOException {
        if (!Files.isDirectory(logDirectory)) return List.of();
        try (var paths = Files.list(logDirectory)) {
            return paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".log"))
                    .map(this::toLogFile).sorted(Comparator.comparing(LogFile::lastModifiedUtc).reversed()
                            .thenComparing(LogFile::name, String.CASE_INSENSITIVE_ORDER)).toList();
        }
    }

    private LogFile findFile(String fileId) throws IOException {
        if (fileId == null || fileId.isBlank()) return null;
        return availableFiles().stream().filter(file -> file.id().equalsIgnoreCase(fileId)).findFirst().orElse(null);
    }

    private LogFile toLogFile(Path path) {
        try {
            String name = path.getFileName().toString();
            return new LogFile(idFor(path), name, kindFor(name), Files.size(path), Files.getLastModifiedTime(path).toInstant(), path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read log file metadata.", exception);
        }
    }

    private Tail readTail(Path path, int maxLines, int maxBytes) throws IOException {
        long size = Files.size(path);
        int readSize = (int) Math.min(size, maxBytes);
        if (readSize == 0) return new Tail("", 0, 0, false);
        byte[] bytes = new byte[readSize];
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(Math.max(0, size - readSize));
            file.readFully(bytes);
        }
        String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        boolean truncatedByBytes = size > readSize;
        if (truncatedByBytes && lines.size() > 1) lines.removeFirst();
        if (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        boolean truncatedByLines = lines.size() > maxLines;
        if (truncatedByLines) lines = new ArrayList<>(lines.subList(lines.size() - maxLines, lines.size()));
        java.util.Collections.reverse(lines);
        return new Tail(String.join(System.lineSeparator(), lines), lines.size(), readSize, truncatedByBytes || truncatedByLines);
    }

    private FileItemResponse listItem(LogFile file) {
        return new FileItemResponse(file.id(), file.name(), file.name(), file.kind(), file.sizeBytes(), file.lastModifiedUtc().toString());
    }

    private String idFor(Path path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create log file id.", exception);
        }
    }

    private static String kindFor(String name) {
        String normalized = name.toLowerCase();
        if (normalized.contains("error")) return "error";
        if (normalized.contains("audit")) return "audit";
        return normalized.contains("info") ? "info" : "other";
    }

    private ResponseEntity<ErrorResponse> badRequest(String message) { return ResponseEntity.badRequest().body(new ErrorResponse(message)); }
    private void requirePrivileged(Jwt jwt) { if (!auth.tokenPayload(jwt).isPrivilegedAccount()) throw new SecurityException("ADMIN_ACCESS_DENIED"); }
    private record LogFile(String id, String name, String kind, long sizeBytes, Instant lastModifiedUtc, Path path) { }
    private record Tail(String content, int lineCount, int loadedBytes, boolean truncated) { }
    public record FilesResponse(List<FileItemResponse> files, String defaultFileId) { }
    public record FileItemResponse(String id, String name, String displayName, String kind, long sizeBytes, String lastModifiedUtc) { }
    public record ContentResponse(String id, String name, String displayName, String kind, long sizeBytes, String lastModifiedUtc, String content, int lineCount, int loadedBytes, boolean isTruncated) { }
    public record ErrorResponse(String errorMessage) { }
}
