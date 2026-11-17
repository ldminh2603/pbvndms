package com.webcore.api.staticmodule.sl24.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.math.BigDecimal;
import java.sql.Timestamp;

import javax.sql.DataSource;

import com.webcore.api.runtime.SourceDataSourceRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-side SL24 contract backed by the db_limit business source. */
@Service
public class Sl24QueryService {
    private static final Set<String> SORT_FIELDS = Set.of("id", "customer_id", "customer_name", "customer_type", "level1_amount", "internal_amount", "limit_code", "limit_status", "fixed_variable", "reducing_limit", "core_source", "review_date", "status", "created_by", "created_at");
    private final SourceDataSourceRegistry sources;

    public Sl24QueryService(SourceDataSourceRegistry sources) { this.sources = sources; }

    public ListResponse list(int requestedPage, int requestedPageSize, String sortField, String sortOrder) {
        int page = Math.max(1, requestedPage);
        int pageSize = Math.min(100, Math.max(1, requestedPageSize));
        String sort = sortField != null && SORT_FIELDS.contains(sortField) ? sortField : "id";
        String direction = "asc".equalsIgnoreCase(sortOrder) ? "ASC" : "DESC";
        JdbcTemplate jdbc = jdbc();
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM customer_limit WHERE status <> 'DELETED'", Integer.class);
        List<CustomerLimitListRow> rows = jdbc.query("""
                SELECT cl.id, cl.customer_id, cl.customer_name, cl.customer_type, cl.limit_code, cl.limit_status,
                       cl.internal_amount, cl.level1_amount, COALESCE(exp.used_amount, 0) AS used_amount,
                       cl.internal_amount - COALESCE(exp.used_amount, 0) AS available_amount,
                       cl.fixed_variable, cl.reducing_limit, cl.core_source, cl.review_date,
                       cl.status, cl.created_by, cl.created_at
                FROM customer_limit cl
                LEFT JOIN (SELECT customer_id, SUM(vnd_amount) AS used_amount FROM exposure GROUP BY customer_id) exp
                  ON exp.customer_id = cl.customer_id
                WHERE cl.status <> 'DELETED'
                ORDER BY cl.%s %s
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """.formatted(sort, direction), (rs, ignored) -> new CustomerLimitListRow(
                rs.getLong("id"), rs.getString("customer_id"), rs.getString("customer_name"), rs.getString("customer_type"),
                rs.getString("limit_code"), rs.getString("limit_status"), rs.getBigDecimal("internal_amount"), rs.getBigDecimal("level1_amount"),
                rs.getBigDecimal("used_amount"), rs.getBigDecimal("available_amount"), rs.getString("fixed_variable"), rs.getString("reducing_limit"),
                rs.getString("core_source"), rs.getTimestamp("review_date"), rs.getString("status"), rs.getString("created_by"), rs.getTimestamp("created_at")),
                (page - 1) * pageSize, pageSize);
        return new ListResponse(rows, total == null ? 0 : total);
    }

