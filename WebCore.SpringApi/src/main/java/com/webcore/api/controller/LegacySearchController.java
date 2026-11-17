package com.webcore.api.controller;

import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.runtime.LegacyButtonActionService;
import com.webcore.api.runtime.LegacySearchService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/legacy-search")
public class LegacySearchController {
    private final AuthService auth;
    private final LegacySearchService search;
    private final LegacyButtonActionService buttons;
    public LegacySearchController(AuthService auth, LegacySearchService search, LegacyButtonActionService buttons) {
        this.auth = auth;
        this.search = search;
        this.buttons = buttons;
    }

    @PostMapping("/{moduleId}/{subModule}/data")
    public Map<String, Object> data(@PathVariable String moduleId, @PathVariable String subModule, @RequestBody(required = false) Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        return search.search(moduleId, subModule, body == null ? Map.of() : body, auth.tokenPayload(jwt));
    }

    // @PostMapping("/{moduleId}/{subModule}/button/{buttonName}/confirmation")
    // public Map<String, Object> confirmation(@PathVariable String moduleId, @PathVariable String subModule,
    //                                         @PathVariable String buttonName, @AuthenticationPrincipal Jwt jwt) {
    //     return buttons.confirmation(moduleId, subModule, buttonName, auth.tokenPayload(jwt));
    // }

    @PostMapping("/{moduleId}/{subModule}/button/{buttonName}/execute")
    public Map<String, Object> execute(@PathVariable String moduleId, @PathVariable String subModule,
                                       @PathVariable String buttonName, @RequestBody(required = false) Map<String, Object> body,
                                       @AuthenticationPrincipal Jwt jwt) {
        return buttons.execute(moduleId, subModule, buttonName, body == null ? Map.of() : body, auth.tokenPayload(jwt));
    }

    /** Commits a Legacy Search maintain popup using the source Type-03 module's permission. */
    @PostMapping("/{moduleId}/{subModule}/button/{buttonName}/maintain/commit")
    public Map<String, Object> maintain(@PathVariable String moduleId, @PathVariable String subModule,
                                        @PathVariable String buttonName, @RequestBody(required = false) Map<String, Object> body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return buttons.maintain(moduleId, subModule, buttonName, body == null ? Map.of() : body, auth.tokenPayload(jwt));
    }
}
