package com.webcore.api.core.metadata;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Schema-owned rows used to build the Core runtime snapshot.  These are kept
 * separate from API contracts: {@link #values()} is only the compatibility
 * boundary for the legacy snapshot builder while it is being migrated.
 */
public final class RuntimeMetadataRows {
    private RuntimeMetadataRows() { }

    public interface Row {
        Map<String, Object> values();
    }

    private static Map<String, Object> mapValues(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return Collections.unmodifiableMap(result);
    }

    public record DefApplication(Integer appid, String appname, String constr, String isdefault, String status, String serviceuri, Integer usertype) implements Row {
        public static DefApplication from(ResultSet rs) throws SQLException { return new DefApplication(nullableInteger(rs, "appid"), rs.getString("appname"), rs.getString("constr"), rs.getString("isdefault"), rs.getString("status"), rs.getString("serviceuri"), nullableInteger(rs, "usertype")); }
        public Map<String, Object> values() { return mapValues("appid", appid, "appname", appname, "constr", constr, "isdefault", isdefault, "status", status, "serviceuri", serviceuri, "usertype", usertype); }
    }

    public record DefModuleValidation(String modid, String submod, String validateid, String fldname, String validaterule, String validatelevel, String validateImmediate) implements Row {
        public static DefModuleValidation from(ResultSet rs) throws SQLException { return new DefModuleValidation(rs.getString("modid"), rs.getString("submod"), rs.getString("validateid"), rs.getString("fldname"), rs.getString("validaterule"), rs.getString("validatelevel"), rs.getString("validate_immediate")); }
        public Map<String, Object> values() { return mapValues("modid", modid, "submod", submod, "validateid", validateid, "fldname", fldname, "validaterule", validaterule, "validatelevel", validatelevel, "validate_immediate", validateImmediate); }
    }

    public record DefError(BigDecimal errcode, String errname) implements Row {
        public static DefError from(ResultSet rs) throws SQLException { return new DefError(rs.getBigDecimal("errcode"), rs.getString("errname")); }
        public Map<String, Object> values() { return mapValues("errcode", errcode, "errname", errname); }
    }

    public record DefValidation(String validatename, String regexmatch, String storevalidate, String isnumber, String numisinteger, BigDecimal numroundunit, BigDecimal numminvalue, BigDecimal nummaxvalue, String numnotequal, Integer minlength, Integer maxlength, String clientvalidate, String handlerTarget) implements Row {
        public static DefValidation from(ResultSet rs) throws SQLException { return new DefValidation(rs.getString("validatename"), rs.getString("regexmatch"), rs.getString("storevalidate"), rs.getString("isnumber"), rs.getString("numisinteger"), rs.getBigDecimal("numroundunit"), rs.getBigDecimal("numminvalue"), rs.getBigDecimal("nummaxvalue"), rs.getString("numnotequal"), nullableInteger(rs, "minlength"), nullableInteger(rs, "maxlength"), rs.getString("clientvalidate"), rs.getString("handler_target")); }
        public Map<String, Object> values() { return mapValues("validatename", validatename, "regexmatch", regexmatch, "storevalidate", storevalidate, "isnumber", isnumber, "numisinteger", numisinteger, "numroundunit", numroundunit, "numminvalue", numminvalue, "nummaxvalue", nummaxvalue, "numnotequal", numnotequal, "minlength", minlength, "maxlength", maxlength, "clientvalidate", clientvalidate, "handler_target", handlerTarget); }
    }

    public record DefCache(String cacheId, String cacheType, String cacheObject) implements Row {
        public static DefCache from(ResultSet rs) throws SQLException { return new DefCache(rs.getString("cache_id"), rs.getString("cache_type"), rs.getString("cache_object")); }
        public Map<String, Object> values() { return mapValues("cache_id", cacheId, "cache_type", cacheType, "cache_object", cacheObject); }
    }

    public record ApiSecurityRule(Long id, String pathPattern, String httpMethod, Boolean allowAnonymous, Boolean active) implements Row {
        public static ApiSecurityRule from(ResultSet rs) throws SQLException { return new ApiSecurityRule(nullableLong(rs, "id"), rs.getString("path_pattern"), rs.getString("http_method"), nullableBoolean(rs, "allow_anonymous"), nullableBoolean(rs, "active")); }
        public Map<String, Object> values() { return mapValues("id", id, "path_pattern", pathPattern, "http_method", httpMethod, "allow_anonymous", allowAnonymous, "active", active); }
    }

    public record DefCode(String cdtype, String cdname, String cdvalue, String cdvaluename, BigDecimal cdposition, String cddesc) implements Row {
        public static DefCode from(ResultSet rs) throws SQLException { return new DefCode(rs.getString("cdtype"), rs.getString("cdname"), rs.getString("cdvalue"), rs.getString("cdvaluename"), rs.getBigDecimal("cdposition"), rs.getString("cddesc")); }
        public Map<String, Object> values() { return mapValues("cdtype", cdtype, "cdname", cdname, "cdvalue", cdvalue, "cdvaluename", cdvaluename, "cdposition", cdposition, "cddesc", cddesc); }
    }

    public record DefLanguage(String langid, String langname, String langvalue, String modifier, String status) implements Row {
        public static DefLanguage from(ResultSet rs) throws SQLException { return new DefLanguage(rs.getString("langid"), rs.getString("langname"), rs.getString("langvalue"), rs.getString("modifier"), rs.getString("status")); }
        public Map<String, Object> values() { return mapValues("langid", langid, "langname", langname, "langvalue", langvalue, "modifier", modifier, "status", status); }
    }

    public record DefModule(String modid, String modtype, String submod, String modname, String role, String uitype, String userhide, String usermax, String userclose, String execmode, String startmode, String createby, Timestamp createdate, Timestamp importdate, Timestamp lastmodify, Timestamp exportdate, String grmod, String loadhandler, String closehandler, String activitylog, String sysvisible, String caEncrypt, String caSignMode, String caSignSaveMode, BigDecimal appid, String loopCallback) implements Row {
        public static DefModule from(ResultSet rs) throws SQLException { return new DefModule(rs.getString("modid"), rs.getString("modtype"), rs.getString("submod"), rs.getString("modname"), rs.getString("role"), rs.getString("uitype"), rs.getString("userhide"), rs.getString("usermax"), rs.getString("userclose"), rs.getString("execmode"), rs.getString("startmode"), rs.getString("createby"), rs.getTimestamp("createdate"), rs.getTimestamp("importdate"), rs.getTimestamp("lastmodify"), rs.getTimestamp("exportdate"), rs.getString("grmod"), rs.getString("loadhandler"), rs.getString("closehandler"), rs.getString("activitylog"), rs.getString("sysvisible"), rs.getString("ca_encrypt"), rs.getString("ca_sign_mode"), rs.getString("ca_sign_save_mode"), rs.getBigDecimal("appid"), rs.getString("loop_callback")); }
        public Map<String, Object> values() { return mapValues("modid", modid, "modtype", modtype, "submod", submod, "modname", modname, "role", role, "uitype", uitype, "userhide", userhide, "usermax", usermax, "userclose", userclose, "execmode", execmode, "startmode", startmode, "createby", createby, "createdate", createdate, "importdate", importdate, "lastmodify", lastmodify, "exportdate", exportdate, "grmod", grmod, "loadhandler", loadhandler, "closehandler", closehandler, "activitylog", activitylog, "sysvisible", sysvisible, "ca_encrypt", caEncrypt, "ca_sign_mode", caSignMode, "ca_sign_save_mode", caSignSaveMode, "appid", appid, "loop_callback", loopCallback); }
    }

    public record DefModuleField(String fldid, String modid, String submod, String fldgroup, String fldname, String paramname, String ctrltype, String defaultvalue, String listsource, String callback, String fldtype, String imptype, String scdtype, String fldformat, String popupmode, String tabstop, String rov, String roa, String roe, String gos, String fos, String icononly, String textalign, Short fixedwidth, String nullable, String customscd, String whereextension, String holdcolumn, String mergecell, String textcase, String columnsort, String fontbold, String serialtarget, String activitylog, String sysvisible, String unboundExpression, Integer maxlength, String linkMod, String allowexp, String callbackMode, String iconName, BigDecimal columnIndex) implements Row {
        public static DefModuleField from(ResultSet rs) throws SQLException { return new DefModuleField(rs.getString("fldid"), rs.getString("modid"), rs.getString("submod"), rs.getString("fldgroup"), rs.getString("fldname"), rs.getString("paramname"), rs.getString("ctrltype"), rs.getString("defaultvalue"), rs.getString("listsource"), rs.getString("callback"), rs.getString("fldtype"), rs.getString("imptype"), rs.getString("scdtype"), rs.getString("fldformat"), rs.getString("popupmode"), rs.getString("tabstop"), rs.getString("rov"), rs.getString("roa"), rs.getString("roe"), rs.getString("gos"), rs.getString("fos"), rs.getString("icononly"), rs.getString("textalign"), nullableShort(rs, "fixedwidth"), rs.getString("nullable"), rs.getString("customscd"), rs.getString("whereextension"), rs.getString("holdcolumn"), rs.getString("mergecell"), rs.getString("textcase"), rs.getString("columnsort"), rs.getString("fontbold"), rs.getString("serialtarget"), rs.getString("activitylog"), rs.getString("sysvisible"), rs.getString("unbound_expression"), nullableInteger(rs, "maxlength"), rs.getString("link_mod"), rs.getString("allowexp"), rs.getString("callback_mode"), rs.getString("icon_name"), rs.getBigDecimal("column_index")); }
        public Map<String, Object> values() { return mapValues("fldid", fldid, "modid", modid, "submod", submod, "fldgroup", fldgroup, "fldname", fldname, "paramname", paramname, "ctrltype", ctrltype, "defaultvalue", defaultvalue, "listsource", listsource, "callback", callback, "fldtype", fldtype, "imptype", imptype, "scdtype", scdtype, "fldformat", fldformat, "popupmode", popupmode, "tabstop", tabstop, "rov", rov, "roa", roa, "roe", roe, "gos", gos, "fos", fos, "icononly", icononly, "textalign", textalign, "fixedwidth", fixedwidth, "nullable", nullable, "customscd", customscd, "whereextension", whereextension, "holdcolumn", holdcolumn, "mergecell", mergecell, "textcase", textcase, "columnsort", columnsort, "fontbold", fontbold, "serialtarget", serialtarget, "activitylog", activitylog, "sysvisible", sysvisible, "unbound_expression", unboundExpression, "maxlength", maxlength, "link_mod", linkMod, "allowexp", allowexp, "callback_mode", callbackMode, "icon_name", iconName, "column_index", columnIndex); }
    }

    private static Short nullableShort(ResultSet rs, String name) throws SQLException { short value = rs.getShort(name); return rs.wasNull() ? null : value; }
    private static Integer nullableInteger(ResultSet rs, String name) throws SQLException { int value = rs.getInt(name); return rs.wasNull() ? null : value; }
    private static Long nullableLong(ResultSet rs, String name) throws SQLException { long value = rs.getLong(name); return rs.wasNull() ? null : value; }
    private static Boolean nullableBoolean(ResultSet rs, String name) throws SQLException { boolean value = rs.getBoolean(name); return rs.wasNull() ? null : value; }

    public record DefModuleButton(String modid, String submod, String btnid, String btnname, String btngroup, String callmodid, String callsubmod, String uiType, String begingroup, String showconfirm, String multiexec, String ontoolbar, String oncontext, String parammode, String visibled, String btnsource, String btnParams) implements Row {
        public static DefModuleButton from(ResultSet rs) throws SQLException { return new DefModuleButton(rs.getString("modid"), rs.getString("submod"), rs.getString("btnid"), rs.getString("btnname"), rs.getString("btngroup"), rs.getString("callmodid"), rs.getString("callsubmod"), rs.getString("ui_type"), rs.getString("begingroup"), rs.getString("showconfirm"), rs.getString("multiexec"), rs.getString("ontoolbar"), rs.getString("oncontext"), rs.getString("parammode"), rs.getString("visibled"), rs.getString("btnsource"), rs.getString("btn_params")); }
        public Map<String, Object> values() { return mapValues("modid", modid, "submod", submod, "btnid", btnid, "btnname", btnname, "btngroup", btngroup, "callmodid", callmodid, "callsubmod", callsubmod, "ui_type", uiType, "begingroup", begingroup, "showconfirm", showconfirm, "multiexec", multiexec, "ontoolbar", ontoolbar, "oncontext", oncontext, "parammode", parammode, "visibled", visibled, "btnsource", btnsource, "btn_params", btnParams); }
    }

    public record DefModuleButtonParameter(String modid, String btnname, String fldname, String colname, String value, String conditionname, BigDecimal priority, String buttonsourcefield) implements Row {
        public static DefModuleButtonParameter from(ResultSet rs) throws SQLException { return new DefModuleButtonParameter(rs.getString("modid"), rs.getString("btnname"), rs.getString("fldname"), rs.getString("colname"), rs.getString("value"), rs.getString("conditionname"), rs.getBigDecimal("priority"), rs.getString("buttonsourcefield")); }
        public Map<String, Object> values() { return mapValues("modid", modid, "btnname", btnname, "fldname", fldname, "colname", colname, "value", value, "conditionname", conditionname, "priority", priority, "buttonsourcefield", buttonsourcefield); }
    }

    public record ModSearch(String modid, String queryformat, String groupbox, String showgrpcol, String fullwidth, String showcheckbox, String pagemode, String statisticquery, String autorefresh, String autofitwidth, String defaultbutton, String commithandler, String searchOnload) implements Row {
        public static ModSearch from(ResultSet rs) throws SQLException { return new ModSearch(rs.getString("modid"), rs.getString("queryformat"), rs.getString("groupbox"), rs.getString("showgrpcol"), rs.getString("fullwidth"), rs.getString("showcheckbox"), rs.getString("pagemode"), rs.getString("statisticquery"), rs.getString("autorefresh"), rs.getString("autofitwidth"), rs.getString("defaultbutton"), rs.getString("commithandler"), rs.getString("search_onload")); }
        public Map<String, Object> values() { return mapValues("modid", modid, "queryformat", queryformat, "groupbox", groupbox, "showgrpcol", showgrpcol, "fullwidth", fullwidth, "showcheckbox", showcheckbox, "pagemode", pagemode, "statisticquery", statisticquery, "autorefresh", autorefresh, "autofitwidth", autofitwidth, "defaultbutton", defaultbutton, "commithandler", commithandler, "search_onload", searchOnload); }
    }

    public record ModMaintain(String modid, String submod, String commitHandler, String repeatInput, String showSuccess) implements Row {
        public static ModMaintain from(ResultSet rs) throws SQLException { return new ModMaintain(rs.getString("modid"), rs.getString("submod"), rs.getString("commit_handler"), rs.getString("repeat_input"), rs.getString("show_success")); }
        public Map<String, Object> values() { return mapValues("modid", modid, "submod", submod, "commit_handler", commitHandler, "repeat_input", repeatInput, "show_success", showSuccess); }
    }
}
