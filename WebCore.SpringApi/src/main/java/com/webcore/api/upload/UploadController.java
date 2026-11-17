package com.webcore.api.upload;

import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.UploadResponses;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Generic upload control-plane for modules with id Uxxx. Bytes travel directly to S3/MinIO. */
@RestController
@RequestMapping("/api/upload/{moduleId}")
public class UploadController {
    private final UploadService uploads;
    private final AuthService auth;

    public UploadController(UploadService uploads, AuthService auth) {
        this.uploads = uploads;
        this.auth = auth;
    }

    @GetMapping("/ui")
    public UploadResponses.UiResponse ui(@PathVariable String moduleId, @AuthenticationPrincipal Jwt jwt) {
        return uploads.ui(moduleId, auth.tokenPayload(jwt));
    }

    @GetMapping("/files")
    public UploadResponses.ListFilesResponse files(@PathVariable String moduleId, @RequestParam(defaultValue = "") String path,
                                     @AuthenticationPrincipal Jwt jwt) {
        return uploads.list(moduleId, path, auth.tokenPayload(jwt));
    }

    @PostMapping("/folders")
    public UploadResponses.CreateFolderResponse createFolder(@PathVariable String moduleId, @RequestBody UploadService.CreateFolderRequest request,
                                             @AuthenticationPrincipal Jwt jwt) {
        return uploads.createFolder(moduleId, request, auth.tokenPayload(jwt));
    }

    @PostMapping("/uploads/init")
    public UploadResponses.InitResponse init(@PathVariable String moduleId, @RequestBody UploadService.UploadInitRequest request,
                                    @AuthenticationPrincipal Jwt jwt) {
        return uploads.init(moduleId, request, auth.tokenPayload(jwt));
    }

    @PostMapping("/uploads/complete")
    public UploadResponses.CompleteResponse complete(@PathVariable String moduleId, @RequestBody UploadService.UploadCompleteRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        return uploads.complete(moduleId, request, auth.tokenPayload(jwt));
    }

    @PostMapping("/uploads/abort")
    public UploadResponses.AbortResponse abort(@PathVariable String moduleId, @RequestBody UploadService.UploadAbortRequest request,
                                     @AuthenticationPrincipal Jwt jwt) {
        return uploads.abort(moduleId, request, auth.tokenPayload(jwt));
    }

    @DeleteMapping("/files")
    public UploadResponses.DeleteResponse delete(@PathVariable String moduleId, @RequestBody UploadService.DeleteFilesRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        return uploads.delete(moduleId, request, auth.tokenPayload(jwt));
    }

    @PostMapping("/items/rename")
    public UploadResponses.RenameResponse rename(@PathVariable String moduleId, @RequestBody UploadService.RenameItemRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        return uploads.rename(moduleId, request, auth.tokenPayload(jwt));
    }

    @PostMapping("/items/move")
    public UploadResponses.MoveResponse move(@PathVariable String moduleId, @RequestBody UploadService.MoveItemRequest request,
                                    @AuthenticationPrincipal Jwt jwt) {
        return uploads.move(moduleId, request, auth.tokenPayload(jwt));
    }

    @PostMapping("/items/copy")
    public UploadResponses.CopyResponse copy(@PathVariable String moduleId, @RequestBody UploadService.MoveItemRequest request,
                                    @AuthenticationPrincipal Jwt jwt) {
        return uploads.copy(moduleId, request, auth.tokenPayload(jwt));
    }

    @GetMapping("/files/download-url")
    public UploadResponses.DownloadResponse download(@PathVariable String moduleId, @RequestParam String path,
                                        @AuthenticationPrincipal Jwt jwt) {
        return uploads.download(moduleId, path, auth.tokenPayload(jwt));
    }

    @GetMapping("/template-url")
    public UploadResponses.DownloadResponse template(@PathVariable String moduleId, @AuthenticationPrincipal Jwt jwt) {
        return uploads.template(moduleId, auth.tokenPayload(jwt));
    }
}
