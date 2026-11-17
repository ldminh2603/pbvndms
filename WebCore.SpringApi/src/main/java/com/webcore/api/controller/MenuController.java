package com.webcore.api.controller;

import java.util.List;
import java.util.Locale;
import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.MetadataLocalizationResponses;
import com.webcore.api.core.CoreMetadataService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final AuthService authService;
    private final CoreMetadataService core;

    public MenuController(AuthService authService, CoreMetadataService core) {
        this.authService = authService;
        this.core = core;
    }

    @GetMapping("/list/{component}")
    public List<CoreMetadataService.Menu> list(
        @PathVariable String component,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return core.visibleMenus(
            component,
            authService.tokenPayload(jwt)
        );
    }

    /** Menu tree declarations for the new Next.js client. */
    @GetMapping("/app/{component}")
    public List<CoreMetadataService.AppMenu> appMenu(
        @PathVariable String component,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return core.visibleAppMenus(component, authService.tokenPayload(jwt));
    }

    @GetMapping("/{component}/localization")
    public MetadataLocalizationResponses.MenuLocalizationResponse localization(
        @PathVariable String component,
        @RequestHeader("Accept-Language") String languageId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        authService.tokenPayload(jwt);

        return core.menuLocalization(
            component,
            languageId.trim().toUpperCase(Locale.ROOT)
        );
    }
}
