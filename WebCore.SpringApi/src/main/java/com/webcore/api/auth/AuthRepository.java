package com.webcore.api.auth;

import java.sql.Array;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.webcore.api.database.CoreDataSourceResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Repository;

@Repository
public class AuthRepository {
    private final ObjectMapper objectMapper;
    private final CoreDataSourceResolver coreSource;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public AuthRepository(ObjectMapper objectMapper, CoreDataSourceResolver coreSource) {
        this.objectMapper = objectMapper;
        this.coreSource = coreSource;
    }

    private NamedParameterJdbcTemplate coreDb() { return new NamedParameterJdbcTemplate(coreSource.resolve("DBCORE_auth")); }

    public UserAuthContext loadForLogin(String email, String password, String type) {
        return "POSTGRES".equals(coreSource.provider("DBCORE_auth"))
                ? loadPostgres("SELECT * FROM get_user_auth_context(:email, :password, :type)", email, password, type)
                : loadPortable(email, password, type, true);
    }

    public UserAuthContext loadByEmail(String email, String type) {
        return "POSTGRES".equals(coreSource.provider("DBCORE_auth"))
                ? loadPostgres("SELECT * FROM get_user_auth_context_by_email(:email, :type)", email, null, type)
                : loadPortable(email, null, type, false);
    }

    /** PostgreSQL retains the existing auth functions; Oracle and SQL Server use the portable SQL contract. */
    private UserAuthContext loadPostgres(String sql, String email, String password, String type) {
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("email", email)
                .addValue("password", password).addValue("type", type);
        List<PostgresAuthRow> rows;
        try {
            rows = coreDb().query(sql, parameters, (rs, ignored) -> new PostgresAuthRow(
                    authUser(rs), normalizeValue(rs.getObject("roles")), normalizeValue(rs.getObject("components"))));
        } catch (DataAccessException exception) {
            if (hasSqlState(exception, "P1001")) throw new InvalidCredentialsException();
            throw exception;
        }
        if (rows.isEmpty()) throw new InvalidCredentialsException();
        PostgresAuthRow row = rows.getFirst();
        try {
            return new UserAuthContext(row.user(), parseRoles(row.roles()), toComponents(row.components()));
        } catch (Exception exception) {
            throw new IllegalStateException("Authentication context is invalid.", exception);
        }
    }

    private UserAuthContext loadPortable(String email, String password, String type, boolean verifyPassword) {
        String provider = coreSource.provider("DBCORE_auth");
        String userTable = "ORACLE".equals(provider) ? "\"USER\"" : "[auth].[user]";
        String roleView = "ORACLE".equals(provider) ? "V_USER_ROLE_ACTIVE" : "[auth].[v_user_role_active]";
        String componentView = "ORACLE".equals(provider) ? "V_ROLE_COMPONENTS_ACTIVE" : "[auth].[v_role_components_active]";
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("email", email)
                .addValue("type", type == null || type.isBlank() ? null : type);
        List<AuthUser> users = coreDb().query("""
                SELECT id, display_name, password, is_privileged_account, type, status, is_deleted,
                       created_date, created_user, modified_date, modified_by, email, last_login,
                       allow_public, avatar_url, setting
                FROM %s
                WHERE email = :email AND (:type IS NULL OR type = :type)
                """.formatted(userTable), parameters, (rs, ignored) -> authUser(rs));
        if (users.isEmpty()) throw new InvalidCredentialsException();
        AuthUser user = users.getFirst();
        if (verifyPassword && !bcrypt.matches(password == null ? "" : password, user.password() == null ? "" : user.password())) {
            throw new InvalidCredentialsException();
        }

        List<RoleRef> roles = coreDb().query("""
                SELECT role_name_ref AS name, role_version AS version, user_role_priority AS priority
                FROM %s
                WHERE user_email = :email
                ORDER BY user_role_priority DESC, user_role_id DESC
                """.formatted(roleView), new MapSqlParameterSource("email", email),
                (rs, ignored) -> new RoleRef(rs.getString("name"), rs.getInt("version"), rs.getInt("priority")));
        List<String> components = roles.isEmpty() ? List.of() : coreDb().queryForList(
                "SELECT DISTINCT component FROM %s WHERE role_name IN (:roles) ORDER BY component".formatted(componentView),
                new MapSqlParameterSource("roles", roles.stream().map(RoleRef::name).toList()), String.class)
                .stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
        return new UserAuthContext(user, roles, components);
    }

