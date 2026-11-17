package com.webcore.api.runtime;

import com.webcore.api.core.CoreMetadataService;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable, metadata-only runtime cache. Source resolution remains exclusively in def_source. */
@Service
public class ModuleRuntimeCache {
    private static final Pattern PARAMETER=Pattern.compile("(?<!:):([A-Za-z][A-Za-z0-9_]*)");
    private final CoreMetadataService core;
    private final RuntimeCacheMetrics metrics;
    public ModuleRuntimeCache(CoreMetadataService core, RuntimeCacheMetrics metrics) { this.core=core; this.metrics=metrics; }

    public static Snapshot build(CoreMetadataService.Snapshot core) {
        Map<String,List<String>> viewParameters=new LinkedHashMap<>();
        Map<String,Map<String,Object>> lookupActions=new LinkedHashMap<>();
        Map<String,Map<String,Object>> callbackActions=new LinkedHashMap<>();
        Map<String,List<SearchDocument>> search=new LinkedHashMap<>();
        Map<String,ModuleArtifact> artifacts=new LinkedHashMap<>();
        for (CoreMetadataService.Module module:core.modules().values()) {
            Map<String,Object> actions=ModuleActionExecutor.object(module.config(),"Actions");
            Map<String,Object> view=object(actions,"View");
            if (!view.isEmpty()) viewParameters.put(module.modId().toLowerCase(Locale.ROOT), parseParameters(ModuleActionExecutor.text(view,"action")));
            collectFieldActions(module,lookupActions,callbackActions);
            char prefix=module.modId().isBlank()?'?':Character.toUpperCase(module.modId().charAt(0));
            if (prefix=='D'||prefix=='R'||prefix=='U') artifacts.put(module.modId().toLowerCase(Locale.ROOT),new ModuleArtifact(module.modId(),prefix,module.config(),module.configUi()));
        }
        for (var component:core.menusByComponent().entrySet()) {
            List<SearchDocument> documents=new ArrayList<>();
            for (CoreMetadataService.Menu menu:component.getValue()) {
                if(menu.moduleId()==null || menu.moduleId().isBlank()) continue;
                CoreMetadataService.Module module=core.modules().get(key(menu.moduleId())); if(module==null) continue;
                documents.add(new SearchDocument(module.modId(),menu.menuId(),menu.param(),menu.icon(),module.modName(),localized(module.language(),"Module$Name",module.modName()),localized(menu.language(),"Name",menu.menuId()),help(module.metadata())));
            }
            search.put(component.getKey().toLowerCase(Locale.ROOT),List.copyOf(documents));
        }
        Snapshot snapshot=new Snapshot(Map.copyOf(viewParameters),Map.copyOf(lookupActions),Map.copyOf(callbackActions),Map.copyOf(search),Map.copyOf(artifacts));
        return snapshot;
    }
    public Snapshot snapshot() { return core.snapshot().runtimeCache(); }
    public List<String> viewParameters(String moduleId) { List<String> value=snapshot().viewParameters().get(key(moduleId)); return hitOrMiss("module-runtime", value, List.of()); }
    public Map<String,Object> lookup(String moduleId,String field) { Map<String,Object> value=snapshot().lookupActions().get(key(moduleId)+"::"+key(field)); return hitOrMiss("module-runtime", value, Map.of()); }
    public Map<String,Object> callback(String moduleId,String field) { Map<String,Object> value=snapshot().callbackActions().get(key(moduleId)+"::"+key(field)); return hitOrMiss("module-runtime", value, Map.of()); }
    public List<SearchDocument> search(String component) { List<SearchDocument> value=snapshot().searchByComponent().get(key(component)); return hitOrMiss("module-runtime", value, List.of()); }
    public ModuleArtifact artifact(String moduleId) { ModuleArtifact value=snapshot().artifacts().get(key(moduleId)); if(value==null)metrics.miss("module-runtime");else metrics.hit("module-runtime"); return value; }
    public Status status() { Snapshot value=snapshot(); return new Status(value.viewParameters().size(), value.lookupActions().size(), value.callbackActions().size(), value.searchByComponent().size(), value.searchByComponent().values().stream().mapToInt(List::size).sum(), value.artifacts().size()); }
    private static void collectFieldActions(CoreMetadataService.Module module,Map<String,Map<String,Object>> lookups,Map<String,Map<String,Object>> callbacks) {
        Object ui=module.config().get("Ui"); if(!(ui instanceof Map<?,?> fields)) return;
        for(var item:fields.entrySet()) if(item.getValue() instanceof Map<?,?> raw) {
            Map<String,Object> field=cast(raw); String prefix=key(module.modId())+"::"+key(String.valueOf(item.getKey()));
            Map<String,Object> lookup=object(field,"ListSource"); if(!lookup.isEmpty()) lookups.put(prefix,Map.copyOf(lookup));
            Map<String,Object> callback=object(field,"Callback"); if(!callback.isEmpty()) callbacks.put(prefix,Map.copyOf(callback));
        }
    }
    private static List<String> parseParameters(String sql) { Matcher matcher=PARAMETER.matcher(sql==null?"":sql); List<String> values=new ArrayList<>(); while(matcher.find())if(!values.contains(matcher.group(1)))values.add(matcher.group(1)); return List.copyOf(values); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Map<String,Object> source,String key) { Object value=source.get(key); return value instanceof Map<?,?> map?(Map<String,Object>)map:Map.of(); }
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Map<?,?> value) { return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static Map<String,String> localized(Map<String,Object> map,String key,String fallback) { Map<String,String> result=new LinkedHashMap<>(); for(var entry:map.entrySet()) if(entry.getValue() instanceof Map<?,?> nested&&nested.get(key)!=null) result.put(entry.getKey().trim().toUpperCase(Locale.ROOT),String.valueOf(nested.get(key))); if(!fallback.isBlank()) result.putIfAbsent("",fallback); return Map.copyOf(result); }
    @SuppressWarnings("unchecked") private static Map<String,String> help(Map<String,Object> metadata) { Object help=metadata.get("help"); if(!(help instanceof Map<?,?> values))return Map.of(); Map<String,String> result=new LinkedHashMap<>(); for(var entry:((Map<String,Object>)values).entrySet()) if(entry.getValue() instanceof Map<?,?> text&&text.get("text")!=null) result.put(entry.getKey().trim().toUpperCase(Locale.ROOT),String.valueOf(text.get("text"))); return Map.copyOf(result); }
    private static String key(String value) { return value==null?"":value.trim().toLowerCase(Locale.ROOT); }
    private <T> T hitOrMiss(String cache,T value,T fallback) { if(value==null||value.equals(fallback))metrics.miss(cache);else metrics.hit(cache); return value==null?fallback:value; }
    public record Snapshot(Map<String,List<String>> viewParameters,Map<String,Map<String,Object>> lookupActions,Map<String,Map<String,Object>> callbackActions,Map<String,List<SearchDocument>> searchByComponent,Map<String,ModuleArtifact> artifacts) { public static Snapshot empty(){return new Snapshot(Map.of(),Map.of(),Map.of(),Map.of(),Map.of());} }
    public record SearchDocument(String moduleId,String menuId,String param,String icon,String moduleName,Map<String,String> moduleNames,Map<String,String> menuNames,Map<String,String> helpTexts) { public String display(String language){return moduleNames.getOrDefault(language,moduleNames.getOrDefault("",moduleName));} public boolean matches(String query){return normalize(moduleId+" "+moduleName+" "+String.join(" ",moduleNames.values())+" "+String.join(" ",menuNames.values())+" "+String.join(" ",helpTexts.values())).contains(normalize(query));} public int score(String query){String normalized=normalize(query);String id=normalize(moduleId);return id.equals(normalized)?100:id.startsWith(normalized)?90:80;} private static String normalize(String value){return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFD).replaceAll("\\p{M}","").replace('đ','d').replace('Đ','D').toLowerCase(Locale.ROOT);} }
    public record ModuleArtifact(String moduleId,char kind,Map<String,Object> config,Map<String,Object> configUi) { }
    public record Status(int viewParameterModules, int lookupActions, int callbackActions, int searchComponents, int searchDocuments, int artifacts) { }
}
