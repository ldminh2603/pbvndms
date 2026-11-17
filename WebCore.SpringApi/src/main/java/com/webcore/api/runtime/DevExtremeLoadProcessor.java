package com.webcore.api.runtime;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** Applies the DevExtreme LoadOptions contract to rows returned by a declared module action. */
@Service
public class DevExtremeLoadProcessor {
    private final ObjectMapper json;
    public DevExtremeLoadProcessor(ObjectMapper json) { this.json = json; }

    public Map<String,Object> load(List<Map<String,Object>> source, Map<String,Object> request) {
        List<Map<String,Object>> rows = new ArrayList<>(source);
        Object filter = value(request, "filter");
        if (filter != null) rows.removeIf(row -> !matches(row, filter));
        applySearch(rows, request);
        applySort(rows, list(request, "sort"));

        List<Object> totalSummary = list(request, "totalSummary");
        List<Object> group = list(request, "group");
        List<Object> groupSummary = list(request, "groupSummary");
        Map<String,Object> response = new LinkedHashMap<>();
        int totalCount = rows.size();
        if (!group.isEmpty()) {
            List<Map<String,Object>> groups = groups(rows, group, 0, groupSummary);
            response.put("data", page(groups, request));
            if (bool(request, "requireGroupCount")) response.put("groupCount", groups.size());
        } else response.put("data", page(rows, request));
        if (bool(request, "requireTotalCount")) response.put("totalCount", totalCount);
        else response.put("totalCount", totalCount); // Legacy React expects totalCount even when not explicitly requested.
        if (!totalSummary.isEmpty()) response.put("summary", summary(rows, totalSummary));
        return response;
    }

