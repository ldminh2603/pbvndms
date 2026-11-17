package com.webcore.api.contract;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonValue;
import com.webcore.api.dms.DmsDocumentService;
import com.webcore.api.dms.DocsvcReadService;

/**
 * Endpoint-specific DMS contracts. JsonValue keeps the established JSON
 * payload rather than adding an envelope.
 */
public final class DmsResponses {
    private DmsResponses() { }

    public record DocumentListResponse(@JsonValue List<DmsDocumentService.DocumentRow> rows) { }
    public record VersionListResponse(@JsonValue List<DmsDocumentService.VersionRow> rows) { }
    public record ApprovalListResponse(@JsonValue List<DmsDocumentService.ApprovalRow> rows) { }
    public record DocsvcDocumentListResponse(@JsonValue List<DocsvcReadService.DocumentRow> rows) { }
    public record DocsvcVersionListResponse(@JsonValue List<DocsvcReadService.VersionRow> rows) { }
    public record DocumentResponse(@JsonValue DmsDocumentService.DocumentRow document) { }
    public record DocumentVersionResponse(@JsonValue DmsDocumentService.DocumentRow version) { }
    public record DocumentViewResponse(@JsonValue DmsDocumentService.DocumentView view) { }
    public record DocsvcDocumentResponse(@JsonValue DocsvcReadService.DocumentRow document) { }
    public record DocsvcDocumentVersionResponse(@JsonValue DocsvcReadService.DocumentRow version) { }
    public record DocumentStatusResponse(@JsonValue DocsvcReadService.DocumentStatus document) { }
    public record ApprovalDecisionResponse(@JsonValue DmsDocumentService.DocumentRow decision) { }
    public record MetadataResponse(@JsonValue DocsvcReadService.Metadata metadata) { }
    public record WorkflowResponse(@JsonValue DocsvcReadService.Workflow workflow) { }
    public record AmendmentHistoryResponse(@JsonValue List<DocsvcReadService.Amendment> rows) { }
    public record AmendmentResponse(@JsonValue DocsvcReadService.Workflow amendment) { }
    public record CaseListResponse(@JsonValue List<DocsvcReadService.CaseRow> rows) { }
    public record CaseResponse(@JsonValue DocsvcReadService.CaseRow workflowCase) { }
    public record ExternalCaseDetailResponse(@JsonValue DocsvcReadService.ExternalCaseDetail detail) { }
    public record OperationalReportResponse(@JsonValue DocsvcReadService.OperationalReport report) { }
}
