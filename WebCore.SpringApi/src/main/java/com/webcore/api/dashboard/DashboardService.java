package com.webcore.api.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.DashboardResponses;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.database.CoreDataSourceResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Spring-native dashboard runtime. Layout is JSON metadata; chart data remains server-authorized. */
@Service
public class DashboardService {
    private static final String DEMO_SOURCE = "DB_Demo";
    private static final Map<String, Set<String>> WIDGETS = Map.of(
            "D001", Set.of("pie", "treemap", "bar"),
            "D002", Set.of("filter", "stackedBar"),
            "D003", Set.of("empty"));

    private final CoreMetadataService core;
    private final CoreDataSourceResolver sources;
    private final ObjectMapper json;

    public DashboardService(CoreMetadataService core, CoreDataSourceResolver sources, ObjectMapper json) {
        this.core = core;
        this.sources = sources;
        this.json = json;
    }

    public DashboardResponses.UiResponse ui(String moduleId, AuthService.TokenPayload user) {
        CoreMetadataService.Module module = requireDashboard(moduleId, user, "View");
        return new DashboardResponses.UiResponse(module.modId(), module.modName(), module.language(),
                module.metadata(), core.uiPermission(user, module.modId()), layout(module));
    }

    public DashboardResponses.DataResponse data(String moduleId, AuthService.TokenPayload user) {
        CoreMetadataService.Module module = requireDashboard(moduleId, user, "View");
        List<? extends DashboardResponses.DataRow> rows = switch (module.modId().toUpperCase(Locale.ROOT)) {
            case "D001" -> new JdbcTemplate(sources.resolve(DEMO_SOURCE)).query("""
                    SELECT product_name AS "name", quantity_per_unit AS "quantity", unit_price AS "price",
                           discontinued AS "discontinued", units_in_stock AS "unitsInStock"
                    FROM demo.dashboard_categories_product
                    ORDER BY product_id
                    """, (rs, ignored) -> new DashboardResponses.CategoryProductRow(rs.getString("name"), rs.getString("quantity"), rs.getBigDecimal("price"), nullableBoolean(rs, "discontinued"), nullableInteger(rs, "unitsInStock")));
            case "D002" -> new JdbcTemplate(sources.resolve(DEMO_SOURCE)).query("""
                    SELECT employee_name AS "employee", product_name AS "productName",
                           resolved_time_sum AS "resolvedTime", issue_type AS "issueType"
                    FROM demo.dashboard_support_issue
                    ORDER BY dashboard_support_issue_id
                    """, (rs, ignored) -> new DashboardResponses.SupportIssueRow(rs.getString("employee"), rs.getString("productName"), rs.getBigDecimal("resolvedTime"), rs.getString("issueType")));
            case "D003" -> List.of();
            default -> throw new IllegalArgumentException("Dashboard module is not supported: " + module.modId());
        };
        return new DashboardResponses.DataResponse(module.modId(), rows);
    }

    public DashboardResponses.LayoutResponse saveLayout(String moduleId, DashboardResponses.DashboardLayout requestedLayout, AuthService.TokenPayload user) {
        CoreMetadataService.Module module = requireDashboard(moduleId, user, "Edit");
        DashboardResponses.DashboardLayout layout = validateLayout(module.modId(), requestedLayout);
        Map<String, Object> configUi = new LinkedHashMap<>(module.configUi());
        configUi.remove("layoutXml");
        configUi.put("dashboardLayout", layout);

        try {
            String serialized = json.writeValueAsString(configUi);
            String provider = sources.provider();
            String sql = "POSTGRES".equalsIgnoreCase(provider)
                    ? "UPDATE def_mod SET config_ui = CAST(? AS jsonb), modified_by = ?, modified_date = CURRENT_TIMESTAMP WHERE UPPER(mod_id) = ?"
                    : "UPDATE def_mod SET config_ui = ?, modified_by = ?, modified_date = CURRENT_TIMESTAMP WHERE UPPER(mod_id) = ?";
            String modifiedBy = user.email() == null || user.email().isBlank() ? user.id() : user.email();
            int changed = new JdbcTemplate(sources.resolve()).update(sql, serialized, modifiedBy, module.modId());
            if (changed != 1) throw new IllegalStateException("Dashboard layout was not saved: " + module.modId());
            core.reload();
            return new DashboardResponses.LayoutResponse(module.modId(), layout);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to save dashboard layout.", exception);
        }
    }

