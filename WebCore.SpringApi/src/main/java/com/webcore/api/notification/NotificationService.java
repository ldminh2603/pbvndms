package com.webcore.api.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.runtime.SourceDataSourceRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Notification persistence matches .NET: PostgreSQL, SQL Server, and Oracle
 * read the durable messaging tables through DBCORE_messaging. Redis is used only
 * when that source is unavailable, never merely because its provider differs.
 */
@Service
public class NotificationService {
    private static final String MESSAGING_SOURCE = "DBCORE_messaging";
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final CoreMetadataService core;
    private final SourceDataSourceRegistry sources;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final NotificationProperties properties;
    private final NotificationRealtimePublisher realtime;

    public NotificationService(CoreMetadataService core, SourceDataSourceRegistry sources, StringRedisTemplate redis,
                               ObjectMapper json, NotificationProperties properties, NotificationRealtimePublisher realtime) {
        this.core = core; this.sources = sources; this.redis = redis; this.json = json; this.properties = properties; this.realtime = realtime;
    }

    public boolean usesRedis() { return core.source(MESSAGING_SOURCE) == null; }

    public Response recent(String email, int limit) {
        int take = Math.clamp(limit, 1, 20);
        return usesRedis() ? redisRecent(email, take) : database(email, "all", false, 0, take, true);
    }

    public Response list(String email, String category, boolean unreadOnly, int skip, int take) {
        String normalized = category == null ? "all" : category.trim().toLowerCase(Locale.ROOT);
        if (!List.of("all", "system", "personal", "workflow").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported notification category: " + category);
        }
        return usesRedis() ? redisList(email, normalized, unreadOnly, skip, take)
                : database(email, normalized, unreadOnly, Math.max(skip, 0), Math.clamp(take, 1, 100), false);
    }

    public Instant read(String email, String id) { return usesRedis() ? redisUpdate(email, id, false) : update(email, id, false); }
    public Instant dismiss(String email, String id) { return usesRedis() ? redisUpdate(email, id, true) : update(email, id, true); }
    public int readAll(String email, String category) { return usesRedis() ? redisReadAll(email, category) : updateAll(email, category); }

    /**
     * Stores a workflow notification in the shared messaging database.  It is deliberately
     * not written to the Redis fallback: a node-local fallback would make a multi-node
     * deployment show different badges depending on which node served the request.
     */
    public void createWorkflow(Collection<String> recipientEmails, String title, String message,
                               String caseId, String actionUrl, String actor, String workflowEvent) {
        if (recipientEmails == null || recipientEmails.isEmpty()) return;
        if (usesRedis()) {
            log.warn("Workflow notification for case {} was skipped because DBCORE_messaging is not configured.", caseId);
            return;
        }
        if (!"POSTGRES".equalsIgnoreCase(sources.provider(MESSAGING_SOURCE))) {
            throw new IllegalStateException("Workflow notifications require the PostgreSQL DBCORE_messaging schema.");
        }
        List<String> recipients = recipientEmails.stream()
                .filter(email -> email != null && !email.isBlank())
                .map(email -> email.trim().toLowerCase(Locale.ROOT)).distinct().toList();
        if (recipients.isEmpty()) return;
        String metadata;
        try { metadata = json.writeValueAsString(java.util.Map.of("workflowEvent", workflowEvent)); }
        catch (Exception error) { throw new IllegalStateException("Unable to serialize workflow notification metadata.", error); }
        JdbcTemplate database = db();
        for (String recipient : recipients) {
            Long id = database.queryForObject("""
                    SELECT app.create_notification(?, ?, ?, 'action', 'workflow', 'high', 'DMS_WORKFLOW',
                        'portal_case', ?, ?, CAST(? AS jsonb), NULL, ?, 'USER', ?)
                    """, Long.class, recipient, title, message, caseId, actionUrl, metadata, actor, recipient);
            if (id != null) realtime.publish(recipient, new Item(String.valueOf(id), "action", "workflow", "high", title, message,
                    "DMS_WORKFLOW", "portal_case", caseId, actionUrl, Instant.now(), null, null, null));
        }
    }

