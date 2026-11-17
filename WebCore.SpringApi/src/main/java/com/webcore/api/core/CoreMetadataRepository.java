package com.webcore.api.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.util.function.Supplier;

import com.webcore.api.core.metadata.RuntimeMetadataRows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.webcore.api.database.CoreDataSourceResolver;
import org.springframework.stereotype.Repository;

/** Bootstrap reads def_source; remaining metadata comes from DBCORE_<database>. */
@Repository
public class CoreMetadataRepository {
    private static final Logger log = LoggerFactory.getLogger(CoreMetadataRepository.class);
    private final JdbcTemplate jdbcTemplate;
    private final CoreDataSourceResolver coreSource;

    public CoreMetadataRepository(JdbcTemplate jdbcTemplate, CoreDataSourceResolver coreSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.coreSource = coreSource;
    }
    private JdbcTemplate coreDb() { return new JdbcTemplate(coreSource.resolve()); }

    public List<ComponentRow> components() {
        String provider = coreSource.provider();
        String deleted = "POSTGRES".equals(provider) ? "COALESCE(is_deleted, false) = false" : "COALESCE(is_deleted, 0) = 0";
        return coreDb().query("""
                SELECT code, icon, type
                FROM def_component
                WHERE %s
                ORDER BY code
                """.formatted(deleted), (rs, ignored) -> new ComponentRow(rs.getString("code"), rs.getString("icon"),
                rs.getString("type")));
    }

    /** Component card labels are stored in legacy DEFLANG, not in def_component.language. */
    public List<ComponentLanguageRow> componentLanguages() {
        return coreDb().query("""
                SELECT langid, langname, langvalue
                FROM core.deflang
                WHERE COALESCE(status, 'A') = 'A'
                  AND (langname LIKE 'COMPONENT$%$NAME' OR langname LIKE 'COMPONENT$%$DESCRIPTION')
                ORDER BY langid, langname
                """, (rs, ignored) -> new ComponentLanguageRow(rs.getString("langid"), rs.getString("langname"),
                rs.getString("langvalue")));
    }

    /** Component group definitions are live DEFCODE rows, ordered by CDPOSITION. */
    public List<ComponentTypeRow> componentTypes() {
        return coreDb().query("""
                SELECT cdvalue, cdvaluename, cdposition
                FROM core.defcode
                WHERE cdtype = 'DEFCOMPONENT'
                  AND cdname = 'TYPE'
                ORDER BY cdposition, cdvalue
                """, (rs, ignored) -> new ComponentTypeRow(rs.getString("cdvalue"),
                rs.getString("cdvaluename"), rs.getBigDecimal("cdposition")));
    }

    /** Captions for DEFCODE(DEFCOMPONENT, TYPE) values. */
    public List<ComponentTypeLanguageRow> componentTypeLanguages() {
        return coreDb().query("""
                SELECT langid, langname, langvalue
                FROM core.deflang
                WHERE COALESCE(status, 'A') = 'A'
                  AND langname LIKE 'CODE$DEFCOMPONENT$TYPE.%'
                ORDER BY langid, langname
                """, (rs, ignored) -> new ComponentTypeLanguageRow(rs.getString("langid"), rs.getString("langname"),
                rs.getString("langvalue")));
    }

    public List<MenuRow> menus() {
        return coreDb().query("""
                SELECT component, menu_id, owner_menu_id, icon, module_id, position, param, language
                FROM def_menu
                ORDER BY component, position, menu_id
                """, (rs, ignored) -> new MenuRow(rs.getString("component"), rs.getString("menu_id"), rs.getString("owner_menu_id"),
                rs.getString("icon"), rs.getString("module_id"), rs.getInt("position"), rs.getString("param"), rs.getString("language")));
    }

