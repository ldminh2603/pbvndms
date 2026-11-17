package com.webcore.api.dms;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalDocumentIntakeServiceTests {
    private static final MockMultipartFile FILE = new MockMultipartFile("file", "document.pdf", "application/pdf", new byte[] { 1 });

    @Test
    void attachesToTheSuppliedCaseWithoutCreatingAnotherCase() {
        DocsvcReadService documents = mock(DocsvcReadService.class);
        when(documents.uploadForCase("case-1", "CATEGORY", "TYPE", "EXT-001", FILE, "request-1", "partner@example.com"))
                .thenReturn(document("document-1"));

        ExternalDocumentIntakeService.Result result = new ExternalDocumentIntakeService(documents).intake(
                request("case-1"), FILE, "request-1", "partner@example.com");

        assertEquals("case-1", result.caseId());
        assertEquals("EXT-001", result.documentCode());
        verify(documents, never()).createCase(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createsOrReusesCaseWhenNoCaseIdIsSupplied() {
        DocsvcReadService documents = mock(DocsvcReadService.class);
        when(documents.createCase("TRADE_FINANCE", "LC00002", "030", "CUS-1", "Customer", "TRADE_FINANCE", "LC_ISSUANCE", "partner@example.com"))
                .thenReturn(new DocsvcReadService.CaseRow("case-2", "TRADE_FINANCE", "LC00002", "030", "DRAFT", "partner@example.com", "CUS-1", "Customer", 0, 0, ""));
        when(documents.uploadForCase("case-2", "CATEGORY", "TYPE", "EXT-001", FILE, "request-2", "partner@example.com"))
                .thenReturn(document("document-2"));

        ExternalDocumentIntakeService.Result result = new ExternalDocumentIntakeService(documents).intake(
                request(""), FILE, "request-2", "partner@example.com");

        assertEquals("case-2", result.caseId());
        assertEquals("document-2", result.documentId());
        verify(documents).createCase("TRADE_FINANCE", "LC00002", "030", "CUS-1", "Customer", "TRADE_FINANCE", "LC_ISSUANCE", "partner@example.com");
    }

    @Test
    void createsAClassifiedDocsvcObjectPrefixFromObjectReference() {
        assertEquals("TRD/BC20001/CONTRACT/CREDIT_AGREEMENT/v2/",
                DmsObjectStorageService.classifiedKeyPrefix("BC20001", "CONTRACT", "CREDIT_AGREEMENT", "v2"));
    }

    private static ExternalDocumentIntakeService.Request request(String caseId) {
        return new ExternalDocumentIntakeService.Request(caseId, "TRADE_FINANCE", "LC00002", "030", "CUS-1", "Customer", "TRADE_FINANCE", "LC_ISSUANCE", "CATEGORY", "TYPE", "EXT-001");
    }

    private static DocsvcReadService.DocumentRow document(String id) {
        return new DocsvcReadService.DocumentRow(id, "document.pdf", "EXT-001", "Category", "Type", "1", "ACTIVE", "CLEAN", "DRAFT", 1, "application/pdf", "partner@example.com", "030", false, "TRADE_FINANCE", "LC00002", "");
    }
}
