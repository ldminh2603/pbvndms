package com.webcore.api.controller;

import java.util.List;
import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.dms.Dms02L45S3DocumentUploadHandler;
import com.webcore.api.dms.DocsvcReadService;
import com.webcore.api.contract.DmsResponses;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.multipart.MultipartFile;

/** Worklist bridge for PBBDMS portal_case records. */
@RestController
@RequestMapping("/api/dms-docsvc/cases")
public class DocsvcCaseController {
    private static final String DMS_MODULE_ID = "02D45";
    private final AuthService auth;
    private final DocsvcReadService documents;
    private final CoreMetadataService core;
    private final Dms02L45S3DocumentUploadHandler uploads;

    public DocsvcCaseController(AuthService auth, DocsvcReadService documents, CoreMetadataService core,
                                Dms02L45S3DocumentUploadHandler uploads) {
        this.auth = auth;
        this.documents = documents;
        this.core = core;
        this.uploads = uploads;
    }

    @GetMapping
    public DmsResponses.CaseListResponse list(@AuthenticationPrincipal Jwt jwt) {
        core.authorize(auth.tokenPayload(jwt), DMS_MODULE_ID, "View", null);
        return new DmsResponses.CaseListResponse(documents.cases());
    }

    @PostMapping
    public DmsResponses.CaseResponse create(@RequestBody CaseRequest request, @AuthenticationPrincipal Jwt jwt) {
        AuthService.TokenPayload actor = auth.tokenPayload(jwt);
        core.authorize(actor, DMS_MODULE_ID, "Add", null);
        return new DmsResponses.CaseResponse(documents.createCase(request.objectType(), request.objectRef(), request.branchCode(),
                request.customerNumber(), request.customerName(), request.productTypeCode(), request.requestTypeCode(), actor.email()));
    }

    /** Multipart adapter over the same handler used by 03L45 -> 02L45. */
    @PostMapping(value = "/{caseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadDocument(@PathVariable String caseId,
                                               @RequestParam String categoryCode, @RequestParam String typeCode,
                                               @RequestParam(defaultValue = "") String referenceText,
                                               @RequestPart("file") MultipartFile file,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                               @AuthenticationPrincipal Jwt jwt) {
        AuthService.TokenPayload actor = auth.tokenPayload(jwt);
        core.authorize(actor, DMS_MODULE_ID, "Add", null);
        return uploads.upload(caseId, categoryCode, typeCode, referenceText, file, idempotencyKey, actor);
    }

    public record CaseRequest(String objectType, String objectRef, String branchCode,
                              String customerNumber, String customerName,
                              String productTypeCode, String requestTypeCode) { }
}
