package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.QueryPageRows;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Execute the actual query mapper against UTC instants at Shanghai day edges. */
class AttendanceReportQueryPageBusinessDateTest {

    private static final String NAMESPACE = AttendanceReportQueryPageMapper.class.getName() + ".";

    @ParameterizedTest
    @ValueSource(strings = {"listOaDocuments", "listOaForEmployees"})
    void sx101MovedLeaveAppearsOnlyOnActualDayAndPreservesOriginalInterval(String statement) throws Exception {
        try (SqlSession s = session()) {
            interval(s, "sx101", "2026-09-16 05:30:00", "2026-09-16 10:00:00");
            updateShares(s, "sx101", 270, "{\"2026-09-15\":270}");
            var actual = dayQuery(15);
            List<QueryPageRows.OaRow> rows = s.selectList(NAMESPACE + statement, actual);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().recognizedMinutes()).isEqualTo(270);
            assertThat(rows.getFirst().startAt()).isEqualTo(Instant.parse("2026-09-16T05:30:00Z"));
            assertThat((Long)s.selectOne(NAMESPACE + "countOaDocuments", actual)).isEqualTo(1L);
            assertThat(s.selectList(NAMESPACE + statement, dayQuery(16))).isEmpty();
            assertThat((Long)s.selectOne(NAMESPACE + "countOaDocuments", dayQuery(16))).isZero();
            List<QueryPageRows.LeaveSummaryRow> summaries = s.selectList(NAMESPACE + "listLeaveSummary", actual);
            assertThat(summaries.getFirst().recognizedMinutes()).isEqualTo(270);
            assertThat(s.selectList(NAMESPACE + "listLeaveSummary", dayQuery(16))).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEAVE", "TIME_OFF"})
    void movedDateWithZeroMinuteKeyCanEnterTheMonthForSickRestDay(String type) throws Exception {
        try (SqlSession s = session()) {
            insert(s, "moved", type, "2026-08-31 00:30:00", "2026-08-31 10:00:00", null);
            updateShares(s, "moved", 480, "{ \"2026-09-01\" : 480, \"2026-09-02\" : 0 }");
            assertThat((Long)s.selectOne(NAMESPACE + "countOaDocuments", dayQuery(1))).isEqualTo(1L);
            assertThat((Long)s.selectOne(NAMESPACE + "countOaDocuments", dayQuery(2))).isEqualTo(1L);
            var original = dayQuery(1);
            original.put("fromDate", LocalDate.of(2026,8,31));
            original.put("toDateExclusive", LocalDate.of(2026,9,1));
            // Original date moved out: 0 count
            assertThat((Long)s.selectOne(NAMESPACE + "countOaDocuments", original)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"listOaDocuments", "listOaForEmployees"})
    void oaRowMapsProjectionWindowAndByDateJsonFields(String statement) throws Exception {
        try (SqlSession s = session()) {
            // interval fully within Sep, byDate JSON with one workday
            interval(s, "proj-fields-test", "2026-09-01 16:00:00", "2026-09-02 16:00:00");
            updateShares(s, "proj-fields-test", 480, "{\"2026-09-02\":480}");
            List<QueryPageRows.OaRow> rows = s.selectList(NAMESPACE + statement, dayQuery(2));
            assertThat(rows).hasSize(1);
            QueryPageRows.OaRow row = rows.getFirst();
            // projection window must be mapped from the joined projection table
            assertThat(row.projectionStart())
                    .as("projectionStart")
                    .isEqualTo(java.time.LocalDate.of(2026, 9, 1));
            assertThat(row.projectionEndExclusive())
                    .as("projectionEndExclusive")
                    .isEqualTo(java.time.LocalDate.of(2026, 10, 1));
            // byDate JSON must pass through verbatim
            assertThat(row.recognizedMinutesByDateJson())
                    .as("recognizedMinutesByDateJson")
                    .contains("2026-09-02");
            // dataAsOf must be mapped from the projection table
            assertThat(row.projectionDataAsOf())
                    .as("projectionDataAsOf")
                    .isEqualTo(Instant.parse("2026-10-04T16:31:25Z"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "NULL"})
    void fullCancellationRemainsVisibleAtOriginalDateWithZeroHours(String shares) throws Exception {
        try (SqlSession s = session()) {
            interval(s, "cancelled", "2026-09-16 05:30:00", "2026-09-16 10:00:00");
            updateShares(s, "cancelled", 0, "NULL".equals(shares) ? null : shares);
            List<QueryPageRows.OaRow> rows = s.selectList(NAMESPACE + "listOaDocuments", dayQuery(16));
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().recognizedMinutes()).isZero();
            assertThat((Long)s.selectOne(NAMESPACE + "countOaDocuments", dayQuery(15))).isZero();
        }
    }

    @Test
    void actualDayFilterRetainsEmployeeCompanyOrganizationAndStatusRestrictions() throws Exception {
        try (SqlSession s = session()) {
            interval(s, "moved", "2026-09-16 05:30:00", "2026-09-16 10:00:00");
            updateShares(s, "moved", 270, "{\"2026-09-15\":270}");
            for (var restriction : Map.of("companyId", "other-company", "employeeId", "other-person",
                    "employeeNumber", "OTHER", "sourceStatus", "PENDING").entrySet()) {
                var q = dayQuery(15);q.put(restriction.getKey(), restriction.getValue());
                assertThat((Long)s.selectOne(NAMESPACE + "countOaDocuments", q)).isZero();
            }
            var q = dayQuery(15);q.put("organizationIds", List.of("other-org"));
            assertThat((Long)s.selectOne(NAMESPACE + "countOaDocuments", q)).isZero();
        }
    }

    private static Map<String,Object> dayQuery(int day) {
        var q = query();q.put("fromDate", LocalDate.of(2026,9,day));
        q.put("toDateExclusive", LocalDate.of(2026,9,day).plusDays(1));return q;
    }

    private static void updateShares(SqlSession s, String id, long minutes, String shares) throws Exception {
        try(var p=s.getConnection().prepareStatement("UPDATE attendance_report_oa_fact SET recognized_minutes=?,recognized_minutes_by_date_json=? WHERE oa_attendance_document_id=?")) {
            p.setLong(1,minutes);p.setString(2,shares);p.setString(3,id);p.executeUpdate();
        }
        s.clearCache();
    }

    @ParameterizedTest
    @ValueSource(strings = {"listOaDocuments", "listOaForEmployees"})
    void septemberLastDayExcludesOtherDaysButIncludesItsFirstAndLastHours(String statement)
            throws Exception {
        try (SqlSession session = session()) {
            interval(session, "previous", "2026-09-29 15:00:00", "2026-09-29 16:00:00");
            interval(session, "first-hour", "2026-09-29 16:00:00", "2026-09-29 17:00:00");
            interval(session, "last-hour", "2026-09-30 15:00:00", "2026-09-30 16:00:00");
            interval(session, "next", "2026-09-30 16:00:00", "2026-09-30 17:00:00");
            interval(session, "other-september-day", "2026-09-10 00:30:00", "2026-09-10 09:30:00");

            List<QueryPageRows.OaRow> rows = session.selectList(NAMESPACE + statement, query());
            assertThat(rows).extracting(QueryPageRows.OaRow::documentId)
                    .containsExactlyInAnyOrder("first-hour", "last-hour");
            assertThat((Long) session.selectOne(NAMESPACE + "countOaDocuments", query()))
                    .isEqualTo(2L);
        }
    }

    @Test
    void pointAtLocalMidnightIsIncludedAndNextMidnightIsExcluded() throws Exception {
        try (SqlSession session = session()) {
            point(session, "first-point", "2026-09-29 16:00:00");
            point(session, "last-point", "2026-09-30 15:59:59");
            point(session, "next-point", "2026-09-30 16:00:00");
            Map<String, Object> query = query();
            query.put("documentType", "PUNCH_CORRECTION");
            List<QueryPageRows.OaRow> rows = session.selectList(NAMESPACE + "listOaDocuments", query);
            assertThat(rows).extracting(QueryPageRows.OaRow::documentId)
                    .containsExactly("first-point", "last-point");
            assertThat((Long) session.selectOne(NAMESPACE + "countOaDocuments", query))
                    .isEqualTo(2L);
        }
    }

    @Test
    void occurrenceDayUsesShanghaiStartDateNotUtcStartDate() throws Exception {
        try (SqlSession session = session()) {
            interval(session, "local-30", "2026-09-29 16:30:00", "2026-09-29 17:30:00");
            // Intersects the selected day but belongs to the preceding start date.
            interval(session, "local-29", "2026-09-29 15:30:00", "2026-09-29 16:30:00");
            Map<String, Object> query = query();
            query.put("occurrenceDay", 30);
            List<QueryPageRows.OaRow> rows = session.selectList(NAMESPACE + "listOaDocuments", query);
            assertThat(rows).extracting(QueryPageRows.OaRow::documentId).containsExactly("local-30");
            assertThat((Long) session.selectOne(NAMESPACE + "countOaDocuments", query))
                    .isEqualTo(1L);
        }
    }

    @Test
    void leaveSummarySharesTheSameDayWindowAndKeepsWholeDocumentRecognition() throws Exception {
        try (SqlSession session = session()) {
            interval(session, "first-hour", "2026-09-29 16:00:00", "2026-09-29 17:00:00");
            interval(session, "last-hour", "2026-09-30 15:00:00", "2026-09-30 16:00:00");
            interval(session, "outside", "2026-09-30 16:00:00", "2026-09-30 17:00:00");
            List<QueryPageRows.LeaveSummaryRow> rows = session.selectList(
                    NAMESPACE + "listLeaveSummary", query());
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().documentCount()).isEqualTo(2);
            assertThat(rows.getFirst().recognizedMinutes()).isEqualTo(120);
            assertThat((Long) session.selectOne(NAMESPACE + "countLeaveSummary", query())).isEqualTo(1L);
        }
    }

    @Test
    void sourcePresenceUsesTheSameShanghaiMonthBoundary() throws Exception {
        try (SqlSession session = session()) {
            try (var sql = session.getConnection().createStatement()) {
                sql.execute("INSERT INTO employee VALUES ('employee', 'company')");
                sql.execute("INSERT INTO effective_attendance_event VALUES ('employee', 'PUNCH_POINT', TIMESTAMP '2026-08-31 16:00:00', NULL, NULL, TIMESTAMP '2026-10-03 00:00:00')");
            }
            Map<String, Object> parameters = Map.of(
                    "companyId", "company", "periodStart", LocalDate.of(2026, 9, 1),
                    "periodEndExclusive", LocalDate.of(2026, 10, 1),
                    "dataAsOf", Instant.parse("2026-10-04T00:00:00Z"));
            assertThat((Boolean) session.selectOne(NAMESPACE + "companyHasSourceEvidence", parameters))
                    .isTrue();
            try (var sql = session.getConnection().createStatement()) {
                sql.execute("UPDATE effective_attendance_event SET point_instant=TIMESTAMP '2026-09-30 16:00:00'");
            }
            session.clearCache();
            assertThat((Boolean) session.selectOne(NAMESPACE + "companyHasSourceEvidence", parameters))
                    .isFalse();
        }
    }

    @Test
    void dailyFactsRemainFilteredByTheirBusinessDate() throws Exception {
        try (SqlSession session = session()) {
            String sql = session.getConfiguration().getMappedStatement(NAMESPACE + "listDailyCells")
                    .getBoundSql(query()).getSql().replaceAll("\\s+", " ");
            assertThat(sql).contains("fact.business_date >= ?", "fact.business_date < ?")
                    .doesNotContain("TIMESTAMPADD(HOUR, -8");
        }
    }

    private static Map<String, Object> query() {
        Map<String, Object> query = new HashMap<>();
        query.put("projectionIds", List.of("projection"));
        query.put("companyId", "company");
        query.put("fromDate", LocalDate.of(2026, 9, 30));
        query.put("toDateExclusive", LocalDate.of(2026, 10, 1));
        query.put("employeeIds", List.of("employee"));
        query.put("documentType", "LEAVE");
        query.put("offset", 0);
        query.put("limit", 100);
        return query;
    }

    private static void interval(SqlSession session, String id, String start, String end)
            throws Exception {
        insert(session, id, "LEAVE", start, end, null);
    }

    private static void point(SqlSession session, String id, String at) throws Exception {
        insert(session, id, "PUNCH_CORRECTION", null, null, at);
    }

    private static void insert(SqlSession session, String id, String type,
            String start, String end, String point) throws Exception {
        try (var sql = session.getConnection().prepareStatement("""
                INSERT INTO attendance_report_oa_fact VALUES
                (?, ?, 'projection', 'company', 'employee', 'employee-version', 'organization',
                 'organization-version', ?, 'COMPENSATORY', ?, ?, ?, 60, 'APPROVED', 'version-1', NULL)
                """)) {
            sql.setString(1, "fact-" + id);  // attendance_report_oa_fact_id (primary key)
            sql.setString(2, id);  // oa_attendance_document_id
            sql.setString(3, type);
            sql.setString(4, start);
            sql.setString(5, end);
            sql.setString(6, point);
            sql.executeUpdate();
        }
    }

    private static SqlSession session() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("query-business-date", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver",
                        "jdbc:h2:mem:query-date-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")));
        String resource = "mappers/AttendanceReportQueryPageMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        try (var sql = session.getConnection().createStatement()) {
            for (String ddl : List.of(
                    "SET TIME ZONE 'UTC'",
                    "CREATE TABLE employee_version (employee_version_id VARCHAR(64), employee_number VARCHAR(64), display_name VARCHAR(64))",
                    "CREATE TABLE organization_version (organization_version_id VARCHAR(64), name VARCHAR(64))",
                    "CREATE TABLE oa_attendance_document (oa_attendance_document_id VARCHAR(64), source_business_key VARCHAR(64))",
                    "CREATE TABLE oa_attendance_document_context (oa_attendance_document_id VARCHAR(64), overtime_type VARCHAR(32))",
                    "CREATE TABLE employee (employee_id VARCHAR(64), company_id VARCHAR(64))",
                    "CREATE TABLE effective_attendance_event (employee_id VARCHAR(64), event_kind VARCHAR(32), point_instant TIMESTAMP, interval_start TIMESTAMP, interval_end TIMESTAMP, created_at TIMESTAMP)",
                    """
                    CREATE TABLE attendance_report_projection (
                        attendance_report_projection_id VARCHAR(64),
                        period_start DATE,
                        period_end_exclusive DATE,
                        data_as_of TIMESTAMP)
                    """,
                    "INSERT INTO attendance_report_projection VALUES ('projection', '2026-09-01', '2026-10-01', TIMESTAMP '2026-10-04 16:31:25')",
                    """
                    CREATE TABLE attendance_report_oa_fact (
                        attendance_report_oa_fact_id VARCHAR(64) PRIMARY KEY,
                        oa_attendance_document_id VARCHAR(64), attendance_report_projection_id VARCHAR(64),
                        company_id VARCHAR(64), employee_id VARCHAR(64), employee_version_id VARCHAR(64),
                        organization_id VARCHAR(64), organization_version_id VARCHAR(64), document_type VARCHAR(32),
                        leave_type_code VARCHAR(32), interval_start TIMESTAMP, interval_end TIMESTAMP,
                        point_instant TIMESTAMP, recognized_minutes BIGINT, source_status VARCHAR(32), source_version VARCHAR(128),
                        recognized_minutes_by_date_json VARCHAR(16000))
                    """,
                    "INSERT INTO employee_version VALUES ('employee-version', 'TEST001', 'Test')",
                    "INSERT INTO organization_version VALUES ('organization-version', 'Test department')",
                    """
                    CREATE TABLE attendance_report_hr_paper_fact (
                        attendance_report_hr_paper_fact_id VARCHAR(36),
                        attendance_report_projection_id VARCHAR(36),
                        company_id VARCHAR(36),
                        employee_id VARCHAR(36),
                        employee_version_id VARCHAR(36),
                        employment_period_id VARCHAR(36),
                        organization_id VARCHAR(36),
                        organization_version_id VARCHAR(36),
                        document_type VARCHAR(32),
                        leave_type_code VARCHAR(32),
                        interval_start TIMESTAMP(6),
                        interval_end TIMESTAMP(6),
                        point_instant TIMESTAMP(6),
                        recognized_minutes BIGINT,
                        source_status VARCHAR(32),
                        source_version VARCHAR(128),
                        source_document_key VARCHAR(256),
                        created_at TIMESTAMP(6),
                        recognized_minutes_by_date_json VARCHAR(16000),
                        hr_adjustment_id VARCHAR(36),
                        hr_source_kind VARCHAR(64))
                    """)) {
                sql.execute(ddl);
            }
        }
        return session;
    }
}
