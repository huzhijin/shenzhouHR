package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Execute the real partial-copy INSERT, not a Java reimplementation of its predicate. */
class OaMovedLeavePartialCopySqlTest {
    private static final String COPY = AttendanceReportProjectionWriteMapper.class.getName()
            + ".copyOaDocumentFactsExceptEmployeeWindow";
    private static final Instant START = Instant.parse("2026-09-14T16:00:00Z");
    private static final Instant END = Instant.parse("2026-09-15T16:00:00Z");
    private static final Instant OLD_START = Instant.parse("2026-08-31T00:30:00Z");
    private static final Instant OLD_END = Instant.parse("2026-08-31T09:30:00Z");

    static Stream<Arguments> dateMaps() {
        return Stream.of(
                Arguments.of("LEAVE", 480L, "{\"2026-09-15\":480}", 0),
                Arguments.of("TIME_OFF", 480L, "{\"2026-09-15\":480}", 0),
                Arguments.of("LEAVE", 480L, "{ \"2026-09-15\" : 480 }", 0),
                Arguments.of("LEAVE", 480L, "{\"2026-09-14\":240,\"2026-09-15\":240}", 0),
                Arguments.of("LEAVE", 480L, "{\"2026-09-15\":240,\"2026-09-16\":240}", 0),
                Arguments.of("LEAVE", 480L, "{\"2026-09-14\":480}", 1),
                Arguments.of("LEAVE", 480L, "{\"2026-09-16\":480}", 1),
                Arguments.of("LEAVE", 480L, "{\"2026-09-15\":0,\"2026-09-16\":480}", 0),  // 0-minute date key replaced
                Arguments.of("LEAVE", 0L, "{}", 1),
                Arguments.of("LEAVE", 0L, "{\"2026-09-15\":0}", 0),  // 0-minute date key replaced
                Arguments.of("LEAVE", 480L, null, 1),
                Arguments.of("OVERTIME", 480L, "{\"2026-09-15\":480}", 1),
                Arguments.of("OUTING", 480L, "{\"2026-09-15\":480}", 1));
    }

    @ParameterizedTest
    @MethodSource("dateMaps")
    void replacesLeaveSharesIncludingZeroWithinShanghaiHalfOpenBusinessDays(
            String type, long minutes, String json, int expectedCopies) throws Exception {
        try (var session = OaReportLegacyPendingPublicationTest.session()) {
            seed(session, "old", "source", "company", "employee", type,
                    OLD_START, OLD_END, null, minutes, json);
            assertThat(session.insert(COPY, parameters(List.of("employee"), null)))
                    .isEqualTo(expectedCopies);
            assertThat(count(session, "source")).isEqualTo(1); // Immutable old pin is retained.
            assertThat(count(session, "target")).isEqualTo(expectedCopies);
        }
    }

    @Test
    void originalOverlapStillReplacesRowWhenPublishedMapMovedOutsideWindow() throws Exception {
        try (var session = OaReportLegacyPendingPublicationTest.session()) {
            seed(session, "old", "source", "company", "employee", "LEAVE",
                    START.plusSeconds(3600), START.plusSeconds(7200), null,
                    480, "{\"2026-09-16\":480}");
            // FullCalculationEngineOrchestrator publishes original-window OR actual-day rows.
            assertThat(session.insert(COPY, parameters(List.of("employee"), null))).isZero();
        }
    }

    @Test
    void legacyAndZeroMinuteRowsKeepOriginalIntervalRule() throws Exception {
        try (var session = OaReportLegacyPendingPublicationTest.session()) {
            seed(session, "legacy", "source", "company", "employee", "LEAVE",
                    START.plusSeconds(3600), START.plusSeconds(7200), null, 480, null);
            seed(session, "zero", "source", "company", "employee", "LEAVE",
                    START.plusSeconds(3600), START.plusSeconds(7200), null, 0, "{}");
            assertThat(session.insert(COPY, parameters(List.of("employee"), null))).isZero();
        }
    }

