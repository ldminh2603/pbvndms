package com.webcore.api.controller;

import com.webcore.api.auth.AllowAnonymous;
import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.MetadataLocalizationResponses;
import com.webcore.api.contract.ModuleFormInfoResponse;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.runtime.LegacyCcbListSourceService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cached ModuleInfo + localization for a Form,
 * addressed by MODID and SUBMOD.
 */
@RestController
@RequestMapping("/api/module")
public class FormInfoController {

    private final AuthService auth;
    private final CoreMetadataService core;
    private final LegacyCcbListSourceService ccbListSources;

    public FormInfoController(
            AuthService auth,
            CoreMetadataService core,
            LegacyCcbListSourceService ccbListSources) {
        this.auth = auth;
        this.core = core;
        this.ccbListSources = ccbListSources;
    }

    /**
     * Full structural metadata.
     *
     * Không phụ thuộc language.
     */
    @GetMapping("/{moduleId}/form-info/{subModule}")
    public ModuleFormInfoResponse byPath(
            @PathVariable String moduleId,
            @PathVariable String subModule,
            @AuthenticationPrincipal Jwt jwt) {

        auth.tokenPayload(jwt);

        return findFormInfo(moduleId, subModule);
    }

    @GetMapping("/{moduleId}/form-info")
    public ModuleFormInfoResponse byQuery(
            @PathVariable String moduleId,
            @RequestParam String subModule,
            @AuthenticationPrincipal Jwt jwt) {

        auth.tokenPayload(jwt);

        return findFormInfo(moduleId, subModule);
    }

    /** DEFMOD-only identity for fixed modules; unlike form-info, it has no DEFMODFLD dependency. */
    @GetMapping("/{moduleId}/definition/{subModule}")
    public java.util.Map<String, String> definition(
            @PathVariable String moduleId,
            @PathVariable String subModule,
            @AuthenticationPrincipal Jwt jwt) {
        auth.tokenPayload(jwt);
        java.util.List<java.util.Map<String, Object>> candidates = core.systemMetadata("DEFMOD").stream()
                .filter(row -> value(row, "modid").trim().equalsIgnoreCase(moduleId.trim())).toList();
        java.util.Map<String, Object> module = candidates.stream()
                .filter(row -> value(row, "submod").trim().equalsIgnoreCase(subModule.trim()))
                .findFirst().orElseGet(() -> candidates.size() == 1 ? candidates.getFirst() : null);
        if (module == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Module definition not found: " + moduleId + "." + subModule);
        return java.util.Map.of("ModuleID", value(module, "modid"), "SubModule", value(module, "submod"),
                "ModuleType", value(module, "modtype"), "UIType", value(module, "uitype"), "ModuleName", value(module, "modname"));
    }

    /** Resolves a parameterized CCB from values currently entered in a form. */
    @PostMapping("/{moduleId}/form-info/{subModule}/ccb/{fieldId}")
    public java.util.List<java.util.Map<String, Object>> ccbItems(
            @PathVariable String moduleId,
            @PathVariable String subModule,
            @PathVariable String fieldId,
            @RequestBody(required = false) java.util.Map<String, Object> values,
            @AuthenticationPrincipal Jwt jwt) {
        var user = auth.tokenPayload(jwt);
        core.authorize(user, moduleId, "View", null);
        java.util.Map<String, Object> form = core.runtimeFormInfo(moduleId, subModule);
        if (form == null || form.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Form metadata not found: " + moduleId + "." + subModule);
        }
        return ccbListSources.items(form, fieldId, values == null ? java.util.Map.of() : values);
    }

    /** Resolves a dependent CFU LISTSOURCE, for example extensions and maximum size for a selected document type. */
    @PostMapping("/{moduleId}/form-info/{subModule}/file-config/{fieldId}")
    public java.util.Map<String, Object> fileConfig(
            @PathVariable String moduleId,
            @PathVariable String subModule,
            @PathVariable String fieldId,
            @RequestBody(required = false) java.util.Map<String, Object> values,
            @AuthenticationPrincipal Jwt jwt) {
        var user = auth.tokenPayload(jwt);
        core.authorize(user, moduleId, "View", null);
        java.util.Map<String, Object> form = core.runtimeFormInfo(moduleId, subModule);
        if (form == null || form.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Form metadata not found: " + moduleId + "." + subModule);
        }
        return ccbListSources.fileConfig(form, fieldId, values == null ? java.util.Map.of() : values);
    }

    /**
     * Lightweight localization only.
     *
     * Example:
     *
     * GET /api/module/03001/MVW/localization
     * Accept-Language: VN
     */
    // @GetMapping("/{moduleId}/{subModule}/localization")
    // public Map<String, Object> localization(
    //         @PathVariable String moduleId,
    //         @PathVariable String subModule,
    //         @RequestHeader(
    //                 value = "Accept-Language",
    //                 required = false)
    //         String acceptLanguage,
    //         @AuthenticationPrincipal Jwt jwt) {

    //     var token = auth.tokenPayload(jwt);

    //     String languageId =
    //             normalizeLanguage(
    //                     acceptLanguage != null
    //                             ? acceptLanguage
    //                             : token.get("languageId"));

    //     return findLocalization(
    //             moduleId,
    //             subModule,
    //             languageId);
    // }

    @GetMapping("/{moduleId}/{subModule}/localization")
    public MetadataLocalizationResponses.FormLocalizationResponse localization(
            @PathVariable String moduleId,
            @PathVariable String subModule,
            @RequestHeader("Accept-Language") String languageId,
            @AuthenticationPrincipal Jwt jwt) {

        auth.tokenPayload(jwt);

        return findLocalization(
                moduleId,
                subModule,
                languageId.trim().toUpperCase()
        );
    }


    private ModuleFormInfoResponse findFormInfo(
            String moduleId,
            String subModule) {

        java.util.Map<String, Object> runtime = core.runtimeFormInfo(moduleId, subModule);
        ModuleFormInfoResponse result = runtime == null || runtime.isEmpty()
                ? null
                : ModuleFormInfoResponse.from(ccbListSources.enrich(runtime));

        if (result == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Form metadata not found: "
                            + moduleId
                            + "."
                            + subModule);
        }

        return result;
    }

