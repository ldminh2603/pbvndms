package com.webcore.api.runtime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.webcore.api.auth.AuthService;
import com.webcore.api.core.CoreMetadataService;
import org.springframework.stereotype.Service;

/**
 * Metadata-only executor for the .NET MODSEARCH (type 03) family.  The query
 * query is read from cached MODSEARCH, while the business connection is read
 * from cached DEFMOD.APPID -> DEFAPPS.CONSTR. Neither comes from a route,
 * browser request, source alias, or an intermediate runtime table.
 */
@Service
public class LegacySearchService {
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private final CoreMetadataService core;
    private final SourceDataSourceRegistry sources;
    private final LegacyCcbListSourceService ccbListSources;

    public LegacySearchService(CoreMetadataService core, SourceDataSourceRegistry sources, LegacyCcbListSourceService ccbListSources) {
        this.core = core;
        this.sources = sources;
        this.ccbListSources = ccbListSources;
    }

    public Map<String, Object> search(String moduleId, String subModule, Map<String, Object> request,
                                      AuthService.TokenPayload user) {
        Map<String, Object> form = requiredForm(moduleId, subModule);
        core.authorize(user, moduleId, "View", null);
        String appId = text(form, "ApplicationId");
        com.webcore.api.core.metadata.RuntimeMetadataRows.DefApplication application = application(appId);
        String constr = application.constr();
        String template = template(form);
        validateTemplate(template);

        List<Object> baseParameters = new ArrayList<>();
        ParameterExpression parameterExpression = parameterExpression(form, request, baseParameters);
        String baseExpression = parameterExpression.missingRequired() ? "1=0" : expression(form, request, baseParameters);
        // A browser can briefly retain a DevExtreme filter while navigating to
        // a different dynamic module.  It must not make the destination form
        // unusable: only fields declared by that module are applied.
        if (baseExpression == null || baseExpression.isBlank()) baseExpression = "1=1";
        if (!parameterExpression.missingRequired() && !parameterExpression.sql().isBlank()) baseExpression = "(" + parameterExpression.sql() + ") AND (" + baseExpression + ")";
        String expression = baseExpression;
        FacetSelection selectedFacet = selectedFacet(form, request);
        List<Object> parameters = new ArrayList<>(baseParameters);
        if (selectedFacet != null) {
            String facetPredicate = predicate(selectedFacet.field(), "eq", selectedFacet.value(), parameters);
            if (facetPredicate != null) expression = "(" + expression + ") AND (" + facetPredicate + ")";
        }
        String query = template.replace("{0}", expression);
        int skip = boundedNumber(request.get("skip"), 0, 0, 1_000_000);
        int take = boundedNumber(request.get("take"), 50, 1, 500);

        DataSource dataSource = sources.resolveApplication(appId, constr);
        String provider = sources.applicationProvider(constr);
        List<Map<String, Object>> rows = query(dataSource, pageSql(query, provider), parameters, skip, take, provider);
        normalizeCodeRows(rows, form);
        long total = count(dataSource, query, parameters);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", rows);
        result.put("totalCount", total);
        result.put("form", form);
        result.put("facets", facetCounts(dataSource, template, baseExpression, baseParameters, form));
        result.put("permission", core.uiPermission(user, moduleId));
        return result;
    }

    private Map<String, Object> requiredForm(String moduleId, String subModule) {
        Map<String, Object> form = ccbListSources.enrich(core.runtimeFormInfo(moduleId, subModule));
        if (form == null || !"03".equals(text(form, "ModuleType"))) throw new IllegalArgumentException("Legacy search module was not found: " + moduleId + "." + subModule);
        return form;
    }

    private com.webcore.api.core.metadata.RuntimeMetadataRows.DefApplication application(String appId) {
        com.webcore.api.core.metadata.RuntimeMetadataRows.DefApplication application = core.application(appId);
        if (application != null) return application;
        throw new IllegalStateException("DEFAPPS is not configured for DEFMOD.APPID=" + appId);
    }

