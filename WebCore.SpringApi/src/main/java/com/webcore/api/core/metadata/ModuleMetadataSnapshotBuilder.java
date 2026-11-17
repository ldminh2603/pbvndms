package com.webcore.api.core.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.webcore.api.contract.ModuleFormInfoResponse;

/**
 * Immutable Form ModuleInfo index equivalent to the .NET App.Cache.Modules cache.
 * It is built only while a Core snapshot is prepared; form requests never re-query
 * DEFMOD/DEFMODFLD or related system tables.
 */
public final class ModuleMetadataSnapshotBuilder {
    private static final String ALL_SUB_MODULE = "ALL";
    private static final Pattern LAYOUT_SIZE = Pattern.compile("<property\\s+name=\"Size\">@\\d+,Width=(\\d+)@\\d+,Height=(\\d+)</property>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL_ITEM = Pattern.compile("<property name=\"ControlName\">([^<]+)</property>(.*?)(?=<property name=\"ControlName\">|</XtraSerializer>)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ITEM_LOCATION = Pattern.compile("<property name=\"Location\">@\\d+,X=(-?\\d+)@\\d+,Y=(-?\\d+)</property>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_SIZE = Pattern.compile("<property name=\"Size\">@\\d+,Width=(\\d+)@\\d+,Height=(\\d+)</property>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_TEXT = Pattern.compile("<property name=\"Text\">([^<]*)</property>", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_PROPERTY = Pattern.compile("<property name=\"([^\"]+)\"[^>]*>([^<]*)</property>", Pattern.CASE_INSENSITIVE);
    
    
    private ModuleMetadataSnapshotBuilder() { }

    /**
     * Typed snapshot boundary. The fallback table map exists only for metadata
     * tables whose schema has not been promoted yet; DEFMODFLD and DEFLANG are
     * deliberately supplied as schema-owned rows.
     */
    public static ModuleMetadataSnapshot build(
            List<RuntimeMetadataRows.DefModuleField> moduleFields,
            List<RuntimeMetadataRows.DefLanguage> languageDefinitions,
            Map<String, List<Map<String, Object>>> fallbackTables) {
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>(fallbackTables);
        tables.put("DEFMODFLD", moduleFields.stream().map(RuntimeMetadataRows.DefModuleField::values).toList());
        tables.put("DEFLANG", languageDefinitions.stream().map(RuntimeMetadataRows.DefLanguage::values).toList());
        return buildTables(tables);
    }

    private static ModuleMetadataSnapshot buildTables(Map<String, List<Map<String, Object>>> tables) {
        List<Map<String, Object>> modules = table(tables, "DEFMOD");
        List<Map<String, Object>> fields = table(tables, "DEFMODFLD");
        List<Map<String, Object>> buttons = table(tables, "DEFMODBTN");
        List<Map<String, Object>> buttonParams = table(tables, "DEFMODBTNPARAM");
        List<Map<String, Object>> validations = table(tables, "DEFMODVALIDATE");
        List<Map<String, Object>> summaries = table(tables, "DEFGROUPSUMMARY");
        List<Map<String, Object>> batchSteps = table(tables, "DEFBATCHSTEP");
        List<Map<String, Object>> searches = table(tables, "MODSEARCH");
        List<Map<String, Object>> maintains = table(tables, "MODMAINTAIN");
        List<Map<String, Object>> alerts = table(tables, "MODALERT");
        List<Map<String, Object>> charts = table(tables, "MODCHART");
        List<Map<String, Object>> excelReports = table(tables, "MODEXCELREPORT");
        List<Map<String, Object>> execProcedures = table(tables, "MODEXECPROC");
        List<Map<String, Object>> imports = table(tables, "MODIMPORT");
        List<Map<String, Object>> reports = table(tables, "MODREPORT");
        List<Map<String, Object>> switches = table(tables, "MODSWITCH");
        List<Map<String, Object>> statistics = table(tables, "MODSTATISTICS");
        List<Map<String, Object>> transactions = table(tables, "MODTRANS");
        List<Map<String, Object>> codes = table(tables, "DEFCODE");
        List<Map<String, Object>> languages = table(tables, "DEFLANG");
        List<Map<String, Object>> layouts = table(tables, "DEFLAYOUT");

        // Phase-1 compiled indexes. The legacy implementation repeatedly scanned
        // every metadata table for every module. Build selector/direct indexes
        // once, then resolve each module with O(1)-ish map lookups.
        BuildIndexes indexes = BuildIndexes.build(
                fields, buttons, buttonParams, validations, summaries, batchSteps,
                searches, maintains, alerts, charts, excelReports, execProcedures,
                imports, reports, switches, statistics, transactions, codes);

        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        Map<String, Map<String, Object>> localizationByKey = new LinkedHashMap<>();
        for (Map<String, Object> source : modules) {
            String moduleId = text(source, "MODID");
            String subModule = text(source, "SUBMOD");
            if (moduleId.isBlank() || subModule.isBlank()) continue;

            Map<String, Object> module = module(source);
            String moduleType = text(source, "MODTYPE");
            String moduleName = text(source, "MODNAME");
            // form-info is language-neutral. Keep only a stable fallback title.
            module.put("Title", moduleName);
            module.put("ModuleTypeName", indexes.codes().moduleTypeName(moduleType));
            // HCD defines URL/host-call parameters for a screen. PRM remains
            // internal hidden state and is intentionally not populated from a route.
            module.put("ParameterFields", fields(moduleId, subModule, indexes, "HCD"));
            List<Map<String, Object>> commonFields = fields(moduleId, subModule, indexes, "CMN");
            module.put("CommonFields", commonFields);
            module.put("LayoutFields", fields(moduleId, subModule, indexes, "LYO"));
            module.put("ConditionFields", fields(moduleId, subModule, indexes, "SCD"));
            module.put("ColumnFields", fields(moduleId, subModule, indexes, "SCL"));
            // SCF drives the optional state/facet tabs on type-03 legacy search.
            module.put("SearchFacetFields", fields(moduleId, subModule, indexes, "SCF"));
            module.put("GroupFields", fields(moduleId, subModule, indexes, "SGR"));
            module.put("ImportFields", fields(moduleId, subModule, indexes, "IMC"));
            module.put("SystemFields", fields(moduleId, subModule, indexes, "SYS"));
            module.put("GroupSummaries", indexes.summaries().resolveEntities(moduleId, subModule, moduleType));
            module.put("Buttons", buttons(moduleId, subModule, moduleType, indexes));
            module.put("Validates", indexes.validations().resolveEntities(moduleId, subModule, moduleType));
            Map<String, Object> popupLayout = popupLayout(moduleName, subModule, commonFields, layouts);
            if (popupLayout != null) module.put("PopupLayout", popupLayout);

            // Build one lightweight localization payload per active language.
            for (String languageId : activeLanguageIds(languages)) {
                LocalizationData localized = localization(
                        moduleId,
                        subModule,
                        moduleName,
                        module,
                        languages,
                        languageId);
                localizationByKey.put(localizationKey(moduleId, subModule, languageId), localized.asMap());
            }

            if ("03".equals(moduleType)) merge(module, indexes.searches().firstEntity(moduleId));
            Map<String, Object> maintain = indexes.maintains().firstEntityPreferExact(moduleId, subModule, moduleType);
            if (maintain != null) module.put("Maintain", maintain);
            module.put("AlertInfos", indexes.alerts().resolveEntities(moduleId, subModule, moduleType));
            module.put("ChartInfos", indexes.charts().resolveEntities(moduleId, subModule, moduleType));
            module.put("ExcelReportInfos", indexes.excelReports().resolveEntities(moduleId, subModule, moduleType));
            module.put("ExecProcedureInfos", indexes.execProcedures().resolveEntities(moduleId, subModule, moduleType));
            module.put("ImportInfos", indexes.imports().resolveEntities(moduleId, subModule, moduleType));
            module.put("ReportInfos", indexes.reports().resolveEntities(moduleId, subModule, moduleType));
            module.put("SwitchInfos", indexes.switches().resolveEntities(moduleId, subModule, moduleType));
            module.put("StatisticsInfos", indexes.statistics().resolveEntities(moduleId, subModule, moduleType));
            module.put("TransactionInfos", indexes.transactions().resolveEntities(moduleId, subModule, moduleType));
            if ("04".equals(moduleType)) merge(module, indexes.switchesDirect().firstEntity(moduleId));
            if ("09".equals(moduleType)) merge(module, indexes.statisticsDirect().firstEntity(moduleId));
            if ("08".equals(moduleType)) module.put("BatchStepInfos", indexes.batchSteps().entities(moduleId));
            byKey.put(key(moduleId, subModule), immutable(module));
        }
        
        Map<ModuleKey, ModuleFormInfo> forms = new LinkedHashMap<>();
        byKey.forEach((legacyKey, value) -> {
            String moduleId = text(value, "ModuleID");
            String subModule = text(value, "SubModule");
            forms.put(new ModuleKey(moduleId, subModule), new ModuleFormInfo(ModuleFormInfoResponse.from(value), value));
        });

        Map<ModuleLocalizationKey, ModuleLocalization> localizations = new LinkedHashMap<>();
        localizationByKey.forEach((legacyKey, value) -> {
            String[] parts = legacyKey.split("\\|", -1);
            if (parts.length >= 3) {
                localizations.put(
                        new ModuleLocalizationKey(parts[0], parts[1], parts[2]),
                        new ModuleLocalization(value));
            }
        });
        return new ModuleMetadataSnapshot(Map.copyOf(forms), Map.copyOf(localizations));
    }

    /**
     * Reads the durable WinForms XtraSerializer layout only while the Core
     * snapshot is prepared. LayoutControlItem.ControlName binds the layout item
     * to DEFMODFLD.FLDNAME; Location/Size become a neutral web descriptor.
     */
    private static Map<String, Object> popupLayout(String moduleName, String subModule, List<Map<String, Object>> fields, List<Map<String, Object>> layouts) {
        String expected = moduleName.trim().toUpperCase(Locale.ROOT) + "[" + subModule.trim().toUpperCase(Locale.ROOT) + "]";
        Map<String, Object> layout = null;
        for (Map<String, Object> candidate : layouts) {
            String layoutName = text(candidate, "LAYOUTNAME").toUpperCase(Locale.ROOT);
            // Equivalent to: LAYOUTNAME LIKE '%<MODNAME>[<SUBMOD>]%' AND STATUS='A'.
            // The target form's DEFMOD.MODNAME is the only layout namespace.
            if (layoutName.contains(expected) && "A".equalsIgnoreCase(text(candidate, "STATUS"))) {
                layout = candidate;
                break;
            }
        }
        if (layout == null) return null;

        String data = text(layout, "LAYOUTDATA");
        Matcher size = LAYOUT_SIZE.matcher(data);
        int sourceWidth = 0;
        int sourceHeight = 0;
        if (size.find()) {
            sourceWidth = integer(size.group(1));
            sourceHeight = integer(size.group(2));
        }

        Map<String, Map<String, Object>> fieldsByName = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) fieldsByName.put(text(field, "FieldName").toUpperCase(Locale.ROOT), field);
        TabParse tabs = parseTabs(data);

        List<Map<String, Object>> items = new ArrayList<>();
        Matcher control = CONTROL_ITEM.matcher(data);
        int order = 0;
        while (control.find()) {
            String fieldName = control.group(1).trim();
            if (!fieldsByName.containsKey(fieldName.toUpperCase(Locale.ROOT))) continue;
            String fragment = control.group(2);
            Matcher location = ITEM_LOCATION.matcher(fragment);
            Matcher itemSize = ITEM_SIZE.matcher(fragment);
            Matcher itemText = ITEM_TEXT.matcher(fragment);
            if (!location.find() || !itemSize.find()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("FieldName", fieldName);
            item.put("Order", order++);
            item.put("X", integer(location.group(1)));
            item.put("Y", integer(location.group(2)));
            item.put("Width", integer(itemSize.group(1)));
            item.put("Height", integer(itemSize.group(2)));
            if (itemText.find()) item.put("Text", itemText.group(1).trim());
            TabInfo tab = tabs.byControlName().get(fieldName.toUpperCase(Locale.ROOT));
            if (tab != null) item.put("TabId", tab.id());
            items.add(immutable(item));
        }
        // A field missing from a corrupted/older XML layout must remain usable.
        // It is appended after the parsed controls instead of being silently lost.
        int fallbackY = Math.max(sourceHeight, 0);
        for (Map<String, Object> field : fields) {
            String fieldName = text(field, "FieldName");
            boolean parsed = items.stream().anyMatch(item -> same(fieldName, text(item, "FieldName")));
            if (parsed) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("FieldName", fieldName);
            item.put("Order", order++);
            item.put("X", (order % 2 == 0) ? 0 : Math.max(sourceWidth / 2, 1));
            item.put("Y", fallbackY);
            item.put("Width", Math.max(sourceWidth / 2, 1));
            item.put("Height", 24);
            items.add(immutable(item));
            if (order % 2 == 0) fallbackY += 28;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("LayoutName", text(layout, "LAYOUTNAME"));
        result.put("Format", "WinFormsXtraSerializer");
        result.put("SourceWidth", sourceWidth);
        result.put("SourceHeight", sourceHeight);
        result.put("Items", List.copyOf(items));
        if (!tabs.tabs().isEmpty()) {
            List<Map<String, Object>> tabData = new ArrayList<>();
            for (TabInfo tab : tabs.tabs()) {
                Map<String, Object> tabItem = new LinkedHashMap<>();
                tabItem.put("Id", tab.id());
                tabItem.put("GroupId", tab.groupId());
                tabItem.put("Caption", tab.caption());
                tabItem.put("Y", tab.y());
                tabData.add(immutable(tabItem));
            }
            result.put("Tabs", List.copyOf(tabData));
        }
        return immutable(result);
    }

    /** Extracts the TabbedGroup -> LayoutGroup -> LayoutControlItem relationship from XtraSerializer XML. */
    private static TabParse parseTabs(String data) {
        Map<String, TabInfo> byControl = new LinkedHashMap<>();
        Map<String, TabInfo> tabsById = new LinkedHashMap<>();
        Map<String, TabInfo> tabsByLayoutGroupName = new LinkedHashMap<>();
        String type = "";
        String groupParent = "";
        String groupName = "";
        String controlName = "";
        String tabbedGroupName = "";
        int tabbedGroupLocationY = Integer.MAX_VALUE;
        Map<String, Integer> tabbedGroupY = new LinkedHashMap<>();
        TabInfo currentTab = null;
        Matcher property = XML_PROPERTY.matcher(data);
        while (property.find()) {
            String name = property.group(1).trim();
            String value = property.group(2).trim();
            if ("TypeName".equalsIgnoreCase(name)) {
                type = value;
                if ("LayoutGroup".equalsIgnoreCase(type)) {
                    groupParent = "";
                    groupName = "";
                }
                if ("LayoutControlItem".equalsIgnoreCase(type)) controlName = "";
                if ("TabbedGroup".equalsIgnoreCase(type)) {
                    tabbedGroupName = "";
                    tabbedGroupLocationY = Integer.MAX_VALUE;
                }
                continue;
            }
            if ("TabbedGroup".equalsIgnoreCase(type) && "Name".equalsIgnoreCase(name)) {
                tabbedGroupName = value;
                if (tabbedGroupLocationY != Integer.MAX_VALUE) tabbedGroupY.put(tabbedGroupName, tabbedGroupLocationY);
                continue;
            }
            if ("TabbedGroup".equalsIgnoreCase(type) && "Location".equalsIgnoreCase(name)) {
                tabbedGroupLocationY = locationY(value);
                if (!tabbedGroupName.isBlank()) tabbedGroupY.put(tabbedGroupName, tabbedGroupLocationY);
                continue;
            }
            if ("LayoutGroup".equalsIgnoreCase(type) && "TabbedGroupParentName".equalsIgnoreCase(name)) {
                groupParent = value;
                continue;
            }
            if ("LayoutGroup".equalsIgnoreCase(type) && "Name".equalsIgnoreCase(name)) {
                groupName = value;
                continue;
            }
            if ("LayoutGroup".equalsIgnoreCase(type) && "Text".equalsIgnoreCase(name) && !groupParent.isBlank()) {
                String parent = groupParent;
                String caption = value;
                String id = parent + ":" + (groupName.isBlank() ? caption : groupName);
                int y = tabbedGroupY.getOrDefault(parent, Integer.MAX_VALUE);
                currentTab = tabsById.computeIfAbsent(id, ignored -> new TabInfo(id, parent, caption, y));
                if (!groupName.isBlank()) tabsByLayoutGroupName.put(groupName, currentTab);
                continue;
            }
            if ("LayoutControlItem".equalsIgnoreCase(type) && "ControlName".equalsIgnoreCase(name)) {
                controlName = value;
                continue;
            }
            if ("LayoutControlItem".equalsIgnoreCase(type) && "ParentName".equalsIgnoreCase(name) && !controlName.isBlank()) {
                // XtraSerializer records the owning LayoutGroup here. Only a
                // group that explicitly belongs to a TabbedGroup may assign a
                // field to a tab; standalone controls remain outside it.
                TabInfo tab = tabsByLayoutGroupName.get(value);
                if (tab != null) byControl.put(controlName.toUpperCase(Locale.ROOT), tab);
            }
        }
        return new TabParse(List.copyOf(tabsById.values()), Map.copyOf(byControl));
    }

    private static int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static int locationY(String value) {
        Matcher matcher = Pattern.compile("Y=(-?\\d+)", Pattern.CASE_INSENSITIVE).matcher(value);
        return matcher.find() ? integer(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private record TabInfo(String id, String groupId, String caption, int y) { }
    private record TabParse(List<TabInfo> tabs, Map<String, TabInfo> byControlName) { }

    /**
     * Lightweight localized UI payload kept separately from form-info metadata.
     * One payload is stored per module/submodule/language.
     */
    private record LocalizationData(
            String titleDefault,
            String titleInfo,
            Map<String, String> fields,
            Map<String, String> fieldHiden,
            Map<String, String> buttons,
            Map<String, String> icons,
            Map<String, Map<String, String>> lookups,
            Map<String, Map<String, String>> colors,
            Map<String, String> tabs,
            Map<String, String> texts
            ) {

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            // Title is retained for existing module clients. The login shell
            // uses the explicit variants below.
            result.put("Title", titleDefault == null ? "" : titleDefault);
            result.put("TitleDefault", titleDefault == null ? "" : titleDefault);
            result.put("TitleInfo", titleInfo == null ? "" : titleInfo);
            result.put("Fields", Map.copyOf(fields));
            result.put("FieldHiden", Map.copyOf(fieldHiden));
            result.put("Buttons", Map.copyOf(buttons));
            result.put("Icons", Map.copyOf(icons));
            result.put("Lookups", Map.copyOf(lookups));
            result.put("Colors", Map.copyOf(colors));
            result.put("Tabs", Map.copyOf(tabs));
            result.put("Texts", Map.copyOf(texts));
            return immutable(result);
        }
    }

    private static List<Map<String, Object>> fields(
            String moduleId,
            String subModule,
            BuildIndexes indexes,
            String group) {

        List<Map<String, Object>> rows = indexes.fields().resolve(moduleId, subModule, group);
        if (rows.isEmpty()) return List.of();

        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> field = entity(row);
            // form-info contains only structural metadata and stable fallbacks.
            field.put("Label", text(field, "FieldName").toUpperCase(Locale.ROOT));
            applyCodeListSource(field, indexes.codes());
            result.add(field);
        }
        result.sort(java.util.Comparator.comparing(value -> text(value, "FieldID"), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }


    /**
     * Legacy CCB/CCC convention: LISTSOURCE = :CDTYPE.CDNAME. DEFCODE is pre-indexed
     * by (CDTYPE, CDNAME), avoiding a complete DEFCODE scan for every lookup field.
     */
    private static void applyCodeListSource(Map<String, Object> field, CodeIndex codes) {
        String controlType = text(field, "ControlType");
        if (!"CCB".equalsIgnoreCase(controlType) && !"CCC".equalsIgnoreCase(controlType)) return;
        String listSource = text(field, "ListSource");
        if (!listSource.startsWith(":")) return;

        String[] reference = listSource.substring(1).trim().split("\\.", 2);
        if (reference.length != 2 || reference[0].isBlank() || reference[1].isBlank()) return;
        List<Map<String, Object>> matched = codes.values(reference[0], reference[1]);
        if (matched.isEmpty()) return;

        List<Map<String, Object>> items = new ArrayList<>(matched.size());
        for (Map<String, Object> code : matched) {
            String value = text(code, "CDVALUE");
            if (value.isBlank()) continue;
            String fallback = text(code, "CDVALUENAME");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("Value", value);
            item.put("CodeValueName", fallback);
            item.put("Text", fallback.isBlank() ? value : fallback);
            item.put("Position", text(code, "CDPOSITION"));
            items.add(item);
        }
        items.sort(java.util.Comparator.comparingDouble((Map<String, Object> item) -> numeric(text(item, "Position")))
                .thenComparing(item -> text(item, "Value"), String.CASE_INSENSITIVE_ORDER));
        List<Map<String, Object>> immutableItems = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            item.remove("Position");
            immutableItems.add(immutable(item));
        }
        field.put("ListSourceItems", List.copyOf(immutableItems));
    }

    private static double numeric(String value) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException ignored) { return Double.MAX_VALUE; }
    }



    private static List<Map<String, Object>> buttons(
            String moduleId,
            String subModule,
            String moduleType,
            BuildIndexes indexes) {

        List<Map<String, Object>> rows = indexes.buttons().resolve(moduleId, subModule, moduleType);
        if (rows.isEmpty()) return List.of();

        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (!buttonVisible(row)) continue;
            Map<String, Object> button = entity(row);
            String buttonModule = text(row, "MODID");
            String buttonName = text(row, "BTNNAME");
            button.put("Caption", buttonName);
            button.put("Icon", "");
            button.put("ParamsInfo", indexes.buttonParams().entities(buttonModule, buttonName));
            result.add(immutable(button));
        }
        result.sort(java.util.Comparator.comparing(value -> text(value, "ButtonID"), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }


    private static List<String> activeLanguageIds(List<Map<String, Object>> languages) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> row : languages) {
            if (!"A".equalsIgnoreCase(text(row, "STATUS"))) continue;
            String languageId = text(row, "LANGID").toUpperCase(Locale.ROOT);
            if (!languageId.isBlank() && !result.contains(languageId)) result.add(languageId);
        }
        return List.copyOf(result);
    }