    private MetadataLocalizationResponses.FormLocalizationResponse findLocalization(
            String moduleId,
            String subModule,
            String languageId) {

        MetadataLocalizationResponses.FormLocalizationResponse result =
                core.formLocalization(
                        moduleId,
                        subModule,
                        languageId);

        // A form may legitimately have no translation for an enabled language.
        // This is not a missing endpoint or module, so expose an empty payload
        // instead of a 404 response.
        return result != null ? result : new MetadataLocalizationResponses.FormLocalizationResponse(java.util.Map.of());
    }

    private static String value(java.util.Map<String, Object> row, String name) {
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name)).map(entry -> String.valueOf(entry.getValue())).findFirst().orElse("");
    }

    // private String normalizeLanguage(String language) {
    //     if (language == null || language.isBlank()) {
    //         return "VN";
    //     }

    //     String value = language
    //             .trim()
    //             .toUpperCase();

    //     if (value.startsWith("VI")
    //             || value.startsWith("VN")) {
    //         return "VN";
    //     }

    //     if (value.startsWith("EN")) {
    //         return "EN";
    //     }

    //     return value;
    // }

    @AllowAnonymous    
    @GetMapping("/forminfo/{moduleId}/{subModule}")
    public ModuleFormInfoResponse forminfoNA(
            @PathVariable String moduleId,
            @PathVariable String subModule
    ) {
        return findFormInfo(moduleId, subModule);
    }

    @AllowAnonymous 
    @GetMapping("/localizationna/{moduleId}/{subModule}")
    public MetadataLocalizationResponses.FormLocalizationResponse localizationNoauth(
            @PathVariable String moduleId,
            @PathVariable String subModule,
            @RequestHeader("Accept-Language") String languageId
        ) {
        return findLocalization(
                moduleId,
                subModule,
                languageId.trim().toUpperCase()
        );
    }
}
