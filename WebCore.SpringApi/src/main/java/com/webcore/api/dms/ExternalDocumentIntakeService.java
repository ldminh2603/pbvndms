package com.webcore.api.dms;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Single intake flow for external systems. A supplied case id wins; otherwise
 * the existing object-type/object-reference case is reused or created.
 */
@Service
public class ExternalDocumentIntakeService {
    private final DocsvcReadService documents;

    public ExternalDocumentIntakeService(DocsvcReadService documents) {
        this.documents = documents;
    }

    public Result intake(Request request, MultipartFile file, String idempotencyKey, String actor) {
        require(request.documentCode(), "documentCode");
        require(request.categoryCode(), "categoryCode");
        require(request.typeCode(), "typeCode");

        String caseId = trim(request.caseId());
        if (caseId.isBlank()) {
            require(request.productTypeCode(), "productTypeCode");
            require(request.requestTypeCode(), "requestTypeCode");
            DocsvcReadService.CaseRow workflowCase = documents.createCase(
                    request.objectType(), request.objectRef(), request.branchCode(),
                    request.customerNumber(), request.customerName(), request.productTypeCode(),
                    request.requestTypeCode(), actor);
            caseId = workflowCase.caseId();
        }

        // reference_text is the established, persisted trace field for an
        // upstream document code; no unversioned parallel document-code field is introduced.
        DocsvcReadService.DocumentRow document = documents.uploadForCase(caseId,
                request.categoryCode(), request.typeCode(), request.documentCode(), file, idempotencyKey, actor);
        return new Result(caseId, document.id(), request.documentCode().trim(), document.version(), document.name());
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record Request(String caseId, String objectType, String objectRef, String branchCode,
                          String customerNumber, String customerName, String productTypeCode,
                          String requestTypeCode, String categoryCode,
                          String typeCode, String documentCode) { }

    public record Result(String caseId, String documentId, String documentCode, String version, String fileName) { }
}
