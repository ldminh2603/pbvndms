package com.webcore.api.staticmodule.sl24.api;

import java.util.List;
import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.staticmodule.sl24.application.Sl24QueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/static/sl24")
public class Sl24Controller {
    private final Sl24QueryService service; private final AuthService auth; private final CoreMetadataService core;
    public Sl24Controller(Sl24QueryService service, AuthService auth, CoreMetadataService core) { this.service = service; this.auth = auth; this.core = core; }
    @GetMapping("/data") public Sl24QueryService.ListResponse list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="25") int pageSize, @RequestParam(required=false) String sortField, @RequestParam(required=false) String sortOrder) { authorize(jwt); return service.list(page,pageSize,sortField,sortOrder); }
    @GetMapping("/data/{id}") public ResponseEntity<Sl24QueryService.DetailResponse> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable int id) { authorize(jwt); try { return ResponseEntity.ok(service.detail(id)); } catch (Sl24QueryService.Sl24NotFoundException exception) { return ResponseEntity.notFound().build(); } }
    @GetMapping("/lookup/{type}") public Object lookup(@AuthenticationPrincipal Jwt jwt,@PathVariable String type,@RequestParam(required=false) String customerId,@RequestParam(required=false) String limitType,@RequestParam(required=false) String currency) { authorize(jwt); return service.lookup(type, customerId, limitType, currency); }
    private void authorize(Jwt jwt) { core.authorize(auth.tokenPayload(jwt), "SL24", "View", null); }
}
