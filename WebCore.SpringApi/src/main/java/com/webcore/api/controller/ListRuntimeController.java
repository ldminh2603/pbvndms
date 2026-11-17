package com.webcore.api.controller;

import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.ModuleRuntimeResponses;
import com.webcore.api.runtime.ModuleRuntimeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/list","/api/find"})
public class ListRuntimeController {
    private final AuthService auth; private final ModuleRuntimeService runtime;
    public ListRuntimeController(AuthService auth,ModuleRuntimeService runtime){this.auth=auth;this.runtime=runtime;}
    @PostMapping("/{moduleId}/add") public ModuleRuntimeResponses.WriteResponse add(@PathVariable String moduleId,@RequestBody Map<String,Object> values,@AuthenticationPrincipal Jwt jwt){return runtime.write(moduleId,"Add",values,auth.tokenPayload(jwt));}
    @PutMapping("/{moduleId}/update") public ModuleRuntimeResponses.WriteResponse update(@PathVariable String moduleId,@RequestBody Map<String,Object> values,@AuthenticationPrincipal Jwt jwt){return runtime.write(moduleId,"Edit",values,auth.tokenPayload(jwt));}
    @DeleteMapping("/{moduleId}/delete") public ModuleRuntimeResponses.WriteResponse delete(@PathVariable String moduleId,@RequestBody Map<String,Object> values,@AuthenticationPrincipal Jwt jwt){return runtime.write(moduleId,"Delete",values,auth.tokenPayload(jwt));}
}