    @Test
    void selectedEmployeeAndSourceProjectionRemainIsolated() throws Exception {
        try (var session = OaReportLegacyPendingPublicationTest.session()) {
            String map = "{\"2026-09-15\":480}";
            seed(session, "target-employee", "source", "company", "employee", "LEAVE",
                    OLD_START, OLD_END, null, 480, map);
            seed(session, "other-employee", "source", "company", "other", "LEAVE",
                    OLD_START, OLD_END, null, 480, map);
            seed(session, "other-company", "foreign-pin", "other-company", "employee", "LEAVE",
                    OLD_START, OLD_END, null, 480, map);
            assertThat(session.insert(COPY, parameters(List.of("employee"), null))).isEqualTo(1);
            try (var sql = session.getConnection().createStatement(); var rows = sql.executeQuery(
                    "SELECT company_id,employee_id,oa_attendance_document_id FROM attendance_report_oa_fact "
                            + "WHERE attendance_report_projection_id='target'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("company");
                assertThat(rows.getString(2)).isEqualTo("other");
                assertThat(rows.getString(3)).isEqualTo("other-employee");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void legacySingleEmployeeArgumentIsStillHonoured() throws Exception {
        try (var session = OaReportLegacyPendingPublicationTest.session()) {
            seed(session, "old", "source", "company", "employee", "LEAVE",
                    OLD_START, OLD_END, null, 480, "{\"2026-09-15\":480}");
            assertThat(session.insert(COPY, parameters(List.of(), "other"))).isEqualTo(1);
        }
    }

    @Test
    void companyScopeReplacesMovedRowsWithoutAnEmployeeList() throws Exception {
        try (var session = OaReportLegacyPendingPublicationTest.session()) {
            seed(session, "old", "source", "company", "employee", "LEAVE",
                    OLD_START, OLD_END, null, 480, "{\"2026-09-15\":480}");
            assertThat(session.insert(COPY, parameters(List.of(), null))).isZero();
        }
    }

    @Test
    void pointBoundaryRuleRemainsInclusiveStartExclusiveEnd() throws Exception {
        try (var session = OaReportLegacyPendingPublicationTest.session()) {
            seed(session, "first", "source", "company", "employee", "PUNCH_CORRECTION",
                    null, null, START, 0, null);
            seed(session, "end", "source", "company", "employee", "PUNCH_CORRECTION",
                    null, null, END, 0, null);
            assertThat(session.insert(COPY, parameters(List.of("employee"), null))).isEqualTo(1);
            try (var sql = session.getConnection().createStatement(); var rows = sql.executeQuery(
                    "SELECT oa_attendance_document_id FROM attendance_report_oa_fact "
                            + "WHERE attendance_report_projection_id='target'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("end");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void movedOldFactDoesNotCollideWithNewFactAndOtherEmployeeMapIsPreserved() throws Exception {
        try (var session = OaReportLegacyPendingPublicationTest.session()) {
            String json = "{\"2026-09-15\":480}";
            seed(session, "same-document", "source", "company", "employee", "LEAVE",
                    OLD_START, OLD_END, null, 480, json);
            seed(session, "other-document", "source", "company", "other", "LEAVE",
                    OLD_START, OLD_END, null, 480, json);
            assertThat(session.insert(COPY, parameters(List.of("employee"), null))).isEqualTo(1);
            seed(session, "same-document", "target", "company", "employee", "LEAVE",
                    OLD_START, OLD_END, null, 240, "{\"2026-09-15\":240}");
            assertThat(count(session, "target")).isEqualTo(2);
            try (var sql = session.getConnection().createStatement(); var rows = sql.executeQuery(
                    "SELECT recognized_minutes_by_date_json FROM attendance_report_oa_fact "
                            + "WHERE attendance_report_projection_id='target' AND employee_id='other'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(json);
            }
        }
    }

    @Test
    void copyKeepsEachRowsAllocationSemanticsAndSkipsReEmittedForms() throws Exception {
        try (var session = OaReportLegacyPendingPublicationTest.session()) {
            String json = "{\"2026-09-01\":480}";
            // Unselected employee: one legacy (NULL) row and one NET_WORK_V1 row.
            seed(session, "legacy-row", "source", "company", "other", "LEAVE",
                    OLD_START, OLD_END, null, 480, json);
            seed(session, "net-row", "source", "company", "other", "LEAVE",
                    OLD_START, OLD_END, null, 480, json);
            // Selected employee's partner outside the window, re-emitted by this run.
            seed(session, "partner", "source", "company", "employee", "LEAVE",
                    OLD_START, OLD_END, null, 480, json);
            try (var sql = session.getConnection().createStatement()) {
                sql.executeUpdate("UPDATE attendance_report_oa_fact SET allocation_semantics='NET_WORK_V1' "
                        + "WHERE oa_attendance_document_id='net-row'");
            }
            var parameters = parameters(List.of("employee"), null);
            parameters.put("replacedDocumentIds", List.of("partner"));
            assertThat(session.insert(COPY, parameters)).isEqualTo(2);
            try (var sql = session.getConnection().createStatement(); var rows = sql.executeQuery(
                    "SELECT oa_attendance_document_id, allocation_semantics FROM attendance_report_oa_fact "
                            + "WHERE attendance_report_projection_id='target' ORDER BY oa_attendance_document_id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("legacy-row");
                assertThat(rows.getString(2)).isNull();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("net-row");
                assertThat(rows.getString(2)).isEqualTo("NET_WORK_V1");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void datePatternIsBoundAsAParameterRatherThanInterpolatedIntoSql() throws Exception {
        try (var session = OaReportLegacyPendingPublicationTest.session()) {
            var bound = session.getConfiguration().getMappedStatement(COPY)
                    .getBoundSql(parameters(List.of("employee"), null));
            assertThat(bound.getSql()).contains("REGEXP ?").doesNotContain("2026-09-15");
            assertThat(bound.getAdditionalParameter("recognizedCopyDatePattern"))
                    .isEqualTo("\"(?:2026-09-15)\"\\s*:\\s*[0-9]+\\s*[,}]");  // anyDatePattern includes 0
        }
    }

    private static Map<String, Object> parameters(List<String> employees, String employee) {
        var parameters = new HashMap<String, Object>();
        parameters.put("sourceProjectionId", "source");
        parameters.put("targetProjectionId", "target");
        parameters.put("windowStart", START);
        parameters.put("windowEndExclusive", END);
        parameters.put("createdAt", Instant.parse("2026-10-06T00:00:00Z"));
        parameters.put("employeeIds", employees);
        parameters.put("employeeId", employee);
        return parameters;
    }

    private static int count(SqlSession session, String projection) throws Exception {
        try (var sql = session.getConnection().prepareStatement(
                "SELECT COUNT(*) FROM attendance_report_oa_fact WHERE attendance_report_projection_id=?")) {
            sql.setString(1, projection);
            try (var rows = sql.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    private static void seed(SqlSession session, String document, String projection,
            String company, String employee, String type, Instant start, Instant end,
            Instant point, long minutes, String json) throws Exception {
        try (var sql = session.getConnection().prepareStatement("""
                INSERT INTO attendance_report_oa_fact (
                  attendance_report_oa_fact_id,attendance_report_projection_id,company_id,
                  oa_attendance_document_id,employee_id,document_type,temporal_shape,
                  interval_start,interval_end,point_instant,recognized_minutes,
                  recognized_minutes_by_date_json,source_status,source_version,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            sql.setString(1, UUID.randomUUID().toString()); sql.setString(2, projection);
            sql.setString(3, company); sql.setString(4, document); sql.setString(5, employee);
            sql.setString(6, type); sql.setString(7, point == null ? "INTERVAL" : "POINT");
            sql.setTimestamp(8, start == null ? null : Timestamp.from(start));
            sql.setTimestamp(9, end == null ? null : Timestamp.from(end));
            sql.setTimestamp(10, point == null ? null : Timestamp.from(point));
            sql.setLong(11, minutes); sql.setString(12, json); sql.setString(13, "APPROVED");
            sql.setString(14, "v1"); sql.setTimestamp(15, Timestamp.from(Instant.parse("2026-10-06T00:00:00Z")));
            sql.executeUpdate();
        }
    }
}