    /** App shell menu declaration used by the Next.js/AdminLTE client. */
    public List<AppMenuRow> appMenus(String componentCode) {
        return coreDb().query("""
                SELECT
                    "menu_id",
                    "component_code",
                    "menu_key",
                    "parent_menu_id",
                    "node_type",
                    "text",
                    "text_i18n"::text AS "text_i18n",
                    "href",
                    "target",
                    "icon",
                    "icon_color",
                    "badge",
                    "badge_color",
                    "position"
                FROM "core"."app_menu"
                WHERE "component_code" = ?
                  AND "is_enabled" = TRUE
                ORDER BY "position", "menu_id"
                """, (rs, ignored) -> new AppMenuRow(rs.getLong("menu_id"), rs.getString("component_code"), rs.getString("menu_key"),
                nullableLong(rs, "parent_menu_id"), rs.getString("node_type"), rs.getString("text"), rs.getString("text_i18n"),
                rs.getString("href"), rs.getString("target"), rs.getString("icon"), rs.getString("icon_color"), rs.getString("badge"),
                rs.getString("badge_color"), rs.getInt("position")), componentCode);
    }

    public List<LanguageRow> languages() {
        return coreDb().query("""
                SELECT langid AS lang_id, langname AS lang_key, langvalue AS lang_value
                FROM "core"."deflang"
                WHERE COALESCE(status, 'A') = 'A'
                ORDER BY langid, langname
                """, (rs, ignored) -> new LanguageRow(rs.getString("lang_id"), rs.getString("lang_key"), rs.getString("lang_value")));
    }

    public List<RolePolicyRow> rolePolicies() {
        return new JdbcTemplate(coreSource.resolve("DBCORE_auth")).query("""
                SELECT role_name_ref, role_version, role_policy_priority, default_data_scope, policy_permission
                FROM v_role_policy_active
                ORDER BY role_name_ref, role_version
                """, (rs, ignored) -> new RolePolicyRow(rs.getString("role_name_ref"), rs.getInt("role_version"),
                rs.getInt("role_policy_priority"), rs.getString("default_data_scope"), rs.getString("policy_permission")));
    }

    public List<ModuleRow> modules() {
        return coreDb().query("""
                SELECT mod_id, mod_name, config, config_ui, language, metadata, status
                FROM def_mod
                WHERE COALESCE(status, 'active') = 'active'
                ORDER BY mod_id
                """, (rs, ignored) -> new ModuleRow(rs.getString("mod_id"), rs.getString("mod_name"), rs.getString("config"),
                rs.getString("config_ui"), rs.getString("language"), rs.getString("metadata"), rs.getString("status")));
    }

    public List<SourceRow> sources() {
        return jdbcTemplate.query("""
                SELECT source_name, type, config
                FROM def_source
                ORDER BY source_name
                """, (rs, ignored) -> new SourceRow(rs.getString("source_name"), rs.getString("type"), rs.getString("config")));
    }

    /** sys_var is part of the Core snapshot: workers must not query it per request/cycle. */
    public List<SystemValueRow> systemValues() {
        return coreDb().query("SELECT group_name, var_name, var_value FROM sys_var ORDER BY group_name, var_name",
                (rs, ignored) -> new SystemValueRow(rs.getString("group_name"), rs.getString("var_name"), rs.getString("var_value")));
    }

