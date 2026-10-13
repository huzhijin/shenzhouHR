package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaLeaveTypeShowValueCatalog;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaSourceDateTimes;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
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
 * Live {@code col_summary} has no {@code lastmodifydate}; the page cursor
 * uses {@code COALESCE(finish_date, start_date, create_date)}.
 * State=3 (approved) and state=0 (pending) can cover attendance. State
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
                COALESCE(cs.finish_date, cs.start_date, cs.create_date)   AS last_modified
            FROM formmain_0170 m
            LEFT JOIN ctp_enum_item e
                ON e.id = m.field0089
            LEFT JOIN col_summary cs
                ON cs.form_recordid = m.id
            WHERE (COALESCE(cs.finish_date, cs.start_date, cs.create_date) > ? OR (COALESCE(cs.finish_date, cs.start_date, cs.create_date) = ? AND m.id > ?))
            ORDER BY COALESCE(cs.finish_date, cs.start_date, cs.create_date), m.id
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
                m.field0100         AS revocation_type_id,
                cancellation_type.showvalue AS revocation_type_label,
                cs.state            AS approval_state,
                COALESCE(cs.finish_date, cs.start_date, cs.create_date)   AS last_modified
            FROM formmain_0370 m
            LEFT JOIN ctp_enum_item cancellation_type
                ON cancellation_type.id = m.field0100
            LEFT JOIN col_summary cs
                ON cs.form_recordid = m.id
            WHERE (COALESCE(cs.finish_date, cs.start_date, cs.create_date) > ? OR (COALESCE(cs.finish_date, cs.start_date, cs.create_date) = ? AND m.id > ?))
            ORDER BY COALESCE(cs.finish_date, cs.start_date, cs.create_date), m.id
            LIMIT ?
            """;

    // Live OA enum verified 2026-10-04: this is "cancel leave", not a
    // leave-category enum. Blank actual endpoints alone never prove full cancellation.
    private static final long CANCEL_LEAVE_ENUM_ID = 5457595020900320516L;
    private static final String CANCEL_LEAVE_LABEL = "取消请假";
    private static final String CANCELLATION_ORIGINAL_SQL = """
            SELECT original.id AS original_id,
                   original.field0097 AS original_serial,
                   original.field0083 AS original_member_id,
                   original.field0084 AS original_employee_code,
                   original.field0086 AS original_start_dt,
                   original.field0087 AS original_end_dt
            FROM formmain_0170 original
            WHERE original.field0097 = ?
            ORDER BY original.id
            LIMIT 2
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
                COALESCE(cs.finish_date, cs.start_date, cs.create_date)   AS last_modified
            FROM formson_0172 s
            JOIN formmain_0171 m
                ON m.id = s.formmain_id
            LEFT JOIN col_summary cs
                ON cs.form_recordid = m.id
            WHERE (COALESCE(cs.finish_date, cs.start_date, cs.create_date) > ? OR (COALESCE(cs.finish_date, cs.start_date, cs.create_date) = ? AND s.id > ?))
            ORDER BY COALESCE(cs.finish_date, cs.start_date, cs.create_date), s.id
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
                COALESCE(cs.finish_date, cs.start_date, cs.create_date)   AS last_modified
            FROM formson_0252 s
            JOIN formmain_0251 m
                ON m.id = s.formmain_id
            JOIN col_summary cs
                ON cs.form_recordid = m.id
            WHERE (COALESCE(cs.finish_date, cs.start_date, cs.create_date) > ? OR (COALESCE(cs.finish_date, cs.start_date, cs.create_date) = ? AND s.id > ?))
            ORDER BY COALESCE(cs.finish_date, cs.start_date, cs.create_date), s.id
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
                COALESCE(cs.finish_date, cs.start_date, cs.create_date)   AS last_modified
            FROM formson_0202 s
            JOIN formmain_0201 m
                ON m.id = s.formmain_id
            JOIN col_summary cs
                ON cs.form_recordid = m.id
            WHERE (COALESCE(cs.finish_date, cs.start_date, cs.create_date) > ? OR (COALESCE(cs.finish_date, cs.start_date, cs.create_date) = ? AND s.id > ?))
              AND s.field0132 IS NOT NULL
              AND s.field0134 IS NOT NULL
              AND DATE(s.field0132) <= DATE(s.field0134)
            ORDER BY COALESCE(cs.finish_date, cs.start_date, cs.create_date), s.id
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
                COALESCE(cs.finish_date, cs.start_date, cs.create_date)   AS last_modified
            FROM formson_0204 s
            JOIN formmain_0203 m
                ON m.id = s.formmain_id
            LEFT JOIN col_summary cs
                ON cs.form_recordid = m.id
            WHERE (COALESCE(cs.finish_date, cs.start_date, cs.create_date) > ? OR (COALESCE(cs.finish_date, cs.start_date, cs.create_date) = ? AND s.id > ?))
            ORDER BY COALESCE(cs.finish_date, cs.start_date, cs.create_date), s.id
            LIMIT ?
            """;

    private static final String LEAVE_WINDOW_SQL = selectFrom(LEAVE_SQL)
            + " WHERE m.field0086 < ? AND m.field0087 > ? ORDER BY m.id";
    private static final String OVERTIME_WINDOW_SQL = selectFrom(OVERTIME_SQL)
            + " WHERE s.field0100 < ? AND s.field0099 > ? ORDER BY s.id";
    private static final String OUTING_WINDOW_SQL = selectFrom(OUTING_SQL)
            + " WHERE s.field0132 < ? AND s.field0135 > ? ORDER BY s.id";
    private static final String EXEMPT_WINDOW_SQL = selectFrom(EXEMPT_PUNCH_SQL)
            + " WHERE DATE(s.field0132) < DATE(?) AND DATE(s.field0134) >= DATE(?) ORDER BY s.id";
    private static final String CORRECTION_WINDOW_SQL = selectFrom(PUNCH_CORRECTION_SQL)
            + " WHERE s.field0132 < ? AND s.field0132 >= ? ORDER BY s.id";
    private static final String REVOCATION_WINDOW_SQL = selectFrom(LEAVE_REVOCATION_SQL) + """
             WHERE (m.field0086 < ? AND COALESCE(m.field0087, m.field0086) >= ?)
                OR EXISTS (
                    SELECT 1 FROM formmain_0170 original
                    WHERE original.field0086 < ? AND original.field0087 > ?
                      AND (original.field0083 = m.field0083
                           OR ((m.field0083 IS NULL OR TRIM(m.field0083) = '')
                               AND m.field0084 IS NOT NULL AND TRIM(m.field0084) <> ''
                               AND original.field0084 = m.field0084))
                      AND ((m.field0099 IS NOT NULL AND TRIM(m.field0099) <> ''
                            AND TRIM(original.field0097) = TRIM(m.field0099))
                           OR ((m.field0099 IS NULL OR TRIM(m.field0099) = '')
                               AND m.field0086 >= original.field0086
                               AND COALESCE(m.field0087, m.field0086) <= original.field0087)))
             ORDER BY m.id
            """;

    private static String selectFrom(String pagedSql) {
        return pagedSql.substring(0, pagedSql.indexOf("WHERE"));
    }

    private final OaReadOnlyConnectionProvider connections;
    private final int queryTimeoutSeconds;
    private final Instant syncNotBefore;
    private final Cursor syncNotBeforeCursor;

    OaMysqlAttendanceDocumentAdapter(
            OaReadOnlyConnectionProvider connections,
            OaMysqlProperties properties) {
        this.connections = connections;
        this.queryTimeoutSeconds = properties.queryTimeoutSeconds();
        this.sourceZone = properties.getSourceTimeZone();
        this.sourceTimeZone = sourceZone.getId();
        this.syncNotBefore = properties.syncNotBeforeInstant();
        Instant floor = this.syncNotBefore;
        this.syncNotBeforeCursor = new Cursor(
                floor.getEpochSecond(),
                floor.getNano(),
                Long.MIN_VALUE);
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
            inputCursor = raiseToNotBefore(
                    MultiCursor.parse(committedCursor));
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

        records.removeIf(record ->
                record.end() != null && record.end().isBefore(syncNotBefore));

        String encodedNext = nextCursor.equals(inputCursor)
                ? committedCursor
                : nextCursor.encode();
        return new OaPage(
                records,
                committedCursor,
                encodedNext,
                pageDigest(sourceId, committedCursor, encodedNext, records));
    }

    @Override
    public List<OaDocumentRecord> fetchOverlapping(
            String sourceId, Instant fromInclusive, Instant toExclusive) {
        if (fromInclusive == null
                || toExclusive == null
                || !fromInclusive.isBefore(toExclusive)) {
            throw new OaReadOnlyQueryException(
                    "OA_WINDOW_INVALID", "OA rematch window is invalid");
        }
        Timestamp fromTs = Timestamp.valueOf(
                LocalDateTime.ofInstant(fromInclusive, sourceZone));
        Timestamp toTs = Timestamp.valueOf(
                LocalDateTime.ofInstant(toExclusive, sourceZone));
        List<OaDocumentRecord> records = new ArrayList<>();
        try (Connection conn = connections.openConnection()) {
            records.addAll(fetchWindow(conn, LEAVE_WINDOW_SQL, DocumentType.LEAVE, fromTs, toTs, false));
            records.addAll(fetchWindow(conn, REVOCATION_WINDOW_SQL, DocumentType.LEAVE_REVOCATION, fromTs, toTs, true));
            records.addAll(fetchWindow(conn, OVERTIME_WINDOW_SQL, DocumentType.OVERTIME, fromTs, toTs, false));
            records.addAll(fetchWindow(conn, OUTING_WINDOW_SQL, DocumentType.OUTING, fromTs, toTs, false));
            records.addAll(fetchWindow(conn, EXEMPT_WINDOW_SQL, DocumentType.EXEMPT_PUNCH, fromTs, toTs, false));
            records.addAll(fetchWindow(conn, CORRECTION_WINDOW_SQL, DocumentType.PUNCH_CORRECTION, fromTs, toTs, false));
        } catch (OaReadOnlyQueryException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("OA window rematch fetch failed for source {}",
                    sourceId, exception);
            throw new OaReadOnlyQueryException(
                    "OA_DOCUMENT_FETCH_FAILED",
                    "OA document fetch failed: " + exception.getMessage());
        }
        records.removeIf(record ->
                record.end() != null && record.end().isBefore(syncNotBefore));
        return List.copyOf(records);
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
                    Instant start = OaSourceDateTimes.wallClock(rs, "start_dt");
                    Instant end = OaSourceDateTimes.wallClock(rs, "end_dt");
                    Integer state = nullableInt(rs, "approval_state");
                    Timestamp modified = rs.getTimestamp("last_modified");
                    long formId = rs.getLong("form_id");
                    scannedCount++;
                    nextCursor = nextCursor.advance(modified, formId);
                    String sourceVersion = sourceVersion(modified, state);

                    if (sourceVersion == null) {
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
                    boolean effective = leaveType != null && attendanceEffective(status);

                    result.add(new OaDocumentRecord(
                            "LEAVE:" + formId,
                            sourceVersion,
                            trimToNull(memberIdStr),
                            employeeCode != null ? employeeCode.trim() : null,
                            DocumentType.LEAVE,
                            status,
                            start,
                            end,
                            sourceTimeZone,
                            modified != null ? modified.toInstant() : null,
                            status == SourceStatus.APPROVED ? modified != null ? modified.toInstant() : null : null,
                            null,
                            status == SourceStatus.REVOKED ? modified != null ? modified.toInstant() : null : null,
                            "OA_LEAVE_BATCH",
                            effective,
                            null,
                            leaveType,
                            trimToNull(rs.getString("leave_serial")),
                            null));
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
                    var actualInterval = readRevocationInterval(rs, conn);
                    Instant start = actualInterval.start();
                    Instant end = actualInterval.end();
                    Integer state = nullableInt(rs, "approval_state");
                    Timestamp modified = rs.getTimestamp("last_modified");
                    long formId = rs.getLong("form_id");
                    scannedCount++;
                    nextCursor = nextCursor.advance(modified, formId);
                    String sourceVersion = sourceVersion(modified, state);

                    if (sourceVersion == null) {
                        continue;
                    }

                    SourceStatus status = oaStateToStatus(state);
                    boolean effective = status == SourceStatus.APPROVED;

                    result.add(new OaDocumentRecord(
                            "LEAVE_REVOCATION:" + formId,
                            sourceVersion,
                            trimToNull(memberIdStr),
                            employeeCode != null ? employeeCode.trim() : null,
                            DocumentType.LEAVE_REVOCATION,
                            status,
                            start,
                            end,
                            sourceTimeZone,
                            modified != null ? modified.toInstant() : null,
                            status == SourceStatus.APPROVED ? modified != null ? modified.toInstant() : null : null,
                            null,
                            null,
                            "OA_LEAVE_REVOCATION_BATCH",
                            effective,
                            null,
                            null,
                            trimToNull(rs.getString("revocation_serial")),
                            trimToNull(rs.getString("original_leave_serial"))));
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
                    Instant start = OaSourceDateTimes.wallClock(rs, "start_dt");
                    Instant end = OaSourceDateTimes.wallClock(rs, "end_dt");
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

                    if (sourceVersion == null) {
                        continue;
                    }

                    SourceStatus status = oaStateToStatus(state);
                    boolean effective = attendanceEffective(status)
                            && overtimeType != null;

                    result.add(new OaDocumentRecord(
                            "OVERTIME:" + formId,
                            sourceVersion,
                            trimToNull(memberIdStr),
                            employeeCode != null ? employeeCode.trim() : null,
                            DocumentType.OVERTIME,
                            status,
                            start,
                            end,
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
                    Instant start = OaSourceDateTimes.wallClock(rs, "start_dt");
                    Instant end = OaSourceDateTimes.wallClock(rs, "end_dt");
                    Integer state = nullableInt(rs, "approval_state");
                    Timestamp modified = rs.getTimestamp("last_modified");
                    long formId = rs.getLong("form_id");
                    scannedCount++;
                    nextCursor = nextCursor.advance(modified, formId);
                    String sourceVersion = sourceVersion(modified, state);

                    if (sourceVersion == null) {
                        continue;
                    }

                    SourceStatus status = oaStateToStatus(state);
                    boolean effective = attendanceEffective(status);

                    result.add(new OaDocumentRecord(
                            "OUTING:" + formId,
                            sourceVersion,
                            trimToNull(memberIdStr),
                            employeeCode != null ? employeeCode.trim() : null,
                            DocumentType.OUTING,
                            status,
                            start,
                            end,
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
                    Instant start = OaSourceDateTimes.inclusiveDateStart(
                            rs, "start_date");
                    Instant end = OaSourceDateTimes.inclusiveDateEndExclusive(
                            rs, "end_date");
                    Integer state = nullableInt(rs, "approval_state");
                    Timestamp modified = rs.getTimestamp("last_modified");
                    long formId = rs.getLong("form_id");
                    scannedCount++;
                    nextCursor = nextCursor.advance(modified, formId);
                    String sourceVersion = sourceVersion(modified, state);

                    if (sourceVersion == null) {
                        continue;
                    }

                    SourceStatus status = oaStateToStatus(state);
                    boolean effective = attendanceEffective(status);

                    result.add(new OaDocumentRecord(
                            "EXEMPT_PUNCH:" + formId,
                            sourceVersion,
                            trimToNull(memberIdStr),
                            employeeCode != null ? employeeCode.trim() : null,
                            DocumentType.EXEMPT_PUNCH,
                            status,
                            start,
                            end,
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
                    Instant punch = OaSourceDateTimes.wallClock(rs, "punch_dt");
                    Integer state = nullableInt(rs, "approval_state");
                    Timestamp modified = rs.getTimestamp("last_modified");
                    long formId = rs.getLong("form_id");
                    scannedCount++;
                    nextCursor = nextCursor.advance(modified, formId);
                    String sourceVersion = sourceVersion(modified, state);

                    if (memberIdStr == null
                            || punch == null
                            || sourceVersion == null) {
                        continue;
                    }

                    SourceStatus status = oaStateToStatus(state);
                    boolean effective = attendanceEffective(status);

                    result.add(new OaDocumentRecord(
                            "PUNCH_CORRECTION:" + formId,
                            sourceVersion,
                            trimToNull(memberIdStr),
                            employeeCode != null ? employeeCode.trim() : null,
                            DocumentType.PUNCH_CORRECTION,
                            status,
                            punch,
                            punch,
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

    @Override
    public boolean hasNoLeaveRevocationsForSerial(String sourceId, String leaveSerial) {
        if (leaveSerial == null || leaveSerial.isBlank() || !leaveSerial.equals(leaveSerial.strip())) return false;
        // No status/date/person filter: a moved or unresolved amendment still
        // prevents isolating an unparseable original. Blank serials cannot prove
        // absence because legacy matching can use subject/interval instead.
        String sql = "SELECT 1 FROM formmain_0370 WHERE field0099 IS NULL OR TRIM(field0099) = '' "
                + "OR TRIM(field0099) = TRIM(?) LIMIT 1";
        try (Connection connection = connections.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setString(1, leaveSerial);
            try (ResultSet rows = statement.executeQuery()) { return !rows.next(); }
        } catch (Exception unavailable) {
            throw new OaReadOnlyQueryException("OA_DOCUMENT_FETCH_FAILED", "OA amendment absence could not be verified");
        }
    }

    @Override
    public OvertimeDocumentPresence checkOvertimeDocumentPresence(String sourceId, long overtimeDetailId) {
        if (!SHARED_SOURCE_ID.equals(sourceId)) {
            return OvertimeDocumentPresence.UNSUPPORTED;
        }
        String detailSql = "SELECT 1 FROM formson_0172 WHERE id = ? LIMIT 1";
        try (Connection connection = connections.openConnection();
                PreparedStatement statement = connection.prepareStatement(detailSql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setLong(1, overtimeDetailId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? OvertimeDocumentPresence.PRESENT : OvertimeDocumentPresence.ABSENT;
            }
        } catch (Exception unavailable) {
            log.error("OA overtime document existence check failed for id={}", overtimeDetailId, unavailable);
            return OvertimeDocumentPresence.ERROR;
        }
    }

    private static final String SHARED_SOURCE_ID = "e05866a7-97b0-11f1-8924-00155d09fd0e";

    @Override
    public List<OaDocumentRecord> fetchByBusinessKeys(String sourceId, List<String> sourceBusinessKeys) {
        if (sourceBusinessKeys == null || sourceBusinessKeys.isEmpty()) { return List.of(); }
        java.util.Map<DocumentType, java.util.Set<Long>> grouped = new java.util.EnumMap<>(DocumentType.class);
        for (String key : sourceBusinessKeys) {
            if (key == null || !key.matches("(LEAVE|LEAVE_REVOCATION|OVERTIME|OUTING|EXEMPT_PUNCH|PUNCH_CORRECTION):-?[0-9]+")) {
                throw new OaReadOnlyQueryException("OA_DOCUMENT_KEY_INVALID", "OA document identity is invalid");
            }
            int separator = key.indexOf(':');
            try {
                grouped.computeIfAbsent(DocumentType.valueOf(key.substring(0, separator)),
                        ignored -> new java.util.LinkedHashSet<>()).add(Long.parseLong(key.substring(separator + 1)));
            } catch (IllegalArgumentException exception) {
                throw new OaReadOnlyQueryException("OA_DOCUMENT_KEY_INVALID", "OA document identity is invalid");
            }
        }
        List<OaDocumentRecord> records = new ArrayList<>();
        try (Connection connection = connections.openConnection()) {
            for (var entry : grouped.entrySet()) {
                String sourceSql = switch (entry.getKey()) {
                    case LEAVE -> LEAVE_SQL;
                    case LEAVE_REVOCATION -> LEAVE_REVOCATION_SQL;
                    case OVERTIME -> OVERTIME_SQL;
                    case OUTING -> OUTING_SQL;
                    case EXEMPT_PUNCH -> EXEMPT_PUNCH_SQL;
                    case PUNCH_CORRECTION -> PUNCH_CORRECTION_SQL;
                    default -> throw new IllegalStateException("unsupported OA family");
                };
                String alias = entry.getKey() == DocumentType.LEAVE || entry.getKey() == DocumentType.LEAVE_REVOCATION ? "m" : "s";
                List<Long> ids = List.copyOf(entry.getValue());
                for (int offset = 0; offset < ids.size(); offset += PAGE_SIZE) {
                    List<Long> batch = ids.subList(offset, Math.min(ids.size(), offset + PAGE_SIZE));
                    var missing = new java.util.LinkedHashSet<>(batch);
                    String sql = selectFrom(sourceSql) + " WHERE " + alias + ".id IN ("
                            + String.join(",", java.util.Collections.nCopies(batch.size(), "?")) + ") ORDER BY " + alias + ".id";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setQueryTimeout(queryTimeoutSeconds);
                        for (int index = 0; index < batch.size(); index++) { statement.setLong(index + 1, batch.get(index)); }
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) {
                                records.add(readDocument(rows, entry.getKey(), connection));
                                missing.remove(rows.getLong("form_id"));
                            }
                        }
                    }
                    records.addAll(fetchMissingDocumentTombstones(connection, entry.getKey(), List.copyOf(missing)));
                }
            }
            return List.copyOf(records);
        } catch (Exception exception) {
            throw new OaReadOnlyQueryException("OA_DOCUMENT_FETCH_FAILED", "OA identity refresh failed: " + exception.getMessage());
        }
    }

    /**
     * Missing source rows are not cancellations. Only a surviving, unambiguous
     * revoked workflow for the exact absent main-form identity can prove one.
     * Detail families require a verified historical detail-to-parent relation;
     * until that evidence is available they remain missing and block refresh.
     */
    private List<OaDocumentRecord> fetchMissingDocumentTombstones(
            Connection connection, DocumentType type, List<Long> missingIds) throws Exception {
        String mainTable = switch (type) {
            case LEAVE -> "formmain_0170";
            case LEAVE_REVOCATION -> "formmain_0370";
            default -> null;
        };
        if (mainTable == null || missingIds.isEmpty()) { return List.of(); }
        String sql = "SELECT cs.form_recordid, cs.state, cs.create_date, cs.start_date, cs.finish_date "
                + "FROM col_summary cs WHERE cs.form_recordid IN ("
                + String.join(",", java.util.Collections.nCopies(missingIds.size(), "?"))
                + ") AND NOT EXISTS (SELECT 1 FROM " + mainTable + " m WHERE m.id = cs.form_recordid)";
        var summaries = new java.util.TreeMap<Long, List<String>>();
        var ambiguous = new java.util.HashSet<Long>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            for (int index = 0; index < missingIds.size(); index++) { statement.setLong(index + 1, missingIds.get(index)); }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong("form_recordid");
                    if (!Integer.valueOf(2).equals(nullableInt(rows, "state"))) {
                        // Never hide a conflicting workflow by filtering state in SQL.
                        ambiguous.add(id);
                        continue;
                    }
                    String evidence = summaryTimestamp(rows, "create_date") + "|"
                            + summaryTimestamp(rows, "start_date") + "|" + summaryTimestamp(rows, "finish_date");
                    summaries.computeIfAbsent(id, ignored -> new ArrayList<>()).add(evidence);
                }
            }
        }
        List<OaDocumentRecord> records = new ArrayList<>();
        for (var entry : summaries.entrySet()) {
            if (ambiguous.contains(entry.getKey())) { continue; }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, "OA_MISSING_MAIN_REVOKED_V1");
            updateDigest(digest, mainTable);
            updateDigest(digest, Long.toString(entry.getKey()));
            updateDigest(digest, "state=2");
            entry.getValue().stream().sorted().forEach(evidence -> updateDigest(digest, evidence));
            // No source interval or person is invented. The ingestion transaction
            // inherits those fields only from this same key's accepted observation.
            records.add(new OaDocumentRecord(type.name() + ":" + entry.getKey(),
                    "OA_MAIN_REVOKED:" + HexFormat.of().formatHex(digest.digest()),
                    null, null, type, SourceStatus.REVOKED, null, null, sourceTimeZone,
                    null, null, null, null, "OA_" + type.name() + "_MISSING_MAIN", false));
        }
        return List.copyOf(records);
    }

    private static String summaryTimestamp(ResultSet rows, String column) throws java.sql.SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? "NULL" : value.toLocalDateTime().toString();
    }

    private List<OaDocumentRecord> fetchWindow(Connection connection, String sql, DocumentType type,
            Timestamp from, Timestamp to, boolean relatedOriginal) throws Exception {
        List<OaDocumentRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setTimestamp(1, to);
            statement.setTimestamp(2, from);
            if (relatedOriginal) {
                statement.setTimestamp(3, to);
                statement.setTimestamp(4, from);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) { records.add(readDocument(rows, type, connection)); }
            }
        }
        return records;
    }

    private record RevocationInterval(Instant start, Instant end) { }

    /** The same explicit cancellation proof is used by cursor and occurrence-window reads. */
    private RevocationInterval readRevocationInterval(ResultSet row, Connection connection) throws Exception {
        Instant start = OaSourceDateTimes.wallClock(row, "actual_start_dt");
        Instant end = OaSourceDateTimes.wallClock(row, "actual_end_dt");
        var actual = new RevocationInterval(start, end);
        if (start != null || end != null
                || !Long.valueOf(CANCEL_LEAVE_ENUM_ID).equals(nullableLong(row, "revocation_type_id"))
                || !CANCEL_LEAVE_LABEL.equals(row.getString("revocation_type_label"))) {
            return actual;
        }
        String serial = row.getString("original_leave_serial");
        if (serial == null || serial.isBlank()) return actual;
        try (PreparedStatement statement = connection.prepareStatement(CANCELLATION_ORIGINAL_SQL)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setString(1, serial);
            try (ResultSet original = statement.executeQuery()) {
                if (!original.next()) return actual;
                String originalSerial = original.getString("original_serial");
                String originalMember = original.getString("original_member_id");
                String originalCode = original.getString("original_employee_code");
                Instant originalStart = OaSourceDateTimes.wallClock(original, "original_start_dt");
                Instant originalEnd = OaSourceDateTimes.wallClock(original, "original_end_dt");
                // Check whole-source serial uniqueness before considering person.
                // An ambiguous second row must not be hidden by a subject filter.
                if (original.next() || !serial.equals(originalSerial)
                        || originalStart == null || originalEnd == null
                        || !originalStart.isBefore(originalEnd)) return actual;
                String member = row.getString("member_id");
                String code = row.getString("employee_code");
                boolean sameSubject = member != null && !member.isBlank()
                        ? member.equals(originalMember)
                        : code != null && !code.isBlank() && code.equals(originalCode);
                if (!sameSubject) return actual;
                // Existing calculation semantics interpret an approved zero actual
                // leave interval as full cancellation. Preserve the OA status: a
                // revoked/draft cancellation never becomes approved here.
                return new RevocationInterval(originalStart, originalStart);
            }
        }
    }

    private OaDocumentRecord readDocument(ResultSet row, DocumentType type, Connection connection) throws Exception {
        Integer state = nullableInt(row, "approval_state");
        SourceStatus status = oaStateToStatus(state);
        Timestamp modified = row.getTimestamp("last_modified");
        Instant start;
        Instant end;
        if (type == DocumentType.LEAVE_REVOCATION) {
            var actualInterval = readRevocationInterval(row, connection);
            start = actualInterval.start();
            end = actualInterval.end();
        } else if (type == DocumentType.EXEMPT_PUNCH) {
            start = OaSourceDateTimes.inclusiveDateStart(row, "start_date");
            end = OaSourceDateTimes.inclusiveDateEndExclusive(row, "end_date");
        } else if (type == DocumentType.PUNCH_CORRECTION) {
            start = OaSourceDateTimes.wallClock(row, "punch_dt");
            end = start;
        } else {
            start = OaSourceDateTimes.wallClock(row, "start_dt");
            end = OaSourceDateTimes.wallClock(row, "end_dt");
        }
        LeaveType leaveType = type == DocumentType.LEAVE
                ? LeaveType.fromLeaveCode(OaLeaveTypeShowValueCatalog.resolveLeaveCode(row.getString("leave_type_label"))) : null;
        OvertimeType overtimeType = type == DocumentType.OVERTIME
                ? OvertimeType.fromOaEnumId(nullableLong(row, "overtime_type_id")) : null;
        boolean effective = type == DocumentType.LEAVE_REVOCATION ? status == SourceStatus.APPROVED : attendanceEffective(status);
        return new OaDocumentRecord(type.name() + ":" + row.getLong("form_id"), refreshSourceVersion(modified, state),
                trimToNull(row.getString("member_id")), trimToNull(row.getString("employee_code")), type, status,
                start, end, sourceTimeZone, modified == null ? null : modified.toInstant(),
                status == SourceStatus.APPROVED && modified != null ? modified.toInstant() : null, null,
                status == SourceStatus.REVOKED && modified != null ? modified.toInstant() : null,
                "OA_" + type.name() + "_BATCH", effective && (type != DocumentType.LEAVE || leaveType != null)
                        && (type != DocumentType.OVERTIME || overtimeType != null),
                overtimeType, leaveType,
                type == DocumentType.LEAVE ? trimToNull(row.getString("leave_serial"))
                        : type == DocumentType.LEAVE_REVOCATION ? trimToNull(row.getString("revocation_serial")) : null,
                type == DocumentType.LEAVE_REVOCATION ? trimToNull(row.getString("original_leave_serial")) : null);
    }

    private static String refreshSourceVersion(Timestamp modified, Integer state) {
        String version = sourceVersion(modified, state);
        // A known identity with a deleted summary/draft must still supersede its old approval.
        return version != null ? version : (modified == null ? "NO_TIMESTAMP" : modified.toLocalDateTime().toString())
                + ":state=" + (state == null ? "NULL" : state) + ":Asia/Shanghai";
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static boolean attendanceEffective(SourceStatus status) {
        return com.szsemicon.hr.attendance.domain.OaApprovalStatus.effective(status.name());
    }

    /**
     * Maps col_summary.state to the OA SourceStatus enum.
     * state=3 → APPROVED, state=2 → REVOKED, state=0 → PENDING,
     * null → DRAFT.
     */
    static SourceStatus oaStateToStatus(Integer state) {
        if (state == null) {
            return SourceStatus.DRAFT;
        }
        return switch (state) {
            case 3 -> SourceStatus.APPROVED;
            case 2 -> SourceStatus.REVOKED;
            case 0 -> SourceStatus.PENDING;
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

    static String sourceVersion(Timestamp lastModified, Integer state) {
        return OaSourceDateTimes.sourceVersion(lastModified, state);
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
                updateDigest(digest, record.leaveSerial());
                updateDigest(digest, record.originalLeaveSerial());
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

    private MultiCursor raiseToNotBefore(MultiCursor cursor) {
        return new MultiCursor(
                atLeast(cursor.leave()),
                atLeast(cursor.leaveRevocation()),
                atLeast(cursor.overtime()),
                atLeast(cursor.outing()),
                atLeast(cursor.exemptPunch()),
                atLeast(cursor.punchCorrection()));
    }

    private Cursor atLeast(Cursor cursor) {
        long cursorMillis = cursor.lastModifiedMillis();
        long floorMillis = syncNotBeforeCursor.lastModifiedMillis();
        if (cursorMillis > floorMillis) {
            return cursor;
        }
        if (cursorMillis < floorMillis) {
            return syncNotBeforeCursor;
        }
        return cursor.lastId() >= syncNotBeforeCursor.lastId()
                ? cursor
                : syncNotBeforeCursor;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
                    || nano > 999_999_999) {
                throw new IllegalArgumentException(
                        "OA cursor timestamp cannot be negative");
            }
            // Seeyon form IDs are signed 64-bit values and are often negative.
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
