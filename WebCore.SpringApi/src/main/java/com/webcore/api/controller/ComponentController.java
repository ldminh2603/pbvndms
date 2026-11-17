package com.webcore.api.controller;

import java.util.List;

import com.webcore.api.auth.AllowAnonymous;
import com.webcore.api.auth.AuthService;
import com.webcore.api.core.CoreMetadataService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/component")
public class ComponentController {
    private final AuthService authService;
    private final CoreMetadataService core;
    public ComponentController(AuthService authService, CoreMetadataService core) { this.authService = authService; this.core = core; }
    @GetMapping("/list")
    public List<CoreMetadataService.Component> list
    (
        @AuthenticationPrincipal Jwt jwt
    ) 
    { 
        return core.visibleComponents(authService.tokenPayload(jwt)); 
    }

    @GetMapping("/types")
    public List<CoreMetadataService.ComponentType> types() {
        return core.componentTypes();
    }

    // Public - không yêu cầu JWT
    //@AllowAnonymous
    @GetMapping("/public/list")
    public List<CoreMetadataService.Component> publicList() {
        return core.publicComponents();
    }
    
}