    public List<RuntimeMetadataRows.DefApplication> apps() { return coreDb().query("SELECT appid, appname, constr, isdefault, status, serviceuri, usertype FROM DEFAPPS", (rs, ignored) -> RuntimeMetadataRows.DefApplication.from(rs)); }
    public List<RuntimeMetadataRows.DefCode> codes() { return coreDb().query("SELECT cdtype, cdname, cdvalue, cdvaluename, cdposition, cddesc FROM DEFCODE", (rs, ignored) -> RuntimeMetadataRows.DefCode.from(rs)); }
    public List<RuntimeMetadataRows.DefModule> moduleDefinitions() { return coreDb().query("SELECT modid, modtype, submod, modname, role, uitype, userhide, usermax, userclose, execmode, startmode, createby, createdate, importdate, lastmodify, exportdate, grmod, loadhandler, closehandler, activitylog, sysvisible, ca_encrypt, ca_sign_mode, ca_sign_save_mode, appid, loop_callback FROM DEFMOD", (rs, ignored) -> RuntimeMetadataRows.DefModule.from(rs)); }
    public List<RuntimeMetadataRows.ModSearch> moduleSearches() { return coreDb().query("SELECT modid, queryformat, groupbox, showgrpcol, fullwidth, showcheckbox, pagemode, statisticquery, autorefresh, autofitwidth, defaultbutton, commithandler, search_onload FROM MODSEARCH", (rs, ignored) -> RuntimeMetadataRows.ModSearch.from(rs)); }
    public List<RuntimeMetadataRows.ModMaintain> moduleMaintains() { return coreDb().query("SELECT modid, submod, commit_handler, repeat_input, show_success FROM MODMAINTAIN", (rs, ignored) -> RuntimeMetadataRows.ModMaintain.from(rs)); }
    public List<Map<String, Object>> moduleAlerts() { return metadataRows("SELECT modid, submod, refresh_handler, refresh_interval, click_handler, callmodid, callsubmod FROM MODALERT"); }
    public List<Map<String, Object>> moduleCharts() { return metadataRows("SELECT modid, submod, chart_handler, axisx_source, refresh_interval FROM MODCHART"); }
    public List<Map<String, Object>> moduleExcelReports() { return metadataRows("SELECT modid, submod, load_handler, commit_handler, allow_open, allow_save, allow_download FROM MODEXCELREPORT"); }
    public List<Map<String, Object>> moduleExecProcedures() { return metadataRows("SELECT modid, submod, commit_handler, showsuccess FROM MODEXECPROC"); }
    public List<Map<String, Object>> moduleImports() { return metadataRows("SELECT modid, importstore FROM MODIMPORT"); }
    public List<Map<String, Object>> moduleReports() { return metadataRows("SELECT modid, report_handler, report_size, report_orient, report_language, rpgroup, print_onload FROM MODREPORT"); }
    public List<Map<String, Object>> moduleSwitches() { return metadataRows("SELECT modid, switchstore FROM MODSWITCH"); }
    public List<Map<String, Object>> moduleStatistics() { return metadataRows("SELECT modid, storename, pagemode, fullwidth, statistic_storename, autofitwidth FROM MODSTATISTICS"); }
    public List<Map<String, Object>> moduleTransactions() { return metadataRows("SELECT modid, repeat_input, show_success, check_handler, exec_handler, msg_amt, msg_sicode, msg_micode, msg_txnote, chkid, offid, cfrid, msg_comicode, msg_acctno, deleted, status, isparent, child_modid, ca_confirm_mode, confirm_show, confirm_modid, confirm_submod, msg_coacctno, reload, print_report_id, auto_print FROM MODTRANS"); }
    public List<RuntimeMetadataRows.DefModuleField> moduleFields() { return coreDb().query("SELECT fldid, modid, submod, fldgroup, fldname, paramname, ctrltype, defaultvalue, listsource, callback, fldtype, imptype, scdtype, fldformat, popupmode, tabstop, rov, roa, roe, gos, fos, icononly, textalign, fixedwidth, nullable, customscd, whereextension, holdcolumn, mergecell, textcase, columnsort, fontbold, serialtarget, activitylog, sysvisible, unbound_expression, maxlength, link_mod, allowexp, callback_mode, icon_name, column_index FROM DEFMODFLD", (rs, ignored) -> RuntimeMetadataRows.DefModuleField.from(rs)); }
    public List<RuntimeMetadataRows.DefLanguage> languageDefinitions() { return coreDb().query("SELECT langid, langname, langvalue, modifier, status FROM DEFLANG", (rs, ignored) -> RuntimeMetadataRows.DefLanguage.from(rs)); }
    public List<Map<String, Object>> layouts() { return metadataRows("SELECT langid, layoutname, layoutdata, modifier, status FROM DEFLAYOUT"); }
    public List<Map<String, Object>> batchSteps() { return metadataRows("SELECT modid, stepid, stepname, callmodid, callsubmod, batch_handler, step_type, description, afcheck_handler FROM DEFBATCHSTEP"); }
    public List<Map<String, Object>> groupSummaries() { return metadataRows("SELECT modid, submod, groupid, groupname, fldname, summarytype, showheader, footercolumn FROM DEFGROUPSUMMARY"); }
    public List<RuntimeMetadataRows.DefModuleButton> moduleButtons() { return coreDb().query("SELECT modid, submod, btnid, btnname, btngroup, callmodid, callsubmod, ui_type, begingroup, showconfirm, multiexec, ontoolbar, oncontext, parammode, visibled, btnsource, btn_params FROM DEFMODBTN", (rs, ignored) -> RuntimeMetadataRows.DefModuleButton.from(rs)); }
    public List<RuntimeMetadataRows.DefModuleButtonParameter> moduleButtonParameters() { return coreDb().query("SELECT modid, btnname, fldname, colname, value, conditionname, priority, buttonsourcefield FROM DEFMODBTNPARAM", (rs, ignored) -> RuntimeMetadataRows.DefModuleButtonParameter.from(rs)); }
    public List<RuntimeMetadataRows.DefModuleValidation> moduleValidations() { return coreDb().query("SELECT modid, submod, validateid, fldname, validaterule, validatelevel, validate_immediate FROM DEFMODVALIDATE", (rs, ignored) -> RuntimeMetadataRows.DefModuleValidation.from(rs)); }
    public List<RuntimeMetadataRows.DefError> errors() { return coreDb().query("SELECT errcode, errname FROM DEFERROR", (rs, ignored) -> RuntimeMetadataRows.DefError.from(rs)); }
    public List<RuntimeMetadataRows.DefValidation> validations() { return coreDb().query("SELECT validatename, regexmatch, storevalidate, isnumber, numisinteger, numroundunit, numminvalue, nummaxvalue, numnotequal, minlength, maxlength, clientvalidate, handler_target FROM DEFVALIDATE", (rs, ignored) -> RuntimeMetadataRows.DefValidation.from(rs)); }
    public List<RuntimeMetadataRows.DefCache> cacheDefinitions() { return coreDb().query("SELECT cache_id, cache_type, cache_object FROM DEFCACHE", (rs, ignored) -> RuntimeMetadataRows.DefCache.from(rs)); }
    public List<RuntimeMetadataRows.ApiSecurityRule> cacheapisecurityrule() { return coreDb().query("SELECT id, path_pattern, http_method, allow_anonymous, active FROM api_security_rule", (rs, ignored) -> RuntimeMetadataRows.ApiSecurityRule.from(rs)); }
    
