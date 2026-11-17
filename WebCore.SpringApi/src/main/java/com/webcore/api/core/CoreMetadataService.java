package com.webcore.api.core;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.webcore.api.auth.AuthRepository;
import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.MetadataLocalizationResponses;
import com.webcore.api.core.metadata.ModuleMetadataSnapshot;
import com.webcore.api.core.metadata.ModuleMetadataSnapshotBuilder;
import com.webcore.api.core.metadata.RuntimeMetadataRows;
import com.webcore.api.runtime.ModuleActionExecutor;
import com.webcore.api.runtime.ModuleRuntimeCache;
import com.webcore.api.runtime.OpenApiDocumentCache;
import com.webcore.api.runtime.RoutineMetadataReader;
import com.webcore.api.runtime.RuntimeCacheMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Immutable in-memory view of the metadata that is required immediately after login.
 * A reload replaces the whole snapshot, so requests never observe half-loaded core data.
 */
@Service
@Order(0)
public class CoreMetadataService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CoreMetadataService.class);
    private static final Pattern LEGACY_SEARCH_MODULE_PATH = Pattern.compile(
            "(?:^|/)legacy-search/([^/?#]+)(?:/|[?#]|$)", Pattern.CASE_INSENSITIVE);
    private final CoreMetadataRepository repository;
    private final ObjectMapper objectMapper;
    private final RuntimeCacheMetrics cacheMetrics;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public CoreMetadataService(CoreMetadataRepository repository, ObjectMapper objectMapper, RuntimeCacheMetrics cacheMetrics) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.cacheMetrics = cacheMetrics;
    }

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    /** Loads a complete Core snapshot without publishing it; used by cluster prepare. */
    public Snapshot prepareCore() {
        Timer.Sample warmup = cacheMetrics.warmupStart();
        long startedAt = System.nanoTime();
        try {
        long stageStartedAt = System.nanoTime();
        List<RuntimeMetadataRows.DefApplication> applications = List.copyOf(repository.apps());
        logMetadataStage("applications", stageStartedAt);
        stageStartedAt = System.nanoTime();
        List<RuntimeMetadataRows.DefModuleField> moduleFields = List.copyOf(repository.moduleFields());
        logMetadataStage("moduleFields", stageStartedAt);
        stageStartedAt = System.nanoTime();
        List<RuntimeMetadataRows.DefLanguage> languageDefinitions = List.copyOf(repository.languageDefinitions());
        logMetadataStage("languageDefinitions", stageStartedAt);
        stageStartedAt = System.nanoTime();
        List<RuntimeMetadataRows.ApiSecurityRule> apiSecurityRules = List.copyOf(repository.cacheapisecurityrule());
        logMetadataStage("apiSecurityRules", stageStartedAt);
        stageStartedAt = System.nanoTime();
        Map<String, List<Map<String,Object>>> systemMetadata = immutableMetadataTables(repository.systemMetadata(moduleFields, languageDefinitions));
        logMetadataStage("systemMetadata", stageStartedAt);
        stageStartedAt = System.nanoTime();
        ModuleMetadataSnapshot formInfoCache = ModuleMetadataSnapshotBuilder.build(moduleFields, languageDefinitions, systemMetadata);
        logMetadataStage("formInfoCache", stageStartedAt);
        Map<String, List<Menu>> menusByComponent = new HashMap<>();
        stageStartedAt = System.nanoTime();
        for (CoreMetadataRepository.MenuRow row : repository.menus()) {
            String moduleId = blank(row.moduleId());
            Menu menu = new Menu(blank(row.component()), blank(row.menuId()), blank(row.ownerMenuId()), blank(row.icon()), moduleId, row.position(), blank(row.param()),
                    localizedJsonMap(row.language()), moduleType(systemMetadata, moduleId));
            if (!menu.component().isBlank() && !menu.menuId().isBlank()) {
                menusByComponent.computeIfAbsent(menu.component(), ignored -> new ArrayList<>()).add(menu);
            }
        }
        menusByComponent.replaceAll((key, value) -> value.stream()
                .sorted(Comparator.comparingInt(Menu::position).thenComparing(Menu::menuId, String.CASE_INSENSITIVE_ORDER)).toList());
        logMetadataStage("menus", stageStartedAt);

        Map<String, Map<String, String>> languages = new LinkedHashMap<>();

        // Global language dictionary is built only from active DEFLANG rows.
        // If DEFLANG contains only VN and EN, /api/language will expose only VN and EN.
        for (RuntimeMetadataRows.DefLanguage row : languageDefinitions) {
            String status = row.status();
            if (!"A".equalsIgnoreCase(status)) {
                continue;
            }

            String lang = normalizeLanguageKey(row.langid());
            String key = row.langname() == null ? "" : row.langname().trim();
            String value = row.langvalue() == null ? "" : row.langvalue().trim();

            if (lang.isBlank() || key.isBlank() || value.isBlank()) {
                continue;
            }

            languages
                    .computeIfAbsent(lang, ignored -> new LinkedHashMap<>())
                    .put(key, value);
        }

        Map<String, Module> modules = new HashMap<>();
        stageStartedAt = System.nanoTime();
        for (CoreMetadataRepository.ModuleRow row : repository.modules()) {
            Module module = new Module(row.moduleId(), row.moduleName(), jsonMap(row.config()), jsonMap(row.configUi()), localizedJsonMap(row.language()), jsonMap(row.metadata()));
            if (!module.modId().isBlank()) modules.put(module.modId().toLowerCase(Locale.ROOT), module);
        }
        logMetadataStage("modules", stageStartedAt);
        Map<String, Source> sources = new HashMap<>();
        stageStartedAt = System.nanoTime();
        for (CoreMetadataRepository.SourceRow row : repository.sources()) {
            Source source = new Source(row.sourceName(), row.type(), jsonMap(row.config()));
            if (!source.sourceName().isBlank()) sources.put(source.sourceName().toLowerCase(Locale.ROOT), source);
        }
        logMetadataStage("sources", stageStartedAt);
        Map<String, String> systemValues = new HashMap<>();
        stageStartedAt = System.nanoTime();
        for (CoreMetadataRepository.SystemValueRow row : repository.systemValues()) {
            String group = row.groupName();
            String name = row.variableName();
            if (group != null && !group.isBlank() && name != null && !name.isBlank()) systemValues.put((group + "$" + name).toLowerCase(Locale.ROOT), row.value());
        }
        logMetadataStage("systemValues", stageStartedAt);
        stageStartedAt = System.nanoTime();
        Map<String, RolePolicy> roles = loadRoles();
        logMetadataStage("roles", stageStartedAt);
        Snapshot base = new Snapshot(
                immutableMenuMap(menusByComponent),
                immutableLanguages(languages),
                Map.copyOf(modules),
                Map.copyOf(sources),
                Map.copyOf(systemValues),
                Map.copyOf(roles),
                systemMetadata,
                applications,
                moduleFields,
                languageDefinitions,
                apiSecurityRules,
                formInfoCache,
                ModuleRuntimeCache.Snapshot.empty(),
                Map.of(),
                RoutineMetadataReader.Snapshot.empty(),
                Instant.now());

        long metadataLoadMs = elapsedMillis(startedAt);
        ModuleRuntimeCache.Snapshot runtimeCache = ModuleRuntimeCache.build(base);
        long runtimeCacheBuildMs = elapsedMillis(startedAt) - metadataLoadMs;
        var openApiDocuments = OpenApiDocumentCache.prepare(base, objectMapper);
        long openApiBuildMs = elapsedMillis(startedAt) - metadataLoadMs - runtimeCacheBuildMs;
        var routineMetadata = RoutineMetadataReader.prepare(base);
        long routineMetadataBuildMs = elapsedMillis(startedAt) - metadataLoadMs - runtimeCacheBuildMs - openApiBuildMs;
        log.info("core_prepare_timing metadataLoadMs={} runtimeCacheBuildMs={} openApiBuildMs={} routineMetadataBuildMs={} totalMs={}",
                metadataLoadMs, runtimeCacheBuildMs, openApiBuildMs, routineMetadataBuildMs, elapsedMillis(startedAt));

        return new Snapshot(
                base.menusByComponent(),
                base.languages(),
                base.modules(),
                base.sources(),
                base.systemValues(),
                base.roles(),
                base.systemMetadata(),
                base.applications(),
                base.moduleFields(),
                base.languageDefinitions(),
                base.apiSecurityRules(),
                base.formInfoCache(),
                runtimeCache,
                openApiDocuments,
                routineMetadata,
                base.loadedAtUtc());
        } finally { cacheMetrics.warmup("core-snapshot", warmup); }
    }

    private static long elapsedMillis(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }
    private static void logMetadataStage(String stage, long startedAt) {
        log.info("core_metadata_stage_timing stage={} elapsedMs={}", stage, elapsedMillis(startedAt));
    }

    public Snapshot reload() { return publish(prepareCore()); }

    /** Reload role policy only, publishing a complete replacement snapshot. */
    /** Loads role policy against the active immutable Core snapshot without publishing it. */
    public Snapshot prepareRoles() {
        Snapshot current = snapshot();
        return new Snapshot(current.menusByComponent(), current.languages(),
                current.modules(), current.sources(), current.systemValues(), Map.copyOf(loadRoles()), current.systemMetadata(), current.applications(), current.moduleFields(), current.languageDefinitions(), current.apiSecurityRules(), current.formInfoCache(), current.runtimeCache(), current.openApiDocuments(), current.routineMetadata(), Instant.now());
    }
    public Snapshot reloadRoles() { return publish(prepareRoles()); }
    /** Atomic publish boundary: requests see either the old or the fully built snapshot. */
    public Snapshot publish(Snapshot loaded) { snapshot.set(loaded); return loaded; }

    private Map<String, RolePolicy> loadRoles() {
        Map<String, RolePolicy> roles = new HashMap<>();
        for (CoreMetadataRepository.RolePolicyRow row : repository.rolePolicies()) {
            String role = row.roleName() == null ? "" : row.roleName().trim();
            if (role.isBlank()) continue;
            RolePolicy policy = roles.computeIfAbsent(role.toLowerCase(Locale.ROOT), ignored -> new RolePolicy());
            policy.version = Math.max(policy.version, row.roleVersion());
            policy.merge(parsePermissions(row.policyPermission() == null ? "" : row.policyPermission()), row.defaultDataScope());
        }
        return roles;
    }

    public Snapshot snapshot() { cacheMetrics.hit("core-snapshot"); return snapshot.get(); }

    public List<Component> visibleComponents(AuthService.TokenPayload payload) {
        List<Component> components = currentComponents();
        if (payload.isPrivilegedAccount()) return components;
        Set<String> granted = normalizedSet(payload.components());
        return components.stream().filter(component -> granted.contains(component.code().toLowerCase(Locale.ROOT))).toList();
    }
    // Không JWT - trả toàn bộ component
    public List<Component> publicComponents() {
        return currentComponents();
    }

    /** Component groups are managed live in DEFCODE(DEFCOMPONENT, TYPE). */
    public List<ComponentType> componentTypes() {
        Map<String, Map<String, Object>> translations = componentTypeTranslations();
        return repository.componentTypes().stream()
                .map(row -> new ComponentType(row.value(), row.name(), row.position(),
                        translations.getOrDefault(componentKey(row.value()), Map.of())))
                .toList();
    }

    /** Components are managed live; they are intentionally excluded from the metadata snapshot. */
    public int componentCount() {
        return currentComponents().size();
    }

    private List<Component> currentComponents() {
        Map<String, Map<String, Object>> translations = componentTranslations();
        return repository.components().stream()
                .map(row -> new Component(row.code(), row.icon(), row.type(),
                        translations.getOrDefault(componentKey(row.code()), Map.of())))
                .toList();
    }

    private Map<String, Map<String, Object>> componentTranslations() {
        Map<String, Map<String, Map<String, Object>>> values = new HashMap<>();
        for (CoreMetadataRepository.ComponentLanguageRow row : repository.componentLanguages()) {
            String key = blank(row.languageKey());
            String languageId = normalizeLanguageKey(row.languageId());
            String property = key.endsWith("$NAME") ? "name" : key.endsWith("$DESCRIPTION") ? "description" : "";
            if (!key.startsWith("COMPONENT$") || property.isBlank() || languageId.isBlank()) continue;

            String componentCode = componentKey(key.substring("COMPONENT$".length(), key.length() - property.length() - 1));
            String value = blank(row.languageValue());
            if (componentCode.isBlank() || value.isBlank()) continue;

            values.computeIfAbsent(componentCode, ignored -> new HashMap<>())
                    .computeIfAbsent(languageId, ignored -> new HashMap<>())
                    .put(property, value);
        }

        Map<String, Map<String, Object>> immutable = new HashMap<>();
        values.forEach((componentCode, byLanguage) -> {
            Map<String, Object> localized = new HashMap<>();
            byLanguage.forEach((languageId, text) -> localized.put(languageId, Map.copyOf(text)));
            immutable.put(componentCode, Map.copyOf(localized));
        });
        return Map.copyOf(immutable);
    }

    private Map<String, Map<String, Object>> componentTypeTranslations() {
        Map<String, Map<String, Map<String, Object>>> values = new HashMap<>();
        for (CoreMetadataRepository.ComponentTypeLanguageRow row : repository.componentTypeLanguages()) {
            String key = blank(row.languageKey());
            String languageId = normalizeLanguageKey(row.languageId());
            if (!key.startsWith("CODE$DEFCOMPONENT$TYPE.") || languageId.isBlank()) continue;

            String typeValue = componentKey(key.substring("CODE$DEFCOMPONENT$TYPE.".length()));
            String value = blank(row.languageValue());
            if (typeValue.isBlank() || value.isBlank()) continue;
            values.computeIfAbsent(typeValue, ignored -> new HashMap<>())
                    .computeIfAbsent(languageId, ignored -> new HashMap<>())
                    .put("name", value);
        }

        Map<String, Map<String, Object>> immutable = new HashMap<>();
        values.forEach((typeValue, byLanguage) -> {
            Map<String, Object> localized = new HashMap<>();
            byLanguage.forEach((languageId, text) -> localized.put(languageId, Map.copyOf(text)));
            immutable.put(typeValue, Map.copyOf(localized));
        });
        return Map.copyOf(immutable);
    }

    private static String componentKey(String value) {
        return blank(value).toUpperCase(Locale.ROOT);
    }

    public List<Menu> visibleMenus(String component, AuthService.TokenPayload payload) {
        String key = component == null ? "" : component.trim();
        if (key.isBlank() || (!payload.isPrivilegedAccount() && !normalizedSet(payload.components()).contains(key.toLowerCase(Locale.ROOT)))) return List.of();
        List<Menu> source = snapshot().menusByComponent().getOrDefault(key, List.of());
        if (payload.isPrivilegedAccount()) return source;
        Set<String> allowedModules = accessibleModules(payload.roles());
        if (allowedModules.isEmpty()) return List.of();

        Map<String, Menu> byId = new HashMap<>();
        for (Menu menu : source) byId.put(menu.menuId().toLowerCase(Locale.ROOT), menu);
        Set<String> visible = new HashSet<>();
        for (Menu menu : source) {
            if (!menu.moduleId().isBlank() && allowedModules.contains(menu.moduleId().toLowerCase(Locale.ROOT))) {
                visible.add(menu.menuId().toLowerCase(Locale.ROOT));
                String parent = menu.ownerMenuId();
                while (!parent.isBlank() && byId.containsKey(parent.toLowerCase(Locale.ROOT))) {
                    visible.add(parent.toLowerCase(Locale.ROOT));
                    parent = byId.get(parent.toLowerCase(Locale.ROOT)).ownerMenuId();
                }
            }
        }
        return source.stream().filter(menu -> visible.contains(menu.menuId().toLowerCase(Locale.ROOT)))
                .map(menu -> menu.moduleId().isBlank() || allowedModules.contains(menu.moduleId().toLowerCase(Locale.ROOT)) ? menu : menu.asGroup())
                .toList();
    }

    public Set<String> accessibleModules(List<AuthRepository.RoleRef> roles) {
        Set<String> result = new HashSet<>();
        for (AuthRepository.RoleRef role : roles) {
            RolePolicy policy = snapshot().roles().get(role.name().trim().toLowerCase(Locale.ROOT));
            if (policy != null && policy.version >= role.version()) result.addAll(policy.viewableModules);
        }
        return result;
    }

    /** Returns the declarative menu for the new client, filtered by View permission. */
    public List<AppMenu> visibleAppMenus(String component, AuthService.TokenPayload payload) {
        String componentCode = component == null ? "" : component.trim().toUpperCase(Locale.ROOT);
        if (componentCode.isBlank()
                || (!payload.isPrivilegedAccount()
                && !normalizedSet(payload.components()).contains(componentCode.toLowerCase(Locale.ROOT)))) {
            return List.of();
        }

        List<AppMenu> source = repository.appMenus(componentCode).stream()
                .map(row -> new AppMenu(row.menuId(), row.componentCode(), row.menuKey(), row.parentMenuId(), row.nodeType(),
                        row.text(), row.textI18n(), row.href(), row.target(), row.icon(), row.iconColor(), row.badge(),
                        row.badgeColor(), row.position()))
                .toList();
        if (payload.isPrivilegedAccount()) return source;

        Set<String> allowedModules = accessibleModules(payload.roles());
        Map<Long, AppMenu> byId = new HashMap<>();
        for (AppMenu menu : source) byId.put(menu.menuId(), menu);

        Set<Long> visible = new HashSet<>();
        for (AppMenu menu : source) {
            String moduleId = legacySearchModuleId(menu.href());
            // Groups have no route and are added only as ancestors of a visible
            // menu item, so the sidebar never renders an empty group.
            boolean isAppLevelLink = moduleId == null && menu.href() != null && !menu.href().isBlank();
            if (isAppLevelLink || (moduleId != null && allowedModules.contains(moduleId.toLowerCase(Locale.ROOT)))) {
                addMenuWithParents(menu, byId, visible);
            }
        }
        return source.stream().filter(menu -> visible.contains(menu.menuId())).toList();
    }

    private static String legacySearchModuleId(String href) {
        if (href == null || href.isBlank()) return null;
        Matcher match = LEGACY_SEARCH_MODULE_PATH.matcher(href);
        return match.find() ? match.group(1).trim() : null;
    }

    private static void addMenuWithParents(AppMenu menu, Map<Long, AppMenu> byId, Set<Long> visible) {
        AppMenu current = menu;
        while (current != null && visible.add(current.menuId())) {
            current = current.parentMenuId() == null ? null : byId.get(current.parentMenuId());
        }
    }

    public Authorization authorize(AuthService.TokenPayload payload,String moduleId,String action,String operation) {
        if(payload.isPrivilegedAccount()) return new Authorization(null,null,null);
        Candidate selected=null;
        for(AuthRepository.RoleRef role:payload.roles()){RolePolicy policy=snapshot().roles().get(role.name().trim().toLowerCase(Locale.ROOT)); if(policy==null||policy.version<role.version())continue; Permission permission=policy.permissions.get(moduleId.toLowerCase(Locale.ROOT)); if(permission==null)continue; Candidate next=new Candidate(role,permission,policy.defaultDataScope); if(selected==null||role.priority()>selected.role.priority())selected=next; else if(role.priority()==selected.role.priority()&&!next.permission.equals(selected.permission))throw new SecurityException("MODULE_PERMISSION_CONFLICT");}
        if(selected==null)throw new SecurityException("MODULE_PERMISSION_NOT_FOUND"); if(operation!=null&&!operation.isBlank()){if(!selected.permission.operations.contains("*")&&!selected.permission.operations.contains(operation))throw new SecurityException("MODULE_OPERATION_DENIED");} else if(!selected.permission.actions.contains(action.toLowerCase(Locale.ROOT)))throw new SecurityException("MODULE_ACTION_DENIED"); return new Authorization(selected.permission.dataScope,selected.defaultDataScope,selected.role.name());
    }

    /** UI permission contract consumed by React before it enables Add/Edit/Delete buttons. */
    public UiPermission uiPermission(AuthService.TokenPayload payload, String moduleId) {
        String resolvedModuleId = moduleId == null ? "" : moduleId.trim();
        if (resolvedModuleId.isBlank()) throw new IllegalArgumentException("Module id is required.");
        // Type-03 Legacy Search definitions do not have a Core runtime Module
        // record, but their policy key is still their MODID (for example 03L31).
        Module runtimeModule = module(resolvedModuleId);
        if (payload.isPrivilegedAccount()) {
            // Privileged accounts bypass role-policy authorization.  The UI must
            // receive the same standard Legacy Search actions even when a Type-03
            // module has no Actions block in its metadata.
            Set<String> actions = new HashSet<>(List.of("View", "Add", "Edit", "Delete"));
            Set<String> operations = runtimeModule == null ? Set.of()
                    : new HashSet<>(ModuleActionExecutor.object(runtimeModule.config(), "Operations").keySet());
            return new UiPermission(resolvedModuleId, List.copyOf(actions), List.copyOf(operations), true, false, List.of(), List.of("*"));
        }
        Candidate selected = null;
        for (AuthRepository.RoleRef role : payload.roles()) {
            RolePolicy policy = snapshot().roles().get(role.name().trim().toLowerCase(Locale.ROOT));
            if (policy == null || policy.version < role.version()) continue;
            Permission permission = policy.permissions.get(resolvedModuleId.toLowerCase(Locale.ROOT));
            if (permission == null) continue;
            Candidate next = new Candidate(role, permission, policy.defaultDataScope);
            if (selected == null || role.priority() > selected.role.priority()) selected = next;
            else if (role.priority() == selected.role.priority() && !next.permission.equals(selected.permission)) {
                return new UiPermission(resolvedModuleId, List.of(), List.of(), false, true, List.of("MODULE_PERMISSION_CONFLICT"), new ArrayList<>(accessibleModules(payload.roles())));
            }
        }
        if (selected == null) return new UiPermission(resolvedModuleId, List.of(), List.of(), false, false, List.of("MODULE_PERMISSION_NOT_FOUND"), new ArrayList<>(accessibleModules(payload.roles())));
        return new UiPermission(resolvedModuleId, new ArrayList<>(selected.permission.actions), new ArrayList<>(selected.permission.operations), false, false, List.of(), new ArrayList<>(accessibleModules(payload.roles())));
    }

    public Module module(String moduleId) { return snapshot().modules().get(moduleId == null ? "" : moduleId.trim().toLowerCase(Locale.ROOT)); }
    public Source source(String sourceName) { return snapshot().sources().get(sourceName == null ? "" : sourceName.trim().toLowerCase(Locale.ROOT)); }
    public String systemValue(String group, String name, String fallback) {
        String value = snapshot().systemValues().get((group + "$" + name).toLowerCase(Locale.ROOT));
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    /** System metadata cache addressed by table name, e.g. systemMetadata("DEFMODFLD"). */
    public List<Map<String,Object>> systemMetadata(String tableName) {
        if (tableName == null || tableName.isBlank()) return List.of();
        return snapshot().systemMetadata().getOrDefault(tableName.trim().toUpperCase(Locale.ROOT), List.of());
    }
    /** Typed DEFMODFLD rows from the active immutable Core snapshot. */
    public List<RuntimeMetadataRows.DefModuleField> moduleFields() { return snapshot().moduleFields(); }
    /** Typed DEFLANG rows from the active immutable Core snapshot. */
    public List<RuntimeMetadataRows.DefLanguage> languageDefinitions() { return snapshot().languageDefinitions(); }
    /** Active application metadata resolved from the immutable Core snapshot. */
    public RuntimeMetadataRows.DefApplication application(String appId) {
        return snapshot().applications().stream().filter(app -> app.appid() != null
                && String.valueOf(app.appid()).equals(appId == null ? "" : appId.trim())
                && !"N".equalsIgnoreCase(app.status())).findFirst().orElse(null);
    }
    public List<RuntimeMetadataRows.ApiSecurityRule> apiSecurityRules() { return snapshot().apiSecurityRules(); }
    /** Form metadata from the same immutable Core snapshot; no database query is made per request. */
    public com.webcore.api.contract.ModuleFormInfoResponse formInfo(String moduleId, String subModule) {
        return snapshot().formInfoCache().find(moduleId, subModule);
    }
    /** Temporary internal bridge for legacy executors not yet migrated to typed metadata. */
    public Map<String,Object> runtimeFormInfo(String moduleId, String subModule) {
        return snapshot().formInfoCache().findRuntime(moduleId, subModule);
    }

    private Component toComponent(Map<String, Object> row) { return new Component(text(row, "code"), text(row, "icon"), text(row, "type"), localizedJsonMap(text(row, "language"))); }
    /** Resolves MODTYPE from DEFMOD already loaded into the immutable Core snapshot. */
    private static String moduleType(Map<String, List<Map<String,Object>>> metadata, String moduleId) {
        if (moduleId == null || moduleId.isBlank()) return "";
        return metadata.getOrDefault("DEFMOD", List.of()).stream()
                .filter(row -> moduleId.equalsIgnoreCase(text(row, "MODID")))
                .map(row -> text(row, "MODTYPE"))
                .findFirst()
                .orElse("");
    }
    private Menu toMenu(Map<String, Object> row, String moduleType) { return new Menu(text(row, "component"), text(row, "menu_id"), text(row, "owner_menu_id"), text(row, "icon"), text(row, "module_id"), number(value(row, "position")), text(row, "param"), localizedJsonMap(text(row, "language")), moduleType); }
    private Map<String, Object> jsonMap(String value) { try { return value == null || value.isBlank() ? Map.of() : objectMapper.readValue(value, new TypeReference<>() {}); } catch (Exception ignored) { return Map.of(); } }
    /**
     * Language map keys are identifiers, not display text.  PostgreSQL JSON can
     * contain vn while DEFLANG/LANGID is VN; cache one canonical key without
     * inventing aliases (vi is never translated to VN here).
     */
    private Map<String, Object> localizedJsonMap(String value) {
        Map<String, Object> source = jsonMap(value);
        if (source.isEmpty()) return source;
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, entry) -> result.put(normalizeLanguageKey(key), entry));
        return Map.copyOf(result);
    }
    private static String normalizeLanguageKey(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String blank(String value) { return value == null ? "" : value.trim(); }
    private Map<String, Permission> parsePermissions(String value) {
        Map<String, Permission> result = new HashMap<>();
        try {
            Map<String, Map<String, Object>> raw = objectMapper.readValue(value, new TypeReference<>() {});
            for (var entry : raw.entrySet()) {
                Object actions = entry.getValue().get("Actions");
                if (actions == null) actions = entry.getValue().get("actions");
                Set<String> names = new HashSet<>();
                if (actions instanceof Collection<?> list) for (Object item : list) names.add(String.valueOf(item).toLowerCase(Locale.ROOT));
                Object operations=entry.getValue().getOrDefault("Operations",entry.getValue().get("operations")); Set<String> ops=new HashSet<>(); if(operations instanceof Collection<?> list)for(Object item:list)ops.add(String.valueOf(item)); Object scope=entry.getValue().getOrDefault("DataScope",entry.getValue().get("dataScope")); result.put(entry.getKey().toLowerCase(Locale.ROOT),new Permission(names,ops,scope==null?null:String.valueOf(scope)));
            }
        } catch (Exception ignored) { }
        return result;
    }
    private static Set<String> normalizedSet(Collection<String> values) { Set<String> result = new HashSet<>(); for (String value : values) if (value != null) result.add(value.trim().toLowerCase(Locale.ROOT)); return result; }
    /** JDBC drivers expose unquoted identifiers with provider-specific casing. */
    // private static Object value(Map<String, Object> row, String name) {
    //     if (row == null || name == null) return null;
    //     if (row.containsKey(name)) return row.get(name);
    //     return row.entrySet().stream()
    //             .filter(entry -> entry.getKey().equalsIgnoreCase(name))
    //             .map(Map.Entry::getValue)
    //             .findFirst()
    //             .orElse(null);
    // }
    private static Object value(
            Map<String, Object> row,
            String name) {

        if (row == null || name == null) {
            return null;
        }

        if (row.containsKey(name)) {
            return row.get(name);
        }

        for (Map.Entry<String, Object> entry : row.entrySet()) {

            String key = entry.getKey();

            if (key != null && key.equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private static String text(Map<String, Object> row, String name) { Object value = value(row, name); return value == null ? "" : String.valueOf(value).trim(); }
    private static int number(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private static long longNumber(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
    private static Long nullableLong(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private static String nullableText(Map<String, Object> row, String name) { Object value = value(row, name); return value == null ? null : String.valueOf(value).trim(); }
    private static Map<String, List<Menu>> immutableMenuMap(Map<String, List<Menu>> value) { return value.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue()))); }
    private static Map<String, Map<String, String>> immutableLanguages(Map<String, Map<String, String>> value) { return value.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Map.copyOf(entry.getValue()))); }
    private static Map<String, List<Map<String,Object>>> immutableMetadataTables(Map<String, List<Map<String,Object>>> value) { return value.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().stream().map(row -> java.util.Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList())); }

    public record Component(String code, String icon, String type, Map<String, Object> language) { }
    public record ComponentType(String value, String name, BigDecimal position, Map<String, Object> language) { }
    public record Menu(String component, String menuId, String ownerMenuId, String icon, String moduleId, int position, String param, Map<String, Object> language, String moduleType) { Menu asGroup() { return new Menu(component, menuId, ownerMenuId, icon, "", position, param, language, moduleType); } }
    public record AppMenu(long menuId, String componentCode, String menuKey, Long parentMenuId,
                          String nodeType, String text, String textI18n, String href, String target,
                          String icon, String iconColor, String badge, String badgeColor, int position) { }
    public record Module(String modId, String modName, Map<String, Object> config, Map<String, Object> configUi, Map<String, Object> language, Map<String, Object> metadata) { }
    public record Source(String sourceName, String type, Map<String, Object> config) { }
    public record Snapshot(
            Map<String, List<Menu>> menusByComponent,
            Map<String, Map<String, String>> languages,
            Map<String, Module> modules,
            Map<String, Source> sources,
            Map<String, String> systemValues,
            Map<String, RolePolicy> roles,
            Map<String, List<Map<String, Object>>> systemMetadata,
            List<RuntimeMetadataRows.DefApplication> applications,
            List<RuntimeMetadataRows.DefModuleField> moduleFields,
            List<RuntimeMetadataRows.DefLanguage> languageDefinitions,
            List<RuntimeMetadataRows.ApiSecurityRule> apiSecurityRules,
            ModuleMetadataSnapshot formInfoCache,
            ModuleRuntimeCache.Snapshot runtimeCache,
            Map<String, OpenApiDocumentCache.Document> openApiDocuments,
            RoutineMetadataReader.Snapshot routineMetadata,
            Instant loadedAtUtc) {

        static Snapshot empty() {
            return new Snapshot(
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    ModuleMetadataSnapshot.empty(),
                    ModuleRuntimeCache.Snapshot.empty(),
                    Map.of(),
                    RoutineMetadataReader.Snapshot.empty(),
                    Instant.EPOCH);
        }
    }
    public record Authorization(String dataScope,String defaultDataScope,String roleName) { }
    public record UiPermission(String moduleId, List<String> actions, List<String> operations, boolean isPrivilegedAccount, boolean isConflict, List<String> messages, List<String> accessibleModules) { }
    private record Candidate(AuthRepository.RoleRef role,Permission permission,String defaultDataScope) { }
    private record Permission(Set<String> actions,Set<String> operations,String dataScope) { }
    public static final class RolePolicy { private int version; private String defaultDataScope; private final Map<String,Permission> permissions=new HashMap<>(); private final Set<String> viewableModules = new HashSet<>(); private void merge(Map<String, Permission> loaded,String defaultDataScope) {this.defaultDataScope=defaultDataScope;permissions.putAll(loaded);loaded.forEach((module,permission)->{if(module.toUpperCase(Locale.ROOT).startsWith("S")||permission.actions.contains("view"))viewableModules.add(module);});} }




    /** Menu text localization from the immutable Core language cache. */
    // public Map<String, Object> menuLocalization(
    //         String component,
    //         String languageId) {

    //     String componentKey = component == null ? "" : component.trim();
    //     String langKey = normalizeLanguageKey(languageId);

    //     if (componentKey.isBlank() || langKey.isBlank()) {
    //         return Map.of("Menus", Map.of());
    //     }

    //     Map<String, String> languageValues = snapshot().languages()
    //             .getOrDefault(langKey, Map.of());

    //     Map<String, String> menus = new LinkedHashMap<>();
    //     for (Menu menu : snapshot().menusByComponent().getOrDefault(componentKey, List.of())) {
    //         String menuId = menu.menuId() == null ? "" : menu.menuId().trim();
    //         if (menuId.isBlank()) {
    //             continue;
    //         }

    //         String componentValue = localizedLanguageValue(
    //                 languageValues,
    //                 componentKey + "$" + menuId + ".Name");

    //         String globalValue = localizedLanguageValue(
    //                 languageValues,
    //                 "GLOBAL$" + menuId + ".Name");

    //         String text = !componentValue.isBlank()
    //                 ? componentValue
    //                 : (!globalValue.isBlank() ? globalValue : menuId);

    //         menus.put(menuId, text);
    //     }

    //     return Map.of("Menus", Map.copyOf(menus));
    // }

    // public Map<String, Object> menuLocalization(
    //     String component,
    //     String languageId) {

    // String componentKey = component == null
    //         ? ""
    //         : component.trim();

    // String langKey = normalizeLanguageKey(languageId);

    // if (componentKey.isBlank() || langKey.isBlank()) {
    //     return Map.of("Menus", Map.of());
    // }

    // List<Map<String, Object>> defLang = snapshot()
    //         .systemMetadata()
    //         .getOrDefault("DEFLANG", List.of());

    // Map<String, Object> menus = new LinkedHashMap<>();

    // for (Menu menu : snapshot()
    //         .menusByComponent()
    //         .getOrDefault(componentKey, List.of())) {

    //     String menuId = menu.menuId() == null
    //             ? ""
    //             : menu.menuId().trim();

    //     if (menuId.isBlank()) {
    //         continue;
    //     }

    //     String menuKey =
    //             "CODE$DEF_MENU$"
    //             + componentKey
    //             + "."
    //             + menuId.replace('-', '_');

    //     // Caption:
    //     // CODE$DEF_MENU$SYSTEM_MANAGER.00_00_00
    //     String caption = localizedMenuValue(
    //             defLang,
    //             menuKey,
    //             langKey);

    //     if (caption.isBlank()) {
    //         caption = menuId;
    //     }

    //     // Icon:
    //     // CODE$DEF_MENU$SYSTEM_MANAGER.00_00_00.Icon
    //     String icon = localizedMenuValue(
    //             defLang,
    //             menuKey + ".Icon",
    //             langKey);

    //     // Nếu DEFLANG chưa cấu hình Icon,
    //     // fallback về DEF_MENU.ICON hiện tại.
    //     if (icon.isBlank()) {
    //         icon = menu.icon() == null
    //                 ? ""
    //                 : menu.icon().trim();
    //     }

    //     Map<String, Object> menuLocalization = new LinkedHashMap<>();
    //     menuLocalization.put("Caption", caption);
    //     menuLocalization.put("Icon", icon);

    //     menus.put(
    //             menuId,
    //             Map.copyOf(menuLocalization));
    // }

    //     return Map.of(
    //             "Menus",
    //             Map.copyOf(menus));
    // }

   public MetadataLocalizationResponses.MenuLocalizationResponse menuLocalization(
        String component,
        String languageId) {

    String componentKey = component == null
            ? ""
            : component.trim();

    String langKey = normalizeLanguageKey(languageId);

    Map<String, MetadataLocalizationResponses.MenuText> menus = new LinkedHashMap<>();

    if (componentKey.isBlank()) {
        return new MetadataLocalizationResponses.MenuLocalizationResponse(menus);
    }

    // Tìm menu của component không phân biệt hoa/thường.
    List<Menu> source = snapshot()
            .menusByComponent()
            .entrySet()
            .stream()
            .filter(entry ->
                    entry.getKey() != null
                    && entry.getKey().trim().equalsIgnoreCase(componentKey))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(List.of());

    List<Map<String, Object>> defLang = snapshot()
            .systemMetadata()
            .getOrDefault("DEFLANG", List.of());

    for (Menu menu : source) {

        if (menu == null) {
            continue;
        }

        String menuId = menu.menuId() == null
                ? ""
                : menu.menuId().trim();

        if (menuId.isBlank()) {
            continue;
        }

        // Ví dụ:
        // CODE$DEF_MENU$DEMO.01_03_00
        String menuKey =
                "CODE$DEF_MENU$"
                + componentKey
                + "."
                + menuId.replace('-', '_');

        // =========================
        // Caption
        // =========================
        String caption = localizedMenuValue(
                defLang,
                menuKey,
                langKey);

        // Không có DEFLANG -> fallback MENU_ID
        if (caption.isBlank()) {
            caption = menuId;
        }

        // =========================
        // Icon
        // =========================
        String icon = localizedMenuValue(
                defLang,
                menuKey + ".Icon",
                langKey);

        // Không có DEFLANG .Icon
        // -> fallback core.def_menu.icon
        if (icon.isBlank()) {
            icon = menu.icon() == null
                    ? ""
                    : menu.icon().trim();
        }

        menus.put(menuId, new MetadataLocalizationResponses.MenuText(caption, icon));
    }

        return new MetadataLocalizationResponses.MenuLocalizationResponse(menus);
    }

    private static String localizedMenuValue(
        List<Map<String, Object>> defLang,
        String langName,
        String languageId) {

    if (defLang == null
            || defLang.isEmpty()
            || langName == null
            || langName.isBlank()
            || languageId == null
            || languageId.isBlank()) {

        return "";
    }

    for (Map<String, Object> row : defLang) {

        if (row == null) {
            continue;
        }

        String rowLangName = text(row, "LANGNAME");
        String rowLangId = text(row, "LANGID");
        String rowStatus = text(row, "STATUS");

        if (!langName.equalsIgnoreCase(rowLangName)) {
            continue;
        }

        if (!languageId.equalsIgnoreCase(rowLangId)) {
            continue;
        }

        if (!"A".equalsIgnoreCase(rowStatus)) {
            continue;
        }

        String value = text(row, "LANGVALUE");

        if (!value.isBlank()) {
            return value;
        }
    }

        return "";
    }



    private static String localizedLanguageValue(
            Map<String, String> languageValues,
            String key) {

        if (languageValues == null || languageValues.isEmpty() || key == null || key.isBlank()) {
            return "";
        }

        String direct = languageValues.get(key);
        if (direct != null && !direct.isBlank()) {
            return direct.trim();
        }

        return languageValues.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .findFirst()
                .orElse("");
    }
    public MetadataLocalizationResponses.FormLocalizationResponse formLocalization(
            String moduleId,
            String subModule,
            String languageId) {

        Map<String, Object> value = snapshot.get()
                .formInfoCache()
                .findLocalization(
                        moduleId,
                        subModule,
                        languageId);
        return new MetadataLocalizationResponses.FormLocalizationResponse(value);
    }

}
