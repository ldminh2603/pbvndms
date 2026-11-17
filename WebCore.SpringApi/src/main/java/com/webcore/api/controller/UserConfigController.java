package com.webcore.api.controller;

import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.userconfig.UserConfigRepository;
import tools.jackson.databind.JsonNode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user-config")
public class UserConfigController {
    private final AuthService authService;
    private final UserConfigRepository userConfigRepository;

    public UserConfigController(AuthService authService, UserConfigRepository userConfigRepository) {
        this.authService = authService;
        this.userConfigRepository = userConfigRepository;
    }

    @GetMapping
    public Map<String, Object> get(@AuthenticationPrincipal Jwt jwt) {
        String email = authService.tokenPayload(jwt).email();
        return userConfigRepository.loadOrEmpty(email).values();
    }

    @PutMapping("/{key}")
    public UserConfigRepository.SaveResult save(@PathVariable String key, @RequestBody JsonNode value, @AuthenticationPrincipal Jwt jwt) {
        String email = authService.tokenPayload(jwt).email();
        return userConfigRepository.save(email, key, value, email);
    }

    @DeleteMapping("/{key}")
    public UserConfigRepository.ClearResult clear(@PathVariable String key, @AuthenticationPrincipal Jwt jwt) {
        String email = authService.tokenPayload(jwt).email();
        return userConfigRepository.clear(email, key, email);
    }
}