    private CoreMetadataService.Module requireDashboard(String moduleId, AuthService.TokenPayload user, String action) {
        CoreMetadataService.Module module = core.module(moduleId);
        if (module == null || !module.modId().toUpperCase(Locale.ROOT).startsWith("D")) {
            throw new IllegalArgumentException("Dashboard module not found: " + moduleId);
        }
        core.authorize(user, module.modId(), action, null);
        return module;
    }

    @SuppressWarnings("unchecked")
    private DashboardResponses.DashboardLayout layout(CoreMetadataService.Module module) {
        Object value = module.configUi().get("dashboardLayout");
        if (value instanceof Map<?, ?> map) return parseLayout(module.modId(), (Map<String, Object>) map);
        return defaultLayout(module.modId());
    }

    private DashboardResponses.DashboardLayout validateLayout(String moduleId, DashboardResponses.DashboardLayout requested) {
        if (requested == null || requested.widgets().size() > 12) {
            throw new IllegalArgumentException("dashboardLayout.widgets must contain at most 12 widgets.");
        }
        Set<String> allowed = WIDGETS.getOrDefault(moduleId.toUpperCase(Locale.ROOT), Set.of());
        List<DashboardResponses.DashboardWidget> safeWidgets = new ArrayList<>();
        for (DashboardResponses.DashboardWidget widget : requested.widgets()) {
            String type = widget.type() == null ? "" : widget.type().trim();
            if (!allowed.contains(type)) throw new IllegalArgumentException("Unsupported dashboard widget: " + type);
            safeWidgets.add(new DashboardResponses.DashboardWidget(type + "-" + (safeWidgets.size() + 1), type));
        }
        return new DashboardResponses.DashboardLayout(1, safeWidgets);
    }

    @SuppressWarnings("unchecked")
    private DashboardResponses.DashboardLayout parseLayout(String moduleId, Map<String, Object> value) {
        Object widgets = value.get("widgets");
        if (!(widgets instanceof List<?> rows)) throw new IllegalArgumentException("dashboardLayout.widgets must be an array.");
        List<DashboardResponses.DashboardWidget> parsed = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> widget)) throw new IllegalArgumentException("Each dashboard widget must be an object.");
            parsed.add(new DashboardResponses.DashboardWidget(String.valueOf(widget.get("id")), String.valueOf(widget.get("type"))));
        }
        return validateLayout(moduleId, new DashboardResponses.DashboardLayout(1, parsed));
    }

    private static DashboardResponses.DashboardLayout defaultLayout(String moduleId) {
        List<DashboardResponses.DashboardWidget> widgets = switch (moduleId.toUpperCase(Locale.ROOT)) {
            case "D001" -> List.of(new DashboardResponses.DashboardWidget("pie-1", "pie"), new DashboardResponses.DashboardWidget("treemap-2", "treemap"), new DashboardResponses.DashboardWidget("bar-3", "bar"));
            case "D002" -> List.of(new DashboardResponses.DashboardWidget("filter-1", "filter"), new DashboardResponses.DashboardWidget("stackedBar-2", "stackedBar"));
            default -> List.of(new DashboardResponses.DashboardWidget("empty-1", "empty"));
        };
        return new DashboardResponses.DashboardLayout(1, widgets);
    }

    private static Boolean nullableBoolean(java.sql.ResultSet rs, String column) throws java.sql.SQLException { boolean value = rs.getBoolean(column); return rs.wasNull() ? null : value; }
    private static Integer nullableInteger(java.sql.ResultSet rs, String column) throws java.sql.SQLException { int value = rs.getInt(column); return rs.wasNull() ? null : value; }
}
