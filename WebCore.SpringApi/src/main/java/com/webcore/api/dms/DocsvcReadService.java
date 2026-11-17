package com.webcore.api.dms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;

import com.webcore.api.database.CoreDataSourceResolver;
import com.webcore.api.core.CoreMetadataService;
import com.webcore.api.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** Read adapter for the PBBDMS docsvc schema. It never writes around the PBBDMS document service. */
@Service
public class DocsvcReadService {
    private static final Logger log = LoggerFactory.getLogger(DocsvcReadService.class);
    private final CoreDataSourceResolver sources;
    private final DmsObjectStorageService objects;
    private final DmsClamdScanService scanner;
    private final NotificationService notifications;
    private final CoreMetadataService core;

    public DocsvcReadService(CoreDataSourceResolver sources, DmsObjectStorageService objects, DmsClamdScanService scanner,
                             NotificationService notifications, CoreMetadataService core) {
        this.sources = sources; this.objects = objects; this.scanner = scanner; this.notifications = notifications; this.core = core;
    }

    public List<DocumentRow> list() {
        return jdbc().query("""
                SELECT d.document_id::text AS "id", d.original_filename AS "name", COALESCE(d.reference_text, '') AS "referenceText",
                       COALESCE(category.description, d.category_code) AS "category", COALESCE(type.description, d.type_code) AS "documentType",
                       d.version::text AS "version", d.status AS "documentStatus", d.scan_status AS "scanStatus",
                       COALESCE(workflow.state, 'NO_CASE') AS "workflowState", d.size_bytes AS "fileSizeBytes",
                       d.detected_mime AS "mimeType", d.created_by AS "createdBy", d.branch_code AS "branchCode",
                       d.legal_hold AS "legalHold", COALESCE(link.object_type, '') AS "objectType", COALESCE(link.object_ref, '') AS "objectRef",
                       to_char(d.created_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "createdAt"
                FROM document d
                LEFT JOIN LATERAL (
                    SELECT object_type, object_ref FROM document_link
                    WHERE document_id = d.document_id ORDER BY linked_at DESC LIMIT 1
                ) link ON true
                LEFT JOIN LATERAL (
                    SELECT * FROM portal_case candidate
                    WHERE candidate.object_type = link.object_type AND candidate.object_ref = link.object_ref
                      AND candidate.state <> 'CANCELLED'
                    ORDER BY candidate.amendment_no DESC LIMIT 1
                ) workflow ON true
                LEFT JOIN doc_category category ON category.category_code = d.category_code
                LEFT JOIN doc_type type ON type.type_code = d.type_code
                ORDER BY d.created_at DESC
                """, (rs, row) -> new DocumentRow(rs.getString("id"), rs.getString("name"), rs.getString("referenceText"), rs.getString("category"), rs.getString("documentType"), rs.getString("version"), rs.getString("documentStatus"), rs.getString("scanStatus"), rs.getString("workflowState"), rs.getLong("fileSizeBytes"), rs.getString("mimeType"), rs.getString("createdBy"), rs.getString("branchCode"), rs.getBoolean("legalHold"), rs.getString("objectType"), rs.getString("objectRef"), rs.getString("createdAt")));
    }

    /** Reads one document for a fixed right-panel module without exposing the DMS page route. */
    public DocumentRow document(String id) {
        return list().stream().filter(item -> item.id().equalsIgnoreCase(id))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Document was not found: " + id));
    }

    /** Current PBBDMS transaction cycles used by the DMS worklist. */
    public List<CaseRow> cases() {
        return jdbc().query("""
                WITH current_cycle AS (
                    SELECT workflow.*, row_number() OVER (
                        PARTITION BY workflow.object_type, workflow.object_ref
                        ORDER BY workflow.amendment_no DESC, workflow.updated_at DESC
                    ) AS cycle_rank
                    FROM portal_case workflow
                    WHERE workflow.state <> 'CANCELLED'
                )
                SELECT workflow.case_id::text AS "caseId", workflow.object_type AS "objectType", workflow.object_ref AS "objectRef",
                       workflow.branch_code AS "branchCode", workflow.state, workflow.maker,
                       COALESCE(workflow.customer_number, '') AS "customerNumber", COALESCE(workflow.customer_name, '') AS "customerName",
                       workflow.amendment_no AS "amendmentNo",
                       (SELECT count(*) FROM document_link link JOIN document d ON d.document_id = link.document_id
                        WHERE link.object_type = workflow.object_type AND link.object_ref = workflow.object_ref AND d.status = 'ACTIVE') AS "activeDocumentCount",
                       to_char(workflow.updated_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "updatedAt"
                FROM current_cycle workflow
                WHERE workflow.cycle_rank = 1
                ORDER BY workflow.updated_at DESC
                """, (rs, row) -> new CaseRow(rs.getString("caseId"),rs.getString("objectType"),rs.getString("objectRef"),rs.getString("branchCode"),rs.getString("state"),rs.getString("maker"),rs.getString("customerNumber"),rs.getString("customerName"),rs.getInt("amendmentNo"),rs.getInt("activeDocumentCount"),rs.getString("updatedAt")));
    }

    /** Opens a transaction case before documents are attached to it. */
    public CaseRow createCase(String objectType, String objectRef, String branchCode,
                                           String customerNumber, String customerName, String productTypeCode,
                                           String requestTypeCode, String actor) {
        require(objectType, "objectType"); require(objectRef, "objectRef"); require(branchCode, "branchCode");
        require(customerNumber, "customerNumber"); require(customerName, "customerName");
        require(productTypeCode, "productTypeCode"); require(requestTypeCode, "requestTypeCode");
        return transaction().execute(status -> {
            JdbcTemplate jdbc = jdbc();
            validateRequirementContext(jdbc, productTypeCode.trim(), requestTypeCode.trim());
            UUID caseId = ensureCase(jdbc, objectType.trim(), objectRef.trim(), branchCode.trim(), customerNumber.trim(), customerName.trim(),
                    productTypeCode.trim(), requestTypeCode.trim(), actor);
            return jdbc().queryForObject("""
                    SELECT case_id::text AS "caseId", object_type AS "objectType", object_ref AS "objectRef", branch_code AS "branchCode",
                           state, maker, COALESCE(customer_number, '') AS "customerNumber", COALESCE(customer_name, '') AS "customerName",
                           amendment_no AS "amendmentNo", 0 AS "activeDocumentCount",
                           to_char(updated_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "updatedAt"
                    FROM portal_case WHERE case_id = CAST(? AS uuid)
                    """, (rs, row) -> new CaseRow(rs.getString("caseId"),rs.getString("objectType"),rs.getString("objectRef"),rs.getString("branchCode"),rs.getString("state"),rs.getString("maker"),rs.getString("customerNumber"),rs.getString("customerName"),rs.getInt("amendmentNo"),rs.getInt("activeDocumentCount"),rs.getString("updatedAt")), caseId.toString());
        });
    }