    private AuthUser authUser(ResultSet rs) throws SQLException {
        return new AuthUser(rs.getObject("id"), rs.getString("display_name"), optionalString(rs, "password"),
                booleanValue(rs, "is_privileged_account"), rs.getString("type"), rs.getString("status"),
                booleanValue(rs, "is_deleted"), rs.getTimestamp("created_date"), rs.getString("created_user"),
                rs.getTimestamp("modified_date"), rs.getString("modified_by"), rs.getString("email"),
                rs.getTimestamp("last_login"), booleanValue(rs, "allow_public"), rs.getString("avatar_url"),
                normalizeValue(rs.getObject("setting")));
    }

    private static boolean booleanValue(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Boolean result ? result : value instanceof Number number && number.intValue() != 0;
    }

    /** get_user_auth_context_by_email deliberately omits the credential hash. */
    private static String optionalString(ResultSet rs, String column) throws SQLException {
        try {
            return rs.getString(column);
        } catch (SQLException exception) {
            if ("42703".equals(exception.getSQLState())
                    || exception.getMessage() != null && exception.getMessage().contains("was not found")) return null;
            throw exception;
        }
    }

    private List<String> toComponents(Object value) throws SQLException {
        if (value instanceof Collection<?> values) return values.stream().map(String::valueOf).sorted().toList();
        if (value instanceof Array sqlArray) return Arrays.stream((Object[]) sqlArray.getArray()).map(String::valueOf).sorted().toList();
        if (value instanceof String[] values) return Arrays.stream(values).sorted().toList();
        return List.of();
    }

    private static boolean hasSqlState(Throwable error, String expected) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && expected.equalsIgnoreCase(sql.getSQLState())) return true;
        }
        return false;
    }

    private List<RoleRef> parseRoles(Object value) throws Exception {
        if (value == null) return List.of();
        JsonNode rawRoles = value instanceof String raw ? objectMapper.readTree(raw) : objectMapper.valueToTree(value);
        if (!rawRoles.isArray()) return List.of();
        Map<String, RoleRef> selected = new HashMap<>();
        for (JsonNode role : rawRoles) {
            String name = text(role, "Name", "name");
            if (name.isBlank()) continue;
            RoleRef candidate = new RoleRef(name, number(role, "Version", "version"), number(role, "Priority", "priority"));
            selected.merge(name.toLowerCase(Locale.ROOT), candidate,
                    (left, right) -> right.priority() > left.priority()
                            || right.priority() == left.priority() && right.version() > left.version() ? right : left);
        }
        return selected.values().stream().sorted(Comparator.comparingInt(RoleRef::priority).reversed()
                .thenComparing(RoleRef::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private static String text(JsonNode value, String primary, String alternative) {
        JsonNode field = value.get(primary);
        if (field == null) field = value.get(alternative);
        return field == null || field.isNull() ? "" : field.asText().trim();
    }

    private static int number(JsonNode value, String primary, String alternative) {
        JsonNode field = value.get(primary);
        if (field == null) field = value.get(alternative);
        return field == null || field.isNull() ? 0 : field.asInt();
    }

    /** Converts JDBC-only PostgreSQL values into JSON-safe Java values before API serialization. */
    private Object normalizeValue(Object value) {
        if (value == null) return null;
        if (value instanceof Array sqlArray) {
            try { return Arrays.asList((Object[]) sqlArray.getArray()); }
            catch (Exception exception) { return List.of(); }
        }
        if (value instanceof Clob clob) {
            try { return clob.getSubString(1, Math.toIntExact(clob.length())); }
            catch (Exception exception) { return null; }
        }
        if ("org.postgresql.util.PGobject".equals(value.getClass().getName())) {
            try { return objectMapper.readValue(String.valueOf(value), new TypeReference<Object>() { }); }
            catch (Exception exception) { return String.valueOf(value); }
        }
        return value;
    }

    public record RoleRef(String name, int version, int priority) { }
    private record PostgresAuthRow(AuthUser user, Object roles, Object components) { }
    public record AuthUser(Object id, @JsonProperty("display_name") String displayName, String password,
                           @JsonProperty("is_privileged_account") boolean isPrivilegedAccount, String type, String status,
                           @JsonProperty("is_deleted") boolean isDeleted, @JsonProperty("created_date") Timestamp createdDate,
                           @JsonProperty("created_user") String createdUser, @JsonProperty("modified_date") Timestamp modifiedDate,
                           @JsonProperty("modified_by") String modifiedBy, String email, @JsonProperty("last_login") Timestamp lastLogin,
                           @JsonProperty("allow_public") boolean allowPublic, @JsonProperty("avatar_url") String avatarUrl,
                           Object setting) { }
    public record UserAuthContext(AuthUser user, List<RoleRef> roles, List<String> components) { }
    public static final class InvalidCredentialsException extends RuntimeException { }
}
