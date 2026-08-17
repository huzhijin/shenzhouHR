package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaLeaveTypeShowValueCatalog;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.DocumentType;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OaDocumentRecord;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OaPage;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.SourceStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production OA MySQL adapter for attendance document ingestion.
 *
 * <p>Reads leave, overtime, outing, exempt-punch and
 * punch-correction documents directly from the Seeyon OA database via
 * the read-only connection pool configured by
 * {@link OaMysqlConfiguration}.
 *
 * <p>Approval-state filtering uses {@code col_summary.state}:
 * 3 = approved/ended; NULL = draft; 0 = pending; 2 = revoked.
 * Only state=3 (approved) records are marked effectiveCandidate=true. State
 * transitions 0/2 are still versioned into the immutable evidence chain so a
 * later cancellation supersedes an earlier approval during latest-version
 * projection; drafts without a stable state/version are skipped fail-closed.
 *
 * <p>The durable cursor stores an independent timestamp/id pair for every OA
 * form family. This prevents a busy or newer form table from advancing the
 * cursor past older rows in another table. A null cursor starts every stream
 * at the epoch; the legacy single-stream timestamp/id cursor is accepted for
 * upgrade compatibility.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "shenzhouhr.integrations.oa-mysql",
        name = "enabled",
        havingValue = "true")