    /** System metadata tables loaded through the same Core source/Search Path as def_mod and deflang. */
    public Map<String, List<Map<String, Object>>> systemMetadata() {
        return systemMetadata(moduleFields(), languageDefinitions());
    }

    /**
     * Compatibility projection for consumers not migrated yet.  The typed rows
     * are supplied by the caller so one Core snapshot never mixes two reads of
     * DEFMODFLD or DEFLANG.
     */
    public Map<String, List<Map<String, Object>>> systemMetadata(
            List<RuntimeMetadataRows.DefModuleField> moduleFields,
            List<RuntimeMetadataRows.DefLanguage> languageDefinitions) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("DEFAPPS", values(timed("DEFAPPS", this::apps)));
        result.put("DEFCODE", values(timed("DEFCODE", this::codes)));
        result.put("DEFMOD", values(timed("DEFMOD", this::moduleDefinitions)));
        result.put("MODSEARCH", values(timed("MODSEARCH", this::moduleSearches)));
        result.put("MODMAINTAIN", values(timed("MODMAINTAIN", this::moduleMaintains)));
        result.put("MODALERT", timed("MODALERT", this::moduleAlerts));
        result.put("MODCHART", timed("MODCHART", this::moduleCharts));
        result.put("MODEXCELREPORT", timed("MODEXCELREPORT", this::moduleExcelReports));
        result.put("MODEXECPROC", timed("MODEXECPROC", this::moduleExecProcedures));
        result.put("MODIMPORT", timed("MODIMPORT", this::moduleImports));
        result.put("MODREPORT", timed("MODREPORT", this::moduleReports));
        result.put("MODSWITCH", timed("MODSWITCH", this::moduleSwitches));
        result.put("MODSTATISTICS", timed("MODSTATISTICS", this::moduleStatistics));
        result.put("MODTRANS", timed("MODTRANS", this::moduleTransactions));
        result.put("DEFMODFLD", values(moduleFields));
        result.put("DEFLANG", values(languageDefinitions));
        result.put("DEFLAYOUT", timed("DEFLAYOUT", this::layouts));
        result.put("DEFBATCHSTEP", timed("DEFBATCHSTEP", this::batchSteps));
        result.put("DEFGROUPSUMMARY", timed("DEFGROUPSUMMARY", this::groupSummaries));
        result.put("DEFMODBTN", values(timed("DEFMODBTN", this::moduleButtons)));
        result.put("DEFMODBTNPARAM", values(timed("DEFMODBTNPARAM", this::moduleButtonParameters)));
        result.put("DEFMODVALIDATE", values(timed("DEFMODVALIDATE", this::moduleValidations)));
        result.put("DEFERROR", values(timed("DEFERROR", this::errors)));
        result.put("DEFVALIDATE", values(timed("DEFVALIDATE", this::validations)));
        result.put("DEFCACHE", values(timed("DEFCACHE", this::cacheDefinitions)));
        result.put("API_SECURITY_RULE", values(timed("API_SECURITY_RULE", this::cacheapisecurityrule)));

