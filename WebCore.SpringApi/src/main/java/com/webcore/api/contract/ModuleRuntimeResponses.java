package com.webcore.api.contract;

import java.util.List;
import java.util.Map;

import com.webcore.api.core.CoreMetadataService;
/** Fixed envelopes around config-driven, therefore dynamic, module data. */
public final class ModuleRuntimeResponses {
    private ModuleRuntimeResponses() { }
    public record UiResponse(String moduleId, String moduleName, Map<String, Object> configUi,
            Map<String, Object> language, Map<String, Object> metadata, CoreMetadataService.UiPermission permission) { }
    public record LookupResponse(List<Map<String, Object>> listSource, List<Map<String, Object>> listSourceEdit) { }
    public record DataResponse(List<Map<String, Object>> data) { }
    public record OperationResponse(String operationAction, List<Map<String, Object>> result) { }
    public record WriteResponse(String action, List<Map<String, Object>> result) { }
}
