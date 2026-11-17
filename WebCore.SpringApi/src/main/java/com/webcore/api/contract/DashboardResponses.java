package com.webcore.api.contract;

import java.util.List;
import java.util.Map;

import com.webcore.api.core.CoreMetadataService;

/** Fixed dashboard envelopes; layout and query rows remain metadata-driven. */
public final class DashboardResponses {
    private DashboardResponses() { }
    public record DashboardWidget(String id, String type) { }
    public record DashboardLayout(int schemaVersion, List<DashboardWidget> widgets) {
        public DashboardLayout { widgets = List.copyOf(widgets); }
    }
    public sealed interface DataRow permits CategoryProductRow, SupportIssueRow { }
    public record CategoryProductRow(String name, String quantity, java.math.BigDecimal price, Boolean discontinued, Integer unitsInStock) implements DataRow { }
    public record SupportIssueRow(String employee, String productName, java.math.BigDecimal resolvedTime, String issueType) implements DataRow { }
    public record LayoutRequest(DashboardLayout dashboardLayout) { }
    public record UiResponse(String moduleId, String moduleName, Map<String, Object> language,
            Map<String, Object> metadata, CoreMetadataService.UiPermission permission,
            DashboardLayout dashboardLayout) { }
    public record DataResponse(String moduleId, List<? extends DataRow> rows) { public DataResponse { rows = List.copyOf(rows); } }
    public record LayoutResponse(String moduleId, DashboardLayout dashboardLayout) { }
}
