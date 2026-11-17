package com.webcore.api.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Typed public contract for {@code /api/module/{moduleId}/form-info/{subModule}.
 *
 * <p>The Core snapshot is currently metadata-driven and stores its raw database
 * rows internally. This record is the boundary that prevents those dynamic maps
 * from becoming an HTTP API contract.</p>
 */
public record ModuleFormInfoResponse(
        @JsonProperty("ModuleID") String moduleId,
        @JsonProperty("SubModule") String subModule,
        @JsonProperty("ModuleType") String moduleType,
        @JsonProperty("ModuleName") String moduleName,
        @JsonProperty("Title") String title,
        @JsonProperty("ModuleTypeName") String moduleTypeName,
        @JsonProperty("ApplicationId") String applicationId,
        @JsonProperty("ParameterFields") List<ModuleFieldResponse> parameterFields,
        @JsonProperty("CommonFields") List<ModuleFieldResponse> commonFields,
        @JsonProperty("LayoutFields") List<ModuleFieldResponse> layoutFields,
        @JsonProperty("ConditionFields") List<ModuleFieldResponse> conditionFields,
        @JsonProperty("SearchFacetFields") List<ModuleFieldResponse> searchFacetFields,
        @JsonProperty("ColumnFields") List<ModuleFieldResponse> columnFields,
        @JsonProperty("GroupFields") List<ModuleFieldResponse> groupFields,
        @JsonProperty("ImportFields") List<ModuleFieldResponse> importFields,
        @JsonProperty("SystemFields") List<ModuleFieldResponse> systemFields,
        @JsonProperty("Buttons") List<ModuleButtonResponse> buttons,
        @JsonProperty("Validates") List<ModuleValidationResponse> validates,
        @JsonProperty("GroupSummaries") List<ModuleSummaryResponse> groupSummaries,
        @JsonProperty("StatisticsInfos") List<ModuleExtensionResponse> statisticsInfos,
        @JsonProperty("AlertInfos") List<ModuleExtensionResponse> alertInfos,
        @JsonProperty("ChartInfos") List<ModuleExtensionResponse> chartInfos,
        @JsonProperty("ExcelReportInfos") List<ModuleExtensionResponse> excelReportInfos,
        @JsonProperty("ExecProcedureInfos") List<ModuleExtensionResponse> execProcedureInfos,
        @JsonProperty("ImportInfos") List<ModuleExtensionResponse> importInfos,
        @JsonProperty("ReportInfos") List<ModuleExtensionResponse> reportInfos,
        @JsonProperty("SwitchInfos") List<ModuleExtensionResponse> switchInfos,
        @JsonProperty("TransactionInfos") List<ModuleExtensionResponse> transactionInfos,
        @JsonProperty("PopupLayout") PopupLayoutResponse popupLayout,
        @JsonProperty("Maintain") ModuleExtensionResponse maintain,
        @JsonProperty("BatchStepInfos") List<ModuleExtensionResponse> batchStepInfos,
        @JsonProperty("UIType") String uiType,
        @JsonProperty("ExecuteMode") String executeMode,
        @JsonProperty("StartMode") String startMode,
        @JsonProperty("GroupModule") String groupModule,
        @JsonProperty("RoleID") String roleId,
        @JsonProperty("UserHide") String userHide,
        @JsonProperty("UserMax") String userMax,
        @JsonProperty("UserClose") String userClose,
        @JsonProperty("LoadHandler") String loadHandler,
        @JsonProperty("CloseHandler") String closeHandler,
        @JsonProperty("ActivityLog") String activityLog,
        @JsonProperty("QueryFormat") String queryFormat,
        @JsonProperty("StatisticQuery") String statisticQuery) {

    public static ModuleFormInfoResponse from(Map<String, Object> source) {
        return new ModuleFormInfoResponse(
                text(source, "ModuleID"), text(source, "SubModule"), text(source, "ModuleType"),
                text(source, "ModuleName"), text(source, "Title"), text(source, "ModuleTypeName"),
                text(source, "ApplicationId"),
                fields(source, "ParameterFields"), fields(source, "CommonFields"), fields(source, "LayoutFields"),
                fields(source, "ConditionFields"), fields(source, "SearchFacetFields"), fields(source, "ColumnFields"),
                fields(source, "GroupFields"), fields(source, "ImportFields"), fields(source, "SystemFields"),
                buttons(source, "Buttons"), validations(source, "Validates"), summaries(source, "GroupSummaries"),
                extensions(source, "StatisticsInfos"), extensions(source, "AlertInfos"), extensions(source, "ChartInfos"),
                extensions(source, "ExcelReportInfos"), extensions(source, "ExecProcedureInfos"), extensions(source, "ImportInfos"),
                extensions(source, "ReportInfos"), extensions(source, "SwitchInfos"), extensions(source, "TransactionInfos"),
                popupLayout(source), extension(source, "Maintain"), extensions(source, "BatchStepInfos"),
                text(source, "UIType"), text(source, "ExecuteMode"), text(source, "StartMode"), text(source, "GroupModule"),
                text(source, "RoleID"), text(source, "UserHide"), text(source, "UserMax"), text(source, "UserClose"),
                text(source, "LoadHandler"), text(source, "CloseHandler"), text(source, "ActivityLog"),
                text(source, "QueryFormat"), text(source, "StatisticQuery"));
    }

    public record ModuleFieldResponse(
            @JsonProperty("FieldID") String fieldId,
            @JsonProperty("FieldName") String fieldName,
            @JsonProperty("ParameterName") String parameterName,
            @JsonProperty("FieldGroup") String fieldGroup,
            @JsonProperty("FieldType") String fieldType,
            @JsonProperty("ControlType") String controlType,
            @JsonProperty("FieldFormat") String fieldFormat,
            @JsonProperty("DefaultValue") String defaultValue,
            @JsonProperty("ListSource") String listSource,
            @JsonProperty("Callback") String callback,
            @JsonProperty("ConditionType") String conditionType,
            @JsonProperty("Nullable") String nullable,
            @JsonProperty("ReadOnlyOnAdd") String readOnlyOnAdd,
            @JsonProperty("ReadOnlyOnEdit") String readOnlyOnEdit,
            @JsonProperty("ReadOnlyOnView") String readOnlyOnView,
            @JsonProperty("FixedWidth") Integer fixedWidth,
            @JsonProperty("ColumnIndex") Integer columnIndex,
            @JsonProperty("Label") String label,
            @JsonProperty("ListSourceItems") List<LookupItemResponse> listSourceItems,
            @JsonProperty("ImportType") String importType,
            @JsonProperty("TextCase") String textCase,
            @JsonProperty("FieldValidate") String fieldValidate,
            @JsonProperty("CustomSearchCondition") String customSearchCondition,
            @JsonProperty("PopupMode") String popupMode,
            @JsonProperty("TabStop") String tabStop,
            @JsonProperty("FixedOnSearch") String fixedOnSearch,
            @JsonProperty("GroupOnSearch") String groupOnSearch,
            @JsonProperty("IconOnly") String iconOnly,
            @JsonProperty("TextAlign") String textAlign,
            @JsonProperty("HoldColumn") String holdColumn,
            @JsonProperty("MegerCell") String megerCell,
            @JsonProperty("ColumnSort") String columnSort,
            @JsonProperty("FontBold") String fontBold,
            @JsonProperty("UnboundExpression") String unboundExpression,
            @JsonProperty("SystemVisible") String systemVisible,
            @JsonProperty("LinkModule") String linkModule,
            @JsonProperty("AllowExport") String allowExport,
            @JsonProperty("SerialTarget") String serialTarget,
            @JsonProperty("MaxLength") Integer maxLength,
            @JsonProperty("CallbackMode") String callbackMode,
            @JsonProperty("IconName") String iconName,
            @JsonProperty("RowId") String rowId) {
        private static ModuleFieldResponse from(Map<String, Object> source) {
            return new ModuleFieldResponse(
                    text(source, "FieldID"), text(source, "FieldName"), text(source, "ParameterName"),
                    text(source, "FieldGroup"), text(source, "FieldType"), text(source, "ControlType"),
                    text(source, "FieldFormat"), text(source, "DefaultValue"), text(source, "ListSource"),
                    text(source, "Callback"), text(source, "ConditionType"), text(source, "Nullable"),
                    text(source, "ReadOnlyOnAdd"), text(source, "ReadOnlyOnEdit"), text(source, "ReadOnlyOnView"),
                    integer(source, "FixedWidth"), integer(source, "ColumnIndex"), text(source, "Label"),
                    lookupItems(source, "ListSourceItems"), text(source, "ImportType"), text(source, "TextCase"),
                    text(source, "FieldValidate"), text(source, "CustomSearchCondition"), text(source, "PopupMode"),
                    text(source, "TabStop"), text(source, "FixedOnSearch"), text(source, "GroupOnSearch"), text(source, "IconOnly"),
                    text(source, "TextAlign"), text(source, "HoldColumn"), text(source, "MegerCell"), text(source, "ColumnSort"),
                    text(source, "FontBold"), text(source, "UnboundExpression"), text(source, "SystemVisible"), text(source, "LinkModule"),
                    text(source, "AllowExport"), text(source, "SerialTarget"), integer(source, "MaxLength"), text(source, "CallbackMode"),
                    text(source, "IconName"), text(source, "RowId"));
        }
    }

    public record LookupItemResponse(
            @JsonProperty("Value") String value,
            @JsonProperty("Text") String text,
        @JsonProperty("CodeValueName") String codeValueName,
        @JsonProperty("Image") String image) {
        private static LookupItemResponse from(Map<String, Object> source) {
            return new LookupItemResponse(ModuleFormInfoResponse.text(source, "Value"),
                    ModuleFormInfoResponse.text(source, "Text"),
                    ModuleFormInfoResponse.text(source, "CodeValueName"),
                    ModuleFormInfoResponse.text(source, "Image"));
        }
    }

    public record ModuleButtonResponse(
            @JsonProperty("ButtonID") String buttonId,
            @JsonProperty("ButtonName") String buttonName,
            @JsonProperty("Caption") String caption,
            @JsonProperty("Icon") String icon,
            @JsonProperty("ShowOnToolbar") String showOnToolbar,
            @JsonProperty("BeginGroup") String beginGroup,
            @JsonProperty("CallModuleID") String callModuleId,
            @JsonProperty("CallSubModule") String callSubModule,
            @JsonProperty("ShowConfirm") String showConfirm,
            @JsonProperty("ButtonGroup") String buttonGroup,
            @JsonProperty("ButtonSource") String buttonSource,
            @JsonProperty("MultiExecute") String multiExecute,
            @JsonProperty("ParameterMode") String parameterMode,
            @JsonProperty("CloseParentModule") String closeParentModule,
            @JsonProperty("BtnParams") String btnParams,
            @JsonProperty("ParamsInfo") List<ButtonParameterResponse> paramsInfo) {
        private static ModuleButtonResponse from(Map<String, Object> source) {
            return new ModuleButtonResponse(text(source, "ButtonID"), text(source, "ButtonName"), text(source, "Caption"),
                    text(source, "Icon"), text(source, "ShowOnToolbar"), text(source, "BeginGroup"),
                    text(source, "CallModuleID"), text(source, "CallSubModule"), text(source, "ShowConfirm"), text(source, "ButtonGroup"),
                    text(source, "ButtonSource"), text(source, "MultiExecute"), text(source, "ParameterMode"),
                    text(source, "CloseParentModule"), text(source, "BtnParams"), buttonParameters(source, "ParamsInfo"));
        }
    }

    public record ButtonParameterResponse(
            @JsonProperty("ColumnName") String columnName,
            @JsonProperty("Value") String value,
            @JsonProperty("ButtonSourceField") String buttonSourceField,
            @JsonProperty("CommonName") String commonName) {
        private static ButtonParameterResponse from(Map<String, Object> source) {
            return new ButtonParameterResponse(text(source, "ColumnName"), text(source, "Value"),
                    text(source, "ButtonSourceField"), text(source, "CommonName"));
        }
    }

    public record ModuleValidationResponse(
            @JsonProperty("FieldName") String fieldName,
            @JsonProperty("ValidateRule") String validateRule,
            @JsonProperty("ValidateLevel") String validateLevel,
            @JsonProperty("ValidateImmediate") String validateImmediate) {
        private static ModuleValidationResponse from(Map<String, Object> source) {
            return new ModuleValidationResponse(text(source, "FieldName"), text(source, "ValidateRule"),
                    text(source, "ValidateLevel"), text(source, "ValidateImmediate"));
        }
    }

    public record ModuleSummaryResponse(
            @JsonProperty("FieldName") String fieldName,
            @JsonProperty("SummaryType") String summaryType,
            @JsonProperty("DisplayFormat") String displayFormat) {
        private static ModuleSummaryResponse from(Map<String, Object> source) {
            return new ModuleSummaryResponse(text(source, "FieldName"), text(source, "SummaryType"), text(source, "DisplayFormat"));
        }
    }

    /** Common typed properties exposed by module-type-specific metadata rows. */
    public record ModuleExtensionResponse(
            @JsonProperty("ModuleID") String moduleId,
            @JsonProperty("SubModule") String subModule,
            @JsonProperty("Name") String name,
            @JsonProperty("Title") String title,
            @JsonProperty("QueryFormat") String queryFormat,
            @JsonProperty("StatisticQuery") String statisticQuery,
            @JsonProperty("CommitHandler") String commitHandler,
            @JsonProperty("ShowSuccess") String showSuccess,
            @JsonProperty("StoreName") String storeName,
            @JsonProperty("SwitchStore") String switchStore,
            @JsonProperty("PageMode") String pageMode,
            @JsonProperty("AutoRefresh") String autoRefresh,
            @JsonProperty("AutoFitWidthColumns") String autoFitWidthColumns,
            @JsonProperty("DefaultButton") String defaultButton,
            @JsonProperty("SearchOnload") String searchOnload,
            @JsonProperty("WhereExtension") String whereExtension,
            @JsonProperty("FullWidth") String fullWidth) {
        private static ModuleExtensionResponse from(Map<String, Object> source) {
            return new ModuleExtensionResponse(text(source, "ModuleID"), text(source, "SubModule"), text(source, "Name"),
                    text(source, "Title"), text(source, "QueryFormat"), text(source, "StatisticQuery"),
                    text(source, "CommitHandler"), text(source, "ShowSuccess"), text(source, "StoreName"),
                    text(source, "SwitchStore"), text(source, "PageMode"), text(source, "AutoRefresh"),
                    text(source, "AutoFitWidthColumns"), text(source, "DefaultButton"), text(source, "SearchOnload"),
                    text(source, "WhereExtension"), text(source, "FullWidth"));
        }
    }

    public record PopupLayoutResponse(
            @JsonProperty("LayoutName") String layoutName,
            @JsonProperty("Format") String format,
            @JsonProperty("SourceWidth") Integer sourceWidth,
            @JsonProperty("SourceHeight") Integer sourceHeight,
            @JsonProperty("Items") List<PopupLayoutItemResponse> items,
            @JsonProperty("Tabs") List<PopupLayoutTabResponse> tabs) {
        private static PopupLayoutResponse from(Map<String, Object> source) {
            return new PopupLayoutResponse(text(source, "LayoutName"), text(source, "Format"),
                    integer(source, "SourceWidth"), integer(source, "SourceHeight"),
                    rows(source, "Items").stream().map(PopupLayoutItemResponse::from).toList(),
                    rows(source, "Tabs").stream().map(PopupLayoutTabResponse::from).toList());
        }
    }

    public record PopupLayoutItemResponse(
            @JsonProperty("FieldName") String fieldName,
            @JsonProperty("Order") Integer order,
            @JsonProperty("X") Integer x,
            @JsonProperty("Y") Integer y,
            @JsonProperty("Width") Integer width,
            @JsonProperty("Height") Integer height,
            @JsonProperty("Text") String text,
        @JsonProperty("TabId") String tabId) {
        private static PopupLayoutItemResponse from(Map<String, Object> source) {
            return new PopupLayoutItemResponse(ModuleFormInfoResponse.text(source, "FieldName"), integer(source, "Order"), integer(source, "X"),
                    integer(source, "Y"), integer(source, "Width"), integer(source, "Height"),
                    ModuleFormInfoResponse.text(source, "Text"), ModuleFormInfoResponse.text(source, "TabId"));
        }
    }

    public record PopupLayoutTabResponse(
            @JsonProperty("Id") String id,
            @JsonProperty("GroupId") String groupId,
            @JsonProperty("Caption") String caption,
            @JsonProperty("Y") Integer y) {
        private static PopupLayoutTabResponse from(Map<String, Object> source) {
            return new PopupLayoutTabResponse(text(source, "Id"), text(source, "GroupId"), text(source, "Caption"), integer(source, "Y"));
        }
    }

    private static List<ModuleFieldResponse> fields(Map<String, Object> source, String key) {
        return rows(source, key).stream().map(ModuleFieldResponse::from).toList();
    }

    private static List<LookupItemResponse> lookupItems(Map<String, Object> source, String key) {
        return rows(source, key).stream().map(LookupItemResponse::from).toList();
    }

    private static List<ModuleButtonResponse> buttons(Map<String, Object> source, String key) {
        return rows(source, key).stream().map(ModuleButtonResponse::from).toList();
    }

    private static List<ButtonParameterResponse> buttonParameters(Map<String, Object> source, String key) {
        return rows(source, key).stream().map(ButtonParameterResponse::from).toList();
    }

    private static List<ModuleValidationResponse> validations(Map<String, Object> source, String key) {
        return rows(source, key).stream().map(ModuleValidationResponse::from).toList();
    }

    private static List<ModuleSummaryResponse> summaries(Map<String, Object> source, String key) {
        return rows(source, key).stream().map(ModuleSummaryResponse::from).toList();
    }

    private static List<ModuleExtensionResponse> extensions(Map<String, Object> source, String key) {
        return rows(source, key).stream().map(ModuleExtensionResponse::from).toList();
    }

    @SuppressWarnings("unchecked")
    private static ModuleExtensionResponse extension(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Map<?, ?> row ? ModuleExtensionResponse.from((Map<String, Object>) row) : null;
    }

    @SuppressWarnings("unchecked")
    private static PopupLayoutResponse popupLayout(Map<String, Object> source) {
        Object value = source.get("PopupLayout");
        return value instanceof Map<?, ?> row ? PopupLayoutResponse.from((Map<String, Object>) row) : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(Map.class::isInstance)
                .map(valueRow -> (Map<String, Object>) valueRow).toList();
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Integer integer(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }
}
