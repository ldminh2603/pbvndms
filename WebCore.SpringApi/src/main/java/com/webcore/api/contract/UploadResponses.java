package com.webcore.api.contract;

import java.util.List;
import java.util.Map;

import com.webcore.api.core.CoreMetadataService;

/** Fixed upload endpoint envelopes; provider/config-dependent payloads remain maps. */
public final class UploadResponses {
    private UploadResponses() { }

    public record UiResponse(String moduleId, String moduleName, Map<String, Object> configUi,
            Map<String, Object> language, Map<String, Object> metadata, CoreMetadataService.UiPermission permission) { }
    public record FileItem(String key, String name, String path, String parentPath, boolean isDirectory,
            boolean hasSubDirectories, long size, String dateModified, String eTag) { }
    public record PresignedUploadFile(String clientFileName, String targetPath, String finalObjectKey,
            String uploadObjectKey, String uploadMethod, String url, Map<String, String> headers) { }
    public record CompletedUploadFile(String clientFileName, String uploadObjectKey, String finalObjectKey,
            long fileSize, String status) { }
    public record DeleteItem(String path, boolean isDirectory) { }
    public record ListFilesResponse(String path, List<FileItem> items) { }
    public record CreateFolderResponse(FileItem item) { }
    public record InitResponse(String uploadId, String mode, String expiresAtUtc,
            List<PresignedUploadFile> files) { }
    public record CompleteResponse(String uploadId, String mode, List<CompletedUploadFile> files) { }
    public record AbortResponse(String uploadId, boolean aborted, List<String> deletedObjects, String reason) { }
    public record DeleteResponse(List<DeleteItem> deleted, int deletedObjectCount, List<DeleteItem> failed) { }
    public record RenameResponse(FileItem item) { }
    public record MoveResponse(FileItem item, int copiedObjectCount, Boolean deletedSource) { }
    public record CopyResponse(FileItem item, int copiedObjectCount) { }
    public record DownloadResponse(String path, String url, String expiresAtUtc) { }
}
