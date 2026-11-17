package com.webcore.api.contract;

import java.util.List;

/** One stable response type per S001/S002 package endpoint. */
public final class ModulePackageResponses {
    private ModulePackageResponses() { }
    public record ModuleItem(String moduleId, String moduleName, String status, String moduleType,
            String capability, List<String> sources, boolean hasMenu, int menuCount, int directRoutineCount,
            boolean requiresCodeDeployment, boolean requiresDdl, String risk) { }
    public record DependencySummary(int moduleCount, int sourceCount, int directRoutineCount, int fileCount,
            int blockerCount, int warningCount, List<String> sources) { }
    public record DependencyModule(String moduleId, List<String> dependencyTree, List<String> warnings, List<String> blockers) { }
    public record ApplySource(String sourceName, String status) { }
    public record HistoryItem(String packageId, String status, Object createdAtUtc) { }
    public record ExportModulesResponse(Object generatedAtUtc, int total, List<ModuleItem> modules) { }
    public record ExportDependenciesResponse(Object generatedAtUtc, List<String> selectedModuleIds,
            String exportMode, DependencySummary summary, List<DependencyModule> modules, List<String> packageBlockers, List<String> packageWarnings) { }
    public record ExportPackageResponse(String packageId, String packageType, String exportMode,
            List<String> modules, String packageChecksum, String signatureStatus, int fileCount,
            String downloadUrl, List<String> warnings) { }
    public record ImportStageResponse(String packageId, String packageChecksum, int fileCount, List<String> modules) { }
    public record ImportValidationResponse(String packageId, boolean isValid, String packageChecksum,
            String signatureStatus, List<String> modules, List<ApplySource> applySources, boolean requiresManualSourceApply,
            boolean canApplyAutomatically, List<String> blockers, List<String> warnings) { }
    public record ImportHistoryResponse(Object generatedAtUtc, List<HistoryItem> items) { }
}
