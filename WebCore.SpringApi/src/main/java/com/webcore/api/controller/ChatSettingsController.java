package com.webcore.api.controller;

import com.webcore.api.contract.CommonResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compatibility endpoint used when the React shell mounts a component layout.
 * The actual AI proxy is optional; these limits let the client keep its local
 * support-chat session without treating an unconfigured chat provider as a
 * navigation failure.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatSettingsController {
    @GetMapping("/settings")
    public CommonResponses.ChatSettingsResponse settings() {
        return new CommonResponses.ChatSettingsResponse(51, 12, 50, 30, 2000, 4000, 4000);
    }
}
