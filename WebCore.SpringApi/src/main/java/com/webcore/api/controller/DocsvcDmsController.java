package com.webcore.api.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.dms.DmsObjectStorageService;
import com.webcore.api.dms.DocsvcReadService;
import com.webcore.api.contract.DmsResponses;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** DMS UI bridge over the imported PBBDMS docsvc schema. */
@RestController
@RequestMapping("/api/dms-docsvc/documents")
public class DocsvcDmsController {
    private static final String DMS_MODULE_ID = "02D45";
    private final AuthService auth;
    private final CoreMetadataService core;
    private final DocsvcReadService documents;

    public DocsvcDmsController(AuthService auth, CoreMetadataService core, DocsvcReadService documents) {
        this.auth = auth;
        this.core = core;
        this.documents = documents;
    }

    @GetMapping public DmsResponses.DocsvcDocumentListResponse list(@AuthenticationPrincipal Jwt jwt) { authorize(jwt, "View"); return new DmsResponses.DocsvcDocumentListResponse(documents.list()); }
    @GetMapping("/{id}") public DmsResponses.DocsvcDocumentResponse document(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) { authorize(jwt, "View"); return new DmsResponses.DocsvcDocumentResponse(documents.document(id)); }
    @GetMapping("/metadata") public DmsResponses.MetadataResponse metadata(@AuthenticationPrincipal Jwt jwt) { authorize(jwt, "View"); return new DmsResponses.MetadataResponse(documents.metadata()); }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DmsResponses.DocsvcDocumentResponse upload(@RequestParam String objectType, @RequestParam String objectRef,
                                      @RequestParam String branchCode, @RequestParam String categoryCode,
                                      @RequestParam String typeCode, @RequestParam(defaultValue = "") String referenceText,
                                      @RequestParam(defaultValue = "") String customerNumber, @RequestParam(defaultValue = "") String customerName,
                                      @RequestPart("file") MultipartFile file, @RequestHeader("Idempotency-Key") String idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        AuthService.TokenPayload actor = auth.tokenPayload(jwt);
        core.authorize(actor, DMS_MODULE_ID, "Add", null);
        return new DmsResponses.DocsvcDocumentResponse(documents.upload(objectType, objectRef, branchCode, categoryCode, typeCode, referenceText, customerNumber, customerName, file, idempotencyKey, actor.email()));
    }
    @PostMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DmsResponses.DocsvcDocumentVersionResponse createVersion(@PathVariable String id, @RequestParam(defaultValue = "") String referenceText,
                                              @RequestPart("file") MultipartFile file, @RequestHeader("Idempotency-Key") String idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        AuthService.TokenPayload actor = auth.tokenPayload(jwt);
        core.authorize(actor, DMS_MODULE_ID, "Edit", null);
        return new DmsResponses.DocsvcDocumentVersionResponse(documents.createVersion(id, referenceText, file, idempotencyKey, actor.email()));
    }
    @GetMapping("/{id}/versions") public DmsResponses.DocsvcVersionListResponse versions(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) { authorize(jwt, "View"); return new DmsResponses.DocsvcVersionListResponse(documents.versions(id)); }
    @GetMapping("/{id}/amendments/history") public DmsResponses.AmendmentHistoryResponse amendmentHistory(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) { authorize(jwt, "View"); return new DmsResponses.AmendmentHistoryResponse(documents.amendmentHistory(id)); }
    @GetMapping("/{id}/workflow") public DmsResponses.WorkflowResponse workflow(@PathVariable String id, @RequestParam(defaultValue = "") String objectType, @RequestParam(defaultValue = "") String objectRef, @AuthenticationPrincipal Jwt jwt) {
        AuthService.TokenPayload actor = authorize(jwt, "View");
        return new DmsResponses.WorkflowResponse(documents.workflow(id, objectType, objectRef, actor.email(), actor.roles().stream().map(role -> role.name()).toList(), actor.isPrivilegedAccount()));
    }
    @PostMapping("/{id}/remove") public DmsResponses.DocumentStatusResponse remove(@PathVariable String id, @RequestParam String reason, @AuthenticationPrincipal Jwt jwt) {
        return new DmsResponses.DocumentStatusResponse(documents.remove(id, reason, authorize(jwt, "Delete").email()));
    }
    @PostMapping("/{id}/restore") public DmsResponses.DocumentStatusResponse restore(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return new DmsResponses.DocumentStatusResponse(documents.restore(id, authorize(jwt, "Delete").email()));
    }
    @GetMapping("/{id}/content") public ResponseEntity<byte[]> content(@PathVariable String id,
            @RequestParam(defaultValue = "PREVIEW") String access, @AuthenticationPrincipal Jwt jwt) {
        // Re-check Core permission on every preview/download request.  A copied
        // URL or direct HTTP call therefore cannot bypass the DMS View right.
        AuthService.TokenPayload actor = authorize(jwt, "View");
        String accessAction = "DOWNLOAD".equalsIgnoreCase(access) ? "DOWNLOAD" : "PREVIEW";
        DmsObjectStorageService.StoredContent file = documents.content(id, actor.email(), accessAction);
        MediaType type;
        try { type = MediaType.parseMediaType(file.contentType()); } catch (IllegalArgumentException ignored) { type = MediaType.APPLICATION_OCTET_STREAM; }
        return ResponseEntity.ok().contentType(type).contentLength(file.bytes().length).header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(file.fileName(), StandardCharsets.UTF_8).build().toString()).header("X-Frame-Options", "SAMEORIGIN").body(file.bytes());
    }

    private AuthService.TokenPayload authorize(Jwt jwt, String action) {
        AuthService.TokenPayload actor = auth.tokenPayload(jwt);
        core.authorize(actor, DMS_MODULE_ID, action, null);
        return actor;
    }
}