    private static String template(Map<String, Object> form) { return text(form, "QueryFormat"); }

    /** Supports the legacy condition tree: {logic: AND|OR, rules:[{field,operator,value}|group]}. */
    private static String expression(Map<String, Object> form, Map<String, Object> request, List<Object> parameters) {
        Map<String, Map<String,Object>> fields = conditionFields(form);
        Object filter = request.get("filter");
        if (filter instanceof Map<?,?> tree) {
            Object expression = cast(tree).get("expression");
            if (expression instanceof List<?> values) return devExtreme(values, fields, parameters);
            return group(cast(tree), fields, parameters);
        }
        List<String> legacy = legacyPredicates(fields, object(request.get("conditions")), parameters);
        return legacy.isEmpty() ? "1=1" : String.join(" AND ", legacy);
    }
    private static String devExtreme(List<?> node, Map<String,Map<String,Object>> fields, List<Object> parameters) {
        if (node.size() == 3 && node.get(0) instanceof String field && node.get(1) instanceof String operator) {
            return rule(Map.of("field", field, "operator", dxOperator(operator), "value", node.get(2)), fields, parameters);
        }
        List<String> parts = new ArrayList<>(); String logic = " AND ";
        for (int i = 0; i < node.size(); i++) {
            Object item = node.get(i);
            if (item instanceof String text && ("and".equalsIgnoreCase(text) || "or".equalsIgnoreCase(text))) {
                logic = "or".equalsIgnoreCase(text) ? " OR " : " AND ";
            } else if (item instanceof List<?> child) {
                String predicate = devExtreme(child, fields, parameters);
                if (predicate != null && !predicate.isBlank()) parts.add(predicate);
            }
        }
        return parts.isEmpty() ? "1=1" : "(" + String.join(logic, parts) + ")";
    }
    private static String dxOperator(String value) { return switch(value) {
        case "=" -> "eq"; case "<>" -> "ne";
        case ">" -> "gt"; case ">=" -> "ge";
        case "<" -> "lt"; case "<=" -> "le";
        default -> value;
    }; }
    private static Map<String, Map<String,Object>> conditionFields(Map<String,Object> form) {
        Map<String, Map<String,Object>> result = new LinkedHashMap<>();
        addFilterFields(result, form.get("ConditionFields"), false);
        // Filter Row / Filter Builder are rendered from SCL metadata. SCL is
        // therefore an allowed, metadata-controlled filter field as well.
        addFilterFields(result, form.get("ColumnFields"), true);
        // SCF may be selected from a tab even when it is not an SCL/SCD field.
        addFilterFields(result, form.get("SearchFacetFields"), false);
        return result;
    }

    /** HCD values are route inputs. Missing mandatory values must never fall back to an unrestricted result. */
    private static ParameterExpression parameterExpression(Map<String, Object> form, Map<String, Object> request, List<Object> parameters) {
        Map<String, Object> values = object(request.get("parameters"));
        List<String> predicates = new ArrayList<>();
        for (Map<String, Object> field : list(form.get("ParameterFields"))) {
            String fieldName = text(field, "FieldName");
            String parameterName = text(field, "ParameterName");
            if (!IDENTIFIER.matcher(fieldName).matches()) continue;
            Object parameterValue = value(values, fieldName, parameterName);
            if (parameterValue == null || String.valueOf(parameterValue).trim().isEmpty()) {
                if ("N".equalsIgnoreCase(text(field, "Nullable"))) return new ParameterExpression("", true);
                continue;
            }
            String predicate = predicate(field, "eq", parameterValue, parameters);
            if (predicate != null) predicates.add(predicate);
        }
        return new ParameterExpression(String.join(" AND ", predicates), false);
    }