    public DetailResponse detail(int id) {
        JdbcTemplate jdbc = jdbc();
        List<CustomerLimitDetailRow> parents = jdbc.query("""
                SELECT id, customer_id, customer_name, customer_type, restriction_type, limit_type, limit_code,
                       currency, core_source, fixed_variable, reducing_limit, review_frequency,
                       review_frequency_unit, review_date, manager_user_id, proposal_date, approval_date,
                       effective_date, last_draw_date, expiry_date, serial_number, internal_amount,
                       maximum_total, level1_amount, level1_secured_amount, level1_unsecured_amount,
                       advised_amount, credit_program, limit_term, kondor_ref, way4_ref, t24_limit_ref,
                       limit_status, status, approved_by, approved_at, reject_reason, created_by,
                       created_at, updated_by, updated_at
                FROM customer_limit
                WHERE id = ?
                """, (rs, ignored) -> new CustomerLimitDetailRow(rs.getLong("id"), rs.getString("customer_id"), rs.getString("customer_name"),
                rs.getString("customer_type"), rs.getString("restriction_type"), rs.getString("limit_type"), rs.getString("limit_code"),
                rs.getString("currency"), rs.getString("core_source"), rs.getString("fixed_variable"), rs.getString("reducing_limit"),
                rs.getString("review_frequency"), rs.getString("review_frequency_unit"), rs.getTimestamp("review_date"), rs.getString("manager_user_id"),
                rs.getTimestamp("proposal_date"), rs.getTimestamp("approval_date"), rs.getTimestamp("effective_date"), rs.getTimestamp("last_draw_date"),
                rs.getTimestamp("expiry_date"), rs.getString("serial_number"), rs.getBigDecimal("internal_amount"), rs.getBigDecimal("maximum_total"),
                rs.getBigDecimal("level1_amount"), rs.getBigDecimal("level1_secured_amount"), rs.getBigDecimal("level1_unsecured_amount"),
                rs.getBigDecimal("advised_amount"), rs.getString("credit_program"), rs.getString("limit_term"), rs.getString("kondor_ref"),
                rs.getString("way4_ref"), rs.getString("t24_limit_ref"), rs.getString("limit_status"), rs.getString("status"),
                rs.getString("approved_by"), rs.getTimestamp("approved_at"), rs.getString("reject_reason"), rs.getString("created_by"),
                rs.getTimestamp("created_at"), rs.getString("updated_by"), rs.getTimestamp("updated_at")), id);
        if (parents.isEmpty()) throw new Sl24NotFoundException();
        CustomerLimitDetailRow parent = parents.getFirst();
        String customerId = parent.customerId() == null ? "" : parent.customerId();
        List<LimitLevelRow> levels = jdbc.query("SELECT id, customer_limit_id, level, parent_level_id, category_code, category_name, sharing_type, limit_amount FROM customer_limit_level WHERE customer_limit_id = ? ORDER BY level, id", (rs, ignored) -> new LimitLevelRow(rs.getLong("id"), rs.getLong("customer_limit_id"), rs.getInt("level"), nullableLong(rs, "parent_level_id"), rs.getString("category_code"), rs.getString("category_name"), rs.getString("sharing_type"), rs.getBigDecimal("limit_amount")), id);
        List<RelatedGroupRow> khlq = jdbc.query("""
                SELECT cgm.group_id, gl.group_name, gl.limit_type, gl.currency, gl.ratio_pct, gl.sharing_type, gl.review_date
                FROM customer_group_membership cgm LEFT JOIN group_limit gl ON gl.group_id = cgm.group_id
                WHERE cgm.customer_id = ? ORDER BY cgm.group_id
                """, (rs, ignored) -> new RelatedGroupRow(rs.getString("group_id"), rs.getString("group_name"), rs.getString("limit_type"), rs.getString("currency"), rs.getBigDecimal("ratio_pct"), rs.getString("sharing_type"), rs.getTimestamp("review_date")), customerId);
        List<CollateralRightRow> collateral = jdbc.query("SELECT collateral_id, collateral_type, nominal_value, allocation_pct, execution_value FROM collateral_right WHERE customer_id = ? ORDER BY collateral_id", (rs, ignored) -> new CollateralRightRow(rs.getString("collateral_id"), rs.getString("collateral_type"), rs.getBigDecimal("nominal_value"), rs.getBigDecimal("allocation_pct"), rs.getBigDecimal("execution_value")), customerId);
        return new DetailResponse(parent, levels, khlq, collateral);
    }