    /**
     * Read contract for a trusted external caller.  The lookup is always by
     * the supplied DMS UUID; it deliberately has no object-reference fallback.
     */
    public ExternalCaseDetail externalCaseDetail(String caseId, String actor) {
        require(caseId, "caseId");
        final String normalizedCaseId;
        try {
            normalizedCaseId = UUID.fromString(caseId.trim()).toString();
        } catch (IllegalArgumentException exception) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "caseId must be a UUID.");
        }
        ExternalCaseInfo workflowCase;
        try {
            workflowCase = jdbc().queryForObject("""
                    SELECT case_id::text AS "caseId", object_type AS "objectType", object_ref AS "objectRef",
                           branch_code AS "branchCode", COALESCE(customer_number, '') AS "customerNumber",
                           COALESCE(customer_name, '') AS "customerName", product_type_code AS "productTypeCode",
                           request_type_code AS "requestTypeCode", state,
                           to_char(created_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "createdAt",
                           to_char(updated_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "updatedAt"
                    FROM portal_case
                    WHERE case_id = CAST(? AS uuid)
                    """, (rs, row) -> new ExternalCaseInfo(rs.getString("caseId"), rs.getString("objectType"), rs.getString("objectRef"),
                    rs.getString("branchCode"), rs.getString("customerNumber"), rs.getString("customerName"), rs.getString("productTypeCode"),
                    rs.getString("requestTypeCode"), rs.getString("state"), rs.getString("createdAt"), rs.getString("updatedAt")), normalizedCaseId);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "DMS case was not found: " + normalizedCaseId);
        }
        List<WorkflowEvent> history = jdbc().query("""
                SELECT action, actor, remarks, to_char(event_time AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "eventTime"
                FROM portal_case_event WHERE case_id = CAST(? AS uuid) ORDER BY event_time
                """, (rs, row) -> new WorkflowEvent(rs.getString("action"), rs.getString("actor"), rs.getString("remarks"), rs.getString("eventTime")), workflowCase.caseId());
        List<ExternalDocument> documents = jdbc().query("""
                SELECT d.document_id::text AS "documentId", d.original_filename AS "fileName", d.category_code AS "categoryCode",
                       d.type_code AS "typeCode", COALESCE(d.reference_text, '') AS "referenceText", d.version,
                       d.status AS "documentStatus", d.scan_status AS "scanStatus",
                       to_char(d.created_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "createdAt"
                FROM document_link link JOIN document d ON d.document_id = link.document_id
                WHERE link.object_type = ? AND link.object_ref = ?
                ORDER BY d.created_at DESC, d.version DESC
                """, (rs, row) -> new ExternalDocument(rs.getString("documentId"), rs.getString("fileName"), rs.getString("categoryCode"),
                rs.getString("typeCode"), rs.getString("referenceText"), rs.getInt("version"), rs.getString("documentStatus"),
                rs.getString("scanStatus"), rs.getString("createdAt")), workflowCase.objectType(), workflowCase.objectRef());
        jdbc().update("""
                INSERT INTO audit_event (event_time, actor, actor_system, action, document_id, object_type, object_ref, outcome, detail_json)
                VALUES (now(), ?, 'EXTERNAL_API', 'EXTERNAL_CASE_READ', NULL, ?, ?, 'SUCCESS', jsonb_build_object('caseId', ?))
                """, actor, workflowCase.objectType(), workflowCase.objectRef(), workflowCase.caseId());
        return new ExternalCaseDetail(workflowCase, history, documents);
    }

    public List<VersionRow> versions(String id) {
        return jdbc().query("""
                WITH RECURSIVE ancestors AS (
                    SELECT document_id, replaces_document_id FROM document WHERE document_id = CAST(? AS uuid)
                    UNION ALL
                    SELECT parent.document_id, parent.replaces_document_id FROM document parent
                    JOIN ancestors child ON child.replaces_document_id = parent.document_id
                ), root AS (
                    SELECT document_id FROM ancestors WHERE replaces_document_id IS NULL LIMIT 1
                ), chain AS (
                    SELECT d.document_id, d.replaces_document_id, d.version, d.original_filename, d.size_bytes, d.status, d.created_by, d.created_at
                    FROM document d JOIN root ON root.document_id = d.document_id
                    UNION ALL
                    SELECT child.document_id, child.replaces_document_id, child.version, child.original_filename, child.size_bytes, child.status, child.created_by, child.created_at
                    FROM document child JOIN chain parent ON child.replaces_document_id = parent.document_id
                )
                SELECT document_id::text AS "id", version::text AS "version", original_filename AS "fileName", size_bytes AS "fileSizeBytes",
                       status AS "documentStatus", created_by AS "createdBy",
                       to_char(created_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "createdAt"
                FROM chain ORDER BY created_at DESC, version DESC
                """, (rs, row) -> new VersionRow(rs.getString("id"),rs.getString("version"),rs.getString("fileName"),rs.getLong("fileSizeBytes"),rs.getString("documentStatus"),rs.getString("createdBy"),rs.getString("createdAt")), id);
    }

    public Workflow workflow(String id, String objectType, String objectRef, String actor, List<String> roles, boolean privileged) {
        List<WorkflowContext> contexts = jdbc().query("""
                SELECT link.object_type, link.object_ref, workflow.case_id::text AS case_id, workflow.state, workflow.maker,
                       workflow.submitted_by, workflow.checker, workflow.authorised_by, workflow.workflow_version, workflow.amendment_no
                FROM document_link link
                LEFT JOIN LATERAL (
                    SELECT * FROM portal_case candidate
                    WHERE candidate.object_type = link.object_type AND candidate.object_ref = link.object_ref
                      AND candidate.state <> 'CANCELLED'
                    ORDER BY candidate.amendment_no DESC LIMIT 1
                ) workflow ON true
                WHERE link.document_id = CAST(? AS uuid) AND (? = '' OR link.object_type = ?) AND (? = '' OR link.object_ref = ?)
                ORDER BY link.linked_at DESC LIMIT 1
                """, (rs, row) -> workflowContext(rs), id, objectType, objectType, objectRef, objectRef);
        if (contexts.isEmpty()) return Workflow.noCase();
        WorkflowContext context = contexts.get(0);
        String caseId = context.caseId();
        List<WorkflowEvent> events = caseId == null ? List.of() : jdbc().query("""
                SELECT action, actor, remarks, to_char(event_time AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "eventTime"
                FROM portal_case_event WHERE case_id = CAST(? AS uuid) ORDER BY event_time
                """, (rs, row) -> new WorkflowEvent(rs.getString("action"), rs.getString("actor"), rs.getString("remarks"), rs.getString("eventTime")), String.valueOf(caseId));
        return new Workflow(context.caseId(), context.objectType(), context.objectRef(), context.state() == null ? "NO_CASE" : context.state(), context.maker(),
                context.submittedBy(), context.checker(), context.authorisedBy(), context.workflowVersion(), context.amendmentNo(), canEditDocuments(context),
                canOpenAmendment(context, roles, privileged), canCancelAmendment(context, roles, privileged), events,
                availableActions(context, actor, roles, privileged));
    }

    /** All portal cycles remain immutable; this view makes prior amendment histories visible beside the current cycle. */
    public List<Amendment> amendmentHistory(String documentId) {
        List<ObjectReference> object = jdbc().query("""
                SELECT object_type, object_ref FROM document_link WHERE document_id = CAST(? AS uuid)
                ORDER BY linked_at DESC LIMIT 1
                """, (rs, row) -> new ObjectReference(rs.getString("object_type"), rs.getString("object_ref")), documentId);
        if (object.isEmpty()) return List.of();
        String objectType = object.getFirst().objectType();
        String objectRef = object.getFirst().objectRef();
        List<Amendment> cycles = jdbc().query("""
                SELECT case_id::text AS "caseId", amendment_no AS "amendmentNo", state, COALESCE(amendment_reason, '') AS "reason",
                       COALESCE(opened_by, '') AS "openedBy", COALESCE(maker, '') AS maker, COALESCE(authorised_by, '') AS "authorisedBy",
                       to_char(created_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "createdAt",
                       to_char(updated_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "updatedAt",
                       (SELECT count(*) FROM authorised_manifest manifest WHERE manifest.object_type = portal_case.object_type
                         AND manifest.object_ref = portal_case.object_ref AND manifest.amendment_no = portal_case.amendment_no) AS "documentCount"
                FROM portal_case WHERE object_type = ? AND object_ref = ? ORDER BY amendment_no DESC
                """, (rs, row) -> new Amendment(rs.getString("caseId"), rs.getInt("amendmentNo"), rs.getString("state"),
                rs.getString("reason"), rs.getString("openedBy"), rs.getString("maker"), rs.getString("authorisedBy"),
                rs.getString("createdAt"), rs.getString("updatedAt"), rs.getInt("documentCount"), List.of()), objectType, objectRef);
        return cycles.stream().map(cycle -> new Amendment(cycle.caseId(), cycle.amendmentNo(), cycle.state(), cycle.reason(),
                cycle.openedBy(), cycle.maker(), cycle.authorisedBy(), cycle.createdAt(), cycle.updatedAt(), cycle.documentCount(),
                amendmentEvents(cycle.caseId()))).toList();
    }

    public DmsObjectStorageService.StoredContent content(String id, String actor, String accessAction) {
        long startedAt = System.nanoTime();
        StoredDocumentLocation row = jdbc().queryForObject("""
                SELECT d.storage_bucket, d.storage_key, d.original_filename, link.object_type, link.object_ref
                FROM document d LEFT JOIN LATERAL (
                    SELECT object_type, object_ref FROM document_link WHERE document_id = d.document_id
                    ORDER BY linked_at DESC LIMIT 1
                ) link ON true
                WHERE d.document_id = CAST(? AS uuid)
                """, (rs, index) -> new StoredDocumentLocation(rs.getString("storage_bucket"), rs.getString("storage_key"),
                rs.getString("original_filename"), rs.getString("object_type"), rs.getString("object_ref")), id);
        long documentLookupMs = elapsedMillis(startedAt);
        try {
            DmsObjectStorageService.StoredContent content = objects.readStored(row.bucket(), row.key(), row.fileName());
            jdbc().update("""
                    INSERT INTO audit_event (event_time, actor, actor_system, action, document_id, object_type, object_ref, outcome, detail_json)
                    VALUES (now(), ?, 'ADMIN_UI', ?, CAST(? AS uuid), ?, ?, 'SUCCESS', jsonb_build_object('filename', ?))
                    """, actor, accessAction, id, row.objectType(), row.objectRef(), row.fileName());
            log.info("docsvc_content_timing documentId={} documentLookupMs={} objectReadMs={} totalMs={}",
                    id, documentLookupMs, elapsedMillis(startedAt) - documentLookupMs, elapsedMillis(startedAt));
            return content;
        } catch (RuntimeException exception) {
            recordAccessFailure(row, actor, accessAction, id, exception);
            log.warn("docsvc_content_failed documentId={} documentLookupMs={} totalMs={} errorType={}",
                    id, documentLookupMs, elapsedMillis(startedAt), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    /** Operational report for FR-019. All business thresholds are maintained in core.sys_var. */
    public OperationalReport operationalReport() {
        int slaHours = positiveInteger(core.systemValue("DMS_REPORT", "SLA_HOURS", "48"), 48);
        int errorLookbackHours = positiveInteger(core.systemValue("DMS_REPORT", "ERROR_LOOKBACK_HOURS", String.valueOf(slaHours)), slaHours);
        List<String> backlogStates = csv(core.systemValue("DMS_REPORT", "BACKLOG_STATES", "DRAFT,SUBMITTED,CHECKED,PENDING_CHECKER"));
        List<String> errorGroups = csv(core.systemValue("DMS_REPORT", "ERROR_GROUPS", "INTEGRATION,STORAGE,DOWNLOAD,VIRUS_SCAN"));
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc());
        MapSqlParameterSource backlogParameters = new MapSqlParameterSource()
                .addValue("states", backlogStates).addValue("slaHours", slaHours);
        List<BacklogState> backlog = named.query("""
                SELECT state, count(*) AS total,
                       count(*) FILTER (WHERE updated_at <= now() - make_interval(hours => :slaHours)) AS overdue
                FROM portal_case
                WHERE state <> 'CANCELLED' AND state IN (:states)
                GROUP BY state ORDER BY state
                """, backlogParameters, (rs, row) -> new BacklogState(rs.getString("state"), rs.getLong("total"), rs.getLong("overdue")));
        Map<String, Long> groupedErrors = new LinkedHashMap<>();
        errorGroups.forEach(group -> groupedErrors.put(group, 0L));
        List<AuditFailure> failures = named.query("""
                SELECT action, COALESCE(detail_json ->> 'reason', '') AS reason,
                       COALESCE(detail_json ->> 'errorGroup', '') AS error_group, count(*) AS total
                FROM audit_event
                WHERE event_time >= now() - make_interval(hours => :hours) AND outcome <> 'SUCCESS'
                GROUP BY action, COALESCE(detail_json ->> 'reason', ''), COALESCE(detail_json ->> 'errorGroup', '')
                """, new MapSqlParameterSource("hours", errorLookbackHours),
                (rs, row) -> new AuditFailure(rs.getString("action"), rs.getString("reason"), rs.getString("error_group"), rs.getLong("total")));
        for (AuditFailure failure : failures) groupedErrors.merge(errorGroup(failure), failure.total(), Long::sum);
        List<ErrorGroup> errors = groupedErrors.entrySet().stream().map(entry -> new ErrorGroup(entry.getKey(), entry.getValue())).toList();
        long backlogTotal = backlog.stream().mapToLong(BacklogState::total).sum();
        long overdueTotal = backlog.stream().mapToLong(BacklogState::overdue).sum();
        return new OperationalReport(slaHours, errorLookbackHours, backlogStates, backlogTotal, overdueTotal, backlog, errors);
    }

    private void recordAccessFailure(StoredDocumentLocation row, String actor, String action, String id, RuntimeException exception) {
        try {
            jdbc().update("""
                    INSERT INTO audit_event (event_time, actor, actor_system, action, document_id, object_type, object_ref, outcome, detail_json)
                    VALUES (now(), ?, 'ADMIN_UI', ?, CAST(? AS uuid), ?, ?, 'FAILED',
                            jsonb_build_object('reason', 'STORAGE_READ_FAILED', 'errorGroup', 'STORAGE', 'errorType', ?))
                    """, actor, action, id, row.objectType(), row.objectRef(), exception.getClass().getSimpleName());
        } catch (RuntimeException auditFailure) {
            log.warn("docsvc_content_failure_audit_failed documentId={} errorType={}", id, auditFailure.getClass().getSimpleName());
        }
    }

    private static String errorGroup(AuditFailure failure) {
        if (!failure.group().isBlank()) return failure.group().trim().toUpperCase(java.util.Locale.ROOT);
        String text = (failure.action() + " " + failure.reason()).toUpperCase(java.util.Locale.ROOT);
        if (text.contains("MALWARE") || text.contains("VIRUS") || text.contains("SCAN")) return "VIRUS_SCAN";
        if (text.contains("DOWNLOAD") || text.contains("PREVIEW")) return "DOWNLOAD";
        if (text.contains("STORAGE") || text.contains("OBJECT")) return "STORAGE";
        return "INTEGRATION";
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank())
                .map(item -> item.toUpperCase(java.util.Locale.ROOT)).distinct().toList();
    }
    private static int positiveInteger(String value, int fallback) {
        try { int parsed = Integer.parseInt(value); return parsed > 0 ? parsed : fallback; }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static long elapsedMillis(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }

    public Workflow openAmendmentByCase(String caseId, String reason, String actor, List<String> roles, boolean privileged) {
        return transaction().execute(status -> openAmendmentInTransaction(workflowContextByCase(jdbc(), caseId), reason, actor, roles, privileged));
    }

    /** Opens a PBBDMS-style post-authorisation amendment cycle directly from its authorised case. */
    private Workflow openAmendmentInTransaction(WorkflowContext current, String reason, String actor, List<String> roles, boolean privileged) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 4000) {
            throw new IllegalArgumentException("An amendment reason of 1-4000 characters is required.");
        }
        JdbcTemplate jdbc = jdbc();
        if (!canOpenAmendment(current, roles, privileged)) {
            throw new IllegalArgumentException("Only an authorised transaction can open an amendment.");
        }
        String objectType = current.objectType();
        String objectRef = current.objectRef();
        int baselineNo = current.amendmentNo();
        Integer hasSnapshot = jdbc.queryForObject("SELECT count(*) FROM authorised_manifest WHERE object_type = ? AND object_ref = ? AND amendment_no = ?", Integer.class, objectType, objectRef, baselineNo);
        if (hasSnapshot == null || hasSnapshot == 0) {
            jdbc.update("""
                    INSERT INTO authorised_manifest (object_type, object_ref, amendment_no, document_id, authorised_by, authorised_at)
                    SELECT ?, ?, ?, d.document_id, COALESCE(NULLIF(?, ''), 'system-baseline'), now()
                    FROM document_link link JOIN document d ON d.document_id = link.document_id
                    WHERE link.object_type = ? AND link.object_ref = ? AND d.status = 'ACTIVE'
                    ON CONFLICT (object_type, object_ref, amendment_no, document_id) DO NOTHING
                    """, objectType, objectRef, baselineNo, current.authorisedBy(), objectType, objectRef);
        }
        int amendmentNo = jdbc.queryForObject("SELECT COALESCE(max(amendment_no), 0) + 1 FROM portal_case WHERE object_type = ? AND object_ref = ?", Integer.class, objectType, objectRef);
        WorkflowDefinition definition = workflowDefinition(jdbc, objectType);
        UUID caseId = UUID.randomUUID();
        String maker = roles.stream().anyMatch(role -> "HEAD_OFFICE".equalsIgnoreCase(role)) && !privileged
                ? current.maker() : actor;
        jdbc.update("""
                INSERT INTO portal_case (case_id, object_type, object_ref, branch_code, state, maker, submitted_by, checker, authorised_by,
                    created_at, updated_at, workflow_version, customer_number, customer_name, product_type_code, request_type_code, amendment_no, amendment_reason, opened_by)
                SELECT ?, object_type, object_ref, branch_code, ?, ?, NULL, NULL, NULL, now(), now(), ?, customer_number, customer_name, product_type_code, request_type_code, ?, ?, ?
                FROM portal_case WHERE case_id = CAST(? AS uuid)
                """, caseId, definition.initial(), maker, definition.version(), amendmentNo, reason.trim(), actor, current.caseId());
        jdbc.update("UPDATE object_state SET authorised = false, updated_by = ?, updated_at = now() WHERE object_type = ? AND object_ref = ?", actor, objectType, objectRef);
        jdbc.update("INSERT INTO portal_case_event (case_id, event_time, actor, action, remarks) VALUES (?, now(), ?, 'AMENDMENT_OPEN', ?)", caseId, actor, reason.trim());
        jdbc.update("""
                INSERT INTO audit_event (event_time, actor, actor_system, action, document_id, object_type, object_ref, outcome, detail_json)
                VALUES (now(), ?, 'ADMIN_UI', 'AMENDMENT_OPEN', NULL, ?, ?, 'SUCCESS', jsonb_build_object('amendmentNo', ?, 'reason', ?))
                """, actor, objectType, objectRef, amendmentNo, reason.trim());
        return workflowByCase(caseId.toString(), actor, roles, privileged);
    }

    /** Case-level entry point for external systems; no document lookup is performed. */
    public Workflow actWorkflowByCase(String caseId, String action, String remarks, String actor, List<String> roles, boolean privileged) {
        return transaction().execute(status -> actWorkflowInTransaction(workflowContextByCase(jdbc(), caseId), action, remarks, actor, roles, privileged));
    }

    private Workflow workflowByCase(String caseId, String actor, List<String> roles, boolean privileged) {
        WorkflowContext context = workflowContextByCase(jdbc(), caseId);
        List<WorkflowEvent> events = jdbc().query("""
                SELECT action, actor, remarks, to_char(event_time AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "eventTime"
                FROM portal_case_event WHERE case_id = CAST(? AS uuid) ORDER BY event_time
                """, (rs, row) -> new WorkflowEvent(rs.getString("action"), rs.getString("actor"), rs.getString("remarks"), rs.getString("eventTime")), caseId);
        return new Workflow(context.caseId(), context.objectType(), context.objectRef(), context.state(), context.maker(),
                context.submittedBy(), context.checker(), context.authorisedBy(), context.workflowVersion(), context.amendmentNo(),
                canEditDocuments(context), canOpenAmendment(context, roles, privileged), canCancelAmendment(context, roles, privileged), events,
                availableActions(context, actor, roles, privileged));
    }

    private Workflow actWorkflowInTransaction(WorkflowContext context, String action, String remarks, String actor, List<String> roles, boolean privileged) {
        JdbcTemplate jdbc = jdbc();
        WorkflowTransition transition = jdbc.queryForObject("""
                SELECT transition.action AS "action", transition."to" AS "toState", transition.event AS "event",
                       transition.role AS "role", transition."fourEyes" AS "fourEyes", transition.remarks AS "remarks", transition."onEnter" AS "onEnter"
                FROM portal_workflow_definition definition
                CROSS JOIN LATERAL jsonb_to_recordset(definition.definition -> 'transitions') AS transition(
                    action text, "from" jsonb, "to" text, role text, "fourEyes" text, event text, remarks text, "onEnter" text)
                WHERE definition.object_type = ? AND definition.version = ? AND transition.action = ? AND jsonb_exists(transition."from", ?)
                """, (rs, row) -> new WorkflowTransition(rs.getString("action"), rs.getString("toState"), rs.getString("event"), rs.getString("role"), rs.getString("fourEyes"), rs.getString("remarks"), rs.getString("onEnter")), context.objectType(), context.workflowVersion(), action, context.state());
        String requiredRole = transition.role();
        if (!roleMatches(requiredRole, roles, privileged)) throw new SecurityException("WORKFLOW_ROLE_DENIED");
        String fourEyes = transition.fourEyes();
        if (!privileged && "NOT_SUBMITTER".equalsIgnoreCase(fourEyes) && actor.equalsIgnoreCase(context.submittedBy())) throw new IllegalArgumentException("The submitter cannot perform this check.");
        if (!privileged && "NOT_MAKER".equalsIgnoreCase(fourEyes) && actor.equalsIgnoreCase(context.maker())) throw new IllegalArgumentException("The case maker cannot authorise this case.");
        if ("required".equalsIgnoreCase(transition.remarks()) && (remarks == null || remarks.isBlank())) throw new IllegalArgumentException("A return reason is required.");
        if ("SUBMIT".equalsIgnoreCase(transition.event())) {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM document_link link JOIN document d ON d.document_id = link.document_id WHERE link.object_type = ? AND link.object_ref = ? AND d.status = 'ACTIVE'", Integer.class, context.objectType(), context.objectRef());
            if (count == null || count == 0) throw new IllegalArgumentException("At least one active document is required before submission.");
            assertRequiredDocumentsPresent(jdbc, context);
        }
        String event = transition.event();
        int changed = jdbc.update("""
                UPDATE portal_case SET state = ?, submitted_by = CASE WHEN ? = 'SUBMIT' THEN ? ELSE submitted_by END,
                    checker = CASE WHEN ? = 'CHECK' THEN ? ELSE checker END, authorised_by = CASE WHEN ? = 'AUTHORISE' THEN ? ELSE authorised_by END, updated_at = now()
                WHERE case_id = CAST(? AS uuid) AND state = ?
                """, transition.toState(), event, actor, event, actor, event, actor, context.caseId(), context.state());
        if (changed != 1) throw new IllegalArgumentException("The workflow state changed; reload and try again.");
        jdbc.update("INSERT INTO portal_case_event (case_id, event_time, actor, action, remarks) VALUES (CAST(? AS uuid), now(), ?, ?, ?)", context.caseId(), actor, event, nullable(remarks));
        // PBBDMS keeps custody history at document level too: a return applies
        // to the case, but every currently active file must carry that audit.
        if ("RETURN".equalsIgnoreCase(event)) {
            jdbc.update("""
                    INSERT INTO audit_event (event_time, actor, actor_system, action, document_id, object_type, object_ref, outcome, detail_json)
                    SELECT now(), ?, 'ADMIN_UI', 'RETURN', d.document_id, ?, ?, 'SUCCESS',
                           jsonb_build_object('remarks', ?, 'workflowEvent', 'RETURN')
                    FROM document_link link JOIN document d ON d.document_id = link.document_id
                    WHERE link.object_type = ? AND link.object_ref = ? AND d.status = 'ACTIVE'
                    """, actor, context.objectType(), context.objectRef(), nullable(remarks), context.objectType(), context.objectRef());
        }
        if ("AUTHORISE_OBJECT".equalsIgnoreCase(transition.onEnter())) {
            jdbc.update("""
                    INSERT INTO object_state (object_type, object_ref, authorised, updated_by, updated_at) VALUES (?, ?, true, ?, now())
                    ON CONFLICT (object_type, object_ref) DO UPDATE SET authorised = true, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                    """, context.objectType(), context.objectRef(), actor);
            // PBBDMS freezes the exact ACTIVE set approved for this amendment cycle.
            // DO NOTHING preserves the snapshot's immutable audit meaning on retries.
            int amendmentNo = context.amendmentNo();
            int manifestCount = jdbc.update("""
                    INSERT INTO authorised_manifest (object_type, object_ref, amendment_no, document_id, authorised_by, authorised_at)
                    SELECT ?, ?, ?, d.document_id, ?, now()
                    FROM document_link link JOIN document d ON d.document_id = link.document_id
                    WHERE link.object_type = ? AND link.object_ref = ? AND d.status = 'ACTIVE'
                    ON CONFLICT (object_type, object_ref, amendment_no, document_id) DO NOTHING
                    """, context.objectType(), context.objectRef(), amendmentNo, actor, context.objectType(), context.objectRef());
            jdbc.update("""
                    INSERT INTO audit_event (event_time, actor, actor_system, action, document_id, object_type, object_ref, outcome, detail_json)
                    VALUES (now(), ?, 'ADMIN_UI', 'AUTHORISE', NULL, ?, ?, 'SUCCESS', jsonb_build_object('amendmentNo', ?, 'documents', ?))
                    """, actor, context.objectType(), context.objectRef(), amendmentNo, manifestCount);
        }
        scheduleWorkflowNotification(context, transition, actor);
        return new Workflow(context.caseId(), context.objectType(), context.objectRef(), transition.toState(), context.maker(), context.submittedBy(), context.checker(), context.authorisedBy(), context.workflowVersion(), context.amendmentNo(), false, false, false, List.of(), List.of());
    }

    private void validateRequirementContext(JdbcTemplate jdbc, String productTypeCode, String requestTypeCode) {
        Integer matches = jdbc.queryForObject("""
                SELECT count(*)
                FROM product_type product
                JOIN request_type request ON request.product_type_code = product.product_type_code
                WHERE product.product_type_code = ? AND product.active = true
                  AND request.request_type_code = ? AND request.active = true
                """, Integer.class, productTypeCode, requestTypeCode);
        if (matches == null || matches != 1) {
            throw new IllegalArgumentException("Request type does not belong to the selected active product type.");
        }
    }

    /** Blocks SUBMIT when the case misses an active, effective required document rule. */
    private void assertRequiredDocumentsPresent(JdbcTemplate jdbc, WorkflowContext context) {
        List<CaseRequirementContext> contexts = jdbc.query("""
                SELECT product_type_code, request_type_code
                FROM portal_case WHERE case_id = CAST(? AS uuid)
                """, (rs, row) -> new CaseRequirementContext(rs.getString("product_type_code"), rs.getString("request_type_code")), context.caseId());
        if (contexts.isEmpty() || contexts.getFirst().productTypeCode() == null || contexts.getFirst().requestTypeCode() == null) {
            throw new IllegalArgumentException("Product type and request type are required before submission.");
        }
        CaseRequirementContext requirement = contexts.getFirst();
        List<RequiredDocument> missing = jdbc.query("""
                SELECT DISTINCT rule.category_code, rule.type_code, COALESCE(document_type.description, rule.type_code) AS description
                FROM document_requirement_rule rule
                LEFT JOIN doc_type document_type ON document_type.type_code = rule.type_code
                                                   AND document_type.category_code = rule.category_code
                WHERE rule.product_type_code = ? AND rule.request_type_code = ?
                  AND rule.active = true AND rule.is_required = true
                  AND rule.effective_from <= CURRENT_DATE
                  AND (rule.effective_to IS NULL OR rule.effective_to >= CURRENT_DATE)
                  AND NOT EXISTS (
                      SELECT 1 FROM document_link link
                      JOIN document document ON document.document_id = link.document_id
                      WHERE link.object_type = ? AND link.object_ref = ?
                        AND document.category_code = rule.category_code
                        AND document.type_code = rule.type_code
                        AND document.status = 'ACTIVE'
                  )
                ORDER BY description, rule.category_code, rule.type_code
                """, (rs, row) -> new RequiredDocument(rs.getString("category_code"), rs.getString("type_code"), rs.getString("description")),
                requirement.productTypeCode(), requirement.requestTypeCode(), context.objectType(), context.objectRef());
        if (!missing.isEmpty()) {
            String names = missing.stream().map(RequiredDocument::description)
                    .collect(java.util.stream.Collectors.joining(", "));
            throw new IllegalArgumentException("Missing required document(s): " + names + ".");
        }
    }

    /** Registers after commit so a rolled-back workflow transition never creates a visible notification. */
    private void scheduleWorkflowNotification(WorkflowContext context, WorkflowTransition transition, String actor) {
        List<String> recipients = switch (transition.event().toUpperCase(java.util.Locale.ROOT)) {
            case "RETURN" -> context.maker() == null || context.maker().isBlank() ? List.of() : List.of(context.maker());
            case "AUTHORISE" -> workflowRecipients(context.maker(), context.submittedBy());
            default -> usersForNextWorkflowRoles(context, transition.toState());
        };
        if (recipients.isEmpty()) return;
        String reference = context.objectRef();
        String event = transition.event().toUpperCase(java.util.Locale.ROOT);
        String title = switch (event) {
            case "RETURN" -> "Hồ sơ " + reference + " cần bổ sung";
            case "AUTHORISE" -> "Hồ sơ " + reference + " đã được phê duyệt";
            default -> "Hồ sơ " + reference + " chờ xử lý";
        };
        String message = switch (event) {
            case "RETURN" -> "Hồ sơ đã bị trả về; vui lòng cập nhật và gửi lại.";
            case "AUTHORISE" -> "Hồ sơ đã hoàn tất phê duyệt.";
            default -> "Hồ sơ vừa " + event + "; bạn có bước workflow cần xử lý.";
        };
        String actionUrl = "/legacy-search/03L45/MMN?CASE_ID=" + context.caseId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                notifications.createWorkflow(recipients, title, message, context.caseId(), actionUrl, actor, event);
            }
        });
    }

    private static List<String> workflowRecipients(String... emails) {
        List<String> recipients = new java.util.ArrayList<>();
        for (String email : emails) {
            if (email != null && !email.isBlank()) recipients.add(email);
        }
        return recipients;
    }

    /** Finds active users assigned to a role that can act from the case's new state. */
    private List<String> usersForNextWorkflowRoles(WorkflowContext context, String nextState) {
        List<String> roles = jdbc().query("""
                SELECT DISTINCT transition.role
                FROM portal_workflow_definition definition
                CROSS JOIN LATERAL jsonb_to_recordset(definition.definition -> 'transitions') AS transition(
                    action text, "from" jsonb, "to" text, role text, "fourEyes" text, event text, remarks text, "onEnter" text)
                WHERE definition.object_type = ? AND definition.version = ? AND jsonb_exists(transition."from", ?)
                  AND COALESCE(transition.role, '') <> '' AND upper(transition.role) <> 'NONE'
                """, (rs, row) -> rs.getString(1), context.objectType(), context.workflowVersion(), nextState);
        if (roles.isEmpty()) return List.of();
        NamedParameterJdbcTemplate auth = new NamedParameterJdbcTemplate(sources.resolve("DBCORE_auth"));
        return auth.query("SELECT DISTINCT user_email FROM auth.v_user_role_active WHERE role_name IN (:roles)",
                new MapSqlParameterSource("roles", new LinkedHashSet<>(roles)), (rs, row) -> rs.getString(1));
    }

    private WorkflowContext workflowContext(JdbcTemplate jdbc, String documentId) {
        return jdbc.queryForObject("""
                SELECT workflow.case_id::text AS case_id, workflow.object_type, workflow.object_ref, workflow.state, workflow.maker, workflow.submitted_by, workflow.checker, workflow.authorised_by, workflow.workflow_version, workflow.amendment_no
                FROM document_link link JOIN LATERAL (
                    SELECT * FROM portal_case candidate WHERE candidate.object_type = link.object_type AND candidate.object_ref = link.object_ref AND candidate.state <> 'CANCELLED'
                    ORDER BY candidate.amendment_no DESC LIMIT 1) workflow ON true
                WHERE link.document_id = CAST(? AS uuid) ORDER BY link.linked_at DESC LIMIT 1
                """, (rs, row) -> workflowContext(rs), documentId);
    }

    private WorkflowContext workflowContextByCase(JdbcTemplate jdbc, String caseId) {
        return jdbc.queryForObject("""
                SELECT case_id::text AS case_id, object_type, object_ref, state, maker, submitted_by, checker, authorised_by, workflow_version, amendment_no
                FROM portal_case WHERE case_id = CAST(? AS uuid) AND state <> 'CANCELLED'
                """, (rs, row) -> workflowContext(rs), caseId);
    }

    private List<WorkflowAction> availableActions(WorkflowContext context, String actor, List<String> roles, boolean privileged) {
        if (context.caseId() == null || context.workflowVersion() == null) return List.of();
        List<WorkflowTransition> transitions = jdbc().query("""
                SELECT transition.action AS "action", transition.event AS "event", transition.role AS "role",
                       transition."fourEyes" AS "fourEyes", transition.remarks AS "remarks"
                FROM portal_workflow_definition definition
                CROSS JOIN LATERAL jsonb_to_recordset(definition.definition -> 'transitions') AS transition(
                    action text, "from" jsonb, "to" text, role text, "fourEyes" text, event text, remarks text, "onEnter" text)
                WHERE definition.object_type = ? AND definition.version = ? AND jsonb_exists(transition."from", ?)
                ORDER BY transition.action
                """, (rs, row) -> new WorkflowTransition(rs.getString("action"), null, rs.getString("event"), rs.getString("role"), rs.getString("fourEyes"), rs.getString("remarks"), null), context.objectType(), context.workflowVersion(), context.state());
        return transitions.stream().map(transition -> {
            String requiredRole = transition.role();
            String fourEyes = transition.fourEyes();
            boolean fourEyesAllowed = privileged || (!("NOT_SUBMITTER".equalsIgnoreCase(fourEyes) && actor.equalsIgnoreCase(context.submittedBy()))
                    && !("NOT_MAKER".equalsIgnoreCase(fourEyes) && actor.equalsIgnoreCase(context.maker())));
            boolean roleAllowed = roleMatches(requiredRole, roles, privileged);
            return new WorkflowAction(transition.action(), transition.event(),
                    "required".equalsIgnoreCase(transition.remarks()), requiredRole,
                    roleAllowed && fourEyesAllowed, !roleAllowed ? "ROLE" : fourEyesAllowed ? "" : "FOUR_EYES");
        }).toList();
    }

    private boolean canOpenAmendment(WorkflowContext context, List<String> roles, boolean privileged) {
        if (context.caseId() == null || context.workflowVersion() == null) return false;
        Boolean terminal = jdbc().queryForObject("""
                SELECT jsonb_exists(definition.definition -> 'terminalStates', ?) FROM portal_workflow_definition definition
                WHERE definition.object_type = ? AND definition.version = ?
                """, Boolean.class, context.state(), context.objectType(), context.workflowVersion());
        return Boolean.TRUE.equals(terminal) && roles.stream().anyMatch(role -> "G3".equalsIgnoreCase(role));
    }
    private boolean canCancelAmendment(WorkflowContext context, List<String> roles, boolean privileged) {
        if (context.caseId() == null || context.amendmentNo() == null || isTerminal(context)) return false;
        return context.amendmentNo() > 0 && roles.stream().anyMatch(role -> "G3".equalsIgnoreCase(role));
    }

    private boolean canEditDocuments(WorkflowContext context) {
        if (context.caseId() == null || context.workflowVersion() == null) return false;
        Boolean editable = jdbc().queryForObject("""
                SELECT jsonb_exists(definition.definition -> 'editableStates', ?)
                       AND NOT EXISTS (SELECT 1 FROM object_state state WHERE state.object_type = ? AND state.object_ref = ? AND state.authorised)
                FROM portal_workflow_definition definition
                WHERE definition.object_type = ? AND definition.version = ?
                """, Boolean.class, context.state(), context.objectType(), context.objectRef(), context.objectType(), context.workflowVersion());
        return Boolean.TRUE.equals(editable);
    }

    /** Cancels only a live amendment and restores the last immutable authorised manifest. */
    public Workflow cancelAmendmentByCase(String caseId, String reason, String actor, List<String> roles, boolean privileged) {
        return transaction().execute(status -> {
            JdbcTemplate jdbc = jdbc();
            WorkflowContext current = workflowContextByCase(jdbc, caseId);
            int amendmentNo = current.amendmentNo();
            if (amendmentNo <= 0 || isTerminal(current)) throw new IllegalArgumentException("There is no open amendment to cancel.");
            boolean permitted = roles.stream().anyMatch(role -> "G3".equalsIgnoreCase(role));
            if (!permitted) throw new SecurityException("AMENDMENT_CANCEL_DENIED");
            String objectType = current.objectType(); String objectRef = current.objectRef();
            Integer baseline = jdbc.queryForObject("SELECT COALESCE(max(amendment_no), -1) FROM authorised_manifest WHERE object_type = ? AND object_ref = ?", Integer.class, objectType, objectRef);
            if (baseline == null || baseline < 0) throw new IllegalStateException("Cannot cancel amendment without an authorised manifest baseline.");
            jdbc.update("""
                    UPDATE document SET status = 'SUPERSEDED'
                    WHERE status = 'ACTIVE' AND document_id IN (
                      SELECT d.document_id FROM document d JOIN document_link link ON link.document_id = d.document_id
                      WHERE link.object_type = ? AND link.object_ref = ?)
                      AND document_id NOT IN (SELECT document_id FROM authorised_manifest WHERE object_type = ? AND object_ref = ? AND amendment_no = ?)
                    """, objectType, objectRef, objectType, objectRef, baseline);
            jdbc.update("""
                    UPDATE document SET status = 'ACTIVE'
                    WHERE document_id IN (SELECT document_id FROM authorised_manifest WHERE object_type = ? AND object_ref = ? AND amendment_no = ?)
                    """, objectType, objectRef, baseline);
            jdbc.update("""
                    INSERT INTO object_state (object_type, object_ref, authorised, updated_by, updated_at) VALUES (?, ?, true, ?, now())
                    ON CONFLICT (object_type, object_ref) DO UPDATE SET authorised = true, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                    """, objectType, objectRef, actor);
            jdbc.update("UPDATE portal_case SET state = 'CANCELLED', updated_at = now() WHERE case_id = CAST(? AS uuid)", current.caseId());
            jdbc.update("INSERT INTO portal_case_event (case_id, event_time, actor, action, remarks) VALUES (CAST(? AS uuid), now(), ?, 'AMENDMENT_CANCEL', ?)", current.caseId(), actor, nullable(reason));
            jdbc.update("""
                    INSERT INTO audit_event (event_time, actor, actor_system, action, document_id, object_type, object_ref, outcome, detail_json)
                    VALUES (now(), ?, 'ADMIN_UI', 'AMENDMENT_CANCEL', NULL, ?, ?, 'SUCCESS', jsonb_build_object('amendmentNo', ?, 'restoredTo', ?, 'reason', ?))
                    """,
                    actor, objectType, objectRef, amendmentNo, baseline, nullable(reason));
            return workflowByCase(baselineCaseId(jdbc, objectType, objectRef, baseline), actor, roles, privileged);
        });
    }

    private String baselineCaseId(JdbcTemplate jdbc, String objectType, String objectRef, int amendmentNo) {
        return jdbc.queryForObject("""
                SELECT case_id::text FROM portal_case
                WHERE object_type = ? AND object_ref = ? AND amendment_no = ? AND state = 'AUTHORISED'
                ORDER BY updated_at DESC LIMIT 1
                """, String.class, objectType, objectRef, amendmentNo);
    }

    /** A remove is WORM-safe: it only removes a file from the active set. */
    public DocumentStatus remove(String documentId, String reason, String actor) {
        require(reason, "removal reason");
        return changeRemovedStatus(documentId, actor, "ACTIVE", "SUPERSEDED", "DELETE", reason.trim());
    }

    /** Restore is allowed only for an explicitly removed file, never a replaced version. */
    public DocumentStatus restore(String documentId, String actor) {
        return transaction().execute(status -> {
            JdbcTemplate jdbc = jdbc();
            DocumentContext row = documentContext(jdbc, documentId);
            if (!"SUPERSEDED".equals(row.status())) throw new IllegalArgumentException("Only a removed document can be restored.");
            Integer successors = jdbc.queryForObject("SELECT count(*) FROM document WHERE replaces_document_id = CAST(? AS uuid)", Integer.class, documentId);
            if (successors != null && successors > 0) throw new IllegalArgumentException("This document was replaced by a newer version and cannot be restored.");
            assertObjectEditable(jdbc, row.objectType(), row.objectRef());
            jdbc.update("UPDATE document SET status = 'ACTIVE' WHERE document_id = CAST(? AS uuid) AND status = 'SUPERSEDED'", documentId);
            auditStatus(jdbc, actor, "RESTORE", documentId, row, "ACTIVE", null);
            return documentView(jdbc, documentId);
        });
    }

    private DocumentStatus changeRemovedStatus(String documentId, String actor, String expected, String next, String action, String reason) {
        return transaction().execute(status -> {
            JdbcTemplate jdbc = jdbc(); DocumentContext row = documentContext(jdbc, documentId);
            if (!expected.equals(row.status())) throw new IllegalArgumentException("Only an ACTIVE document can be removed.");
            assertObjectEditable(jdbc, row.objectType(), row.objectRef());
            jdbc.update("UPDATE document SET status = ? WHERE document_id = CAST(? AS uuid) AND status = ?", next, documentId, expected);
            auditStatus(jdbc, actor, action, documentId, row, next, reason); return documentView(jdbc, documentId);
        });
    }

    private DocumentContext documentContext(JdbcTemplate jdbc, String id) { return jdbc.queryForObject("""
            SELECT d.status, link.object_type, link.object_ref FROM document d JOIN document_link link ON link.document_id = d.document_id
            WHERE d.document_id = CAST(? AS uuid) ORDER BY link.linked_at DESC LIMIT 1""", (rs, row) -> new DocumentContext(rs.getString("status"), rs.getString("object_type"), rs.getString("object_ref")), id); }
    private void auditStatus(JdbcTemplate jdbc, String actor, String action, String id, DocumentContext row, String next, String reason) {
        if (reason == null || reason.isBlank()) {
            jdbc.update("""
                    INSERT INTO audit_event (event_time, actor, actor_system, action, document_id, object_type, object_ref, outcome, detail_json)
                    VALUES (now(), ?, 'ADMIN_UI', ?, CAST(? AS uuid), ?, ?, 'SUCCESS', jsonb_build_object('newStatus', ?))""",
                    actor, action, id, row.objectType(), row.objectRef(), next);
            return;
        }
        jdbc.update("""
                INSERT INTO audit_event (event_time, actor, actor_system, action, document_id, object_type, object_ref, outcome, detail_json)
                VALUES (now(), ?, 'ADMIN_UI', ?, CAST(? AS uuid), ?, ?, 'SUCCESS',
                        jsonb_build_object('newStatus', ?, 'removalReason', ?))""",
                actor, action, id, row.objectType(), row.objectRef(), next, reason);
    }
    private DocumentStatus documentView(JdbcTemplate jdbc, String id) { return jdbc.queryForObject("SELECT document_id::text AS \"id\", status AS \"documentStatus\" FROM document WHERE document_id = CAST(? AS uuid)", (rs, row) -> new DocumentStatus(rs.getString("id"), rs.getString("documentStatus")), id); }
    private boolean isTerminal(WorkflowContext context) { Boolean value = jdbc().queryForObject("SELECT jsonb_exists(definition -> 'terminalStates', ?) FROM portal_workflow_definition WHERE object_type = ? AND version = ?", Boolean.class, context.state(), context.objectType(), context.workflowVersion()); return Boolean.TRUE.equals(value); }

    /** Creates a docsvc document and its business-object linkage in one database transaction. */
    public DocumentRow upload(String objectType, String objectRef, String branchCode,
                                      String categoryCode, String typeCode, String referenceText,
                                      String customerNumber, String customerName, MultipartFile file,
                                      String idempotencyKey, String actor) {
        return transaction().execute(status -> {
            CaseUploadContext existing = existingCaseForObject(jdbc(), objectType, objectRef);
            if (!existing.branchCode().equals(branchCode == null ? "" : branchCode.trim())
                    || !existing.customerNumber().equals(customerNumber == null ? "" : customerNumber.trim())
                    || !existing.customerName().equals(customerName == null ? "" : customerName.trim())) {
                throw new IllegalArgumentException("Upload details must match the existing transaction case.");
            }
            return uploadInTransaction(existing.objectType(), existing.objectRef(), existing.branchCode(), categoryCode, typeCode,
                    referenceText, existing.customerNumber(), existing.customerName(), file, idempotencyKey, actor);
        });
    }

    /**
     * Uploads into an existing transaction case.  This is used by non-DMS
     * channels (for example a Legacy CFU maintain form) so they cannot supply
     * or forge the object, branch, or customer attributes themselves.
     */
    public DocumentRow uploadForCase(String caseId, String categoryCode, String typeCode,
                                     String referenceText, MultipartFile file,
                                     String idempotencyKey, String actor) {
        require(caseId, "caseId");
        return transaction().execute(status -> {
            JdbcTemplate jdbc = jdbc();
            CaseUploadContext context = existingCaseById(jdbc, caseId.trim());
            return uploadInTransaction(context.objectType(), context.objectRef(), context.branchCode(),
                    categoryCode, typeCode, referenceText, context.customerNumber(), context.customerName(),
                    file, idempotencyKey, actor);
        });
    }

    /** Every document belongs to an independently opened case with requirement context. */
    private CaseUploadContext existingCaseForObject(JdbcTemplate jdbc, String objectType, String objectRef) {
        require(objectType, "objectType"); require(objectRef, "objectRef");
        List<CaseUploadContext> rows = jdbc.query("""
                SELECT object_type, object_ref, branch_code, COALESCE(customer_number, '') AS customer_number,
                       COALESCE(customer_name, '') AS customer_name
                FROM portal_case
                WHERE object_type = ? AND object_ref = ? AND state <> 'CANCELLED'
                  AND product_type_code IS NOT NULL AND request_type_code IS NOT NULL
                ORDER BY amendment_no DESC, updated_at DESC LIMIT 1
                """, (rs, row) -> new CaseUploadContext(rs.getString("object_type"), rs.getString("object_ref"),
                rs.getString("branch_code"), rs.getString("customer_number"), rs.getString("customer_name")), objectType.trim(), objectRef.trim());
        if (rows.isEmpty()) throw new IllegalArgumentException("Open a transaction case with product type and request type before uploading documents.");
        return rows.getFirst();
    }

    private CaseUploadContext existingCaseById(JdbcTemplate jdbc, String caseId) {
        List<CaseUploadContext> rows = jdbc.query("""
                SELECT object_type, object_ref, branch_code, COALESCE(customer_number, '') AS customer_number,
                       COALESCE(customer_name, '') AS customer_name
                FROM portal_case
                WHERE case_id = CAST(? AS uuid) AND state <> 'CANCELLED'
                  AND product_type_code IS NOT NULL AND request_type_code IS NOT NULL
                """, (rs, row) -> new CaseUploadContext(rs.getString("object_type"), rs.getString("object_ref"),
                rs.getString("branch_code"), rs.getString("customer_number"), rs.getString("customer_name")), caseId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Transaction case with product type and request type was not found: " + caseId);
        return rows.getFirst();
    }
    private DocumentRow uploadInTransaction(String objectType, String objectRef, String branchCode,
                                      String categoryCode, String typeCode, String referenceText,
                                      String customerNumber, String customerName, MultipartFile file,
                                      String idempotencyKey, String actor) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("A document file is required.");
        require(objectType, "objectType"); require(objectRef, "objectRef"); require(branchCode, "branchCode");
        require(categoryCode, "categoryCode"); require(typeCode, "typeCode");
        JdbcTemplate jdbc = jdbc();
        Integer typeMatchesCategory = jdbc.queryForObject("SELECT count(*) FROM doc_type WHERE type_code = ? AND category_code = ? AND active = true", Integer.class, typeCode, categoryCode);
        if (typeMatchesCategory == null || typeMatchesCategory != 1) throw new IllegalArgumentException("Document type does not belong to the selected category.");
        assertObjectEditable(jdbc, objectType.trim(), objectRef.trim());
        DmsObjectStorageService.StoredObject stored = null;
        try {
            byte[] contents = file.getBytes();
            String requestHash = requestHash(contents, "UPLOAD", objectType, objectRef, categoryCode, typeCode, referenceText, branchCode, "");
            lockRequest(jdbc, requestHash);
            DocumentRow replay = replay(jdbc, idempotencyKey, requestHash);
            if (replay != null) return replay;
            validateConfiguredFile(file.getOriginalFilename(), contents.length,
                    configuredFilePolicy(jdbc, categoryCode.trim(), typeCode.trim()));
            DmsClamdScanService.ScanResult scan = scanner.scan(contents);
            if (!scan.clean()) {
                String quarantineKey = objects.quarantine(contents, file.getOriginalFilename());
                jdbc.update("""
                        INSERT INTO audit_event (event_time, actor, actor_system, action, document_id, object_type, object_ref, outcome, detail_json)
                        VALUES (now(), ?, 'ADMIN_UI', 'UPLOAD', NULL, ?, ?, 'DENIED', jsonb_build_object('reason', 'MALWARE_DETECTED', 'signature', ?, 'engine', ?, 'quarantineKey', ?))
                        """, actor, objectType.trim(), objectRef.trim(), scan.signature(), scan.engineVersion(), quarantineKey);
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "Malware detected (" + scan.signature() + "); upload was quarantined.");
            }
            String detectedMime = inspectMime(contents, file.getOriginalFilename());
            Retention retention = retention(jdbc, typeCode.trim());
            UUID caseId = ensureCase(jdbc, objectType.trim(), objectRef.trim(), branchCode.trim(), customerNumber, customerName, actor);
            CaseMaster master = jdbc.queryForObject("SELECT branch_code, customer_number, customer_name FROM portal_case WHERE case_id = CAST(? AS uuid)", (rs, row) -> new CaseMaster(rs.getString("branch_code"), rs.getString("customer_number"), rs.getString("customer_name")), caseId.toString());
            String masterBranchCode = master.branchCode();
            String masterCustomerNumber = nullable(master.customerNumber());
            String masterCustomerName = nullable(master.customerName());
            stored = objects.upload(objectRef, categoryCode, typeCode, "v1", file, retention.retainUntil(), retention.wormMode());
            UUID documentId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO document (document_id, storage_bucket, storage_key, storage_version_id, sha256, size_bytes,
                        detected_mime, original_filename, category_code, type_code, reference_text, scan_status, scan_engine_ver, retention_class, retain_until, status,
                        created_by, created_at, branch_code, version, replaces_document_id)
                    VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, 'CLEAN', ?, ?, ?, 'ACTIVE', ?, now(), ?, 1, NULL)
                    """, documentId, stored.bucket(), stored.key(), sha256(contents), stored.sizeBytes(), detectedMime,
                    stored.fileName(), categoryCode.trim(), typeCode.trim(), nullable(referenceText), scan.engineVersion(), retention.retentionClass(), retention.retainUntil(), actor, masterBranchCode);
            jdbc.update("""
                    INSERT INTO document_link (link_id, document_id, object_type, object_ref, linked_by, linked_at, customer_number, customer_name)
                    VALUES (?, ?, ?, ?, ?, now(), ?, ?)
                    """, UUID.randomUUID(), documentId, objectType.trim(), objectRef.trim(), actor, masterCustomerNumber, masterCustomerName);
            jdbc.update("""
                    INSERT INTO audit_event (event_time, actor, actor_system, action, document_id, object_type, object_ref, outcome, detail_json)
                    VALUES (now(), ?, 'ADMIN_UI', 'UPLOAD', ?, ?, ?, 'SUCCESS', jsonb_build_object('sizeBytes', ?, 'version', 1))
                    """, actor, documentId, objectType.trim(), objectRef.trim(), stored.sizeBytes());
            jdbc.update("INSERT INTO idempotency_key (idempotency_key, request_sha256, document_id, created_at) VALUES (?, ?, ?, now())", normalizedKey(idempotencyKey), requestHash, documentId);
            return jdbc.queryForObject("""
                    SELECT d.document_id::text AS "id", d.original_filename AS "name", COALESCE(d.reference_text, '') AS "referenceText",
                           COALESCE(category.description, d.category_code) AS "category", COALESCE(type.description, d.type_code) AS "documentType",
                           d.version::text AS "version", d.status AS "documentStatus", d.scan_status AS "scanStatus",
                           COALESCE(workflow.state, 'NO_CASE') AS "workflowState", d.size_bytes AS "fileSizeBytes", d.detected_mime AS "mimeType",
                           d.created_by AS "createdBy", d.branch_code AS "branchCode", d.legal_hold AS "legalHold", link.object_type AS "objectType", link.object_ref AS "objectRef",
                           to_char(d.created_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "createdAt"
                    FROM document d JOIN document_link link ON link.document_id = d.document_id
                    LEFT JOIN LATERAL (
                        SELECT * FROM portal_case candidate
                        WHERE candidate.object_type = link.object_type AND candidate.object_ref = link.object_ref
                          AND candidate.state <> 'CANCELLED'
                        ORDER BY candidate.amendment_no DESC LIMIT 1
                    ) workflow ON true
                    LEFT JOIN doc_category category ON category.category_code = d.category_code LEFT JOIN doc_type type ON type.type_code = d.type_code
                    WHERE d.document_id = CAST(? AS uuid)
                    """, this::documentRow, documentId.toString());
        } catch (java.io.IOException error) { throw new IllegalStateException("Unable to read uploaded file.", error); }
        catch (RuntimeException error) { if (stored != null) objects.deleteOrphanQuietly(stored.bucket(), stored.key()); throw error; }
    }

    public Metadata metadata() {
        return new Metadata(
                jdbc().query("SELECT category_code AS \"code\", description FROM doc_category WHERE active = true ORDER BY category_code", (rs, row) -> new Category(rs.getString("code"), rs.getString("description"))),
                jdbc().query("SELECT type_code AS \"code\", category_code AS \"categoryCode\", description FROM doc_type WHERE active = true ORDER BY type_code", (rs, row) -> new DocumentType(rs.getString("code"), rs.getString("categoryCode"), rs.getString("description"))),
                jdbc().query("SELECT DISTINCT object_type AS \"code\" FROM portal_workflow_definition ORDER BY object_type", (rs, row) -> new ObjectType(rs.getString("code"))),
                jdbc().query("SELECT product_type_code AS \"code\", description FROM product_type WHERE active = true ORDER BY product_type_code", (rs, row) -> new ProductType(rs.getString("code"), rs.getString("description"))),
                jdbc().query("SELECT request_type_code AS \"code\", product_type_code AS \"productTypeCode\", description FROM request_type WHERE active = true ORDER BY product_type_code, request_type_code", (rs, row) -> new RequestType(rs.getString("code"), rs.getString("productTypeCode"), rs.getString("description"))));
    }

    /** Replaces an editable document with an immutable successor in its version chain. */
    public DocumentRow createVersion(String id, String referenceText, MultipartFile file, String idempotencyKey, String actor) {
        return transaction().execute(status -> createVersionInTransaction(id, referenceText, file, idempotencyKey, actor));
    }
    private DocumentRow createVersionInTransaction(String id, String referenceText, MultipartFile file, String idempotencyKey, String actor) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("A version file is required.");
        JdbcTemplate jdbc = jdbc();
        PriorDocument prior = jdbc.queryForObject("""
                SELECT d.document_id, d.version, d.category_code, d.type_code, d.branch_code, d.status, link.object_type, link.object_ref,
                       link.customer_number, link.customer_name, COALESCE(workflow.state, '') AS case_state,
                       COALESCE(jsonb_exists(definition.definition -> 'editableStates', workflow.state), true) AS editable
                FROM document d JOIN document_link link ON link.document_id = d.document_id
                LEFT JOIN LATERAL (
                    SELECT * FROM portal_case candidate
                    WHERE candidate.object_type = link.object_type AND candidate.object_ref = link.object_ref AND candidate.state <> 'CANCELLED'
                    ORDER BY candidate.amendment_no DESC LIMIT 1
                ) workflow ON true
                LEFT JOIN portal_workflow_definition definition ON definition.object_type = workflow.object_type AND definition.version = workflow.workflow_version
                WHERE d.document_id = CAST(? AS uuid) ORDER BY link.linked_at DESC LIMIT 1
                """, (rs, row) -> new PriorDocument(rs.getString("category_code"), rs.getString("type_code"), rs.getString("branch_code"),
                rs.getString("status"), rs.getString("object_type"), rs.getString("object_ref"), rs.getString("customer_number"),
                rs.getString("customer_name"), rs.getString("case_state"), rs.getBoolean("editable")), id);
        if (!"ACTIVE".equals(prior.status())) throw new IllegalArgumentException("Only an ACTIVE document can receive a new version.");
        if (!prior.editable()) throw new IllegalArgumentException("The transaction case is " + prior.caseState() + "; document changes are locked.");
        assertObjectEditable(jdbc, prior.objectType(), prior.objectRef());
        DmsObjectStorageService.StoredObject stored = null;
        try {
            byte[] contents = file.getBytes();
            String requestHash = requestHash(contents, "VERSION", id, referenceText, "", "", "", "", id);
            lockRequest(jdbc, requestHash);
            DocumentRow replay = replay(jdbc, idempotencyKey, requestHash);
            if (replay != null) return replay;
            validateConfiguredFile(file.getOriginalFilename(), contents.length,
                    configuredFilePolicy(jdbc, prior.categoryCode(), prior.typeCode()));
            DmsClamdScanService.ScanResult scan = scanner.scan(contents);
            if (!scan.clean()) {
                String quarantineKey = objects.quarantine(contents, file.getOriginalFilename());
                jdbc.update("""
                        INSERT INTO audit_event (event_time, actor, actor_system, action, document_id, object_type, object_ref, outcome, detail_json)
                        VALUES (now(), ?, 'ADMIN_UI', 'AMEND', NULL, ?, ?, 'DENIED', jsonb_build_object('reason', 'MALWARE_DETECTED', 'signature', ?, 'engine', ?, 'quarantineKey', ?))
                        """, actor, prior.objectType(), prior.objectRef(), scan.signature(), scan.engineVersion(), quarantineKey);
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "Malware detected (" + scan.signature() + "); replacement file was quarantined.");
            }
            String objectType = prior.objectType(); String objectRef = prior.objectRef();
            Integer nextVersion = jdbc.queryForObject("""
                    SELECT COALESCE(max(d.version), 0) + 1
                    FROM document d JOIN document_link link ON link.document_id = d.document_id
                    WHERE link.object_type = ? AND link.object_ref = ?
                    """, Integer.class, objectType, objectRef);
            int version = nextVersion == null ? 1 : nextVersion;
            String detectedMime = inspectMime(contents, file.getOriginalFilename());
            Retention retention = retention(jdbc, prior.typeCode());
            stored = objects.upload(objectRef, prior.categoryCode(), prior.typeCode(), "v" + version, file, retention.retainUntil(), retention.wormMode());
            UUID nextId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO document (document_id, storage_bucket, storage_key, storage_version_id, sha256, size_bytes,
                        detected_mime, original_filename, category_code, type_code, reference_text, scan_status, scan_engine_ver, retention_class, retain_until, status,
                        created_by, created_at, branch_code, version, replaces_document_id)
                    VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, 'CLEAN', ?, ?, ?, 'ACTIVE', ?, now(), ?, ?, CAST(? AS uuid))
                    """, nextId, stored.bucket(), stored.key(), sha256(contents), stored.sizeBytes(), detectedMime,
                    stored.fileName(), prior.categoryCode(), prior.typeCode(), nullable(referenceText), scan.engineVersion(),
                    retention.retentionClass(), retention.retainUntil(), actor, prior.branchCode(), version, id);
            jdbc.update("INSERT INTO document_link (link_id, document_id, object_type, object_ref, linked_by, linked_at, customer_number, customer_name) "
                    + "VALUES (?, ?, ?, ?, ?, now(), ?, ?)", UUID.randomUUID(), nextId, objectType, objectRef, actor, prior.customerNumber(), prior.customerName());
            jdbc.update("UPDATE document SET status = 'SUPERSEDED' WHERE document_id = CAST(? AS uuid)", id);
            jdbc.update("INSERT INTO audit_event (event_time, actor, actor_system, action, document_id, object_type, object_ref, outcome, detail_json) "
                    + "VALUES (now(), ?, 'ADMIN_UI', 'AMEND', ?, ?, ?, 'SUCCESS', jsonb_build_object('replacesDocumentId', ?, 'version', ?))", actor, nextId, objectType, objectRef, id, version);
            jdbc.update("INSERT INTO idempotency_key (idempotency_key, request_sha256, document_id, created_at) VALUES (?, ?, ?, now())", normalizedKey(idempotencyKey), requestHash, nextId);
            return jdbc.queryForObject("""
                    SELECT d.document_id::text AS "id", d.original_filename AS "name", COALESCE(d.reference_text, '') AS "referenceText",
                           COALESCE(category.description, d.category_code) AS "category", COALESCE(type.description, d.type_code) AS "documentType",
                           d.version::text AS "version", d.status AS "documentStatus", d.scan_status AS "scanStatus", COALESCE(workflow.state, 'NO_CASE') AS "workflowState",
                           d.size_bytes AS "fileSizeBytes", d.detected_mime AS "mimeType", d.created_by AS "createdBy", d.branch_code AS "branchCode", d.legal_hold AS "legalHold",
                           link.object_type AS "objectType", link.object_ref AS "objectRef", to_char(d.created_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "createdAt"
                    FROM document d JOIN document_link link ON link.document_id = d.document_id
                    LEFT JOIN LATERAL (
                        SELECT * FROM portal_case candidate
                        WHERE candidate.object_type = link.object_type AND candidate.object_ref = link.object_ref
                          AND candidate.state <> 'CANCELLED'
                        ORDER BY candidate.amendment_no DESC LIMIT 1
                    ) workflow ON true
                    LEFT JOIN doc_category category ON category.category_code = d.category_code LEFT JOIN doc_type type ON type.type_code = d.type_code WHERE d.document_id = CAST(? AS uuid)
                    """, this::documentRow, nextId.toString());
        } catch (java.io.IOException error) { throw new IllegalStateException("Unable to read uploaded file.", error); }
        catch (RuntimeException error) { if (stored != null) objects.deleteOrphanQuietly(stored.bucket(), stored.key()); throw error; }
    }

    private UUID ensureCase(JdbcTemplate jdbc, String objectType, String objectRef, String branchCode, String customerNumber, String customerName, String actor) {
        return ensureCase(jdbc, objectType, objectRef, branchCode, customerNumber, customerName, null, null, actor);
    }

    private UUID ensureCase(JdbcTemplate jdbc, String objectType, String objectRef, String branchCode, String customerNumber, String customerName,
                            String productTypeCode, String requestTypeCode, String actor) {
        List<UUID> existing = jdbc.query("SELECT case_id FROM portal_case WHERE object_type = ? AND object_ref = ? AND state <> 'CANCELLED' ORDER BY amendment_no DESC LIMIT 1", (rs, row) -> UUID.fromString(rs.getString(1)), objectType, objectRef);
        if (!existing.isEmpty()) return existing.get(0);
        WorkflowDefinition definition = workflowDefinition(jdbc, objectType);
        UUID caseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO portal_case (case_id, object_type, object_ref, branch_code, state, maker, submitted_by, checker, authorised_by,
                    created_at, updated_at, workflow_version, customer_number, customer_name, product_type_code, request_type_code, amendment_no, amendment_reason, opened_by)
                VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, now(), now(), ?, ?, ?, ?, ?, 0, NULL, ?)
                """, caseId, objectType, objectRef, branchCode, definition.initial(), actor,
                definition.version(), nullable(customerNumber), nullable(customerName), productTypeCode, requestTypeCode, actor);
        jdbc.update("INSERT INTO portal_case_event (case_id, event_time, actor, action, remarks) VALUES (?, now(), ?, 'CREATE', 'Created from DMS UI')", caseId, actor);
        return caseId;
    }

    /** PBBDMS amend lock: no channel may change documents of an authorised or non-editable case. */
    private void assertObjectEditable(JdbcTemplate jdbc, String objectType, String objectRef) {
        List<Boolean> authorisation = jdbc.query("SELECT authorised FROM object_state WHERE object_type = ? AND object_ref = ?", (rs, row) -> rs.getBoolean(1), objectType, objectRef);
        if (!authorisation.isEmpty() && Boolean.TRUE.equals(authorisation.getFirst())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Transaction is authorised; open an amendment before changing its documents.");
        }
        List<EditableCase> cases = jdbc.query("""
                SELECT workflow.state, COALESCE(jsonb_exists(definition.definition -> 'editableStates', workflow.state), false) AS editable
                FROM portal_case workflow
                LEFT JOIN portal_workflow_definition definition ON definition.object_type = workflow.object_type AND definition.version = workflow.workflow_version
                WHERE workflow.object_type = ? AND workflow.object_ref = ? AND workflow.state <> 'CANCELLED'
                ORDER BY workflow.amendment_no DESC LIMIT 1
                """, (rs, row) -> new EditableCase(rs.getString("state"), rs.getBoolean("editable")), objectType, objectRef);
        if (!cases.isEmpty() && !cases.getFirst().editable()) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "The transaction case is " + cases.getFirst().state() + "; document changes are locked.");
        }
    }

    private JdbcTemplate jdbc() { return new JdbcTemplate(sources.resolve("DB_DOCSVC")); }
    private TransactionTemplate transaction() { return new TransactionTemplate(new DataSourceTransactionManager(sources.resolve("DB_DOCSVC"))); }
    private static String nullToEmpty(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean roleMatches(String requiredRole, List<String> roles, boolean privileged) {
        return privileged || requiredRole.isBlank() || "NONE".equalsIgnoreCase(requiredRole)
                || roles.stream().anyMatch(role -> requiredRole.equalsIgnoreCase(role));
    }
    private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required."); }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    /** Reads the persisted rule; it is deliberately enforced server-side so an HTTP client cannot bypass the CFU UI. */
    private static FilePolicy configuredFilePolicy(JdbcTemplate jdbc, String categoryCode, String typeCode) {
        List<FilePolicy> values = jdbc.query("""
                SELECT max_file_size_mb, allowed_extensions::text AS allowed_extensions
                FROM doc_type
                WHERE category_code = ? AND type_code = ? AND active = true
                """, (rs, row) -> new FilePolicy(rs.getInt("max_file_size_mb"), rs.getString("allowed_extensions")), categoryCode, typeCode);
        if (values.isEmpty()) throw new IllegalArgumentException("Active document type was not found for the selected category.");
        return values.getFirst();
    }
    private static void validateConfiguredFile(String fileName, int fileSizeBytes, FilePolicy policy) {
        String extension = fileExtension(fileName);
        String configuredExtensions = policy.allowedExtensions() == null ? "" : policy.allowedExtensions().toLowerCase(java.util.Locale.ROOT);
        if (extension.isBlank() || !configuredExtensions.matches(".*\\\"" + java.util.regex.Pattern.quote(extension) + "\\\".*")) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "File extension ." + (extension.isBlank() ? "?" : extension) + " is not allowed for the selected document type.");
        }
        long maximumBytes = Math.multiplyExact((long) policy.maxFileSizeMb(), 1024L * 1024L);
        if (fileSizeBytes > maximumBytes) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "File size exceeds the configured " + policy.maxFileSizeMb() + " MB limit for the selected document type.");
        }
    }
    private static String fileExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).trim().toLowerCase(java.util.Locale.ROOT);
    }
    private Retention retention(JdbcTemplate jdbc, String typeCode) {
        List<RetentionPolicy> values = jdbc.query("""
                SELECT policy.retention_class, policy.min_years, policy.worm_mode
                FROM doc_type type JOIN retention_policy policy ON policy.retention_class = type.retention_class
                WHERE type.type_code = ?""", (rs, row) -> new RetentionPolicy(rs.getString("retention_class"), rs.getLong("min_years"), rs.getString("worm_mode")), typeCode);
        if (values.isEmpty()) return new Retention(null, null, null);
        RetentionPolicy value = values.getFirst();
        return new Retention(value.retentionClass(), OffsetDateTime.now().plusYears(value.minYears()), value.wormMode());
    }
    private record Retention(String retentionClass, OffsetDateTime retainUntil, String wormMode) { }
    private record FilePolicy(int maxFileSizeMb, String allowedExtensions) { }
    private record CaseUploadContext(String objectType, String objectRef, String branchCode, String customerNumber, String customerName) { }
    /** Serializes identical requests, including concurrent retries with different HTTP keys. */
    private static void lockRequest(JdbcTemplate jdbc, String requestHash) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, requestHash);
    }

    /** Returns the original document for an exact retry; rejects a key reused with another payload. */
    private DocumentRow replay(JdbcTemplate jdbc, String key, String requestHash) {
        String normalized = normalizedKey(key);
        List<IdempotencyRecord> found = jdbc.query("SELECT request_sha256, document_id::text AS document_id FROM idempotency_key WHERE idempotency_key = ?",
                (rs, row) -> new IdempotencyRecord(rs.getString("request_sha256"), rs.getString("document_id")), normalized);
        if (!found.isEmpty() && !requestHash.equals(found.getFirst().requestHash())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Idempotency-Key was already used with a different request.");
        }
        if (found.isEmpty()) {
            // Legacy retries do not retain request headers.  This canonical hash
            // contains the content SHA-256 and DMS context, never the filename.
            found = jdbc.query("SELECT request_sha256, document_id::text AS document_id FROM idempotency_key WHERE request_sha256 = ?",
                    (rs, row) -> new IdempotencyRecord(rs.getString("request_sha256"), rs.getString("document_id")), requestHash);
        }
        if (found.isEmpty()) return null;
        String documentId = found.getFirst().documentId();
        return list().stream().filter(row -> documentId.equals(row.id())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Idempotency record refers to a missing document."));
    }
    private static String normalizedKey(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 64) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Idempotency-Key is required and must not exceed 64 characters.");
        return value.trim();
    }
    private static String requestHash(byte[] content, String operation, String... fields) {
        StringBuilder canonical = new StringBuilder(operation).append('|').append(sha256(content));
        for (String field : fields) canonical.append('|').append(field == null ? "" : field.trim());
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String sha256(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception error) { throw new IllegalStateException("SHA-256 is unavailable.", error); } }
    public record DocumentRow(String id, String name, String referenceText, String category, String documentType, String version, String documentStatus, String scanStatus, String workflowState, long fileSizeBytes, String mimeType, String createdBy, String branchCode, boolean legalHold, String objectType, String objectRef, String createdAt) { }
    public record DocumentStatus(String id, String documentStatus) { }
    private record DocumentContext(String status, String objectType, String objectRef) { }
    private record CaseMaster(String branchCode, String customerNumber, String customerName) { }
    private record StoredDocumentLocation(String bucket, String key, String fileName, String objectType, String objectRef) { }
    private record EditableCase(String state, boolean editable) { }
    private record RetentionPolicy(String retentionClass, long minYears, String wormMode) { }
    private record IdempotencyRecord(String requestHash, String documentId) { }
    private record PriorDocument(String categoryCode, String typeCode, String branchCode, String status, String objectType,
                                 String objectRef, String customerNumber, String customerName, String caseState, boolean editable) { }
    public record CaseRow(String caseId,String objectType,String objectRef,String branchCode,String state,String maker,String customerNumber,String customerName,int amendmentNo,int activeDocumentCount,String updatedAt) { }
    public record ExternalCaseDetail(ExternalCaseInfo workflowCase, List<WorkflowEvent> workflowHistory, List<ExternalDocument> documents) { }
    public record ExternalCaseInfo(String caseId, String objectType, String objectRef, String branchCode, String customerNumber,
                                   String customerName, String productTypeCode, String requestTypeCode, String state,
                                   String createdAt, String updatedAt) { }
    public record ExternalDocument(String documentId, String fileName, String categoryCode, String typeCode, String referenceText,
                                   int version, String documentStatus, String scanStatus, String createdAt) { }
    public record VersionRow(String id,String version,String fileName,long fileSizeBytes,String documentStatus,String createdBy,String createdAt) { }
    public record Metadata(List<Category> categories, List<DocumentType> types, List<ObjectType> objectTypes,
                           List<ProductType> productTypes, List<RequestType> requestTypes) { }
    public record Category(String code, String description) { }
    public record DocumentType(String code, String categoryCode, String description) { }
    public record ObjectType(String code) { }
    public record ProductType(String code, String description) { }
    public record RequestType(String code, String productTypeCode, String description) { }
    public record Workflow(String caseId, String objectType, String objectRef, String state, String maker, String submittedBy, String checker,
                           String authorisedBy, Integer workflowVersion, Integer amendmentNo, boolean canEditDocuments,
                           boolean canOpenAmendment, boolean canCancelAmendment, List<WorkflowEvent> events, List<WorkflowAction> actions) {
        static Workflow noCase() { return new Workflow("", "", "", "NO_CASE", "", "", "", "", null, null, false, false, false, List.of(), List.of()); }
    }
    public record WorkflowEvent(String action, String actor, String remarks, String eventTime) { }
    public record WorkflowAction(String action, String event, boolean requiresRemarks, String requiredRole, boolean allowed, String blockedReason) { }
    private record WorkflowContext(String caseId, String objectType, String objectRef, String state, String maker,
                                   String submittedBy, String checker, String authorisedBy, Integer workflowVersion, Integer amendmentNo) { }
    private record CaseRequirementContext(String productTypeCode, String requestTypeCode) { }
    private record RequiredDocument(String categoryCode, String typeCode, String description) { }
    private record WorkflowTransition(String action, String toState, String event, String role, String fourEyes, String remarks, String onEnter) { }
    private record WorkflowDefinition(int version, String initial) { }
    public record Amendment(String caseId, int amendmentNo, String state, String reason, String openedBy, String maker,
                            String authorisedBy, String createdAt, String updatedAt, int documentCount, List<AmendmentEvent> events) { }
    public record AmendmentEvent(String action, String actor, String remarks, String eventTime) { }
    public record OperationalReport(int slaHours, int errorLookbackHours, List<String> backlogStates, long backlogTotal,
                                    long overdueTotal, List<BacklogState> backlogByState, List<ErrorGroup> errorsByGroup) { }
    public record BacklogState(String state, long total, long overdue) { }
    public record ErrorGroup(String group, long total) { }
    private record AuditFailure(String action, String reason, String group, long total) { }
    private record ObjectReference(String objectType, String objectRef) { }
    private List<AmendmentEvent> amendmentEvents(String caseId) {
        return jdbc().query("""
                SELECT action, actor, remarks, to_char(event_time AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "eventTime"
                FROM portal_case_event WHERE case_id = CAST(? AS uuid) ORDER BY event_time
                """, (rs, row) -> new AmendmentEvent(rs.getString("action"), rs.getString("actor"), rs.getString("remarks"), rs.getString("eventTime")), caseId);
    }
    private static WorkflowContext workflowContext(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WorkflowContext(rs.getString("case_id"), rs.getString("object_type"), rs.getString("object_ref"), rs.getString("state"),
                rs.getString("maker"), rs.getString("submitted_by"), rs.getString("checker"), rs.getString("authorised_by"),
                number(rs.getObject("workflow_version")), number(rs.getObject("amendment_no")));
    }
    private static WorkflowDefinition workflowDefinition(JdbcTemplate jdbc, String objectType) {
        return jdbc.queryForObject("SELECT version, COALESCE(definition ->> 'initial', 'DRAFT') AS initial FROM portal_workflow_definition WHERE object_type = ? ORDER BY version DESC LIMIT 1",
                (rs, row) -> new WorkflowDefinition(rs.getInt("version"), rs.getString("initial")), objectType);
    }
    private static Integer number(Object value) { return value instanceof Number number ? number.intValue() : null; }
    private DocumentRow documentRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new DocumentRow(rs.getString("id"), rs.getString("name"), rs.getString("referenceText"), rs.getString("category"),
                rs.getString("documentType"), rs.getString("version"), rs.getString("documentStatus"), rs.getString("scanStatus"),
                rs.getString("workflowState"), rs.getLong("fileSizeBytes"), rs.getString("mimeType"), rs.getString("createdBy"),
                rs.getString("branchCode"), rs.getBoolean("legalHold"), rs.getString("objectType"), rs.getString("objectRef"), rs.getString("createdAt"));
    }
    /** Minimal magic-byte validation; the browser Content-Type is never trusted. */
    private static String inspectMime(byte[] bytes, String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        boolean pdf = bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
        boolean ole = bytes.length >= 8 && bytes[0] == (byte) 0xD0 && bytes[1] == (byte) 0xCF && bytes[2] == 0x11 && bytes[3] == (byte) 0xE0;
        boolean zip = bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
        boolean jpeg = bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF;
        boolean png = bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
        if (name.endsWith(".pdf") && pdf) return "application/pdf";
        if (name.endsWith(".doc") && ole) return "application/msword";
        if (name.endsWith(".xls") && ole) return "application/vnd.ms-excel";
        if (name.endsWith(".docx") && zip) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".xlsx") && zip) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if ((name.endsWith(".jpg") || name.endsWith(".jpeg")) && jpeg) return "image/jpeg";
        if (name.endsWith(".png") && png) return "image/png";
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Filename extension and file magic do not match an allowed document type.");
    }
}
