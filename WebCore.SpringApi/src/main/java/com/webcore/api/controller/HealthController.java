package com.webcore.api.controller;

import com.webcore.api.contract.CommonResponses;
import com.webcore.api.runtime.StartupGate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
public class HealthController {
    private final StartupGate startupGate;
    public HealthController(StartupGate startupGate) { this.startupGate = startupGate; }
    @GetMapping("/live")
    public CommonResponses.LiveResponse live() {
        return new CommonResponses.LiveResponse("UP");
    }

    @GetMapping("/ready")
    public ResponseEntity<CommonResponses.ReadyResponse> ready() {
        StartupGate.State state = startupGate.state();
        return ResponseEntity.status(state.ready() ? 200 : 503).body(new CommonResponses.ReadyResponse(state.ready() ? "UP" : "DOWN", state.checkedAtUtc(), state.sources()));
    }
}