    private static LocalizationData localization(
            String moduleId,
            String subModule,
            String moduleName,
            Map<String, Object> module,
            List<Map<String, Object>> languages,
            String languageId) {

        String titleDefault = localizedLabel(
                moduleName + ".Title[Default]",
                languageId,
                languages,
                moduleName);
        String titleInfo = localizedLabel(
                moduleName + ".Title[Info]",
                languageId,
                languages,
                "");

        Map<String, String> fieldLabels = new LinkedHashMap<>();
        for (String group : List.of(
                "ParameterFields", "CommonFields", "LayoutFields", "ConditionFields", "SearchFacetFields",
                "ColumnFields", "GroupFields", "ImportFields", "SystemFields")) {

            Object value = module.get(group);
            if (!(value instanceof List<?> list)) continue;

            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> field = (Map<String, Object>) raw;

                String fieldName = text(field, "FieldName");
                if (fieldName.isBlank()) continue;

                String label = localizedFieldLabel(
                        moduleName,
                        fieldName,
                        languageId,
                        languages);

                fieldLabels.put(fieldName, label);
            }
        }

        Map<String, String> fieldHidenLabels = new LinkedHashMap<>();

        for (String group : List.of(
                "ParameterFields", "CommonFields", "LayoutFields", "ConditionFields", "SearchFacetFields",
                "ColumnFields", "GroupFields", "ImportFields", "SystemFields")) {

            Object value = module.get(group);
            if (!(value instanceof List<?> list)) continue;

            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> field = (Map<String, Object>) raw;

                String fieldName = text(field, "FieldName");
                if (fieldName.isBlank()) continue;

                fieldHidenLabels.put(
                        fieldName,
                        localizedFieldHidenLabel(moduleName, fieldName, languageId, languages)
                );
            }
        }

        Map<String, String> buttonCaptions = new LinkedHashMap<>();
        Map<String, String> buttonIcons = new LinkedHashMap<>();
        Object buttonValue = module.get("Buttons");
        if (buttonValue instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> button = (Map<String, Object>) raw;

                String buttonName = text(button, "ButtonName");
                if (buttonName.isBlank()) continue;

                String caption = localizedButtonCaption(
                        moduleName,
                        buttonName,
                        languageId,
                        languages);

                String icon = localizedButtonIcon(
                        moduleName,
                        buttonName,
                        languageId,
                        languages);

                // Buttons and Icons payloads are keyed by BTNNAME only.
                buttonCaptions.put(buttonName, caption);
                buttonIcons.put(buttonName, icon);
            }
        }

        Map<String, Map<String, String>> lookupTexts = new LinkedHashMap<>();
        Map<String, Map<String, String>> colors = new LinkedHashMap<>();
        for (String group : List.of(
                "ParameterFields", "CommonFields", "LayoutFields", "ConditionFields", "SearchFacetFields",
                "ColumnFields", "GroupFields", "ImportFields", "SystemFields")) {

            Object value = module.get(group);
            if (!(value instanceof List<?> list)) continue;

            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> field = (Map<String, Object>) raw;

                String listSource = text(field, "ListSource");
                if (!listSource.startsWith(":")) continue;

                String[] reference = listSource.substring(1).trim().split("\\.", 2);
                if (reference.length != 2 || reference[0].isBlank() || reference[1].isBlank()) continue;

                String codeType = reference[0].trim();
                String codeName = reference[1].trim();
                Map<String, String> values = new LinkedHashMap<>();
                Map<String, String> colorValues = new LinkedHashMap<>();

                Object listSourceItems = field.get("ListSourceItems");
                if (listSourceItems instanceof List<?> items) {
                    for (Object lookupItem : items) {
                        if (!(lookupItem instanceof Map<?, ?> rawLookup)) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> lookup = (Map<String, Object>) rawLookup;

                        String valueKey = text(lookup, "Value");
                        String fallback = text(lookup, "CodeValueName");
                        if (valueKey.isBlank()) continue;

                        String key = "CODE$"
                                + codeType.toUpperCase(Locale.ROOT)
                                + "$"
                                + codeName.toUpperCase(Locale.ROOT)
                                + "."
                                + fallback;

                        values.put(valueKey, localizedLabel(
                                key,
                                languageId,
                                languages,
                                fallback.isBlank() ? valueKey : fallback));
                        String color = localizedLabel(key + "%COLOR", languageId, languages, "");
                        if (!color.isBlank()) colorValues.put(valueKey, color);
                    }
                }

                String fieldName = text(field, "FieldName");
                Map<String, String> immutableValues = Map.copyOf(values);

                // Keep Lookups keyed only by FLDNAME, e.g. STATUS -> { A: ..., C: ... }.
                if (!fieldName.isBlank()) {
                    lookupTexts.put(fieldName, immutableValues);
                    if (!colorValues.isEmpty()) colors.put(fieldName, Map.copyOf(colorValues));
                }
            }
        }

        Map<String, String> tabCaptions = new LinkedHashMap<>();
        Object popupLayoutValue = module.get("PopupLayout");
        if (popupLayoutValue instanceof Map<?, ?> rawLayout) {
            Object tabsValue = rawLayout.get("Tabs");
            if (tabsValue instanceof List<?> tabs) {
                for (Object item : tabs) {
                    if (!(item instanceof Map<?, ?> rawTab)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tab = (Map<String, Object>) rawTab;
                    String id = text(tab, "Id");
                    String caption = text(tab, "Caption");
                    if (!id.isBlank()) tabCaptions.put(id, caption);
                }
            }
        }
        String moduleType = text(module, "ModuleType");    
        Map<String, String> textsCaptions =
                buildTextsCaptions(
                        moduleName,
                        moduleType,
                        languageId,
                        languages
                );
        return new LocalizationData(
                titleDefault,
                titleInfo,
                Map.copyOf(fieldLabels),
                 Map.copyOf(fieldHidenLabels),
                Map.copyOf(buttonCaptions),
                Map.copyOf(buttonIcons),
                Map.copyOf(lookupTexts),
                Map.copyOf(colors),
                Map.copyOf(tabCaptions),
                Map.copyOf(textsCaptions)
            );
    }

    private static String localizedLabel(
            String key,
            String languageId,
            List<Map<String, Object>> languages,
            String fallback) {

        for (Map<String, Object> row : languages) {
            if (!same(key, text(row, "LANGNAME"))) continue;
            if (!same(languageId, text(row, "LANGID"))) continue;
            if (!"A".equalsIgnoreCase(text(row, "STATUS"))) continue;

            String value = text(row, "LANGVALUE");
            if (!value.isBlank()) return value;
        }
        return fallback == null ? "" : fallback;
    }


    /**
     * Field label resolution order:
     * 1) <MODNAME>$<FLDNAME>.Label for the requested language and STATUS='A'
     * 2) GLOBAL$<FLDNAME>.Label for the requested language and STATUS='A'
     * 3) FLDNAME itself
     */
    private static String localizedFieldLabel(
            String moduleName,
            String fieldName,
            String languageId,
            List<Map<String, Object>> languages) {

        String moduleKey = moduleName + "$" + fieldName + ".Label";
        String moduleValue = localizedLabel(moduleKey, languageId, languages, "");
        if (!moduleValue.isBlank()) return moduleValue;

        String globalKey = "GLOBAL$" + fieldName + ".Label";
        String globalValue = localizedLabel(globalKey, languageId, languages, "");
        if (!globalValue.isBlank()) return globalValue;

        return fieldName;
    }

    private static String localizedFieldHidenLabel(
            String moduleName,
            String fieldName,
            String languageId,
            List<Map<String, Object>> languages) {

        String moduleKey = moduleName + "$" + fieldName + ".Hiden";
        String moduleValue = localizedLabel(moduleKey, languageId, languages, "");
        if (!moduleValue.isBlank()) return moduleValue;

        String globalKey = "GLOBAL$" + fieldName + ".Hiden";
        String globalValue = localizedLabel(globalKey, languageId, languages, "");
        if (!globalValue.isBlank()) return globalValue;

        return fieldName;
    }


    
    /**
     * Button caption resolution order:
     * 1) <MODNAME>$<BTNNAME>.Caption for the requested language and STATUS='A'
     * 2) GLOBAL$<BTNNAME>.Caption for the requested language and STATUS='A'
     * 3) BTNNAME itself
     */
    private static String localizedButtonCaption(
            String moduleName,
            String buttonName,
            String languageId,
            List<Map<String, Object>> languages) {

        String moduleKey = moduleName + "$" + buttonName + ".Caption";
        String moduleValue = localizedLabel(moduleKey, languageId, languages, "");
        if (!moduleValue.isBlank()) return moduleValue;

        String globalKey = "GLOBAL$" + buttonName + ".Caption";
        String globalValue = localizedLabel(globalKey, languageId, languages, "");
        if (!globalValue.isBlank()) return globalValue;

        return buttonName;
    }

    /**
     * Button icon resolution order:
     * 1) <MODNAME>$<BTNNAME>.Icon for the requested language and STATUS='A'
     * 2) GLOBAL$<BTNNAME>.Icon for the requested language and STATUS='A'
     * 3) BTNNAME itself
     */
    private static String localizedButtonIcon(
            String moduleName,
            String buttonName,
            String languageId,
            List<Map<String, Object>> languages) {

        String moduleKey = moduleName + "$" + buttonName + ".Icon";
        String moduleValue = localizedLabel(moduleKey, languageId, languages, "");
        if (!moduleValue.isBlank()) return moduleValue;

        String globalKey = "GLOBAL$" + buttonName + ".Icon";
        String globalValue = localizedLabel(globalKey, languageId, languages, "");
        if (!globalValue.isBlank()) return globalValue;

        return buttonName;
    }

    private static String mergedLocalizedLabel(
            String fallbackKey,
            String overrideKey,
            String languageId,
            List<Map<String, Object>> languages,
            String fallback) {

        String global = localizedLabel(fallbackKey, languageId, languages, "");
        return localizedLabel(
                overrideKey,
                languageId,
                languages,
                global.isBlank() ? fallback : global);
    }

    /** Normalized selector key used by legacy ModuleInfo.Contain semantics. */
    private record SelectorKey(String moduleId, String subModule) {
        SelectorKey {
            moduleId = normalize(moduleId);
            subModule = normalize(subModule);
        }
    }

    private record ButtonParamKey(String moduleId, String buttonName) {
        ButtonParamKey {
            moduleId = normalize(moduleId);
            buttonName = normalize(buttonName);
        }
    }

    private record CodeKey(String type, String name) {
        CodeKey {
            type = normalize(type);
            name = normalize(name);
        }
    }

    /**
     * Index for metadata tables that use ModuleInfo.Contain:
     * MODID = actual module OR module type, and SUBMOD = exact OR ALL.
     * Order matches the original table scan by storing source row position.
     */
    private static final class SelectorIndex {
        private final Map<SelectorKey, List<IndexedRow>> rows;

        private SelectorIndex(Map<SelectorKey, List<IndexedRow>> rows) {
            this.rows = rows;
        }

        static SelectorIndex build(List<Map<String, Object>> source) {
            Map<SelectorKey, List<IndexedRow>> mutable = new LinkedHashMap<>();
            for (int i = 0; i < source.size(); i++) {
                Map<String, Object> row = source.get(i);
                SelectorKey key = new SelectorKey(text(row, "MODID"), text(row, "SUBMOD"));
                mutable.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new IndexedRow(i, row));
            }
            Map<SelectorKey, List<IndexedRow>> immutable = new LinkedHashMap<>();
            mutable.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
            return new SelectorIndex(Map.copyOf(immutable));
        }

        List<Map<String, Object>> resolve(String moduleId, String subModule, String moduleType) {
            List<IndexedRow> matches = new ArrayList<>();
            add(matches, moduleId, subModule);
            add(matches, moduleId, ALL_SUB_MODULE);
            if (!same(moduleId, moduleType)) {
                add(matches, moduleType, subModule);
                add(matches, moduleType, ALL_SUB_MODULE);
            }
            if (matches.isEmpty()) return List.of();
            matches.sort(java.util.Comparator.comparingInt(IndexedRow::position));
            List<Map<String, Object>> result = new ArrayList<>(matches.size());
            for (IndexedRow match : matches) result.add(match.row());
            return List.copyOf(result);
        }

        List<Map<String, Object>> resolveEntities(String moduleId, String subModule, String moduleType) {
            return entities(resolve(moduleId, subModule, moduleType));
        }

        Map<String, Object> firstEntityPreferExact(String moduleId, String subModule, String moduleType) {
            List<IndexedRow> exact = rows.getOrDefault(new SelectorKey(moduleId, subModule), List.of());
            if (!exact.isEmpty()) return entity(exact.get(0).row());
            List<Map<String, Object>> inherited = resolve(moduleId, subModule, moduleType);
            return inherited.isEmpty() ? null : entity(inherited.get(0));
        }

        private void add(List<IndexedRow> target, String moduleId, String subModule) {
            if (moduleId == null || moduleId.isBlank()) return;
            target.addAll(rows.getOrDefault(new SelectorKey(moduleId, subModule), List.of()));
        }
    }

    /** DEFMODFLD index: concrete MODID only; MODTYPE inheritance is intentionally forbidden. */
    private static final class FieldIndex {
        private final Map<String, Map<SelectorKey, List<IndexedRow>>> byGroup;

        private FieldIndex(Map<String, Map<SelectorKey, List<IndexedRow>>> byGroup) {
            this.byGroup = byGroup;
        }

        static FieldIndex build(List<Map<String, Object>> source) {
            Map<String, Map<SelectorKey, List<IndexedRow>>> groups = new LinkedHashMap<>();
            for (int i = 0; i < source.size(); i++) {
                Map<String, Object> row = source.get(i);
                if (!"Y".equalsIgnoreCase(text(row, "SYSVISIBLE"))) continue;
                String group = normalize(text(row, "FLDGROUP"));
                SelectorKey key = new SelectorKey(text(row, "MODID"), text(row, "SUBMOD"));
                groups.computeIfAbsent(group, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new IndexedRow(i, row));
            }
            Map<String, Map<SelectorKey, List<IndexedRow>>> immutable = new LinkedHashMap<>();
            groups.forEach((group, selectors) -> {
                Map<SelectorKey, List<IndexedRow>> copy = new LinkedHashMap<>();
                selectors.forEach((key, value) -> copy.put(key, List.copyOf(value)));
                immutable.put(group, Map.copyOf(copy));
            });
            return new FieldIndex(Map.copyOf(immutable));
        }

        List<Map<String, Object>> resolve(String moduleId, String subModule, String group) {
            Map<SelectorKey, List<IndexedRow>> selectors = byGroup.getOrDefault(normalize(group), Map.of());
            List<IndexedRow> matches = new ArrayList<>();
            matches.addAll(selectors.getOrDefault(new SelectorKey(moduleId, subModule), List.of()));
            matches.addAll(selectors.getOrDefault(new SelectorKey(moduleId, ALL_SUB_MODULE), List.of()));
            if (matches.isEmpty()) return List.of();
            matches.sort(java.util.Comparator.comparingInt(IndexedRow::position));
            List<Map<String, Object>> result = new ArrayList<>(matches.size());
            for (IndexedRow match : matches) result.add(match.row());
            return List.copyOf(result);
        }
    }

    private static final class ButtonParamIndex {
        private final Map<ButtonParamKey, List<Map<String, Object>>> rows;

        private ButtonParamIndex(Map<ButtonParamKey, List<Map<String, Object>>> rows) { this.rows = rows; }

        static ButtonParamIndex build(List<Map<String, Object>> source) {
            Map<ButtonParamKey, List<Map<String, Object>>> mutable = new LinkedHashMap<>();
            for (Map<String, Object> row : source) {
                ButtonParamKey key = new ButtonParamKey(text(row, "MODID"), text(row, "BTNNAME"));
                mutable.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
            Map<ButtonParamKey, List<Map<String, Object>>> immutable = new LinkedHashMap<>();
            mutable.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
            return new ButtonParamIndex(Map.copyOf(immutable));
        }

        List<Map<String, Object>> entities(String moduleId, String buttonName) {
            return ModuleMetadataSnapshotBuilder.entities(rows.getOrDefault(new ButtonParamKey(moduleId, buttonName), List.of()));
        }
    }

    private static final class DirectModuleIndex {
        private final Map<String, List<Map<String, Object>>> rows;

        private DirectModuleIndex(Map<String, List<Map<String, Object>>> rows) { this.rows = rows; }

        static DirectModuleIndex build(List<Map<String, Object>> source) {
            Map<String, List<Map<String, Object>>> mutable = new LinkedHashMap<>();
            for (Map<String, Object> row : source) {
                mutable.computeIfAbsent(normalize(text(row, "MODID")), ignored -> new ArrayList<>()).add(row);
            }
            Map<String, List<Map<String, Object>>> immutable = new LinkedHashMap<>();
            mutable.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
            return new DirectModuleIndex(Map.copyOf(immutable));
        }

        Map<String, Object> firstEntity(String moduleId) {
            List<Map<String, Object>> values = rows.getOrDefault(normalize(moduleId), List.of());
            return values.isEmpty() ? null : entity(values.get(0));
        }

        List<Map<String, Object>> entities(String moduleId) {
            return ModuleMetadataSnapshotBuilder.entities(rows.getOrDefault(normalize(moduleId), List.of()));
        }
    }

    private static final class CodeIndex {
        private final Map<CodeKey, List<Map<String, Object>>> values;
        private final Map<String, String> moduleTypeNames;

        private CodeIndex(Map<CodeKey, List<Map<String, Object>>> values, Map<String, String> moduleTypeNames) {
            this.values = values;
            this.moduleTypeNames = moduleTypeNames;
        }

        static CodeIndex build(List<Map<String, Object>> source) {
            Map<CodeKey, List<Map<String, Object>>> mutable = new LinkedHashMap<>();
            Map<String, String> moduleTypeNames = new LinkedHashMap<>();
            for (Map<String, Object> row : source) {
                CodeKey key = new CodeKey(text(row, "CDTYPE"), text(row, "CDNAME"));
                mutable.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
                if (same("DEFMOD", text(row, "CDTYPE")) && same("MODTYPE", text(row, "CDNAME"))) {
                    moduleTypeNames.putIfAbsent(normalize(text(row, "CDVALUE")), text(row, "CDVALUENAME"));
                }
            }
            Map<CodeKey, List<Map<String, Object>>> immutable = new LinkedHashMap<>();
            mutable.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
            return new CodeIndex(Map.copyOf(immutable), Map.copyOf(moduleTypeNames));
        }

        List<Map<String, Object>> values(String type, String name) {
            return values.getOrDefault(new CodeKey(type, name), List.of());
        }

        String moduleTypeName(String moduleType) {
            return moduleTypeNames.getOrDefault(normalize(moduleType), "");
        }
    }

    private record IndexedRow(int position, Map<String, Object> row) { }

    private record BuildIndexes(
            FieldIndex fields,
            SelectorIndex buttons,
            ButtonParamIndex buttonParams,
            SelectorIndex validations,
            SelectorIndex summaries,
            DirectModuleIndex batchSteps,
            DirectModuleIndex searches,
            SelectorIndex maintains,
            SelectorIndex alerts,
            SelectorIndex charts,
            SelectorIndex excelReports,
            SelectorIndex execProcedures,
            SelectorIndex imports,
            SelectorIndex reports,
            SelectorIndex switches,
            SelectorIndex statistics,
            SelectorIndex transactions,
            DirectModuleIndex switchesDirect,
            DirectModuleIndex statisticsDirect,
            CodeIndex codes) {

        static BuildIndexes build(
                List<Map<String, Object>> fields,
                List<Map<String, Object>> buttons,
                List<Map<String, Object>> buttonParams,
                List<Map<String, Object>> validations,
                List<Map<String, Object>> summaries,
                List<Map<String, Object>> batchSteps,
                List<Map<String, Object>> searches,
                List<Map<String, Object>> maintains,
                List<Map<String, Object>> alerts,
                List<Map<String, Object>> charts,
                List<Map<String, Object>> excelReports,
                List<Map<String, Object>> execProcedures,
                List<Map<String, Object>> imports,
                List<Map<String, Object>> reports,
                List<Map<String, Object>> switches,
                List<Map<String, Object>> statistics,
                List<Map<String, Object>> transactions,
                List<Map<String, Object>> codes) {
            return new BuildIndexes(
                    FieldIndex.build(fields),
                    SelectorIndex.build(buttons),
                    ButtonParamIndex.build(buttonParams),
                    SelectorIndex.build(validations),
                    SelectorIndex.build(summaries),
                    DirectModuleIndex.build(batchSteps),
                    DirectModuleIndex.build(searches),
                    SelectorIndex.build(maintains),
                    SelectorIndex.build(alerts),
                    SelectorIndex.build(charts),
                    SelectorIndex.build(excelReports),
                    SelectorIndex.build(execProcedures),
                    SelectorIndex.build(imports),
                    SelectorIndex.build(reports),
                    SelectorIndex.build(switches),
                    SelectorIndex.build(statistics),
                    SelectorIndex.build(transactions),
                    DirectModuleIndex.build(switches),
                    DirectModuleIndex.build(statistics),
                    CodeIndex.build(codes));
        }
    }

    private static List<Map<String, Object>> entities(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) result.add(entity(row));
        return List.copyOf(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static List<Map<String, Object>> contained(String moduleId, String subModule, String moduleType, List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) if (contains(moduleId, subModule, moduleType, row)) result.add(entity(row));
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> directByModule(List<Map<String, Object>> rows, String moduleId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) if (same(moduleId, text(row, "MODID"))) result.add(entity(row));
        return List.copyOf(result);
    }

    private static boolean contains(String moduleId, String subModule, String moduleType, Map<String, Object> row) {
        return (same(moduleId, text(row, "MODID")) || same(moduleType, text(row, "MODID")))
                && (ALL_SUB_MODULE.equalsIgnoreCase(text(row, "SUBMOD")) || same(subModule, text(row, "SUBMOD")));
    }

    /** DEFMODFLD is resolved strictly from the target module, never MODTYPE. */
    private static boolean fieldBelongsTo(String moduleId, String subModule, Map<String, Object> row) {
        return same(moduleId, text(row, "MODID"))
                && (ALL_SUB_MODULE.equalsIgnoreCase(text(row, "SUBMOD")) || same(subModule, text(row, "SUBMOD")));
    }

    private static Map<String, Object> module(Map<String, Object> source) {
        Map<String, Object> result = entity(source);
        result.putIfAbsent("ExtraSubModule", text(source, "EX_SUBMOD").isBlank() ? text(source, "SUBMOD") : text(source, "EX_SUBMOD"));
        return result;
    }

    private static void merge(Map<String, Object> target, Map<String, Object> source) {
        if (source == null) return;
        source.forEach(target::putIfAbsent);
    }

    private static Map<String, Object> firstByModule(List<Map<String, Object>> rows, String moduleId) {
        for (Map<String, Object> row : rows) if (same(moduleId, text(row, "MODID"))) return entity(row);
        return null;
    }

    /** MODMAINTAIN is cached per module/submodule; ALL remains the normal fallback. */
    private static Map<String, Object> firstContained(String moduleId, String subModule, String moduleType, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            if (same(moduleId, text(row, "MODID")) && same(subModule, text(row, "SUBMOD"))) return entity(row);
        }
        for (Map<String, Object> row : rows) if (contains(moduleId, subModule, moduleType, row)) return entity(row);
        return null;
    }

    private static String moduleTypeName(List<Map<String, Object>> codes, String moduleType) {
        for (Map<String, Object> code : codes) {
            if (same(moduleType, text(code, "CDVALUE")) && same("DEFMOD", text(code, "CDTYPE")) && same("MODTYPE", text(code, "CDNAME"))) return text(code, "CDVALUENAME");
        }
        return "";
    }

    /** Converts DB columns to the PascalCase contract understood by fis-entities-ts. */
    private static Map<String, Object> entity(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) result.put(property(entry.getKey()), entry.getValue());
        return result;
    }

    private static String property(String column) {
        String value = column == null ? "" : column.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "APPID" -> "ApplicationId"; case "MODID" -> "ModuleID"; case "SUBMOD" -> "SubModule"; case "EX_SUBMOD" -> "ExtraSubModule";
            case "MODNAME" -> "ModuleName"; case "MODTYPE" -> "ModuleType"; case "UITYPE" -> "UIType"; case "USERHIDE" -> "UserHide";
            case "USERMAX" -> "UserMax"; case "USERCLOSE" -> "UserClose"; case "EXECMODE" -> "ExecuteMode"; case "MODTYPENAME" -> "ModuleTypeName";
            case "ROLE" -> "RoleID"; case "STARTMODE" -> "StartMode"; case "GRMOD" -> "GroupModule"; case "LOADHANDLER" -> "LoadHandler";
            case "CLOSEHANDLER" -> "CloseHandler"; case "ACTIVITYLOG" -> "ActivityLog"; case "CA_ENCRYPT" -> "CAEncrypt";
            case "CA_SIGN_MODE" -> "CASignMode"; case "CA_SIGN_SAVE_MODE" -> "CASignSaveMode"; case "LOOP_CALLBACK" -> "LoopCallback";
            case "FLDID" -> "FieldID"; case "FLDNAME" -> "FieldName"; case "PARAMNAME" -> "ParameterName"; case "FLDGROUP" -> "FieldGroup";
            case "FLDTYPE" -> "FieldType"; case "IMPTYPE" -> "ImportType"; case "SCDTYPE" -> "ConditionType"; case "TEXTCASE" -> "TextCase";
            case "CTRLTYPE" -> "ControlType"; case "FLDVALIDATE" -> "FieldValidate"; case "FLDFORMAT" -> "FieldFormat"; case "DEFAULTVALUE" -> "DefaultValue";
            case "CUSTOMSCD" -> "CustomSearchCondition"; case "LISTSOURCE" -> "ListSource"; case "CALLBACK" -> "Callback"; case "POPUPMODE" -> "PopupMode";
            case "TABSTOP" -> "TabStop"; case "ROE" -> "ReadOnlyOnEdit"; case "ROA" -> "ReadOnlyOnAdd"; case "ROV" -> "ReadOnlyOnView";
            case "FOS" -> "FixedOnSearch"; case "GOS" -> "GroupOnSearch"; case "ICONONLY" -> "IconOnly"; case "TEXTALIGN" -> "TextAlign";
            case "FIXEDWIDTH" -> "FixedWidth"; case "NULLABLE" -> "Nullable"; case "HOLDCOLUMN" -> "HoldColumn"; case "MERGECELL" -> "MegerCell";
            case "COLUMNSORT" -> "ColumnSort"; case "FONTBOLD" -> "FontBold"; case "UNBOUND_EXPRESSION" -> "UnboundExpression"; case "SYSVISIBLE" -> "SystemVisible";
            case "LINK_MOD" -> "LinkModule"; case "ALLOWEXP" -> "AllowExport"; case "SERIALTARGET" -> "SerialTarget"; case "MAXLENGTH" -> "MaxLength";
            case "CALLBACK_MODE" -> "CallbackMode"; case "ICON_NAME" -> "IconName"; case "COLUMN_INDEX" -> "ColumnIndex"; case "ROW_ID" -> "RowId";
            case "BTNID" -> "ButtonID"; case "BTNNAME" -> "ButtonName"; case "BTNGROUP" -> "ButtonGroup"; case "BTNSOURCE" -> "ButtonSource";
            case "BEGINGROUP" -> "BeginGroup"; case "CALLMODID" -> "CallModuleID"; case "CALLSUBMOD" -> "CallSubModule"; case "SHOWCONFIRM" -> "ShowConfirm";
            case "MULTIEXEC" -> "MultiExecute"; case "ONTOOLBAR" -> "ShowOnToolbar"; case "PARAMMODE" -> "ParameterMode"; case "CLOSEPARENTMODULE" -> "CloseParentModule";
            case "COLNAME" -> "ColumnName"; case "VALUE" -> "Value"; case "BUTTONSOURCEFIELD" -> "ButtonSourceField"; case "CONDITIONNAME" -> "CommonName";
            case "VALIDATERULE" -> "ValidateRule"; case "VALIDATELEVEL" -> "ValidateLevel"; case "VALIDATE_IMMEDIATE" -> "ValidateImmediate";
            case "SWITCHSTORE" -> "SwitchStore"; case "STORENAME" -> "StoreName"; case "FULLWIDTH" -> "FullWidth"; case "PAGEMODE" -> "PageMode";
            case "STATISTICSTORENAME" -> "StatisticQuery"; case "AUTOFITWIDTH" -> "AutoFitWidthColumns";
            case "COMMITHANDLER" -> "CommitHandler"; case "GROUPBOX" -> "GroupBox"; case "SHOWGRPCOL" -> "ShowGroupColumn";
            case "SHOWCHECKBOX" -> "ShowCheckBox"; case "WHEREEXTENSION" -> "WhereExtension"; case "QUERYFORMAT" -> "QueryFormat";
            case "STATISTICQUERY" -> "StatisticQuery"; case "AUTOREFRESH" -> "AutoRefresh"; case "DEFAULTBUTTON" -> "DefaultButton";
            case "SEARCH_ONLOAD" -> "SearchOnload";
            default -> pascal(value);
        };
    }

    private static String pascal(String value) {
        StringBuilder result = new StringBuilder();
        for (String part : value.toLowerCase(Locale.ROOT).split("_")) if (!part.isBlank()) result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        return result.toString();
    }

    private static String text(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> entry : row.entrySet()) if (entry.getKey().equalsIgnoreCase(column)) return entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim();
        return "";
    }

    /**
     * Legacy DEFMODBTN snapshots use VISIBLED, while imported sources may use
     * VISIBLE and represent true as 1/true.  A missing flag historically means
     * the row is visible; only an explicit false value hides a button.
     */
    private static boolean buttonVisible(Map<String, Object> row) {
        String value = text(row, "VISIBLED");
        if (value.isBlank()) value = text(row, "VISIBLE");
        return value.isBlank()
                || "Y".equalsIgnoreCase(value)
                || "1".equals(value)
                || "TRUE".equalsIgnoreCase(value);
    }

    private static boolean same(String left, String right) { return left != null && left.trim().equalsIgnoreCase(right == null ? "" : right.trim()); }
    private static String key(String moduleId, String subModule) {
        return (moduleId == null ? "" : moduleId.trim()).toLowerCase(Locale.ROOT)
                + "|"
                + (subModule == null ? "" : subModule.trim()).toLowerCase(Locale.ROOT);
    }

    private static String localizationKey(String moduleId, String subModule, String languageId) {
        return key(moduleId, subModule)
                + "|"
                + (languageId == null ? "" : languageId.trim()).toUpperCase(Locale.ROOT);
    }
    private static List<Map<String, Object>> table(Map<String, List<Map<String, Object>>> tables, String name) { return tables.getOrDefault(name, List.of()); }
    /** JDBC metadata can legitimately contain NULL; Map.copyOf would reject it. */
    private static Map<String, Object> immutable(Map<String, Object> value) { return Collections.unmodifiableMap(new LinkedHashMap<>(value)); }


    private static String localizedUiText(
        String moduleName,
        String textName,
        String languageId,
        List<Map<String, Object>> languages,
        String defaultValue) {

        // 1. Module-specific
        String moduleKey =
                moduleName
                + "."
                + textName
                + "[Default]";

        String moduleValue =
                localizedLabel(
                        moduleKey,
                        languageId,
                        languages,
                        "");

            if (!moduleValue.isBlank()) {
                return moduleValue;
            }

            // 2. GLOBAL fallback
            String globalKey =
                    "GLOBAL."
                    + textName
                    + "[Default]";

            String globalValue =
                    localizedLabel(
                            globalKey,
                            languageId,
                            languages,
                            "");

            if (!globalValue.isBlank()) {
                return globalValue;
            }

            // 3. Code fallback
            return defaultValue;
        }
    
    

        private static Map<String, String> buildTextsCaptions(
            String moduleName,
            String moduleType,
            String languageId,
            List<Map<String, Object>> languages) {

        Map<String, String> textsCaptions = new LinkedHashMap<>();

        // MODTYPE = 03: Search
        if ("03".equalsIgnoreCase(moduleType)) {

            textsCaptions.put(
                    "CREATE_FILTER",
                    localizedUiText(
                            moduleName,
                            "CREATE_FILTER",
                            languageId,
                            languages,
                            "Create Filter"
                    )
            );

            textsCaptions.put(
                    "CLEAR_FILTER",
                    localizedUiText(
                            moduleName,
                            "CLEAR_FILTER",
                            languageId,
                            languages,
                            "Clear Filter"
                    )
            );
        }

        // MODTYPE = 01: Store Execute
        if ("01".equalsIgnoreCase(moduleType)) {

            textsCaptions.put(
                    "BTN_DELETE_WARNING",
                    localizedWarningText(
                            moduleName,
                            "BTN_DELETE",
                            languageId,
                            languages,
                            ""
                    )
            );
        }

        return Map.copyOf(textsCaptions);
    }


        private static String localizedWarningText(
            String moduleName,
            String buttonName,
            String languageId,
            List<Map<String, Object>> languages,
            String defaultValue) {

        String moduleKey =
                moduleName
                + ".Title["
                + buttonName
                + ".Warning]";

        String moduleValue = localizedLabel(
                moduleKey,
                languageId,
                languages,
                ""
        );

        if (!moduleValue.isBlank()) {
            return moduleValue;
        }

        String globalKey =
                "GLOBAL.Title["
                + buttonName
                + ".Warning]";

        String globalValue = localizedLabel(
                globalKey,
                languageId,
                languages,
                ""
        );

        if (!globalValue.isBlank()) {
            return globalValue;
        }

        return defaultValue;
    }
     
}
