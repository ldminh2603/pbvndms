package com.webcore.api.controller;

import com.webcore.api.auth.AuthService;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.dms.ExternalDocumentIntakeService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** API contract for a trusted external system to attach a document to a DMS case. */
@RestController
@RequestMapping("/api/dms-docsvc/external/documents")
public class ExternalDmsDocumentController {
    private final AuthService auth;
    private final CoreMetadataService core;
    private final ExternalDocumentIntakeService intake;

    public ExternalDmsDocumentController(AuthService auth, CoreMetadataService core,
                                         ExternalDocumentIntakeService intake) {
        this.auth = auth;
        this.core = core;
        this.intake = intake;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExternalDocumentIntakeService.Result upload(
            @RequestParam(defaultValue = "") String caseId,
            @RequestParam(defaultValue = "") String objectType,
            @RequestParam(defaultValue = "") String objectRef,
            @RequestParam(defaultValue = "") String branchCode,
            @RequestParam(defaultValue = "") String customerNumber,
            @RequestParam(defaultValue = "") String customerName,
            @RequestParam(defaultValue = "") String productTypeCode,
            @RequestParam(defaultValue = "") String requestTypeCode,
            @RequestParam String categoryCode,
            @RequestParam String typeCode,
            @RequestParam String documentCode,
            @RequestPart("file") MultipartFile file,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        AuthService.TokenPayload actor = auth.tokenPayload(jwt);
        core.authorize(actor, "03L45", "Add", null);
        return intake.intake(new ExternalDocumentIntakeService.Request(caseId, objectType, objectRef,
                branchCode, customerNumber, customerName, productTypeCode, requestTypeCode,
                categoryCode, typeCode, documentCode),
                file, idempotencyKey, actor.email());
    }
}