    private static FacetSelection selectedFacet(Map<String, Object> form, Map<String, Object> request) {
        Map<String, Object> facet = object(request.get("facet"));
        String requestedName = text(facet, "field");
        Object requestedValue = facet.get("value");
        if (requestedName.isBlank() || requestedValue == null || String.valueOf(requestedValue).trim().isEmpty()) return null;
        for (Map<String, Object> field : list(form.get("SearchFacetFields"))) {
            if (same(requestedName, text(field, "FieldName"))) return new FacetSelection(field, requestedValue);
        }
        return null;
    }

    private static List<Map<String, Object>> facetCounts(DataSource source, String template, String baseExpression,
                                                           List<Object> baseParameters, Map<String, Object> form) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> field : list(form.get("SearchFacetFields"))) {
            String fieldName = text(field, "FieldName");
            if (fieldName.isBlank()) continue;
            Map<String, Object> facet = new LinkedHashMap<>();
            facet.put("field", fieldName);
            facet.put("allCount", count(source, template.replace("{0}", baseExpression), baseParameters));
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> item : list(field.get("ListSourceItems"))) {
                Object value = item.get("Value");
                if (value == null || String.valueOf(value).trim().isEmpty()) continue;
                List<Object> parameters = new ArrayList<>(baseParameters);
                String statePredicate = predicate(field, "eq", value, parameters);
                Map<String, Object> countItem = new LinkedHashMap<>();
                countItem.put("value", String.valueOf(value));
                countItem.put("count", count(source, template.replace("{0}", "(" + baseExpression + ") AND (" + statePredicate + ")"), parameters));
                items.add(countItem);
            }
            facet.put("items", items);
            result.add(facet);
        }
        return result;
    }
    private record FacetSelection(Map<String, Object> field, Object value) { }
    private record ParameterExpression(String sql, boolean missingRequired) { }
    private static void addFilterFields(Map<String, Map<String,Object>> result, Object raw, boolean replace) {
        if (!(raw instanceof List<?> values)) return;
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> field = cast(map);
            String name = text(field, "FieldName");
            if (IDENTIFIER.matcher(name).matches()) {
                String key = name.toLowerCase(Locale.ROOT);
                if (replace) result.put(key, field); else result.putIfAbsent(key, field);
            }
        }
    }
    private static List<String> legacyPredicates(Map<String,Map<String,Object>> fields, Map<String,Object> values, List<Object> parameters) { List<String> result = new ArrayList<>(); for (Map<String,Object> field:fields.values()) { String name=text(field,"FieldName"); Object value=value(values,name,text(field,"ParameterName")); String predicate=predicate(field,"contains",value,parameters); if(predicate!=null)result.add(predicate); } return result; }
    @SuppressWarnings("unchecked") private static String group(Map<String,Object> node, Map<String,Map<String,Object>> fields, List<Object> parameters) {
        Object children=node.get("rules"); if(!(children instanceof List<?> list)) return rule(node,fields,parameters);
        String logic="OR".equalsIgnoreCase(String.valueOf(node.get("logic")))?" OR ":" AND "; List<String> result=new ArrayList<>();
        for(Object child:list)if(child instanceof Map<?,?> map){String value=group((Map<String,Object>)map,fields,parameters);if(value!=null&&!value.isBlank())result.add(value);}
        return result.isEmpty()?"1=1":"("+String.join(logic,result)+")";
    }
    private static String rule(Map<String,Object> rule, Map<String,Map<String,Object>> fields, List<Object> parameters) {
        String name = String.valueOf(rule.getOrDefault("field", "")).trim();
        Map<String,Object> field = fields.get(name.toLowerCase(Locale.ROOT));
        // Ignore a stale client-side field from another module.  A field that
        // is not part of this module's cached SCL/SCD metadata can never be
        // interpolated into SQL.
        if (field == null) return null;
        return predicate(field, String.valueOf(rule.getOrDefault("operator", "contains")), rule.get("value"), parameters);
    }
    private static String predicate(Map<String,Object> field,String operator,Object value,List<Object> parameters) {
        if(value==null||String.valueOf(value).trim().isEmpty())return null;
        String name=text(field,"FieldName"); String op=operator==null?"contains":operator.trim().toLowerCase(Locale.ROOT);
        LocalDate date = "CDT".equalsIgnoreCase(text(field, "ControlType")) ? localDate(value) : null;
        if (date != null) return datePredicate(name, op, date, parameters);
        Object typedValue = typedValue(field, value);
        switch(op){
            case "eq"-> {parameters.add(typedValue);return name+" = ?";}
            case "ne"-> {parameters.add(typedValue);return name+" <> ?";}
            case "gt"-> {parameters.add(typedValue);return name+" > ?";}
            case "ge"-> {parameters.add(typedValue);return name+" >= ?";}
            case "lt"-> {parameters.add(typedValue);return name+" < ?";}
            case "le"-> {parameters.add(typedValue);return name+" <= ?";}
            case "startswith"->{parameters.add(value+"%");return "UPPER("+name+") LIKE UPPER(?)";}
            case "endswith"->{parameters.add("%"+value);return "UPPER("+name+") LIKE UPPER(?)";}
            case "contains"->{parameters.add("%"+value+"%");return "UPPER("+name+") LIKE UPPER(?)";}
            case "notcontains"->{parameters.add("%"+value+"%");return "UPPER("+name+") NOT LIKE UPPER(?)";}
            default->throw new IllegalArgumentException("Unsupported search operator: "+operator);
        }
    }

    /**
     * CCB always sends the configured DEFCODE.CDVALUE.  A numeric code must
     * remain numeric when it is bound to a numeric business column (for
     * example ORGID); text codes such as STATUS=A remain strings.  This is
     * metadata-driven and deliberately does not name any business field.
     */
    private static Object typedValue(Map<String, Object> field, Object value) {
        String raw = String.valueOf(value).trim();
        String fieldType = text(field, "FieldType").toUpperCase(Locale.ROOT);
        try {
            if ("INT".equals(fieldType) && raw.matches("[+-]?\\d+")) return Long.valueOf(raw);
            if ("DEC".equals(fieldType) && raw.matches("[+-]?(?:\\d+|\\d*\\.\\d+)")) return new BigDecimal(raw);
            // CCB values are strings in DEFCODE. A purely numeric CDVALUE must
            // be bound as a number when the target business column is numeric.
            if ("CCB".equalsIgnoreCase(text(field, "ControlType")) && raw.matches("[+-]?\\d+")) return Long.valueOf(raw);
        } catch (NumberFormatException ignored) { }
        return value;
    }

    /** Date equality is an inclusive day range, portable across PostgreSQL, SQL Server and Oracle. */
    private static String datePredicate(String name, String operator, LocalDate date, List<Object> parameters) {
        Timestamp start = Timestamp.valueOf(date.atStartOfDay());
        Timestamp end = Timestamp.valueOf(date.plusDays(1).atStartOfDay());
        return switch (operator) {
            case "ne" -> { parameters.add(start); parameters.add(end); yield "(" + name + " < ? OR " + name + " >= ?)"; }
            case "gt" -> { parameters.add(end); yield name + " >= ?"; }
            case "ge" -> { parameters.add(start); yield name + " >= ?"; }
            case "lt" -> { parameters.add(start); yield name + " < ?"; }
            case "le" -> { parameters.add(end); yield name + " < ?"; }
            default -> { parameters.add(start); parameters.add(end); yield "(" + name + " >= ? AND " + name + " < ?)"; }
        };
    }

    private static LocalDate localDate(Object value) {
        if (value instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
        if (value instanceof java.util.Date date) return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        String raw = String.valueOf(value).trim();
        try { return Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDate(); }
        catch (Exception ignored) { }
        try { return LocalDateTime.parse(raw).toLocalDate(); }
        catch (Exception ignored) { }
        try { return LocalDate.parse(raw.length() >= 10 ? raw.substring(0, 10) : raw); }
        catch (Exception ignored) { return null; }
    }

    private static void normalizeCodeRows(List<Map<String, Object>> rows, Map<String, Object> form) {
        for (Map<String, Object> field : list(form.get("ColumnFields"))) {
            if (!"CCB".equalsIgnoreCase(text(field, "ControlType"))) continue;
            String column = text(field, "FieldName").toLowerCase(Locale.ROOT);
            if (column.isBlank()) continue;
            for (Map<String, Object> row : rows) {
                Object current = row.get(column);
                if (current == null) continue;
                for (Map<String, Object> item : list(field.get("ListSourceItems"))) {
                    if (same(String.valueOf(current), text(item, "CodeValueName"))) {
                        row.put(column, text(item, "Value"));
                        break;
                    }
                }
            }
        }
    }

    private static String pageSql(String base, String provider) {
        return switch (provider.toUpperCase(Locale.ROOT)) {
            case "POSTGRES" -> base + " LIMIT ? OFFSET ?";
            case "SQLSERVER" -> base + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
            case "ORACLE" -> base + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
            default -> throw new IllegalStateException("Unsupported legacy search provider: " + provider);
        };
    }

    private static List<Map<String, Object>> query(DataSource source, String sql, List<Object> values, int skip, int take, String provider) {
        try (var connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bind(statement, values);
            // PostgreSQL expects LIMIT, OFFSET; SQL Server/Oracle expect OFFSET, FETCH.
            statement.setInt(index++, "POSTGRES".equalsIgnoreCase(provider) ? take : skip);
            statement.setInt(index, "POSTGRES".equalsIgnoreCase(provider) ? skip : take);
            try (ResultSet result = statement.executeQuery()) { return rows(result); }
        } catch (Exception exception) { throw new IllegalStateException("Legacy search query failed.", exception); }
    }

    private static long count(DataSource source, String query, List<Object> values) {
        try (var connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM (" + query + ") legacy_search_count")) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0L; }
        } catch (Exception exception) { throw new IllegalStateException("Legacy search count failed.", exception); }
    }

    private static int bind(PreparedStatement statement, List<Object> values) throws java.sql.SQLException { int index = 1; for (Object value : values) statement.setObject(index++, value); return index; }
    private static List<Map<String, Object>> rows(ResultSet result) throws java.sql.SQLException { List<Map<String, Object>> rows = new ArrayList<>(); var meta = result.getMetaData(); while (result.next()) { Map<String, Object> row = new LinkedHashMap<>(); for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), result.getObject(i)); rows.add(row); } return rows; }
    private static void validateTemplate(String value) { String sql = value == null ? "" : value.trim(); String upper = sql.toUpperCase(Locale.ROOT); if (!upper.startsWith("SELECT ") || !sql.contains("{0}") || sql.contains(";") || upper.contains("--") || upper.contains("/*")) throw new IllegalArgumentException("MODSEARCH.QUERYFORMAT must be one SELECT containing {0}."); }
    @SuppressWarnings("unchecked") private static Map<String, Object> cast(Map<?, ?> value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> list(Object value) {
        if (!(value instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) if (row instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        return result;
    }
    private static Object value(Map<String, Object> values, String fieldName, String parameterName) { for (var entry : values.entrySet()) if (entry.getKey().equalsIgnoreCase(fieldName) || (!parameterName.isBlank() && entry.getKey().equalsIgnoreCase(parameterName))) return entry.getValue(); return null; }
    private static String text(Map<String, Object> value, String key) { for (var entry : value.entrySet()) if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim(); return ""; }
    private static boolean same(String left, String right) { return left != null && left.trim().equalsIgnoreCase(right == null ? "" : right.trim()); }
    private static int boundedNumber(Object value, int fallback, int min, int max) { try { return Math.max(min, Math.min(max, value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value)))); } catch (Exception ignored) { return fallback; } }
}
