package com.webcore.api.runtime;

import java.util.Locale;
import java.util.Map;

import com.webcore.api.auth.AuthService;
import com.webcore.api.contract.ModuleRuntimeResponses;
import com.webcore.api.core.CoreMetadataService;
import org.springframework.stereotype.Service;

/** Authorization boundary for config-driven modules. Executors are added behind this boundary. */
@Service
public class ModuleRuntimeService {
    private final CoreMetadataService core;
    private final ModuleActionExecutor executor;
    private final ModuleRuntimeCache cache;
    private final DevExtremeLoadProcessor loadProcessor;
    public ModuleRuntimeService(CoreMetadataService core, ModuleActionExecutor executor, ModuleRuntimeCache cache, DevExtremeLoadProcessor loadProcessor) { this.core = core; this.executor=executor; this.cache=cache; this.loadProcessor=loadProcessor; }
    public CoreMetadataService.Module requireViewable(String moduleId, AuthService.TokenPayload payload) {
        CoreMetadataService.Module module = core.module(moduleId);
        if (module == null) throw new IllegalArgumentException("Module not found: " + moduleId);
        core.authorize(payload,module.modId(),"View",null);
        return module;
    }
    public ModuleRuntimeResponses.UiResponse ui(String moduleId, AuthService.TokenPayload payload) {
        CoreMetadataService.Module module = requireViewable(moduleId,payload);
        return new ModuleRuntimeResponses.UiResponse(module.modId(), module.modName(), module.configUi(), module.language(), module.metadata(), core.uiPermission(payload,module.modId()));
    }
    public Map<String,Object> data(String moduleId,String key,Map<String,Object> values,AuthService.TokenPayload payload){CoreMetadataService.Module module=requireViewable(moduleId,payload); CoreMetadataService.Authorization authz=core.authorize(payload,module.modId(),"View",null); String actionKey=(key.equalsIgnoreCase("view")||key.equalsIgnoreCase("load"))?"View":key; Map<String,Object> action=ModuleActionExecutor.object(ModuleActionExecutor.object(module.config(),"Actions"),actionKey); ListResult result=execute(module,action,values,payload,authz); return loadProcessor.load(result.data(),values);}
    public ModuleRuntimeResponses.LookupResponse lookup(String moduleId,String key,Map<String,Object> values,AuthService.TokenPayload payload){CoreMetadataService.Module module=requireViewable(moduleId,payload); CoreMetadataService.Authorization authz=core.authorize(payload,module.modId(),"View",null); Map<String,Object> action=cache.lookup(module.modId(),key); ListResult result=execute(module,action,values,payload,authz); return new ModuleRuntimeResponses.LookupResponse(result.data(),result.data());}
    /** Executes the field callback declared at config.Ui.{field}.Callback, matching the .NET module gateway contract. */
    public ModuleRuntimeResponses.DataResponse callback(String moduleId,String key,Map<String,Object> values,AuthService.TokenPayload payload){
        CoreMetadataService.Module module=requireViewable(moduleId,payload);
        CoreMetadataService.Authorization authz=core.authorize(payload,module.modId(),"View",null);
        Map<String,Object> action=cache.callback(module.modId(),key);
        if(action.isEmpty()) throw new IllegalArgumentException("Module callback is not configured: " + key);
        ListResult result=execute(module,action,values,payload,authz);
        return new ModuleRuntimeResponses.DataResponse(result.data());
    }
    public ModuleRuntimeResponses.OperationResponse operation(String moduleId,String key,Map<String,Object> values,AuthService.TokenPayload payload){CoreMetadataService.Module module=core.module(moduleId);if(module==null)throw new IllegalArgumentException("Module not found: "+moduleId);CoreMetadataService.Authorization authz=core.authorize(payload,module.modId(),"",key); Map<String,Object> action=ModuleActionExecutor.object(ModuleActionExecutor.object(module.config(),"Operations"),key); ListResult result=execute(module,action,values,payload,authz); return new ModuleRuntimeResponses.OperationResponse(key,result.data());}
    public ModuleRuntimeResponses.WriteResponse write(String moduleId,String actionName,Map<String,Object> values,AuthService.TokenPayload payload){CoreMetadataService.Module module=core.module(moduleId);if(module==null)throw new IllegalArgumentException("Module not found: "+moduleId);CoreMetadataService.Authorization authz=core.authorize(payload,module.modId(),actionName,null);Map<String,Object> action=ModuleActionExecutor.object(ModuleActionExecutor.object(module.config(),"Actions"),actionName);ListResult result=execute(module,action,normalizeWriteValues(module,values),payload,authz);return new ModuleRuntimeResponses.WriteResponse(actionName,result.data());}
    private ListResult execute(CoreMetadataService.Module module,Map<String,Object> action,Map<String,Object> body){return execute(module,action,body,null,null);}
    private ListResult execute(CoreMetadataService.Module module,Map<String,Object> action,Map<String,Object> body,AuthService.TokenPayload payload,CoreMetadataService.Authorization authz){if(action.isEmpty())throw new IllegalArgumentException("Module action is not configured."); Map<String,Object> parameters=new java.util.LinkedHashMap<>(); parameters.putAll(body); parameters.remove("operationKey"); parameters.remove("operationAction"); parameters.remove("parameters"); parameters.putAll(ModuleActionExecutor.object(body,"parameters"));if(payload!=null){parameters.put("_v_UserName",payload.id());parameters.put("_v_Email",payload.email());parameters.put("_v_IsPrivilegedAccount",payload.isPrivilegedAccount());parameters.put("_v_DataScope",authz.dataScope()==null?authz.defaultDataScope():authz.dataScope());parameters.put("_v_ModuleDataScope",authz.dataScope());parameters.put("_v_DefaultDataScope",authz.defaultDataScope());} return new ListResult(executor.execute(module,action,parameters));}
    /** React edit/delete requests wrap values in { key, values }; .NET expands that envelope before routine binding. */
    private static Map<String,Object> normalizeWriteValues(CoreMetadataService.Module module, Map<String,Object> request) {
        Map<String,Object> result = new java.util.LinkedHashMap<>();
        Object nested = request.get("values");
        if (nested instanceof Map<?,?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        } else {
            result.putAll(request);
        }
        Object key = request.get("key");
        if (key instanceof Map<?,?> keyMap) {
            keyMap.forEach((name, value) -> result.put(String.valueOf(name), value));
        } else if (key != null) {
            Object rawFields = module.config().get("keyFieldName");
            if (rawFields instanceof java.util.List<?> fields && !fields.isEmpty()) {
                result.put(String.valueOf(fields.getFirst()), key);
            } else {
                Object fallback = module.config().get("keyField");
                result.put(fallback == null ? "id" : String.valueOf(fallback), key);
            }
        }
        return result;
    }
    private record ListResult(java.util.List<Map<String,Object>> data) { }
}
