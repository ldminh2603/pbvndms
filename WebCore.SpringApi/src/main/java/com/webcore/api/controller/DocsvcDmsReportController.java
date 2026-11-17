package com.webcore.api.controller;

import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.DmsResponses;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.dms.DocsvcReadService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only FR-019 operations report; access remains under the DMS View policy. */
@RestController
@RequestMapping("/api/dms-docsvc/reports")
public class DocsvcDmsReportController {
    private final AuthService auth;
    private final CoreMetadataService core;
    private final DocsvcReadService documents;

    public DocsvcDmsReportController(AuthService auth, CoreMetadataService core, DocsvcReadService documents) {
        this.auth = auth;
        this.core = core;
        this.documents = documents;
    }

    @GetMapping("/operations")
    public DmsResponses.OperationalReportResponse operations(@AuthenticationPrincipal Jwt jwt) {
        core.authorize(auth.tokenPayload(jwt), "02D45", "View", null);
        return new DmsResponses.OperationalReportResponse(documents.operationalReport());
    }
}
