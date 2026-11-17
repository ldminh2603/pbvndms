package com.webcore.api.dms;

import java.util.List;
import java.util.Map;

import com.webcore.api.database.CoreDataSourceResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Read contract for the DMS database; business data is never kept in the client bundle. */
@Service
public class DmsDocumentService {
    private final CoreDataSourceResolver sources;
    private final DmsObjectStorageService objects;

    public DmsDocumentService(CoreDataSourceResolver sources, DmsObjectStorageService objects) { this.sources = sources; this.objects = objects; }

    public List<DocumentRow> list() {
        return new JdbcTemplate(sources.resolve("DB_DMS")).query("""
                SELECT document_id AS "id", document_code AS "code", document_name AS "name",
                       category_name AS "category", document_type_name AS "documentType",
                       current_version AS "version", document_status AS "status",
                       owner_name AS "owner", description,
                       to_char(last_updated_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "updatedAt"
                FROM pbbdms.document
                ORDER BY last_updated_at DESC, document_id DESC
                """, (rs, row) -> new DocumentRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("category"), rs.getString("documentType"), rs.getString("version"), rs.getString("status"), rs.getString("owner"), rs.getString("description"), rs.getString("updatedAt")));
    }

    public DocumentRow update(long id, String name, String code, String description) {
        JdbcTemplate jdbc = new JdbcTemplate(sources.resolve("DB_DMS"));
        int changed = jdbc.update("""
                UPDATE pbbdms.document
                SET document_name = ?, document_code = ?, description = ?, last_updated_at = now()
                WHERE document_id = ?
                """, name, code, description, id);
        if (changed != 1) throw new IllegalArgumentException("DMS document was not found: " + id);
        return documentItem(jdbc, id);
    }

    public DocumentRow createDocument(String name, String code, String category, String documentType,
                                               String initialVersion, String description, MultipartFile file, String owner) {
        JdbcTemplate jdbc = new JdbcTemplate(sources.resolve("DB_DMS"));
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM pbbdms.document WHERE document_code = ?", Integer.class, code);
        if (existing != null && existing > 0) throw new IllegalArgumentException("Document code already exists: " + code);
        DmsObjectStorageService.StoredObject stored = objects.upload(code, initialVersion, file);
        long id = jdbc.queryForObject("""
                INSERT INTO pbbdms.document (document_code, document_name, category_name, document_type_name, current_version, document_status, owner_name, description)
                VALUES (?, ?, ?, ?, ?, 'Chờ phê duyệt', ?, ?)
                RETURNING document_id
                """, Long.class, code, name, category, documentType, initialVersion, owner, description);
        jdbc.update("""
                INSERT INTO pbbdms.document_version (document_id, version_no, file_name, file_size_bytes, version_note, effective_date, object_key, created_by, is_current)
                VALUES (?, ?, ?, ?, 'Phiên bản khởi tạo.', current_date, ?, ?, true)
                """, id, initialVersion, stored.fileName(), stored.sizeBytes(), stored.key(), owner);
        resetApprovals(jdbc, id, owner);
        return documentItem(jdbc, id);
    }

    public List<VersionRow> versions(long id) {
        return new JdbcTemplate(sources.resolve("DB_DMS")).query("""
                SELECT version_no AS "version", file_name AS "fileName", file_size_bytes AS "fileSizeBytes", version_note AS "note",
                       is_current AS "isCurrent",
                       (object_key IS NOT NULL AND object_key <> '') AS "hasFile",
                       to_char(created_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "createdAt",
                       created_by AS "createdBy"
                FROM pbbdms.document_version WHERE document_id = ?
                ORDER BY created_at DESC, document_version_id DESC
                """, (rs, row) -> new VersionRow(rs.getString("version"), rs.getString("fileName"), rs.getLong("fileSizeBytes"), rs.getString("note"), rs.getBoolean("isCurrent"), rs.getBoolean("hasFile"), rs.getString("createdAt"), rs.getString("createdBy")), id);
    }

    public List<ApprovalRow> approvals(long id) {
        return new JdbcTemplate(sources.resolve("DB_DMS")).query("""
                SELECT step_no AS "stepNo", approver_name AS "approverName",
                       approval_status AS "approvalStatus", approval_note AS "note",
                       acted_by AS "actedBy",
                       to_char(acted_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "actedAt"
                FROM pbbdms.document_approval
                WHERE document_id = ?
                ORDER BY step_no
                """, (rs, row) -> new ApprovalRow(rs.getInt("stepNo"), rs.getString("approverName"), rs.getString("approvalStatus"), rs.getString("note"), rs.getString("actedBy"), rs.getString("actedAt")), id);
    }

    public DocumentRow decideApproval(long id, String action, String note, String actor) {
        boolean approved = "approve".equalsIgnoreCase(action);
        boolean rejected = "reject".equalsIgnoreCase(action);
        if (!approved && !rejected) throw new IllegalArgumentException("action must be approve or reject.");
        if (rejected && note.isBlank()) throw new IllegalArgumentException("A rejection reason is required.");

        JdbcTemplate jdbc = new JdbcTemplate(sources.resolve("DB_DMS"));
        DocumentApprovalState document = jdbc.queryForObject("SELECT document_status FROM pbbdms.document WHERE document_id = ?", (rs, row) -> new DocumentApprovalState(rs.getString("document_status")), id);
        if (!"Chờ phê duyệt".equals(document.status())) {
            throw new IllegalArgumentException("Document is not awaiting approval.");
        }
        List<PendingApprovalStep> pending = jdbc.query("""
                SELECT document_approval_id, step_no FROM pbbdms.document_approval
                WHERE document_id = ? AND approval_status = 'Chờ xử lý'
                ORDER BY step_no LIMIT 1
                """, (rs, row) -> new PendingApprovalStep(rs.getLong("document_approval_id"), rs.getInt("step_no")), id);
        if (pending.isEmpty()) throw new IllegalArgumentException("No approval step is awaiting action.");

        PendingApprovalStep step = pending.get(0);
        long approvalId = step.approvalId();
        int stepNo = step.stepNo();
        String actionNote = note.isBlank() ? "Đã phê duyệt." : note;
        jdbc.update("""
                UPDATE pbbdms.document_approval
                SET approval_status = ?, approval_note = ?, acted_at = now(), acted_by = ?
                WHERE document_approval_id = ?
                """, approved ? "Đã phê duyệt" : "Từ chối", actionNote, actor, approvalId);

        if (rejected) {
            jdbc.update("UPDATE pbbdms.document SET document_status = 'Từ chối', last_updated_at = now() WHERE document_id = ?", id);
        } else {
            int next = jdbc.update("""
                    UPDATE pbbdms.document_approval
                    SET approval_status = 'Chờ xử lý', approval_note = 'Đang chờ xem xét.'
                    WHERE document_id = ? AND step_no = ? AND approval_status = 'Chưa đến lượt'
                    """, id, stepNo + 1);
            if (next == 0) {
                jdbc.update("UPDATE pbbdms.document SET document_status = 'Hiệu lực', last_updated_at = now() WHERE document_id = ?", id);
            } else {
                jdbc.update("UPDATE pbbdms.document SET last_updated_at = now() WHERE document_id = ?", id);
            }
        }
        return documentItem(jdbc, id);
    }

    public DocumentView viewVersion(long id, String versionNo) {
        DocumentStorageLocation row = new JdbcTemplate(sources.resolve("DB_DMS")).queryForObject("""
                SELECT file_name, object_key FROM pbbdms.document_version
                WHERE document_id = ? AND version_no = ?
                """, (rs, rowIndex) -> new DocumentStorageLocation(rs.getString("file_name"), rs.getString("object_key")), id, versionNo);
        return new DocumentView(objects.viewUrl(row.objectKey() == null ? "" : row.objectKey(), row.fileName()));
    }

    public DmsObjectStorageService.StoredContent contentVersion(long id, String versionNo) {
        DocumentStorageLocation row = new JdbcTemplate(sources.resolve("DB_DMS")).queryForObject("""
                SELECT file_name, object_key FROM pbbdms.document_version
                WHERE document_id = ? AND version_no = ?
                """, (rs, rowIndex) -> new DocumentStorageLocation(rs.getString("file_name"), rs.getString("object_key")), id, versionNo);
        return objects.read(row.objectKey() == null ? "" : row.objectKey(), row.fileName());
    }

    public DocumentRow createVersion(long id, String versionNo, String effectiveDate, String versionNote,
                                             boolean sendForApproval, MultipartFile file, String createdBy) {
        JdbcTemplate jdbc = new JdbcTemplate(sources.resolve("DB_DMS"));
        VersioningDocument document = jdbc.queryForObject("SELECT document_code, current_version FROM pbbdms.document WHERE document_id = ?", (rs, row) -> new VersioningDocument(rs.getString("document_code"), rs.getString("current_version")), id);
        String current = document.currentVersion();
        if (versionNo.equals(current)) throw new IllegalArgumentException("New version must differ from the current version.");
        DmsObjectStorageService.StoredObject stored = objects.upload(document.documentCode(), versionNo, file);
        jdbc.update("UPDATE pbbdms.document_version SET is_current = false WHERE document_id = ?", id);
        jdbc.update("""
                INSERT INTO pbbdms.document_version (document_id, version_no, file_name, file_size_bytes, version_note, effective_date, object_key, created_by, is_current)
                VALUES (?, ?, ?, ?, ?, CAST(? AS date), ?, ?, true)
                """, id, versionNo, stored.fileName(), stored.sizeBytes(), versionNote, effectiveDate, stored.key(), createdBy);
        jdbc.update("""
                UPDATE pbbdms.document
                SET current_version = ?, document_status = ?, last_updated_at = now()
                WHERE document_id = ?
                """, versionNo, sendForApproval ? "Chờ phê duyệt" : "Hiệu lực", id);
        if (sendForApproval) resetApprovals(jdbc, id, createdBy);
        return documentItem(jdbc, id);
    }

    private void resetApprovals(JdbcTemplate jdbc, long id, String owner) {
        jdbc.update("DELETE FROM pbbdms.document_approval WHERE document_id = ?", id);
        jdbc.batchUpdate("""
                INSERT INTO pbbdms.document_approval (document_id, step_no, approver_name, approval_status, approval_note, acted_at, acted_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, List.of(
                new Object[] { id, 1, "Người tạo tài liệu", "Đã gửi", owner + " đã gửi tài liệu.", java.sql.Timestamp.from(java.time.Instant.now()), owner },
                new Object[] { id, 2, "Trưởng bộ phận", "Chờ xử lý", "Đang chờ xem xét.", null, null },
                new Object[] { id, 3, "Ban kiểm soát", "Chưa đến lượt", "Chưa đến lượt xử lý.", null, null }
        ));
    }

    private DocumentRow documentItem(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("""
                SELECT document_id AS "id", document_code AS "code", document_name AS "name",
                       category_name AS "category", document_type_name AS "documentType",
                       current_version AS "version", document_status AS "status",
                       owner_name AS "owner", description,
                       to_char(last_updated_at AT TIME ZONE 'Asia/Bangkok', 'DD/MM/YYYY HH24:MI') AS "updatedAt"
                FROM pbbdms.document WHERE document_id = ?
                """, (rs, row) -> new DocumentRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("category"),
                rs.getString("documentType"), rs.getString("version"), rs.getString("status"), rs.getString("owner"), rs.getString("description"), rs.getString("updatedAt")), id);
    }
    public record DocumentRow(long id, String code, String name, String category, String documentType, String version, String status, String owner, String description, String updatedAt) { }
    public record VersionRow(String version, String fileName, long fileSizeBytes, String note, boolean isCurrent, boolean hasFile, String createdAt, String createdBy) { }
    public record ApprovalRow(int stepNo, String approverName, String approvalStatus, String note, String actedBy, String actedAt) { }
    public record DocumentView(String url) { }
    private record DocumentStorageLocation(String fileName, String objectKey) { }
    private record DocumentApprovalState(String status) { }
    private record PendingApprovalStep(long approvalId, int stepNo) { }
    private record VersioningDocument(String documentCode, String currentVersion) { }
}
