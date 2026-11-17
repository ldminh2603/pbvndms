package com.webcore.api.dms;

import java.util.LinkedHashMap;
import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.runtime.MaintainJavaHandler;
import org.springframework.stereotype.Service;

/** Dispatches 02D45 workflow and amendment buttons through the metadata commit path. */
@Service
public class DmsDocumentSubmitHandler implements MaintainJavaHandler {
    private static final String HANDLER = "DMS_DOCUMENT_WORKFLOW";
    private final DocsvcReadService documents;

    public DmsDocumentSubmitHandler(DocsvcReadService documents) { this.documents = documents; }

    @Override public String name() { return HANDLER; }

    @Override
    public Map<String, Object> commit(String moduleId, String subModule, Map<String, Object> form,
                                      Map<String, Object> request, AuthService.TokenPayload user) {
        Map<String, Object> values = values(request);
        String caseId = optional(values, "CASE_ID");
        if (caseId.isBlank()) throw new IllegalArgumentException("CASE_ID is required.");
        String buttonName = required(request, "buttonName").toUpperCase(java.util.Locale.ROOT);
        String remarks = optional(values, "REMARKS");
        var roles = user.roles().stream().map(role -> role.name()).toList();
        Map<String, String> command = command(form, buttonName);
        DocsvcReadService.Workflow workflow = dispatchCase(command, caseId, remarks, user, roles);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commitHandler", "JAVA:" + HANDLER);
        if (!caseId.isBlank()) result.put("caseId", caseId);
        result.put("buttonName", buttonName);
        result.put("state", workflow.state());
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(Map<String, Object> request) {
        Object value = request.get("values");
        return value instanceof Map<?, ?> source ? new LinkedHashMap<>((Map<String, Object>) source) : Map.of();
    }

    private static String required(Map<String, Object> values, String name) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey().trim().equalsIgnoreCase(name) && entry.getValue() != null && !String.valueOf(entry.getValue()).trim().isBlank()) {
                return String.valueOf(entry.getValue()).trim();
            }
        }
        throw new IllegalArgumentException(name + " is required.");
    }

    private static String optional(Map<String, Object> values, String name) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey().trim().equalsIgnoreCase(name)) return entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim();
        }
        return "";
    }

    private DocsvcReadService.Workflow dispatchCase(Map<String, String> command, String caseId, String remarks,
                                                    AuthService.TokenPayload user, java.util.List<String> roles) {
        String kind = command.getOrDefault("command", "");
        String action = command.getOrDefault("action", "");
        return switch (kind) {
            case "workflow" -> documents.actWorkflowByCase(caseId, action, remarks, user.email(), roles, user.isPrivilegedAccount());
            case "amendment" -> switch (action) {
                case "open" -> documents.openAmendmentByCase(caseId, remarks, user.email(), roles, user.isPrivilegedAccount());
                case "cancel" -> documents.cancelAmendmentByCase(caseId, remarks, user.email(), roles, user.isPrivilegedAccount());
                default -> throw new IllegalArgumentException("Unsupported amendment action: " + action);
            };
            default -> throw new IllegalArgumentException("Unsupported workflow command: " + kind);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> command(Map<String, Object> form, String buttonName) {
        Object rawButtons = form.get("Buttons");
        if (!(rawButtons instanceof java.util.List<?> buttons)) throw new IllegalArgumentException("02D45 buttons are not configured.");
        for (Object rawButton : buttons) {
            if (!(rawButton instanceof Map<?, ?> source)) continue;
            Map<String, Object> button = (Map<String, Object>) source;
            if (!buttonName.equalsIgnoreCase(optional(button, "ButtonName"))) continue;
            if (!"WORKFLOW".equalsIgnoreCase(optional(button, "ButtonSource"))) throw new IllegalArgumentException("Button is not a workflow command: " + buttonName);
            Map<String, String> result = new LinkedHashMap<>();
            for (String part : optional(button, "BtnParams").split(";")) {
                String[] keyValue = part.split("=", 2);
                if (keyValue.length == 2) result.put(keyValue[0].trim().toLowerCase(java.util.Locale.ROOT), keyValue[1].trim().toLowerCase(java.util.Locale.ROOT));
            }
            if (result.isEmpty()) throw new IllegalArgumentException("Workflow button command is not configured: " + buttonName);
            return Map.copyOf(result);
        }
        throw new IllegalArgumentException("Workflow button is not configured: " + buttonName);
    }
}