public final class OaMysqlAttendanceDocumentAdapter
        implements OaAttendanceDocumentSourcePort {

    private static final Logger log =
            LoggerFactory.getLogger(OaMysqlAttendanceDocumentAdapter.class);

    /** OA time zone — all DATETIME columns are interpreted in this zone. */
    private final String sourceTimeZone;
    private final ZoneId sourceZone;
    private static final DateTimeFormatter SOURCE_VERSION_TIME_FORMAT =
            new DateTimeFormatterBuilder()
                    .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                    .toFormatter();

    /** Fixed page size for all OA form queries. */
    static final int PAGE_SIZE = 200;

    /* ------------------------------------------------------------------ */
    /* SQL — leave (formmain_0170) + col_summary                           */
    /* ------------------------------------------------------------------ */
    private static final String LEAVE_SQL = """
            SELECT
                m.id                AS form_id,
                m.field0083         AS member_id,
                m.field0084         AS employee_code,
                m.field0097         AS leave_serial,
                m.field0086         AS start_dt,
                m.field0087         AS end_dt,
                e.showvalue         AS leave_type_label,
                cs.state            AS approval_state,
                cs.lastmodifydate   AS last_modified
            FROM formmain_0170 m
            LEFT JOIN ctp_enum_item e
                ON e.id = m.field0089
            LEFT JOIN col_summary cs
                ON cs.form_recordid = m.id
            WHERE (cs.lastmodifydate > ? OR (cs.lastmodifydate = ? AND m.id > ?))
            ORDER BY cs.lastmodifydate, m.id
            LIMIT ?
            """;

    /* ------------------------------------------------------------------ */
    /* SQL — leave revocation (formmain_0370)                              */
    /* ------------------------------------------------------------------ */
    private static final String LEAVE_REVOCATION_SQL = """
            SELECT
                m.id                AS form_id,
                m.field0083         AS member_id,
                m.field0084         AS employee_code,
                m.field0097         AS revocation_serial,
                m.field0099         AS original_leave_serial,
                m.field0086         AS actual_start_dt,
                m.field0087         AS actual_end_dt,
                cs.state            AS approval_state,
                cs.lastmodifydate   AS last_modified
            FROM formmain_0370 m
            LEFT JOIN col_summary cs
                ON cs.form_recordid = m.id
            WHERE (cs.lastmodifydate > ? OR (cs.lastmodifydate = ? AND m.id > ?))
            ORDER BY cs.lastmodifydate, m.id
            LIMIT ?
            """;

    /* ------------------------------------------------------------------ */
    /* SQL — overtime detail (formson_0172) joined to main (formmain_0171) */
    /* ------------------------------------------------------------------ */
    private static final String OVERTIME_SQL = """
            SELECT
                s.id                AS form_id,
                s.field0093         AS member_id,
                s.field0094         AS employee_code,
                s.field0100         AS start_dt,
                s.field0099         AS end_dt,
                s.field0096         AS overtime_type_id,
                cs.state            AS approval_state,
                cs.lastmodifydate   AS last_modified
            FROM formson_0172 s
            JOIN formmain_0171 m
                ON m.id = s.formmain_id
            LEFT JOIN col_summary cs
                ON cs.form_recordid = m.id
            WHERE (cs.lastmodifydate > ? OR (cs.lastmodifydate = ? AND s.id > ?))
            ORDER BY cs.lastmodifydate, s.id
            LIMIT ?
            """;

    /* ------------------------------------------------------------------ */
    /* SQL — outing detail (formson_0252)                                  */
    /* ------------------------------------------------------------------ */
    private static final String OUTING_SQL = """
            SELECT
                s.id                AS form_id,
                s.field0127         AS member_id,
                s.field0131         AS employee_code,
                s.field0132         AS start_dt,
                s.field0135         AS end_dt,
                cs.state            AS approval_state,
                cs.lastmodifydate   AS last_modified
            FROM formson_0252 s
            JOIN formmain_0251 m
                ON m.id = s.formmain_id
            JOIN col_summary cs
                ON cs.form_recordid = m.id
            WHERE (cs.lastmodifydate > ? OR (cs.lastmodifydate = ? AND s.id > ?))
              AND cs.state IN (0, 2, 3)
            ORDER BY cs.lastmodifydate, s.id
            LIMIT ?
            """;

    /* ------------------------------------------------------------------ */
    /* SQL — exempt punch detail (formson_0202)                            */
    /* ------------------------------------------------------------------ */
    private static final String EXEMPT_PUNCH_SQL = """
            SELECT
                s.id                AS form_id,
                s.field0127         AS member_id,
                s.field0131         AS employee_code,
                s.field0132         AS start_date,
                s.field0134         AS end_date,
                cs.state            AS approval_state,
                cs.lastmodifydate   AS last_modified
            FROM formson_0202 s
            JOIN formmain_0201 m
                ON m.id = s.formmain_id
            JOIN col_summary cs
                ON cs.form_recordid = m.id
            WHERE (cs.lastmodifydate > ? OR (cs.lastmodifydate = ? AND s.id > ?))
              AND cs.state IN (0, 2, 3)
              AND s.field0132 IS NOT NULL
              AND s.field0134 IS NOT NULL
              AND DATE(s.field0132) <= DATE(s.field0134)
            ORDER BY cs.lastmodifydate, s.id
            LIMIT ?
            """;

    /* ------------------------------------------------------------------ */
    /* SQL — punch correction detail (formson_0204)                        */
    /* ------------------------------------------------------------------ */
    private static final String PUNCH_CORRECTION_SQL = """
            SELECT
                s.id                AS form_id,
                s.field0127         AS member_id,
                s.field0131         AS employee_code,
                s.field0132         AS punch_dt,
                cs.state            AS approval_state,
                cs.lastmodifydate   AS last_modified
            FROM formson_0204 s
            JOIN formmain_0203 m
                ON m.id = s.formmain_id
            LEFT JOIN col_summary cs
                ON cs.form_recordid = m.id
            WHERE (cs.lastmodifydate > ? OR (cs.lastmodifydate = ? AND s.id > ?))
            ORDER BY cs.lastmodifydate, s.id
            LIMIT ?
            """;

    private final OaReadOnlyConnectionProvider connections;
    private final int queryTimeoutSeconds;

    OaMysqlAttendanceDocumentAdapter(
            OaReadOnlyConnectionProvider connections,
            OaMysqlProperties properties) {
        this.connections = connections;
        this.queryTimeoutSeconds = properties.queryTimeoutSeconds();
        this.sourceZone = properties.getSourceTimeZone();
        this.sourceTimeZone = sourceZone.getId();
    }

    /**
     * Fetches at most {@link #PAGE_SIZE} raw OA rows across all supported form
     * families. Capacity is shared fairly so a continuously busy family cannot
     * starve the others. A page that scanned invalid rows may contain no records,
     * but still advances its cursor so the poison rows do not loop forever.
     */
    @Override
    public OaPage fetchPage(String sourceId, String committedCursor) {
        MultiCursor inputCursor;
        try {
            inputCursor = MultiCursor.parse(committedCursor);
        } catch (IllegalArgumentException exception) {
            throw new OaReadOnlyQueryException(
                    "OA_CURSOR_INVALID", "OA sync cursor is invalid");
        }

        List<OaDocumentRecord> records = new ArrayList<>();
        MultiCursor nextCursor = inputCursor;
        int remaining = PAGE_SIZE;

        try (Connection conn = connections.openConnection()) {
            FetchBatch batch = fetchLeave(
                    conn, nextCursor.leave(), fairLimit(remaining, 6));
            records.addAll(batch.records());
            remaining -= batch.scannedCount();
            nextCursor = nextCursor.withLeave(batch.nextCursor());

            batch = fetchLeaveRevocation(
                    conn, nextCursor.leaveRevocation(),
                    fairLimit(remaining, 5));
            records.addAll(batch.records());
            remaining -= batch.scannedCount();
            nextCursor = nextCursor.withLeaveRevocation(batch.nextCursor());

            batch = fetchOvertime(
                    conn, nextCursor.overtime(), fairLimit(remaining, 4));
            records.addAll(batch.records());
            remaining -= batch.scannedCount();
            nextCursor = nextCursor.withOvertime(batch.nextCursor());

            batch = fetchOuting(
                    conn, nextCursor.outing(), fairLimit(remaining, 3));
            records.addAll(batch.records());
            remaining -= batch.scannedCount();
            nextCursor = nextCursor.withOuting(batch.nextCursor());

            batch = fetchExemptPunch(
                    conn, nextCursor.exemptPunch(), fairLimit(remaining, 2));
            records.addAll(batch.records());
            remaining -= batch.scannedCount();
            nextCursor = nextCursor.withExemptPunch(batch.nextCursor());

            batch = fetchPunchCorrection(
                    conn, nextCursor.punchCorrection(),
                    fairLimit(remaining, 1));
            records.addAll(batch.records());
            nextCursor = nextCursor.withPunchCorrection(batch.nextCursor());
        } catch (Exception e) {
            log.error("OA document fetch failed for source {}", sourceId, e);
            throw new OaReadOnlyQueryException(
                    "OA_DOCUMENT_FETCH_FAILED",
                    "OA document fetch failed: " + e.getMessage());
        }

        String encodedNext = nextCursor.equals(inputCursor)
                ? committedCursor
                : nextCursor.encode();
        return new OaPage(
                records,
                committedCursor,
                encodedNext,
                pageDigest(sourceId, committedCursor, encodedNext, records));
    }

    /* ------------------------------------------------------------------ */
    /* Private fetch helpers                                               */
    /* ------------------------------------------------------------------ */

    private FetchBatch fetchLeave(
            Connection conn, Cursor cursor, int limit) throws Exception {
        List<OaDocumentRecord> result = new ArrayList<>();
        Cursor nextCursor = cursor;
        int scannedCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(LEAVE_SQL)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            Timestamp ts = cursor.asTimestamp();
            ps.setTimestamp(1, ts);
            ps.setTimestamp(2, ts);
            ps.setLong(3, cursor.lastId());
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String memberIdStr = rs.getString("member_id");
                    String employeeCode = rs.getString("employee_code");
                    Timestamp startTs = rs.getTimestamp("start_dt");
                    Timestamp endTs = rs.getTimestamp("end_dt");
                    Integer state = nullableInt(rs, "approval_state");
                    Timestamp modified = rs.getTimestamp("last_modified");
                    long formId = rs.getLong("form_id");
                    scannedCount++;
                    nextCursor = nextCursor.advance(modified, formId);
                    String sourceVersion = sourceVersion(modified, state);

                    if (memberIdStr == null
                            || startTs == null
                            || endTs == null
                            || sourceVersion == null) {
                        continue;
                    }

                    SourceStatus status = oaStateToStatus(state);
                    String leaveTypeLabel = rs.getString("leave_type_label");
                    LeaveType leaveType = LeaveType.fromLeaveCode(
                            OaLeaveTypeShowValueCatalog.resolveLeaveCode(leaveTypeLabel));
                    if (leaveType == null) {
                        log.warn("未知或缺失请假类型，已隔离: recordId={}, label={}",
                                formId, leaveTypeLabel);
                    }
                    boolean effective = leaveType != null && (status == SourceStatus.APPROVED
                            || status == SourceStatus.MODIFIED
                            || status == SourceStatus.SUPPLEMENTED);

                    result.add(new OaDocumentRecord(
                            "LEAVE:" + formId,
                            sourceVersion,
                            memberIdStr.trim(),
                            employeeCode != null ? employeeCode.trim() : null,
                            DocumentType.LEAVE,
                            status,
                            startTs.toInstant(),
                            endTs.toInstant(),
                            sourceTimeZone,
                            modified != null ? modified.toInstant() : null,
                            status == SourceStatus.APPROVED ? modified != null ? modified.toInstant() : null : null,
                            null,
                            status == SourceStatus.REVOKED ? modified != null ? modified.toInstant() : null : null,
                            "OA_LEAVE_BATCH",
                            effective,
                            null,
                            leaveType));
                }
            }
        }
        return new FetchBatch(result, nextCursor, scannedCount);
    }

    private FetchBatch fetchLeaveRevocation(
            Connection conn, Cursor cursor, int limit) throws Exception {
        List<OaDocumentRecord> result = new ArrayList<>();
        Cursor nextCursor = cursor;
        int scannedCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(LEAVE_REVOCATION_SQL)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            Timestamp ts = cursor.asTimestamp();
            ps.setTimestamp(1, ts);
            ps.setTimestamp(2, ts);
            ps.setLong(3, cursor.lastId());
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String memberIdStr = rs.getString("member_id");
                    String employeeCode = rs.getString("employee_code");
                    Timestamp startTs = rs.getTimestamp("actual_start_dt");
                    Timestamp endTs = rs.getTimestamp("actual_end_dt");
                    Integer state = nullableInt(rs, "approval_state");
                    Timestamp modified = rs.getTimestamp("last_modified");
                    long formId = rs.getLong("form_id");
                    scannedCount++;
                    nextCursor = nextCursor.advance(modified, formId);
                    String sourceVersion = sourceVersion(modified, state);

                    if (memberIdStr == null
                            || startTs == null
                            || endTs == null
                            || sourceVersion == null) {
                        continue;
                    }

                    SourceStatus status = oaStateToStatus(state);
                    boolean effective = status == SourceStatus.APPROVED;

                    result.add(new OaDocumentRecord(
                            "LEAVE_REVOCATION:" + formId,
                            sourceVersion,
                            memberIdStr.trim(),
                            employeeCode != null ? employeeCode.trim() : null,
                            DocumentType.LEAVE_REVOCATION,
                            status,
                            startTs.toInstant(),
                            endTs.toInstant(),
                            sourceTimeZone,
                            modified != null ? modified.toInstant() : null,
                            status == SourceStatus.APPROVED ? modified != null ? modified.toInstant() : null : null,
                            null,
                            null,
                            "OA_LEAVE_REVOCATION_BATCH",
                            effective));
                }
            }
        }
        return new FetchBatch(result, nextCursor, scannedCount);
    }

    private FetchBatch fetchOvertime(
            Connection conn, Cursor cursor, int limit) throws Exception {
        List<OaDocumentRecord> result = new ArrayList<>();
        Cursor nextCursor = cursor;
        int scannedCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(OVERTIME_SQL)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            Timestamp ts = cursor.asTimestamp();
            ps.setTimestamp(1, ts);
            ps.setTimestamp(2, ts);
            ps.setLong(3, cursor.lastId());
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String memberIdStr = rs.getString("member_id");
                    String employeeCode = rs.getString("employee_code");
                    Timestamp startTs = rs.getTimestamp("start_dt");
                    Timestamp endTs = rs.getTimestamp("end_dt");
                    Integer state = nullableInt(rs, "approval_state");
                    Timestamp modified = rs.getTimestamp("last_modified");
                    long formId = rs.getLong("form_id");
                    scannedCount++;
                    nextCursor = nextCursor.advance(modified, formId);
                    String sourceVersion = sourceVersion(modified, state);
                    Long overtimeTypeId = nullableLong(
                            rs, "overtime_type_id");
                    OvertimeType overtimeType =
                            OvertimeType.fromOaEnumId(overtimeTypeId);

                    if (overtimeTypeId == null) {
                        log.warn(
                                "加班记录类型为空，已隔离: recordId={}",
                                formId);
                    } else if (overtimeType == null) {
                        log.error(
                                "未知加班类型: enumId={}, recordId={}",
                                overtimeTypeId,
                                formId);
                    }

                    if (memberIdStr == null
                            || startTs == null
                            || endTs == null
                            || sourceVersion == null) {
                        continue;
                    }

                    SourceStatus status = oaStateToStatus(state);
                    boolean effective = status == SourceStatus.APPROVED
                            && overtimeType != null;

                    result.add(new OaDocumentRecord(
                            "OVERTIME:" + formId,
                            sourceVersion,
                            memberIdStr.trim(),
                            employeeCode != null ? employeeCode.trim() : null,
                            DocumentType.OVERTIME,
                            status,
                            startTs.toInstant(),
                            endTs.toInstant(),
                            sourceTimeZone,
                            modified != null ? modified.toInstant() : null,
                            status == SourceStatus.APPROVED ? modified != null ? modified.toInstant() : null : null,
                            null,
                            null,
                            "OA_OVERTIME_BATCH",
                            effective,
                            overtimeType));
                }
            }
        }
        return new FetchBatch(result, nextCursor, scannedCount);
    }

    private FetchBatch fetchOuting(
            Connection conn, Cursor cursor, int limit) throws Exception {
        List<OaDocumentRecord> result = new ArrayList<>();
        Cursor nextCursor = cursor;
        int scannedCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(OUTING_SQL)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            Timestamp ts = cursor.asTimestamp();
            ps.setTimestamp(1, ts);
            ps.setTimestamp(2, ts);
            ps.setLong(3, cursor.lastId());
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String memberIdStr = rs.getString("member_id");
                    String employeeCode = rs.getString("employee_code");
                    Timestamp startTs = rs.getTimestamp("start_dt");
                    Timestamp endTs = rs.getTimestamp("end_dt");
                    Integer state = nullableInt(rs, "approval_state");
                    Timestamp modified = rs.getTimestamp("last_modified");
                    long formId = rs.getLong("form_id");
                    scannedCount++;
                    nextCursor = nextCursor.advance(modified, formId);
                    String sourceVersion = sourceVersion(modified, state);

                    if (state == null
                            || memberIdStr == null
                            || startTs == null
                            || endTs == null
                            || sourceVersion == null) {
                        continue;
                    }

                    SourceStatus status = oaStateToStatus(state);
                    boolean effective = status == SourceStatus.APPROVED;

                    result.add(new OaDocumentRecord(
                            "OUTING:" + formId,
                            sourceVersion,
                            memberIdStr.trim(),
                            employeeCode != null ? employeeCode.trim() : null,
                            DocumentType.OUTING,
                            status,
                            startTs.toInstant(),
                            endTs.toInstant(),
                            sourceTimeZone,
                            modified != null ? modified.toInstant() : null,
                            effective && modified != null
                                    ? modified.toInstant()
                                    : null,
                            null,
                            status == SourceStatus.REVOKED && modified != null
                                    ? modified.toInstant()
                                    : null,
                            "OA_OUTING_BATCH",
                            effective));
                }
            }
        }
        return new FetchBatch(result, nextCursor, scannedCount);
    }

    private FetchBatch fetchExemptPunch(
            Connection conn, Cursor cursor, int limit) throws Exception {
        List<OaDocumentRecord> result = new ArrayList<>();
        Cursor nextCursor = cursor;
        int scannedCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(EXEMPT_PUNCH_SQL)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            Timestamp ts = cursor.asTimestamp();
            ps.setTimestamp(1, ts);
            ps.setTimestamp(2, ts);
            ps.setLong(3, cursor.lastId());
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String memberIdStr = rs.getString("member_id");
                    String employeeCode = rs.getString("employee_code");
                    Timestamp startTs = rs.getTimestamp("start_date");
                    Timestamp endTs = rs.getTimestamp("end_date");
                    Integer state = nullableInt(rs, "approval_state");
                    Timestamp modified = rs.getTimestamp("last_modified");
                    long formId = rs.getLong("form_id");
                    scannedCount++;
                    nextCursor = nextCursor.advance(modified, formId);
                    String sourceVersion = sourceVersion(modified, state);

                    if (state == null
                            || memberIdStr == null
                            || startTs == null
                            || endTs == null
                            || sourceVersion == null) {
                        continue;
                    }

                    SourceStatus status = oaStateToStatus(state);
                    boolean effective = status == SourceStatus.APPROVED;

                    result.add(new OaDocumentRecord(
                            "EXEMPT_PUNCH:" + formId,
                            sourceVersion,
                            memberIdStr.trim(),
                            employeeCode != null ? employeeCode.trim() : null,
                            DocumentType.EXEMPT_PUNCH,
                            status,
                            inclusiveDateStart(startTs),
                            inclusiveDateEndExclusive(endTs),
                            sourceTimeZone,
                            modified != null ? modified.toInstant() : null,
                            effective && modified != null
                                    ? modified.toInstant()
                                    : null,
                            null,
                            status == SourceStatus.REVOKED && modified != null
                                    ? modified.toInstant()
                                    : null,
                            "OA_EXEMPT_PUNCH_BATCH",
                            effective));
                }
            }
        }
        return new FetchBatch(result, nextCursor, scannedCount);
    }

    private FetchBatch fetchPunchCorrection(
            Connection conn, Cursor cursor, int limit) throws Exception {
        List<OaDocumentRecord> result = new ArrayList<>();
        Cursor nextCursor = cursor;
        int scannedCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(PUNCH_CORRECTION_SQL)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            Timestamp ts = cursor.asTimestamp();
            ps.setTimestamp(1, ts);
            ps.setTimestamp(2, ts);
            ps.setLong(3, cursor.lastId());
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String memberIdStr = rs.getString("member_id");
                    String employeeCode = rs.getString("employee_code");
                    Timestamp punchTs = rs.getTimestamp("punch_dt");
                    Integer state = nullableInt(rs, "approval_state");
                    Timestamp modified = rs.getTimestamp("last_modified");
                    long formId = rs.getLong("form_id");
                    scannedCount++;
                    nextCursor = nextCursor.advance(modified, formId);
                    String sourceVersion = sourceVersion(modified, state);

                    if (memberIdStr == null
                            || punchTs == null
                            || sourceVersion == null) {
                        continue;
                    }

                    SourceStatus status = oaStateToStatus(state);
                    boolean effective = status == SourceStatus.APPROVED;

                    result.add(new OaDocumentRecord(
                            "PUNCH_CORRECTION:" + formId,
                            sourceVersion,
                            memberIdStr.trim(),
                            employeeCode != null ? employeeCode.trim() : null,
                            DocumentType.PUNCH_CORRECTION,
                            status,
                            punchTs.toInstant(),
                            punchTs.toInstant(),
                            sourceTimeZone,
                            modified != null ? modified.toInstant() : null,
                            status == SourceStatus.APPROVED ? modified != null ? modified.toInstant() : null : null,
                            null,
                            null,
                            "OA_PUNCH_CORRECTION_BATCH",
                            effective));
                }
            }
        }
        return new FetchBatch(result, nextCursor, scannedCount);
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Maps col_summary.state to the OA SourceStatus enum.
     * state=3 → APPROVED, state=2 → REVOKED, state=0 → UNKNOWN (pending),
     * null → DRAFT.
     */
    static SourceStatus oaStateToStatus(Integer state) {
        if (state == null) {
            return SourceStatus.DRAFT;
        }
        return switch (state) {
            case 3 -> SourceStatus.APPROVED;
            case 2 -> SourceStatus.REVOKED;
            case 0 -> SourceStatus.UNKNOWN;
            default -> SourceStatus.UNKNOWN;
        };
    }

    private static Integer nullableInt(ResultSet rs, String col)
            throws java.sql.SQLException {
        int val = rs.getInt(col);
        return rs.wasNull() ? null : val;
    }

    private static Long nullableLong(ResultSet rs, String col)
            throws java.sql.SQLException {
        long val = rs.getLong(col);
        return rs.wasNull() ? null : val;
    }

    private Instant inclusiveDateStart(Timestamp value) {
        return value.toLocalDateTime()
                .toLocalDate()
                .atStartOfDay(sourceZone)
                .toInstant();
    }

    private Instant inclusiveDateEndExclusive(Timestamp value) {
        return value.toLocalDateTime()
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(sourceZone)
                .toInstant();
    }

    static String sourceVersion(Timestamp lastModified, Integer state) {
        if (lastModified == null || state == null) {
            return null;
        }
        return SOURCE_VERSION_TIME_FORMAT.format(lastModified.toLocalDateTime())
                + ":state=" + state;
    }

    private static int fairLimit(int remaining, int streamsRemaining) {
        if (remaining < 1 || streamsRemaining < 1) {
            throw new IllegalStateException("OA page capacity is exhausted");
        }
        return Math.max(1, remaining / streamsRemaining);
    }

    private static String pageDigest(
            String sourceId,
            String inputCursor,
            String nextCursor,
            List<OaDocumentRecord> records) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, "OA_PAGE_V2");
            updateDigest(digest, sourceId);
            updateDigest(digest, inputCursor);
            updateDigest(digest, nextCursor);
            updateDigest(digest, Integer.toString(records.size()));
            for (OaDocumentRecord record : records) {
                updateDigest(digest, record.sourceBusinessKey());
                updateDigest(digest, record.sourceVersion());
                updateDigest(digest, name(record.documentType()));
                updateDigest(digest, name(record.sourceStatus()));
                updateDigest(digest, instant(record.start()));
                updateDigest(digest, instant(record.end()));
                updateDigest(digest, name(record.overtimeType()));
                updateDigest(digest, name(record.leaveType()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "required digest algorithm is unavailable", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value)
                .getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length)
                .array());
        digest.update(bytes);
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private record FetchBatch(
            List<OaDocumentRecord> records,
            Cursor nextCursor,
            int scannedCount) {

        private FetchBatch {
            records = List.copyOf(records);
            if (nextCursor == null
                    || scannedCount < 0
                    || records.size() > scannedCount) {
                throw new IllegalArgumentException("invalid OA fetch batch");
            }
        }
    }

    /** One form family's ordered timestamp/id cursor. */
    record Cursor(long epochSecond, int nano, long lastId) {

        Cursor {
            if (epochSecond < 0
                    || nano < 0
                    || nano > 999_999_999
                    || lastId < 0) {
                throw new IllegalArgumentException(
                        "OA cursor values cannot be negative");
            }
        }

        static Cursor parseLegacy(String raw) {
            String[] parts = raw.split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid legacy OA cursor");
            }
            try {
                long ms = Long.parseLong(parts[0]);
                Instant instant = Instant.ofEpochMilli(ms);
                return new Cursor(
                        instant.getEpochSecond(),
                        instant.getNano(),
                        Long.parseLong(parts[1]));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "invalid legacy OA cursor", e);
            }
        }

        static Cursor parseComponent(
                String component, String key, boolean millisecondEncoding) {
            String prefix = key + "=";
            if (!component.startsWith(prefix)) {
                throw new IllegalArgumentException("invalid OA cursor stream");
            }
            String[] values = component.substring(prefix.length())
                    .split(",", -1);
            int expectedLength = millisecondEncoding ? 2 : 3;
            if (values.length != expectedLength) {
                throw new IllegalArgumentException("invalid OA cursor stream");
            }
            try {
                if (millisecondEncoding) {
                    Instant instant = Instant.ofEpochMilli(
                            Long.parseLong(values[0]));
                    return new Cursor(
                            instant.getEpochSecond(),
                            instant.getNano(),
                            Long.parseLong(values[1]));
                }
                return new Cursor(
                        Long.parseLong(values[0]),
                        Integer.parseInt(values[1]),
                        Long.parseLong(values[2]));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "invalid OA cursor stream", exception);
            }
        }

        String encodeComponent(String key) {
            return key + "=" + epochSecond + "," + nano + "," + lastId;
        }

        Cursor advance(Timestamp lastModified, long rowId) {
            if (lastModified == null) {
                return this;
            }
            Instant candidateInstant = lastModified.toInstant();
            Cursor candidate = new Cursor(
                    candidateInstant.getEpochSecond(),
                    candidateInstant.getNano(),
                    rowId);
            int timestampOrder = candidateInstant.compareTo(asInstant());
            if (timestampOrder < 0
                    || (timestampOrder == 0 && candidate.lastId < lastId)) {
                throw new IllegalStateException("OA source cursor regressed");
            }
            return timestampOrder == 0 && candidate.lastId == lastId
                    ? this
                    : candidate;
        }

        long lastModifiedMillis() {
            return asInstant().toEpochMilli();
        }

        private Instant asInstant() {
            return Instant.ofEpochSecond(epochSecond, nano);
        }

        Timestamp asTimestamp() {
            return Timestamp.from(asInstant());
        }
    }

    /**
     * Versioned composite cursor. The six components are deliberately fixed
     * and ordered so encoding is canonical and safely fits the 512-char
     * watermark column.
     */
    record MultiCursor(
            Cursor leave,
            Cursor leaveRevocation,
            Cursor overtime,
            Cursor outing,
            Cursor exemptPunch,
            Cursor punchCorrection) {

        private static final Cursor ZERO = new Cursor(0L, 0, 0L);

        MultiCursor {
            if (leave == null
                    || leaveRevocation == null
                    || overtime == null
                    || outing == null
                    || exemptPunch == null
                    || punchCorrection == null) {
                throw new IllegalArgumentException(
                        "OA cursor streams cannot be null");
            }
        }

        static MultiCursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return all(ZERO);
            }
            if (!raw.startsWith("oa2|") && !raw.startsWith("oa3|")) {
                return all(Cursor.parseLegacy(raw));
            }
            String[] components = raw.split("\\|", -1);
            if (components.length != 7) {
                throw new IllegalArgumentException("invalid OA composite cursor");
            }
            boolean millisecondEncoding = "oa2".equals(components[0]);
            if (!millisecondEncoding && !"oa3".equals(components[0])) {
                throw new IllegalArgumentException("invalid OA composite cursor");
            }
            return new MultiCursor(
                    Cursor.parseComponent(
                            components[1], "L", millisecondEncoding),
                    Cursor.parseComponent(
                            components[2], "R", millisecondEncoding),
                    Cursor.parseComponent(
                            components[3], "O", millisecondEncoding),
                    Cursor.parseComponent(
                            components[4], "U", millisecondEncoding),
                    Cursor.parseComponent(
                            components[5], "E", millisecondEncoding),
                    Cursor.parseComponent(
                            components[6], "P", millisecondEncoding));
        }

        private static MultiCursor all(Cursor cursor) {
            return new MultiCursor(
                    cursor, cursor, cursor, cursor, cursor, cursor);
        }

        String encode() {
            String encoded = "oa3|"
                    + leave.encodeComponent("L") + "|"
                    + leaveRevocation.encodeComponent("R") + "|"
                    + overtime.encodeComponent("O") + "|"
                    + outing.encodeComponent("U") + "|"
                    + exemptPunch.encodeComponent("E") + "|"
                    + punchCorrection.encodeComponent("P");
            if (encoded.length() > 512) {
                throw new IllegalStateException("OA cursor exceeds storage limit");
            }
            return encoded;
        }

        MultiCursor withLeave(Cursor value) {
            return new MultiCursor(
                    value, leaveRevocation, overtime, outing,
                    exemptPunch, punchCorrection);
        }

        MultiCursor withLeaveRevocation(Cursor value) {
            return new MultiCursor(
                    leave, value, overtime, outing,
                    exemptPunch, punchCorrection);
        }

        MultiCursor withOvertime(Cursor value) {
            return new MultiCursor(
                    leave, leaveRevocation, value, outing,
                    exemptPunch, punchCorrection);
        }

        MultiCursor withOuting(Cursor value) {
            return new MultiCursor(
                    leave, leaveRevocation, overtime, value,
                    exemptPunch, punchCorrection);
        }

        MultiCursor withExemptPunch(Cursor value) {
            return new MultiCursor(
                    leave, leaveRevocation, overtime, outing,
                    value, punchCorrection);
        }

        MultiCursor withPunchCorrection(Cursor value) {
            return new MultiCursor(
                    leave, leaveRevocation, overtime, outing,
                    exemptPunch, value);
        }
    }
}
