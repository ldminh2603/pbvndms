package com.webcore.api.controller;

import java.util.List;

import com.webcore.api.auth.AuthService;
import com.webcore.api.dms.DmsDocumentService;
import com.webcore.api.contract.DmsResponses;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/dms/documents")
public class DmsDocumentController {
    private final AuthService auth;
    private final DmsDocumentService documents;

    public DmsDocumentController(AuthService auth, DmsDocumentService documents) {
        this.auth = auth;
        this.documents = documents;
    }

    @GetMapping
    public DmsResponses.DocumentListResponse list(@AuthenticationPrincipal Jwt jwt) {
        auth.tokenPayload(jwt);
        return new DmsResponses.DocumentListResponse(documents.list());
    }

    @GetMapping("/{id}/versions")
    public DmsResponses.VersionListResponse versions(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        auth.tokenPayload(jwt);
        return new DmsResponses.VersionListResponse(documents.versions(id));
    }

    @GetMapping("/{id}/approvals")
    public DmsResponses.ApprovalListResponse approvals(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        auth.tokenPayload(jwt);
        return new DmsResponses.ApprovalListResponse(documents.approvals(id));
    }

    @PostMapping("/{id}/approval")
    public DmsResponses.ApprovalDecisionResponse decideApproval(@PathVariable long id, @RequestBody ApprovalRequest body,
                                               @AuthenticationPrincipal Jwt jwt) {
        AuthService.TokenPayload actor = auth.tokenPayload(jwt);
        return new DmsResponses.ApprovalDecisionResponse(documents.decideApproval(id, body.action(), body.note(), actor.email()));
    }

    @GetMapping("/{id}/versions/{versionNo}/view")
    public DmsResponses.DocumentViewResponse viewVersion(@PathVariable long id, @PathVariable String versionNo, @AuthenticationPrincipal Jwt jwt) {
        auth.tokenPayload(jwt);
        return new DmsResponses.DocumentViewResponse(documents.viewVersion(id, versionNo));
    }

    @GetMapping("/{id}/versions/{versionNo}/content")
    public ResponseEntity<byte[]> contentVersion(@PathVariable long id, @PathVariable String versionNo, @AuthenticationPrincipal Jwt jwt) {
        auth.tokenPayload(jwt);
        var file = documents.contentVersion(id, versionNo);
        MediaType type;
        try { type = MediaType.parseMediaType(file.contentType()); } catch (IllegalArgumentException ignored) { type = MediaType.APPLICATION_OCTET_STREAM; }
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.fileName()).build().toString())
                .header("X-Frame-Options", "SAMEORIGIN")
                .body(file.bytes());
    }

    @PutMapping("/{id}")
    public DmsResponses.DocumentResponse update(
            @PathVariable long id,
            @RequestBody DocumentUpdateRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        auth.tokenPayload(jwt);
        String name = value(body.name());
        String code = value(body.code());
        if (name.isBlank() || code.isBlank()) throw new IllegalArgumentException("name and code are required.");
        return new DmsResponses.DocumentResponse(documents.update(id, name, code, value(body.description())));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DmsResponses.DocumentResponse createDocument(
            @RequestParam String name, @RequestParam String code, @RequestParam String category,
            @RequestParam String documentType, @RequestParam(defaultValue = "1.0") String initialVersion,
            @RequestParam(defaultValue = "") String description, @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        AuthService.TokenPayload actor = auth.tokenPayload(jwt);
        if (name.isBlank() || code.isBlank() || category.isBlank() || documentType.isBlank() || initialVersion.isBlank() || file == null || file.isEmpty()) {
            throw new IllegalArgumentException("name, code, category, documentType, initialVersion and file are required.");
        }
        return new DmsResponses.DocumentResponse(documents.createDocument(name.trim(), code.trim(), category.trim(), documentType.trim(), initialVersion.trim(), description.trim(), file, actor.email()));
    }

    @PostMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DmsResponses.DocumentVersionResponse createVersion(@PathVariable long id,
            @RequestParam String versionNo, @RequestParam String effectiveDate, @RequestParam String versionNote,
            @RequestParam(defaultValue = "true") boolean sendForApproval, @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        AuthService.TokenPayload actor = auth.tokenPayload(jwt);
        if (versionNo.isBlank() || effectiveDate.isBlank() || versionNote.isBlank() || file == null || file.isEmpty()) {
            throw new IllegalArgumentException("versionNo, effectiveDate, versionNote and file are required.");
        }
        return new DmsResponses.DocumentVersionResponse(documents.createVersion(id, versionNo.trim(), effectiveDate.trim(), versionNote.trim(), sendForApproval, file, actor.email()));
    }

    public record ApprovalRequest(String action, String note) { }
    public record DocumentUpdateRequest(String name, String code, String description) { }
    private static String value(String value) { return value == null ? "" : value.trim(); }
}
