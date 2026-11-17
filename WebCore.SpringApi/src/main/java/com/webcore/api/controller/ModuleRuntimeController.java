package com.webcore.api.controller;

import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.ModuleRuntimeResponses;
import com.webcore.api.runtime.MaintainCommitService;
import com.webcore.api.runtime.ModuleRuntimeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/module")
public class ModuleRuntimeController {
    private final AuthService auth; private final ModuleRuntimeService runtime; private final MaintainCommitService maintainCommit;
    public ModuleRuntimeController(AuthService auth, ModuleRuntimeService runtime, MaintainCommitService maintainCommit) { this.auth=auth; this.runtime=runtime; this.maintainCommit=maintainCommit; }
    @GetMapping("/{moduleId}/ui") public ModuleRuntimeResponses.UiResponse ui(@PathVariable String moduleId,@AuthenticationPrincipal Jwt jwt){ return runtime.ui(moduleId,auth.tokenPayload(jwt)); }
    @PostMapping("/{moduleId}/data/{key}") public Map<String,Object> data(@PathVariable String moduleId,@PathVariable String key,@RequestBody(required=false) Map<String,Object> body,@AuthenticationPrincipal Jwt jwt){return runtime.data(moduleId,key,body==null?Map.of():body,auth.tokenPayload(jwt));}
    @PostMapping("/{moduleId}/lookup/{key}") public ModuleRuntimeResponses.LookupResponse lookup(@PathVariable String moduleId,@PathVariable String key,@RequestBody(required=false) Map<String,Object> body,@AuthenticationPrincipal Jwt jwt){return runtime.lookup(moduleId,key,body==null?Map.of():body,auth.tokenPayload(jwt));}
    @PostMapping("/{moduleId}/callback/{key}") public ModuleRuntimeResponses.DataResponse callback(@PathVariable String moduleId,@PathVariable String key,@RequestBody(required=false) Map<String,Object> body,@AuthenticationPrincipal Jwt jwt){return runtime.callback(moduleId,key,body==null?Map.of():body,auth.tokenPayload(jwt));}
    @PostMapping("/{moduleId}/operation") public ModuleRuntimeResponses.OperationResponse operation(@PathVariable String moduleId,@RequestBody Map<String,Object> body,@AuthenticationPrincipal Jwt jwt){String key=String.valueOf(body.getOrDefault("operationKey",body.getOrDefault("operationAction","")));return runtime.operation(moduleId,key,body,auth.tokenPayload(jwt));}
    @PostMapping("/{moduleId}/maintain/{subModule}/commit") public Map<String,Object> commit(@PathVariable String moduleId,@PathVariable String subModule,@RequestBody(required=false) Map<String,Object> body,@AuthenticationPrincipal Jwt jwt){return maintainCommit.commit(moduleId,subModule,body==null?Map.of():body,auth.tokenPayload(jwt));}
}
