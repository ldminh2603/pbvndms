package com.webcore.api.controller;

import com.webcore.api.auth.RequireTokenAccess;
import com.webcore.api.contract.CommonResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/** Temporary endpoint that proves OAuth client scopes reach the resource server. */
@RestController
@RequestMapping("/api/oauth-test")
public class OAuthProofController {

    // @GetMapping("/documents")
    // public Map<String, Object> documents() {
    //     return Map.of("message", "OAuth scope document.read accepted.");
    // }


    @GetMapping("/documents")
    @RequireTokenAccess(allowAnyUserJwt = true, clientScopes = "document.read")
    public CommonResponses.MessageResponse documents() {
        return new CommonResponses.MessageResponse("Document access accepted.");
    }
}
