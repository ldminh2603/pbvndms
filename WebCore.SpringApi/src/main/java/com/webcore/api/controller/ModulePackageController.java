package com.webcore.api.controller;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.webcore.api.auth.AuthService;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.contract.ModulePackageResponses;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Spring implementation of the legacy S001 module-package export contract. */
@RestController
@RequestMapping({"/api/module/s001/modulepackage", "/api/module/s002/modulepackage"})
public class ModulePackageController {
    private final AuthService auth; private final CoreMetadataService core; private final ObjectMapper json;
    private final Map<String, PackageData> packages = new ConcurrentHashMap<>();
    public ModulePackageController(AuthService auth, CoreMetadataService core, ObjectMapper json) { this.auth=auth; this.core=core; this.json=json; }

    @GetMapping("/export/modules")
    public ModulePackageResponses.ExportModulesResponse modules(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) String status, @RequestParam(required=false,name="type") String type) {
        requirePrivileged(jwt);
        List<ModulePackageResponses.ModuleItem> values=core.snapshot().modules().values().stream().filter(module -> type==null||type.isBlank()||module.modId().startsWith(type.trim()))
            .map(module -> exportModuleItem(module)).toList();
        return new ModulePackageResponses.ExportModulesResponse(Instant.now(), values.size(), values);
    }

    @PostMapping("/export/dependencies")
    public ModulePackageResponses.ExportDependenciesResponse dependencies(@AuthenticationPrincipal Jwt jwt,@RequestBody PackageRequest request) {
        requirePrivileged(jwt); List<String> ids=validIds(request.selectedModuleIds());
        return new ModulePackageResponses.ExportDependenciesResponse(Instant.now(), ids, mode(request.exportMode()), new ModulePackageResponses.DependencySummary(ids.size(), 0, 0, ids.size() * 2, 0, 0, List.of()), ids.stream().map(id -> new ModulePackageResponses.DependencyModule(id, List.of(), List.of(), List.of())).toList(), List.of(), List.of());
    }

    @PostMapping("/export/packages")
    public ModulePackageResponses.ExportPackageResponse create(@AuthenticationPrincipal Jwt jwt,@RequestBody PackageRequest request) throws Exception {
        String actor=requirePrivileged(jwt); List<String> ids=validIds(request.selectedModuleIds()); if(ids.isEmpty()) throw new IllegalArgumentException("At least one module is required.");
        Map<String,String> entries=new LinkedHashMap<>();
        for(String id:ids) { CoreMetadataService.Module module=core.module(id); if(module==null) throw new IllegalArgumentException("Module not found: "+id); entries.put("modules/"+id+"/def_mod.json",json.writeValueAsString(Map.of("mod_id",module.modId(),"mod_name",module.modName(),"config",module.config(),"config_ui",module.configUi(),"language",module.language(),"metadata",module.metadata()))); }
        String packageId="EXP-"+UUID.randomUUID(); String checksum=sha(String.join("",entries.values())); entries.put("manifest.json",json.writeValueAsString(Map.of("formatVersion",1,"packageId",packageId,"packageType","export","exportType",mode(request.exportMode()),"createdBy",actor,"createdAtUtc",Instant.now(),"modules",ids,"packageChecksum",checksum)));
        byte[] zip=zip(entries); packages.put(packageId,new PackageData(packageId,ids,checksum,zip));
        return new ModulePackageResponses.ExportPackageResponse(packageId, "export", mode(request.exportMode()), ids, checksum, "unsigned", entries.size(), "/api/module/s001/modulepackage/export/packages/"+packageId+"/zip", List.of());
    }

    @GetMapping("/export/packages/{packageId}/zip") public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt,@PathVariable String packageId) { requirePrivileged(jwt); PackageData data=packages.get(packageId); if(data==null)return ResponseEntity.notFound().build(); return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+packageId+".zip\"").contentType(MediaType.valueOf("application/zip")).body(data.zip()); }
    @PostMapping(value="/import/stage",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ModulePackageResponses.ImportStageResponse stage(@AuthenticationPrincipal Jwt jwt,@RequestPart("package") MultipartFile file) throws Exception { requirePrivileged(jwt); if(file.isEmpty())throw new IllegalArgumentException("Package zip file is required."); byte[] zip=file.getBytes(); List<String> names=zipNames(zip); List<String> modules=names.stream().filter(name->name.startsWith("modules/")).map(name->name.split("/")[1]).distinct().toList(); String checksum=sha(Base64.getEncoder().encodeToString(zip)); String id="STG-"+UUID.randomUUID(); packages.put(id,new PackageData(id,modules,checksum,zip)); return new ModulePackageResponses.ImportStageResponse(id, checksum, names.size(), modules); }
    @PostMapping("/import/validate") public ModulePackageResponses.ImportValidationResponse validate(@AuthenticationPrincipal Jwt jwt,@RequestBody Map<String,String> body) { requirePrivileged(jwt); PackageData data=packages.get(body.get("packageId")); if(data==null)throw new IllegalArgumentException("Package not found."); return new ModulePackageResponses.ImportValidationResponse(data.id(), true, data.checksum(), "unsigned", data.modules(), List.of(), true, false, List.of(), List.of("Automatic apply is disabled until the legacy transactional backup/apply implementation is ported.")); }
    @PostMapping("/import/apply") public Map<String,Object> apply(@AuthenticationPrincipal Jwt jwt,@RequestBody Map<String,String> body) { requirePrivileged(jwt); throw new IllegalStateException("Automatic import apply is not available until transactional backup/apply is ported from WebCore.Api."); }
    @GetMapping("/import/history") public ModulePackageResponses.ImportHistoryResponse history(@AuthenticationPrincipal Jwt jwt) { requirePrivileged(jwt); return new ModulePackageResponses.ImportHistoryResponse(Instant.now(), List.of()); }
    private String requirePrivileged(Jwt jwt){var payload=auth.tokenPayload(jwt);if(!payload.isPrivilegedAccount())throw new SecurityException("ADMIN_ACCESS_DENIED");return payload.email();}
    private static ModulePackageResponses.ModuleItem exportModuleItem(CoreMetadataService.Module module) {
        return new ModulePackageResponses.ModuleItem(module.modId(), module.modName(), "active", module.modId().replaceAll("\\d.*$", ""), "ConfigOnly", List.of(), false, 0, 0, false, false, "Config");
    }
    private List<String> validIds(List<String> ids){return ids==null?List.of():ids.stream().filter(id->id!=null&&!id.isBlank()).map(String::trim).distinct().toList();}
    private String mode(String mode){return "ConfigOnly".equalsIgnoreCase(mode)||"DefSource".equalsIgnoreCase(mode)?mode:"Full";}
    private static byte[] zip(Map<String,String> entries)throws Exception{var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}return output.toByteArray();}
    private static List<String> zipNames(byte[] value)throws Exception{try(var zip=new ZipInputStream(new ByteArrayInputStream(value))){var names=new java.util.ArrayList<String>();for(ZipEntry e;(e=zip.getNextEntry())!=null;){if(e.isDirectory()||e.getName().contains(".."))continue;names.add(e.getName());}return names;}}
    private static String sha(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private record PackageData(String id,List<String> modules,String checksum,byte[] zip){}
    public record PackageRequest(List<String> selectedModuleIds,String exportMode,List<String> excludedDependencyIds){}
}