    /** .NET-compatible view action: validate owner/expiry, mark read, return action URL. */
    public ActionResult action(String email, String id, String action) {
        if (action != null && !action.isBlank() && !"view".equalsIgnoreCase(action.trim())) {
            throw new IllegalArgumentException("Only view notification action is supported.");
        }
        if (usesRedis()) {
            Item item = load(email).stream().filter(value -> value.id().equals(id) && value.active()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Notification not found."));
            redisUpdate(email, id, false);
            return new ActionResult(id, "viewed", item.actionUrl());
        }
        JdbcTemplate db = db(); Dialect dialect = dialect();
        String url = db.query(dialect.selectAction(), rs -> rs.next() ? rs.getString(1) : null, notificationId(id), email);
        if (url == null) throw new IllegalArgumentException("Notification not found.");
        update(email, id, false);
        return new ActionResult(id, "viewed", url);
    }

    private Response database(String email, String category, boolean unreadOnly, int skip, int take, boolean inbox) {
        Dialect dialect = dialect(); JdbcTemplate db = db(); String table = inbox ? dialect.inbox() : dialect.history();
        Object unreadParameter = dialect.booleanParameter(unreadOnly);
        List<Item> items = db.query(dialect.select(table), this::item, email, category, category, unreadParameter, skip, take);
        Integer unread = db.queryForObject(dialect.countUnread(), Integer.class, email);
        Integer total = db.queryForObject(dialect.count(table), Integer.class, email, category, category, unreadParameter);
        return new Response(items, unread == null ? 0 : unread, total == null ? 0 : total);
    }

    private Instant update(String email, String id, boolean dismiss) {
        Dialect dialect = dialect(); JdbcTemplate db = db(); Instant now = Instant.now(); Timestamp timestamp = Timestamp.from(now);
        Object idValue = notificationId(id);
        int count = db.update(dialect.update(dialect.inbox(), dismiss), dismiss ? new Object[]{timestamp, timestamp, idValue, email} : new Object[]{timestamp, idValue, email});
        db.update(dialect.update(dialect.history(), dismiss), dismiss ? new Object[]{timestamp, timestamp, idValue, email} : new Object[]{timestamp, idValue, email});
        if (count == 0) throw new IllegalArgumentException("Notification not found.");
        return now;
    }

    private int updateAll(String email, String category) {
        Dialect dialect = dialect(); Instant now = Instant.now();
        return db().update(dialect.readAll(), Timestamp.from(now), email, category == null ? "all" : category, category == null ? "all" : category);
    }

    private JdbcTemplate db() { return new JdbcTemplate(sources.resolve(MESSAGING_SOURCE)); }
    private Dialect dialect() { return Dialect.forProvider(sources.provider(MESSAGING_SOURCE)); }
    /** Notification tables use a BIGINT key; path variables arrive as text. */
    private static Object notificationId(String id) {
        try { return Long.valueOf(id); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Notification id must be numeric.", error); }
    }
    private Item item(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        return new Item(String.valueOf(rs.getObject("id")), rs.getString("type"), rs.getString("category"), rs.getString("severity"),
                rs.getString("title"), rs.getString("message"), rs.getString("source"), rs.getString("entity_type"), rs.getString("entity_id"),
                rs.getString("action_url"), rs.getTimestamp("created_at").toInstant(), instant(rs, "read_at"), instant(rs, "dismissed_at"), instant(rs, "expires_at"));
    }
    private static Instant instant(java.sql.ResultSet rs, String name) throws java.sql.SQLException { Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant(); }

    private Response redisRecent(String email, int take) { List<Item> all = load(email); return new Response(all.stream().filter(Item::active).sorted(Comparator.comparing(Item::createdAt).reversed()).limit(take).toList(), unread(all), all.size()); }
    private Response redisList(String email, String category, boolean unreadOnly, int skip, int take) { List<Item> all = load(email); List<Item> filtered = all.stream().filter(Item::active).filter(item -> "all".equals(category) || category.equalsIgnoreCase(item.category())).filter(item -> !unreadOnly || item.readAt() == null).sorted(Comparator.comparing(Item::createdAt).reversed()).toList(); return new Response(filtered.stream().skip(Math.max(skip, 0)).limit(Math.clamp(take, 1, 100)).toList(), unread(all), filtered.size()); }
    private Instant redisUpdate(String email, String id, boolean dismiss) { List<Item> items = new ArrayList<>(load(email)); Instant now = Instant.now(); for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(id)) { Item item = items.get(i); items.set(i, dismiss ? item.withDismissed(now) : item.withRead(now)); save(email, items); return now; } throw new IllegalArgumentException("Notification not found."); }
    private int redisReadAll(String email, String category) { List<Item> items = new ArrayList<>(load(email)); Instant now = Instant.now(); int updated = 0; for (int i = 0; i < items.size(); i++) { Item item = items.get(i); if (item.active() && item.readAt() == null && ("all".equals(category) || category.equalsIgnoreCase(item.category()))) { items.set(i, item.withRead(now)); updated++; } } save(email, items); return updated; }
    private List<Item> load(String email) { try { String raw = redis.opsForValue().get(key(email)); return raw == null ? List.of() : json.readValue(raw, new TypeReference<>() {}); } catch (Exception ex) { throw new IllegalStateException("Redis notification store is unavailable.", ex); } }
    private void save(String email, List<Item> items) { try { redis.opsForValue().set(key(email), json.writeValueAsString(items)); } catch (Exception ex) { throw new IllegalStateException("Redis notification store is unavailable.", ex); } }
    private String key(String email) { return (properties.redisKeyPrefix() == null ? "webcore:notification:" : properties.redisKeyPrefix()) + email.trim().toLowerCase(Locale.ROOT); }
    private static int unread(List<Item> items) { return (int) items.stream().filter(Item::active).filter(item -> item.readAt() == null).count(); }

    public record Item(String id, String type, String category, String severity, String title, String message, String source, String entityType, String entityId, String actionUrl, Instant createdAt, Instant readAt, Instant dismissedAt, Instant expiresAt) {
        boolean active() { return dismissedAt == null && (expiresAt == null || expiresAt.isAfter(Instant.now())); }
        Item withRead(Instant at) { return new Item(id, type, category, severity, title, message, source, entityType, entityId, actionUrl, createdAt, readAt == null ? at : readAt, dismissedAt, expiresAt); }
        Item withDismissed(Instant at) { return new Item(id, type, category, severity, title, message, source, entityType, entityId, actionUrl, createdAt, readAt == null ? at : readAt, at, expiresAt); }
    }
    public record Response(List<Item> items, int unreadCount, int totalCount) { }
    public record ActionResult(String id, String actionStatus, String actionUrl) { }

    private record Dialect(String inbox, String history, String now, String falseLiteral, boolean numericBoolean) {
        static Dialect forProvider(String provider) {
            String normalized = provider.toUpperCase(Locale.ROOT);
            if ("MSSQLSERVER".equals(normalized) || "MSSQL".equals(normalized)) normalized = "SQLSERVER";
            return switch (normalized) {
                case "POSTGRES" -> new Dialect("notification_inbox", "notification_history", "CURRENT_TIMESTAMP", "false", false);
                case "SQLSERVER" -> new Dialect("notification_inbox", "notification_history", "SYSUTCDATETIME()", "0", true);
                case "ORACLE" -> new Dialect("notification_inbox", "notification_history", "SYS_EXTRACT_UTC(SYSTIMESTAMP)", "0", true);
                default -> throw new IllegalArgumentException("Unsupported notification provider: " + provider);
            };
        }
        Object booleanParameter(boolean value) { return numericBoolean ? (value ? 1 : 0) : value; }
        String select(String table) { return "SELECT id,title,message,type,category,severity,source,entity_type,entity_id,action_url,created_at,read_at,dismissed_at,expires_at FROM " + table + " WHERE user_email=? AND dismissed_at IS NULL AND (expires_at IS NULL OR expires_at > " + now + ") AND (?='all' OR category=?) AND (?=" + falseLiteral + " OR read_at IS NULL) ORDER BY created_at DESC,id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"; }
        String countUnread() { return "SELECT COUNT(*) FROM " + inbox + " WHERE user_email=? AND dismissed_at IS NULL AND read_at IS NULL AND (expires_at IS NULL OR expires_at > " + now + ")"; }
        String count(String table) { return "SELECT COUNT(*) FROM " + table + " WHERE user_email=? AND dismissed_at IS NULL AND (expires_at IS NULL OR expires_at > " + now + ") AND (?='all' OR category=?) AND (?=" + falseLiteral + " OR read_at IS NULL)"; }
        String update(String table, boolean dismiss) { return dismiss ? "UPDATE " + table + " SET dismissed_at=COALESCE(dismissed_at,?), read_at=COALESCE(read_at,?) WHERE id=? AND user_email=?" : "UPDATE " + table + " SET read_at=COALESCE(read_at,?) WHERE id=? AND user_email=?"; }
        String readAll() { return "UPDATE " + inbox + " SET read_at=COALESCE(read_at,?) WHERE user_email=? AND dismissed_at IS NULL AND read_at IS NULL AND (?='all' OR category=?)"; }
        String selectAction() { return "SELECT action_url FROM " + history + " WHERE id=? AND user_email=? AND dismissed_at IS NULL AND (expires_at IS NULL OR expires_at > " + now + ")"; }
    }
}