    private void applySearch(List<Map<String,Object>> rows, Map<String,Object> request) {
        Object raw = value(request, "searchValue"); if (raw == null || String.valueOf(raw).isBlank()) return;
        List<Object> fields = list(request, "searchExpr"); if (fields.isEmpty()) return;
        String operation = string(value(request, "searchOperation"), "contains");
        rows.removeIf(row -> fields.stream().noneMatch(field -> compare(row.get(String.valueOf(field)), operation, raw)));
    }
    private void applySort(List<Map<String,Object>> rows, List<Object> sorts) {
        Comparator<Map<String,Object>> comparator = null;
        for (Object item : sorts) {
            if (!(item instanceof Map<?,?> descriptor)) continue;
            String selector = string(descriptor.get("selector"), ""); if (selector.isBlank()) continue;
            Comparator<Map<String,Object>> next = Comparator.comparing(row -> comparable(row.get(selector)), Comparator.nullsLast(Comparator.naturalOrder()));
            if (Boolean.TRUE.equals(descriptor.get("desc"))) next = next.reversed();
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        if (comparator != null) rows.sort(comparator);
    }
    private List<Map<String,Object>> groups(List<Map<String,Object>> rows, List<Object> descriptors, int level, List<Object> summaries) {
        if (level >= descriptors.size() || !(descriptors.get(level) instanceof Map<?,?> descriptor)) return rows;
        String selector = string(descriptor.get("selector"), "");
        Map<Object,List<Map<String,Object>>> byKey = new LinkedHashMap<>();
        for (Map<String,Object> row : rows) byKey.computeIfAbsent(field(row, selector), ignored -> new ArrayList<>()).add(row);
        List<Map<String,Object>> result = new ArrayList<>();
        for (var entry : byKey.entrySet()) {
            List<Map<String,Object>> children = entry.getValue(); Map<String,Object> group = new LinkedHashMap<>();
            group.put("key", entry.getKey()); group.put("count", children.size());
            group.put("items", level + 1 < descriptors.size() ? groups(children, descriptors, level + 1, summaries) : children);
            if (!summaries.isEmpty()) group.put("summary", summary(children, summaries)); result.add(group);
        }
        if (Boolean.TRUE.equals(descriptor.get("desc"))) java.util.Collections.reverse(result);
        return result;
    }
    private List<Object> summary(List<Map<String,Object>> rows, List<Object> descriptors) {
        List<Object> result = new ArrayList<>();
        for (Object raw : descriptors) {
            if (!(raw instanceof Map<?,?> descriptor)) { result.add(null); continue; }
            String selector = string(descriptor.get("selector"), ""); String type = string(descriptor.get("summaryType"), "count").toLowerCase(Locale.ROOT);
            List<Object> values = rows.stream().map(row -> field(row, selector)).filter(value -> value != null).toList();
            result.add(switch (type) { case "count" -> values.size(); case "sum" -> values.stream().map(this::decimal).reduce(BigDecimal.ZERO, BigDecimal::add); case "avg" -> values.isEmpty() ? null : values.stream().map(this::decimal).reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(values.size()), java.math.MathContext.DECIMAL64); case "min" -> values.stream().map(this::comparable).min(Comparator.naturalOrder()).orElse(null); case "max" -> values.stream().map(this::comparable).max(Comparator.naturalOrder()).orElse(null); default -> null; });
        }
        return result;
    }
    private List<?> page(List<?> values, Map<String,Object> request) { int skip = number(value(request, "skip"), 0); int take = number(value(request, "take"), values.size()); int from = Math.min(Math.max(0, skip), values.size()); int to = take < 0 ? values.size() : Math.min(values.size(), from + take); return values.subList(from, to); }
    private boolean matches(Map<String,Object> row, Object expression) {
        if (!(expression instanceof List<?> parts) || parts.isEmpty()) return true;
        if (parts.size() == 2 && "!".equals(String.valueOf(parts.getFirst()))) return !matches(row, parts.get(1));
        if (parts.size() == 3 && ("and".equalsIgnoreCase(String.valueOf(parts.get(1))) || "or".equalsIgnoreCase(String.valueOf(parts.get(1))))) return "and".equalsIgnoreCase(String.valueOf(parts.get(1))) ? matches(row, parts.get(0)) && matches(row, parts.get(2)) : matches(row, parts.get(0)) || matches(row, parts.get(2));
        if (parts.size() == 3) return compare(field(row, String.valueOf(parts.get(0))), String.valueOf(parts.get(1)), parts.get(2));
        boolean result = matches(row, parts.getFirst()); for (int i=1;i+1<parts.size();i+=2) result = "or".equalsIgnoreCase(String.valueOf(parts.get(i))) ? result || matches(row,parts.get(i+1)) : result && matches(row,parts.get(i+1)); return result;
    }
    private boolean compare(Object actual, String op, Object expected) { String operation = op == null ? "=" : op.toLowerCase(Locale.ROOT); if ("=".equals(operation)) return equals(actual, expected); if ("<>".equals(operation) || "!=".equals(operation)) return !equals(actual, expected); if (actual == null) return false; if ("contains".equals(operation) || "notcontains".equals(operation) || "startswith".equals(operation) || "endswith".equals(operation)) { String left=String.valueOf(actual).toLowerCase(Locale.ROOT),right=String.valueOf(expected).toLowerCase(Locale.ROOT); boolean result="contains".equals(operation)||"notcontains".equals(operation)?left.contains(right):"startswith".equals(operation)?left.startsWith(right):left.endsWith(right); return "notcontains".equals(operation)?!result:result; } int compared=comparable(actual).compareTo(comparable(expected)); return switch(operation){case ">"->compared>0;case ">="->compared>=0;case "<"->compared<0;case "<="->compared<=0;default->false;}; }
    private boolean equals(Object left, Object right) { return left == null ? right == null : String.valueOf(left).equalsIgnoreCase(String.valueOf(right)); }
    private Comparable comparable(Object value) { if (value instanceof Number || value instanceof BigDecimal) return decimal(value); return String.valueOf(value).toLowerCase(Locale.ROOT); }
    private BigDecimal decimal(Object value) { try { return new BigDecimal(String.valueOf(value)); } catch (Exception ignored) { return BigDecimal.ZERO; } }
    private Object field(Map<String,Object> row,String name){for(var entry:row.entrySet())if(entry.getKey().equalsIgnoreCase(name))return entry.getValue();return null;}
    private Object value(Map<String,Object> values,String key){Object raw=values.get(key);if(raw instanceof String text&&!text.isBlank()&&(text.startsWith("[")||text.startsWith("{"))){try{return json.readValue(text,new TypeReference<Object>(){});}catch(Exception ignored){}}return raw;}
    private List<Object> list(Map<String,Object> values,String key){Object raw=value(values,key);return raw instanceof List<?> list?new ArrayList<>(list):List.of();}
    private boolean bool(Map<String,Object> values,String key){return Boolean.parseBoolean(String.valueOf(value(values,key)));}
    private int number(Object value,int fallback){try{return value instanceof Number number?number.intValue():Integer.parseInt(String.valueOf(value));}catch(Exception ignored){return fallback;}}
    private String string(Object value,String fallback){return value==null?fallback:String.valueOf(value);}
}
