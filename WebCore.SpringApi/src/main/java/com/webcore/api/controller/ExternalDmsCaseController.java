package com.webcore.api.controller;

import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.DmsResponses;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.dms.DocsvcReadService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** External read API. A caller supplies the DMS case UUID; no business-key fallback is permitted. */
@RestController
@RequestMapping("/api/dms-docsvc/external/cases")
public class ExternalDmsCaseController {
    private final AuthService auth;
    private final CoreMetadataService core;
    private final DocsvcReadService documents;

    public ExternalDmsCaseController(AuthService auth, CoreMetadataService core, DocsvcReadService documents) {
        this.auth = auth;
        this.core = core;
        this.documents = documents;
    }

    @GetMapping("/{caseId}")
    public DmsResponses.ExternalCaseDetailResponse detail(@PathVariable String caseId, @AuthenticationPrincipal Jwt jwt) {
        AuthService.TokenPayload actor = auth.tokenPayload(jwt);
        core.authorize(actor, "03L45", "View", null);
        return new DmsResponses.ExternalCaseDetailResponse(documents.externalCaseDetail(caseId, actor.email()));
    }
}
