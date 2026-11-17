package com.webcore.api.controller;

import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.DashboardResponses;
import com.webcore.api.dashboard.DashboardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard module UI contract.  Layout/source metadata stays in the atomic Core snapshot. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final AuthService auth; private final DashboardService dashboards;
    public DashboardController(AuthService auth, DashboardService dashboards){this.auth=auth;this.dashboards=dashboards;}
    @GetMapping("/{moduleId}/ui") public DashboardResponses.UiResponse ui(@PathVariable String moduleId,@AuthenticationPrincipal Jwt jwt) { return dashboards.ui(moduleId, auth.tokenPayload(jwt)); }
    @GetMapping("/{moduleId}/data") public DashboardResponses.DataResponse data(@PathVariable String moduleId,@AuthenticationPrincipal Jwt jwt) { return dashboards.data(moduleId, auth.tokenPayload(jwt)); }
    @PutMapping("/{moduleId}/layout") public DashboardResponses.LayoutResponse saveLayout(@PathVariable String moduleId,@RequestBody DashboardResponses.LayoutRequest body,@AuthenticationPrincipal Jwt jwt) {
        return dashboards.saveLayout(moduleId, body.dashboardLayout(), auth.tokenPayload(jwt));
    }
}
