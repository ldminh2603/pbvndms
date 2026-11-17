package com.webcore.api.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.userconfig.UserConfigRepository;
import org.springframework.stereotype.Service;

/**
 * Executes a grid button whose CALLMODID resolves to a MODEXECPROC module.
 * All module, button, procedure and language metadata comes from the active
 * immutable Core snapshot; this service never queries metadata tables itself.
 */
@Service
public class LegacyButtonActionService {
    private final CoreMetadataService core;
    private final ExecProcCommitService execProcCommits;
    private final MaintainCommitService maintainCommits;
    private final UserConfigRepository userConfig;

    public LegacyButtonActionService(CoreMetadataService core, ExecProcCommitService execProcCommits,
                                     MaintainCommitService maintainCommits, UserConfigRepository userConfig) {
        this.core = core;
        this.execProcCommits = execProcCommits;
        this.maintainCommits = maintainCommits;
        this.userConfig = userConfig;
    }

    /** Metadata for the confirmation dialog shown before a ModType 01 action. */
    public Map<String, Object> confirmation(String moduleId, String subModule, String buttonName,
                                            AuthService.TokenPayload user) {
        Resolution action = resolve(moduleId, subModule, buttonName, user);
        String languageId = userConfig.loadOrDefaultLanguage(user.email(), Map.of(), user.email()).language();
        String moduleName = text(action.targetForm(), "ModuleName");
        String title = languageWithGlobalFallback(moduleName + ".Title[Default]", "GLOBAL.Title[Default]", languageId);
        String warning = languageWithGlobalFallback(
                moduleName + ".Title[" + action.buttonName() + ".Warning]",
                "GLOBAL.Title[" + action.buttonName() + ".Warning]",
                languageId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", title.isBlank() ? moduleName : title);
        result.put("message", warning.isBlank() ? "" : warning);
        result.put("moduleId", action.targetModuleId());
        result.put("subModule", action.targetSubModule());
        result.put("buttonName", action.buttonName());
        return Map.copyOf(result);
    }

    /** Executes MODEXECPROC.CommitHandler after the UI has received confirmation. */
    public Map<String, Object> execute(String moduleId, String subModule, String buttonName,
                                       Map<String, Object> request, AuthService.TokenPayload user) {
        Resolution action = resolve(moduleId, subModule, buttonName, user);
        if (!"01".equals(text(action.targetForm(), "ModuleType"))) {
            throw new IllegalArgumentException("Button target must be ModType 01 (ExecProcModuleInfo).");
        }
        if (action.commitHandler().isBlank()) {
            throw new IllegalStateException("MODEXECPROC.CommitHandler is not configured for "
                    + action.targetModuleId() + "/" + action.targetSubModule() + ".");
        }
        return execProcCommits.execute(action.targetModuleId(), action.targetSubModule(), action.targetForm(),
                action.commitHandler(), action.showSuccess(), request, user);
    }

    /** Commits a Type-02 popup after source-module authorization is enforced. */
    public Map<String, Object> maintain(String moduleId, String subModule, String buttonName,
                                        Map<String, Object> request, AuthService.TokenPayload user) {
        Resolution action = resolve(moduleId, subModule, buttonName, user);
        if (!"02".equals(text(action.targetForm(), "ModuleType"))) {
            throw new IllegalArgumentException("Button target must be ModType 02 (MaintainModuleInfo).");
        }
        return maintainCommits.commitAuthorized(action.targetModuleId(), action.targetSubModule(),
                action.targetForm(), request, user);
    }

    private Resolution resolve(String moduleId, String subModule, String buttonName,
                               AuthService.TokenPayload user) {
        String sourceSubModule = upper(subModule);
        Map<String, Object> source = core.runtimeFormInfo(moduleId, sourceSubModule);
        if (source.isEmpty()) throw new IllegalArgumentException("Source module is not configured: " + moduleId + "/" + sourceSubModule);
        Map<String, Object> button = findButton(list(source.get("Buttons")), buttonName);
        if (button == null) throw new IllegalArgumentException("Button is not configured: " + buttonName);
        String targetModuleId = text(button, "CallModuleID");
        String targetSubModule = upper(text(button, "CallSubModule"));
        if (targetModuleId.isBlank() || targetSubModule.isBlank()) {
            throw new IllegalArgumentException("Button has no CallModuleID/CallSubModule: " + text(button, "ButtonName"));
        }

        // Equivalent to SELECT DEFMOD WHERE MODID=CALLMODID AND SUBMOD=CALLSUBMOD,
        // but served from the Core form-info snapshot.
        Map<String, Object> target = core.runtimeFormInfo(targetModuleId, targetSubModule);
        if (target.isEmpty()) throw new IllegalArgumentException("Target module is not configured: " + targetModuleId + "/" + targetSubModule);
        core.authorize(user, moduleId, actionFor(buttonName, targetSubModule), operationFor(buttonName));

        Map<String, Object> procedure = first(list(target.get("ExecProcedureInfos")));
        String handler = text(procedure, "CommitHandler");
        return new Resolution(targetModuleId, targetSubModule, text(button, "ButtonName"), target,
                handler, text(procedure, "ShowSuccess"));
    }

    private String language(String languageName, String languageId) {
        for (com.webcore.api.core.metadata.RuntimeMetadataRows.DefLanguage row : core.languageDefinitions()) {
            if (same(row.langname(), languageName)
                    && same(row.langid(), languageId)
                    && "A".equalsIgnoreCase(row.status())) return row.langvalue() == null ? "" : row.langvalue().trim();
        }
        return "";
    }

    /** Module-specific DEFLANG wins; GLOBAL is the standard fallback for shared button messages. */
    private String languageWithGlobalFallback(String moduleLanguageName, String globalLanguageName, String languageId) {
        String resolved = language(moduleLanguageName, languageId);
        return resolved.isBlank() ? language(globalLanguageName, languageId) : resolved;
    }

    private static Map<String, Object> findButton(List<Map<String, Object>> buttons, String buttonName) {
        for (Map<String, Object> button : buttons) if (same(text(button, "ButtonName"), buttonName)) return button;
        return null;
    }
    private static Map<String, Object> first(List<Map<String, Object>> rows) { return rows.isEmpty() ? Map.of() : rows.getFirst(); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> rows ? rows.stream().filter(Map.class::isInstance)
                .<Map<String, Object>>map(row -> new LinkedHashMap<>((Map<String, Object>) row)).toList() : List.of();
    }
    private static String text(Map<String, Object> row, String name) {
        String expected = canonical(name);
        for (Map.Entry<String, Object> entry : row.entrySet()) if (canonical(entry.getKey()).equals(expected)) {
            return entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim();
        }
        return "";
    }
    private static String canonical(String value) { return value == null ? "" : value.replaceAll("[_\\s]", "").trim().toUpperCase(Locale.ROOT); }
    private static String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String actionFor(String buttonName, String targetSubModule) {
        String button = upper(buttonName);
        String target = upper(targetSubModule);
        if ("BTN_ADD".equals(button) || "MAD".equals(target)) return "Add";
        if ("BTN_EDIT".equals(button) || "MED".equals(target)) return "Edit";
        if ("BTN_DELETE".equals(button)) return "Delete";
        return "View";
    }
    /** Standard CRUD buttons use Actions; every other BTN_* key is a role Operation. */
    private static String operationFor(String buttonName) {
        String button = upper(buttonName);
        return switch (button) {
            case "BTN_VIEW", "BTN_ADD", "BTN_EDIT", "BTN_DELETE" -> null;
            default -> button.startsWith("BTN_") ? button : null;
        };
    }
    private static boolean same(String left, String right) { return left != null && right != null && left.trim().equalsIgnoreCase(right.trim()); }

    private record Resolution(String targetModuleId, String targetSubModule, String buttonName,
                              Map<String, Object> targetForm, String commitHandler, String showSuccess) { }
}