    public Object lookup(String type, String customerId, String limitType, String currency) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "limit-type" -> items(new String[][]{{"CREDIT_LIMIT", "Hạn mức tín dụng"}, {"OTHER_LIMIT", "Hạn mức khác"}});
            case "currency" -> items(new String[][]{{"VND", "VND"}, {"USD", "USD"}, {"EUR", "EUR"}});
            case "core-source" -> items(new String[][]{{"T24", "T24"}, {"NEW_CORE", "New Core"}, {"ALL", "Tất cả"}});
            case "fixed-variable" -> items(new String[][]{{"FIXED", "Cố định"}, {"VARIABLE", "Biến đổi"}});
            case "reducing-limit" -> items(new String[][]{{"Y", "Không tuần hoàn"}, {"N", "Tuần hoàn"}});
            case "review-frequency-unit" -> items(new String[][]{{"DAY", "Ngày"}, {"WEEK", "Tuần"}, {"MONTH", "Tháng"}, {"QUARTER", "Quý"}, {"YEAR", "Năm"}});
            case "default-limit" -> defaultLimit(customerId, limitType, currency);
            case "exposure" -> exposure(customerId);
            default -> List.of();
        };
    }

    private Map<String, Object> defaultLimit(String customerId, String limitType, String currency) {
        // This information box is advisory. Return a stable zero contract when
        // no approved default template exists instead of an empty array.
        return Map.of("ratioPct", 0, "vtcAmount", 0, "maxAllowedAmount", 0);
    }

    private Map<String, Object> exposure(String customerId) {
        if (customerId == null || customerId.isBlank()) return Map.of("totalUsed", 0, "securedUsed", 0, "unsecuredUsed", 0);
        BigDecimal total = jdbc().queryForObject("SELECT COALESCE(SUM(vnd_amount), 0) FROM exposure WHERE customer_id = ?", BigDecimal.class, customerId);
        return Map.of("totalUsed", total == null ? BigDecimal.ZERO : total, "securedUsed", 0, "unsecuredUsed", 0);
    }

    private JdbcTemplate jdbc() { DataSource source = sources.resolve("db_limit"); return new JdbcTemplate(source); }
    private List<Map<String, String>> items(String[][] values) { List<Map<String, String>> result = new ArrayList<>(); for (String[] value : values) result.add(Map.of("value", value[0], "text", value[1])); return result; }
    private static Long nullableLong(java.sql.ResultSet rs, String name) throws java.sql.SQLException { long value = rs.getLong(name); return rs.wasNull() ? null : value; }
    public static final class Sl24NotFoundException extends RuntimeException { }
    public record CustomerLimitListRow(long id, String customerId, String customerName, String customerType, String limitCode, String limitStatus, BigDecimal internalAmount, BigDecimal level1Amount, BigDecimal usedAmount, BigDecimal availableAmount, String fixedVariable, String reducingLimit, String coreSource, Timestamp reviewDate, String status, String createdBy, Timestamp createdAt) { }
    public record CustomerLimitDetailRow(long id, String customerId, String customerName, String customerType, String restrictionType, String limitType, String limitCode, String currency, String coreSource, String fixedVariable, String reducingLimit, String reviewFrequency, String reviewFrequencyUnit, Timestamp reviewDate, String managerUserId, Timestamp proposalDate, Timestamp approvalDate, Timestamp effectiveDate, Timestamp lastDrawDate, Timestamp expiryDate, String serialNumber, BigDecimal internalAmount, BigDecimal maximumTotal, BigDecimal level1Amount, BigDecimal level1SecuredAmount, BigDecimal level1UnsecuredAmount, BigDecimal advisedAmount, String creditProgram, String limitTerm, String kondorRef, String way4Ref, String t24LimitRef, String limitStatus, String status, String approvedBy, Timestamp approvedAt, String rejectReason, String createdBy, Timestamp createdAt, String updatedBy, Timestamp updatedAt) { }
    public record LimitLevelRow(long id, long customerLimitId, int level, Long parentLevelId, String categoryCode, String categoryName, String sharingType, BigDecimal limitAmount) { }
    public record RelatedGroupRow(String groupId, String groupName, String limitType, String currency, BigDecimal ratioPct, String sharingType, Timestamp reviewDate) { }
    public record CollateralRightRow(String collateralId, String collateralType, BigDecimal nominalValue, BigDecimal allocationPct, BigDecimal executionValue) { }
    public record ListResponse(List<CustomerLimitListRow> data, int total) { }
    public record DetailResponse(CustomerLimitDetailRow parent, List<LimitLevelRow> levels, List<RelatedGroupRow> khlqGroups, List<CollateralRightRow> collateralRights) { }
}
