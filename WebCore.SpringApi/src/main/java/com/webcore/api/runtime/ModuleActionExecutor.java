package com.webcore.api.runtime;

import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.CallableStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.webcore.api.core.CoreMetadataService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Executes only declared SQL actions; request values are always JDBC parameters. */
@Service
public class ModuleActionExecutor {
    /**
     * Metadata written by the legacy .NET runtime uses {@code @pv_*}; newer
     * Spring-authored metadata may use {@code :pv_*}.  Both are bind markers,
     * never SQL text, so normalize either form to JDBC positional parameters.
     */
    private static final Pattern PARAMETER = Pattern.compile("(?<![:@])[@:]([A-Za-z][A-Za-z0-9_]*)");
    private static final Pattern ROUTINE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,1}$");
    private final SourceDataSourceRegistry sources;
    private final RoutineMetadataReader metadata;
    private final ApiActionExecutor api;
    public ModuleActionExecutor(SourceDataSourceRegistry sources, RoutineMetadataReader metadata, ApiActionExecutor api) { this.sources = sources; this.metadata=metadata; this.api=api; }
    public List<Map<String,Object>> sql(CoreMetadataService.Module module, Map<String,Object> descriptor, Map<String,Object> values) {
        String method=text(descriptor,"actionMethod"); if (!"SQL".equalsIgnoreCase(method) && !"FUNCTION".equalsIgnoreCase(method)) throw new UnsupportedOperationException("Only SQL/function module actions are enabled in Spring runtime.");
        String source=text(descriptor,"source"); String statement=text(descriptor,"action");
        if(source.isBlank()||statement.isBlank()||!statement.trim().toUpperCase().startsWith("SELECT")) throw new IllegalArgumentException("Module action must declare a read-only SELECT statement and source.");
        List<Object> parameters=new ArrayList<>(); Matcher matcher=PARAMETER.matcher(statement); StringBuffer safe=new StringBuffer();
        while(matcher.find()){String name=matcher.group(1); if(!values.containsKey(name)) throw new IllegalArgumentException("Missing action parameter: "+name); parameters.add(values.get(name)); matcher.appendReplacement(safe,"?");} matcher.appendTail(safe);
        return new JdbcTemplate(sources.resolve(source)).query(safe.toString(), (PreparedStatement ps)->{for(int i=0;i<parameters.size();i++)ps.setObject(i+1,parameters.get(i));}, (rs,row)->{Map<String,Object> result=new LinkedHashMap<>(); for(int i=1;i<=rs.getMetaData().getColumnCount();i++)result.put(columnName(rs.getMetaData().getColumnLabel(i)),columnValue(rs,i)); return result;});
    }
    public List<Map<String,Object>> execute(CoreMetadataService.Module module, Map<String,Object> descriptor, Map<String,Object> values) {
        String method=text(descriptor,"actionMethod").toUpperCase(java.util.Locale.ROOT);
        return switch(method) { case "SQL" -> sql(module,descriptor,values); case "FUNCTION", "FUNCTIONTABLE" -> function(descriptor,values); case "STORE", "PROCEDURE" -> routine(descriptor,values); case "GET", "POST", "PUT", "PATCH", "DELETE" -> api.execute(descriptor,values); default -> throw new UnsupportedOperationException("Unsupported actionMethod: "+method); };
    }
    private List<Map<String,Object>> function(Map<String,Object> descriptor,Map<String,Object> values) {
        String source=text(descriptor,"source"); String reference=text(descriptor,"function"); if(reference.isBlank())reference=text(descriptor,"action"); validateRoutine(reference);
        List<Object> parameters=orderedInputs(source,reference,values,descriptor); String sql="SELECT * FROM "+reference+"("+String.join(",",java.util.Collections.nCopies(parameters.size(),"?"))+")";
        return query(sources.resolve(source),sql,parameters);
    }
    /** The legacy metadata labels some PostgreSQL list-source functions as Store; catalog kind is authoritative. */
    private List<Map<String,Object>> routine(Map<String,Object> descriptor, Map<String,Object> values) {
        String source=text(descriptor,"source"); String reference=text(descriptor,"action");
        return metadata.kind(source, reference) == RoutineMetadataReader.Kind.FUNCTION ? function(descriptor, values) : store(descriptor, values);
    }
    private List<Map<String,Object>> store(Map<String,Object> descriptor,Map<String,Object> values) {
        String source=text(descriptor,"source"); String reference=text(descriptor,"action"); validateRoutine(reference); List<RoutineMetadataReader.Parameter> definitions=metadata.parameters(source,reference); List<OutParameter> outputs=metadataOutputs(definitions); if(outputs.isEmpty())outputs=outParameters(descriptor); int count=Math.max(definitions.stream().mapToInt(RoutineMetadataReader.Parameter::position).max().orElse(0),outputs.stream().mapToInt(OutParameter::position).max().orElse(0)); String provider=sources.provider(source); String placeholders="POSTGRES".equalsIgnoreCase(provider)?postgresPlaceholders(definitions,count):String.join(",",java.util.Collections.nCopies(count,"?")); String call="POSTGRES".equalsIgnoreCase(provider)?"CALL "+reference+"("+placeholders+")":"{call "+reference+"("+placeholders+")}";
        try(Connection connection=sources.resolve(source).getConnection()) { boolean original=connection.getAutoCommit(); connection.setAutoCommit(false); try(CallableStatement prepared=connection.prepareCall(call)){for(var definition:definitions)if(definition.input()){Object input=inputValue(values,descriptor,definition.name());bindRoutineInput(prepared, definition, input==Missing.VALUE?null:input, provider);} for(OutParameter output:outputs)prepared.registerOutParameter(output.position(),output.sqlType()); boolean hasResult=prepared.execute(); List<Map<String,Object>> result=hasResult?rows(prepared.getResultSet()):new ArrayList<>(); for(OutParameter output:outputs){Object value=prepared.getObject(output.position()); if(value instanceof ResultSet cursor)result.addAll(rows(cursor)); else result.add(Map.of("outputName",output.name(),"value",value));} connection.commit(); return result;} catch(Exception ex){connection.rollback();throw new IllegalStateException("Store action rolled back: "+reference,ex);} finally{connection.setAutoCommit(original);} } catch(java.sql.SQLException ex){throw new IllegalStateException("Cannot execute Store action: "+reference,ex);}
    }
    /**
     * PostgreSQL does not infer a VARCHAR argument as json/jsonb for a CALL.
     * The routine catalog supplies the declared type, so this remains generic
     * for every metadata-driven procedure rather than special-casing modules.
     */
    private static void bindRoutineInput(CallableStatement statement, RoutineMetadataReader.Parameter definition, Object value, String provider) throws java.sql.SQLException {
        if (value == null) { statement.setNull(definition.position(), Types.NULL); return; }
        if ("POSTGRES".equalsIgnoreCase(provider) && "bit".equalsIgnoreCase(definition.type())) {
            if (value instanceof Boolean bool) statement.setString(definition.position(), bool ? "1" : "0");
            else statement.setString(definition.position(), String.valueOf(value));
            return;
        }
        if ("POSTGRES".equalsIgnoreCase(provider) && "money".equalsIgnoreCase(definition.type()) && value instanceof Number number) {
            // JSON numeric values arrive as Double. PostgreSQL cannot cast
            // double precision directly to money, while numeric can be cast safely.
            statement.setBigDecimal(definition.position(), new java.math.BigDecimal(number.toString()));
            return;
        }
        if ("POSTGRES".equalsIgnoreCase(provider) && ("json".equalsIgnoreCase(definition.type()) || "jsonb".equalsIgnoreCase(definition.type()))) {
            if (!(value instanceof String)) throw new IllegalArgumentException("PostgreSQL " + definition.type() + " routine parameter must be supplied as JSON text: " + definition.name());
            // Types.OTHER preserves the PostgreSQL server-side json/jsonb type
            // without importing a driver-specific PGobject into the compile API.
            statement.setObject(definition.position(), value, Types.OTHER);
            return;
        }
        statement.setObject(definition.position(), value);
    }
    /**
     * PostgreSQL procedure lookup is type-sensitive, including for null
     * values: an uncast null is {@code unknown} and cannot select a procedure
     * overload.  The type names come from {@code pg_type}, not request data;
     * accept only simple catalog type identifiers before emitting a cast.
     */
    private static String postgresPlaceholders(List<RoutineMetadataReader.Parameter> definitions, int count) {
        List<String> result = new ArrayList<>();
        for (int position = 1; position <= count; position++) {
            int parameterPosition = position;
            RoutineMetadataReader.Parameter parameter = definitions.stream().filter(value -> value.position() == parameterPosition).findFirst().orElse(null);
            String type = parameter == null || !parameter.input() ? "" : parameter.type().toLowerCase(Locale.ROOT);
            result.add(type.matches("[a-z_][a-z0-9_]*") ? "?::" + type : "?");
        }
        return String.join(",", result);
    }
    private List<Map<String,Object>> query(javax.sql.DataSource source,String sql,List<Object> parameters){try(Connection c=source.getConnection();var p=c.prepareStatement(sql)){for(int i=0;i<parameters.size();i++)p.setObject(i+1,parameters.get(i));try(ResultSet rs=p.executeQuery()){return rows(rs);}}catch(Exception ex){throw new IllegalStateException("Module SQL action failed.",ex);}}
    private List<Map<String,Object>> rows(ResultSet rs)throws java.sql.SQLException{List<Map<String,Object>> result=new ArrayList<>();if(rs==null)return result;var meta=rs.getMetaData();while(rs.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=meta.getColumnCount();i++)row.put(columnName(meta.getColumnLabel(i)),columnValue(rs,i));result.add(row);}return result;}
    /** Some legacy sources expose display-formatted numbers through a numeric JDBC type. */
    private static Object columnValue(ResultSet rs, int index) throws java.sql.SQLException {
        try { return jdbcValue(rs.getObject(index)); }
        catch (java.sql.SQLException malformedValue) {
            String text = rs.getString(index);
            if (text != null && text.matches("[-+]?[0-9]{1,3}(,[0-9]{3})+(\\.[0-9]+)?")) {
                try { return new java.math.BigDecimal(text.replace(",", "")); }
                catch (NumberFormatException ignored) { /* return source text below */ }
            }
            return text;
        }
    }
    /** Oracle and SQL Server commonly uppercase unquoted labels; module metadata fields are lowercase snake_case. */
    private static String columnName(String label) { return label == null ? "" : label.toLowerCase(Locale.ROOT); }
    /** PostgreSQL exposes json/jsonb as PGobject; generic text editors require its JSON value, not a Java object. */
    private static Object jdbcValue(Object value) {
        if (value != null && "org.postgresql.util.PGobject".equals(value.getClass().getName())) return String.valueOf(value);
        return value;
    }
    private static void validateRoutine(String reference){if(!ROUTINE.matcher(reference).matches())throw new IllegalArgumentException("Invalid routine reference.");}
    private List<Object> orderedInputs(String source,String reference,Map<String,Object> values,Map<String,Object> descriptor){List<RoutineMetadataReader.Parameter> definitions=metadata.parameters(source,reference);List<Object> result=new ArrayList<>();for(var definition:definitions)if(definition.input()){Object input=inputValue(values,descriptor,definition.name());if(input==Missing.VALUE)throw new IllegalArgumentException("Missing routine parameter: "+definition.name());result.add(input);}return result;}
    /**
     * Config-driven routine binding.  An action may declare parameterMapping as
     * { "pv_database_name": "form_field" }; without it only neutral naming
     * normalization is used, so no module/procedure is coded in Java.
     */
    private static Object inputValue(Map<String,Object> values, Map<String,Object> descriptor, String parameterName) {
        String configuredField = configuredField(descriptor, parameterName);
        if (configuredField != null) {
            Object configuredValue = valueIgnoreCase(values, configuredField);
            if (configuredValue != Missing.VALUE) return configuredValue;
        }
        Object exact = valueIgnoreCase(values, parameterName);
        if (exact != Missing.VALUE) return exact;
        String canonicalParameter = canonicalName(parameterName);
        for (var entry : values.entrySet()) {
            if (canonicalName(entry.getKey()).equals(canonicalParameter)) return entry.getValue();
        }
        return Missing.VALUE;
    }
    @SuppressWarnings("unchecked")
    private static String configuredField(Map<String,Object> descriptor, String parameterName) {
        Object raw = descriptor.get("parameterMapping");
        if (!(raw instanceof Map<?,?> mapping)) return null;
        for (var entry : mapping.entrySet()) {
            String left = String.valueOf(entry.getKey());
            String right = String.valueOf(entry.getValue());
            if (left.equalsIgnoreCase(parameterName)) return right;
            if (right.equalsIgnoreCase(parameterName)) return left;
        }
        return null;
    }
    private static Object valueIgnoreCase(Map<String,Object> values, String name) {
        for (var entry : values.entrySet()) if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        return Missing.VALUE;
    }
    private static String canonicalName(String name) {
        String normalized = name == null ? "" : name.trim().replaceFirst("^@", "").toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("pv_") ? normalized.substring(3) : normalized;
    }
    @SuppressWarnings("unchecked") private static List<OutParameter> outParameters(Map<String,Object> descriptor){Object raw=descriptor.get("outParameters");if(!(raw instanceof List<?> values))return List.of();List<OutParameter> result=new ArrayList<>();int next=1;for(Object value:values){if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException("outParameters entries must be objects.");Object p=map.get("position");int position=p instanceof Number n?n.intValue():next;Object rawName=map.get("name");Object rawType=map.get("type");String name=rawName==null?"out"+position:String.valueOf(rawName);String type=rawType==null?"VARCHAR":String.valueOf(rawType);result.add(new OutParameter(name,position,sqlType(type)));next=Math.max(next,position+1);}return result;}
    private static int sqlType(String name){return switch(name.trim().toUpperCase(java.util.Locale.ROOT)){case "REF_CURSOR","CURSOR","SYS_REFCURSOR"->Types.REF_CURSOR;case "VARCHAR","VARCHAR2","STRING"->Types.VARCHAR;case "NVARCHAR","NVARCHAR2"->Types.NVARCHAR;case "INTEGER","INT","NUMBER"->Types.INTEGER;case "BIGINT","LONG"->Types.BIGINT;case "NUMERIC","DECIMAL"->Types.NUMERIC;case "BOOLEAN","BIT"->Types.BOOLEAN;case "TIMESTAMP"->Types.TIMESTAMP;case "DATE"->Types.DATE;default->Types.OTHER;};}
    private static List<OutParameter> metadataOutputs(List<RoutineMetadataReader.Parameter> definitions){return definitions.stream().filter(RoutineMetadataReader.Parameter::output).map(value->new OutParameter(value.name(),value.position(),sqlType(value.type()))).toList();}
    private record OutParameter(String name,int position,int sqlType){ }
    private enum Missing { VALUE }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Map<String,Object> source,String key){Object value=source.get(key); return value instanceof Map<?,?> map?(Map<String,Object>)map:Map.of();}
    public static String text(Map<String,Object> source,String key){Object value=source.get(key);return value==null?"":String.valueOf(value).trim();}
}
