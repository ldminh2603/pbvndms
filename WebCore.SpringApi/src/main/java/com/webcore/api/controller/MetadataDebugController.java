package com.webcore.api.controller;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.webcore.api.auth.AllowAnonymous;
import com.webcore.api.contract.ModuleFormInfoResponse;
import com.webcore.api.core.CoreMetadataService;
//import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.sql.DataSource;

@RestController
@RequestMapping("/api/debug")
// @RequiredArgsConstructor


public class MetadataDebugController {

    private final CoreMetadataService core;

    // public MetadataDebugController(CoreMetadataService core) {
    //     this.core = core;
    // }
    private final DataSource dataSource;

    public MetadataDebugController(
            CoreMetadataService core,
            DataSource dataSource) {

        this.core = core;
        this.dataSource = dataSource;
    }

    @AllowAnonymous
    @GetMapping("/form/{modId}/{subMod}")
    public ModuleFormInfoResponse form(
            @PathVariable String modId,
            @PathVariable String subMod) {

        return core.formInfo(
            modId,
            subMod
        );
    }

    // @AllowAnonymous
    // @GetMapping("/raw-field/{modId}/{subMod}")
    //   public Object rawField(
    //           @PathVariable String modId,
    //           @PathVariable String subMod) {

    //       return core.systemMetadata("DEFMODFLD")
    //           .stream()
    //           .filter(x ->
    //               modId.equalsIgnoreCase(String.valueOf(x.get("MODID")))
    //               && (
    //                   subMod.equalsIgnoreCase(String.valueOf(x.get("SUBMOD")))
    //                   || "ALL".equalsIgnoreCase(String.valueOf(x.get("SUBMOD")))
    //               )
    //           )
    //           .toList();
    //   }
      
      @AllowAnonymous
      @GetMapping("/raw-table/{table}")
      public Object rawTable(
              @PathVariable String table) {

          return core.systemMetadata(table);
      }

      @AllowAnonymous
      @GetMapping("/raw-field/{modId}/{subMod}/{fieldId}")
      public Object rawField(
              @PathVariable String modId,
              @PathVariable String subMod,
              @PathVariable String fieldId) {

          return core.systemMetadata("DEFMODFLD")
              .stream()
              .filter(row ->
                  modId.equalsIgnoreCase(value(row, "MODID"))
                  && subMod.equalsIgnoreCase(value(row, "SUBMOD"))
                  && fieldId.equalsIgnoreCase(value(row, "FLDID"))
              )
              .findFirst()
              .orElse(Map.of());
      }
        private static String value(
            Map<String, Object> row,
            String name) {

        if (row == null || name == null) {
            return "";
        }

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().equalsIgnoreCase(name)) {

                Object value = entry.getValue();

                return value == null
                    ? ""
                    : String.valueOf(value).trim();
            }
        }

        return "";
    }


    @AllowAnonymous
    @GetMapping("/db-info")
    public Map<String, Object> dbInfo() throws Exception {

        try (Connection connection =
                dataSource.getConnection()) {

            return Map.of(
                "url",
                connection.getMetaData().getURL(),

                "user",
                connection.getMetaData().getUserName(),

                "catalog",
                String.valueOf(connection.getCatalog()),

                "schema",
                String.valueOf(connection.getSchema())
            );
        }
        }

      
      
       @AllowAnonymous     
      @GetMapping("/check-field/{modId}/{subMod}/{fieldId}")

public Map<String, Object> checkField(
        @PathVariable String modId,
        @PathVariable String subMod,
        @PathVariable String fieldId) {

    List<Map<String, Object>> candidates = new ArrayList<>();

    for (Map<String, Object> row :
            core.systemMetadata("DEFMODFLD")) {

        String rowModId = getIgnoreCase(row, "MODID");
        String rowSubMod = getIgnoreCase(row, "SUBMOD");
        String rowFieldId = getIgnoreCase(row, "FLDID");

        // Chỉ lọc MODID trước để xem Java thực tế thấy gì
        if (normalize(modId).equals(normalize(rowModId))) {

            Map<String, Object> item = new LinkedHashMap<>();

            item.put("MODID", rowModId);
            item.put("SUBMOD", rowSubMod);
            item.put("FLDID", rowFieldId);

            item.put(
                "modMatch",
                normalize(modId).equals(normalize(rowModId))
            );

            item.put(
                "subMatch",
                normalize(subMod).equals(normalize(rowSubMod))
            );

            item.put(
                "fieldMatch",
                normalize(fieldId).equals(normalize(rowFieldId))
            );

            item.put(
                "modLength",
                rowModId.length()
            );

            item.put(
                "subLength",
                rowSubMod.length()
            );

            item.put(
                "fieldLength",
                rowFieldId.length()
            );

            item.put(
                "columns",
                row.keySet()
            );

            candidates.add(item);
        }
    }

    Map<String, Object> result =
        new LinkedHashMap<>();

    result.put("requestedModId", modId);
    result.put("requestedSubMod", subMod);
    result.put("requestedFieldId", fieldId);

    result.put("totalDefModFld",
        core.systemMetadata("DEFMODFLD").size());

    result.put("candidates", candidates);

    return result;
}




private static String getIgnoreCase(
        Map<String, Object> row,
        String column) {

    if (row == null || column == null) {
        return "";
    }

    for (Map.Entry<String, Object> entry :
            row.entrySet()) {

        String key = entry.getKey();

        if (key != null
                && key.equalsIgnoreCase(column)) {

            Object value = entry.getValue();

            return value == null
                ? ""
                : String.valueOf(value).trim();
        }
    }

    return "";
}

private static String normalize(String value) {
    return value == null
        ? ""
        : value.trim().toUpperCase(Locale.ROOT);
}



}