        return Map.copyOf(result);
    }

    private <T> T timed(String table, Supplier<T> operation) {
        long startedAt = System.nanoTime();
        T result = operation.get();
        log.info("core_system_metadata_table_timing table={} elapsedMs={}", table, (System.nanoTime() - startedAt) / 1_000_000);
        return result;
    }

    /** Ordered metadata payload used only by the opt-in invalidation monitor. */
    public List<Object> revisionPayload() { return List.of(components(), menus(), languages(), modules(), sources(), systemValues(), rolePolicies(), systemMetadata()); }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
    private List<Map<String, Object>> metadataRows(String sql) {
        return coreDb().query(sql, (rs, ignored) -> {
            ResultSetMetaData metadata = rs.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) row.put(metadata.getColumnLabel(index), rs.getObject(index));
            return Collections.unmodifiableMap(row);
        });
    }
    private static List<Map<String, Object>> values(List<? extends RuntimeMetadataRows.Row> rows) { return rows.stream().map(RuntimeMetadataRows.Row::values).toList(); }
    public record ComponentRow(String code, String icon, String type) { }
    public record ComponentLanguageRow(String languageId, String languageKey, String languageValue) { }
    public record ComponentTypeRow(String value, String name, BigDecimal position) { }
    public record ComponentTypeLanguageRow(String languageId, String languageKey, String languageValue) { }
    public record MenuRow(String component, String menuId, String ownerMenuId, String icon, String moduleId, int position, String param, String language) { }
    public record AppMenuRow(long menuId, String componentCode, String menuKey, Long parentMenuId, String nodeType, String text,
                             String textI18n, String href, String target, String icon, String iconColor, String badge, String badgeColor, int position) { }
    public record LanguageRow(String languageId, String languageKey, String languageValue) { }
    public record RolePolicyRow(String roleName, int roleVersion, int priority, String defaultDataScope, String policyPermission) { }
    public record ModuleRow(String moduleId, String moduleName, String config, String configUi, String language, String metadata, String status) { }
    public record SourceRow(String sourceName, String type, String config) { }
    public record SystemValueRow(String groupName, String variableName, String value) { }
}
