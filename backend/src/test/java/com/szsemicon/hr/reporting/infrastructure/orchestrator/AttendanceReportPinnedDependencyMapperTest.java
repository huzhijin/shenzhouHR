package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaDocumentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ProjectedLeaveUsageRow;
import java.time.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

class AttendanceReportPinnedDependencyMapperTest {
    private static final String NS = AttendanceReportCalculationMapper.class.getName() + ".";

    @Test void readsExactPinnedOvertimeCategoryWithoutConvertingItToLeaveEnum() throws Exception {
        try (SqlSession session = session()) {
            insert(session, "paid", "OVERTIME", "PAID", "2026-09-29 16:30:00", "2026-09-30 02:00:00");
            insert(session, "comp", "OVERTIME", "COMPENSATORY", "2026-09-29 18:00:00", "2026-09-30 03:00:00");
            insert(session, "vol", "OVERTIME", "VOLUNTARY", "2026-09-29 18:00:00", "2026-09-30 03:00:00");
            execute(session, "INSERT INTO attendance_report_daily_fact VALUES ('p', 'e', DATE '2026-09-30', 120, 120, 0, 0)");
            List<OaDocumentRow> rows = rows(session);
            assertThat(rows).extracting(OaDocumentRow::overtimeType)
                    .containsExactlyInAnyOrder(OvertimeType.PAID, OvertimeType.COMPENSATORY, OvertimeType.VOLUNTARY);
            assertThat(rows).allMatch(row -> row.leaveType() == null);
        }
    }
    @Test void zeroContributionAndCrossYearGhostOvertimeDoNotBecomeDailyDependencies() throws Exception {
        try (SqlSession session = session()) {
            insert(session, "ghost", "OVERTIME", "PAID", "2025-09-01 00:00:00", "2026-10-01 00:00:00");
            insert(session, "zero", "OVERTIME", "PAID", "2026-09-29 16:30:00", "2026-09-30 02:00:00");
            execute(session, "INSERT INTO attendance_report_daily_fact VALUES ('p', 'e', DATE '2026-09-30', 0, 0, 0, 0)");
            assertThat(rows(session)).hasSize(2).allMatch(row -> !row.effectiveCandidate());
        }
    }
    @Test void unclassifiedGhostIsExcludedEvenWhenAnotherDocumentContributedOnItsStartDay() throws Exception {
        try (SqlSession session = session()) {
            insert(session, "ghost", "OVERTIME", "", "2026-09-29 16:30:00", "2027-09-30 02:00:00");
            insert(session, "valid", "OVERTIME", "PAID", "2026-09-29 17:00:00", "2026-09-30 02:00:00");
            execute(session, "INSERT INTO attendance_report_daily_fact VALUES ('p', 'e', DATE '2026-09-30', 120, 120, 0, 0)");
            assertThat(rows(session)).hasSize(2);
            assertThat(rows(session).stream().filter(OaDocumentRow::effectiveCandidate))
                    .extracting(OaDocumentRow::sourceBusinessKey).containsExactly("OVERTIME:valid");
        }
    }
    @Test void secondPartialUsesCopiedFactNotGlobalNewCutoffOrNewSourceVersion() throws Exception {
        try (SqlSession session = session()) {
            insert(session, "leave-old", "LEAVE", "PERSONAL", "2026-09-23 16:00:00", "2026-09-24 16:00:00");
            execute(session, "INSERT INTO oa_attendance_document VALUES ('leave-new', 'LEAVE:leave-old', TIMESTAMP '2026-09-01 00:00:00', 'serial', NULL, 'source')");
            // A previous partial advanced global cutoff and copied this old fact.
            execute(session, "UPDATE attendance_report_projection SET data_as_of=TIMESTAMP '2026-10-04 10:00:00', projection_version='pin-second'");
            List<OaDocumentRow> result = session.selectList(NS + "findProjectedOaDependencies",
                    Map.of("companyId", "company", "projectionVersion", "pin-second"));
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().startInstant()).isEqualTo(Instant.parse("2026-09-23T16:00:00Z"));
            assertThat(result.getFirst().sourceBusinessKey()).isEqualTo("LEAVE:leave-old");
        }
    }
    @Test void companyBoundaryIsEnforcedEvenWhenProjectionVersionIsKnown() throws Exception {
        try (SqlSession session = session()) {
            insert(session, "leave", "LEAVE", "PERSONAL", "2026-09-23 16:00:00", "2026-09-24 16:00:00");
            assertThat(session.selectList(NS + "findProjectedOaDependencies",
                    Map.of("companyId", "other", "projectionVersion", "pin"))).isEmpty();
        }
    }
    @Test void actualDayDependenciesComeFromImmutablePinnedFactNotCurrentSourceOrDailyTotals() throws Exception {
        try (SqlSession session = session()) {
            insert(session, "leave", "LEAVE", "PERSONAL", "2026-09-16 05:30:00", "2026-09-16 10:00:00");
            execute(session, "UPDATE attendance_report_oa_fact SET recognized_minutes=270,recognized_minutes_by_date_json='{\"2026-09-15\":270}'");
            List<ProjectedLeaveUsageRow> rows = session.selectList(NS + "findProjectedLeaveUsageDependencies",
                    Map.of("companyId", "company", "projectionVersion", "pin"));
            assertThat(rows).containsExactly(new ProjectedLeaveUsageRow("source", "LEAVE:leave", "e", 270, "{\"2026-09-15\":270}"));
            assertThat(session.selectList(NS + "findProjectedLeaveUsageDependencies",
                    Map.of("companyId", "other", "projectionVersion", "pin"))).isEmpty();
            assertThat(session.selectList(NS + "findProjectedLeaveUsageDependencies",
                    Map.of("companyId", "company", "projectionVersion", "different"))).isEmpty();
        }
    }
    @Test void legacyNullAndExplicitCancelledEmptyMapRemainDistinctInActualMyBatisMapping() throws Exception {
        try (SqlSession session = session()) {
            insert(session, "legacy", "LEAVE", "PERSONAL", "2026-09-16 05:30:00", "2026-09-16 10:00:00");
            insert(session, "cancelled", "LEAVE", "PERSONAL", "2026-09-16 05:30:00", "2026-09-16 10:00:00");
            execute(session, "UPDATE attendance_report_oa_fact SET recognized_minutes_by_date_json='{}' WHERE oa_attendance_document_id='cancelled'");
            List<ProjectedLeaveUsageRow> rows = session.selectList(NS + "findProjectedLeaveUsageDependencies",
                    Map.of("companyId", "company", "projectionVersion", "pin"));
            assertThat(rows).filteredOn(r -> r.sourceBusinessKey().equals("LEAVE:legacy")).singleElement()
                    .satisfies(r -> assertThat(r.recognizedMinutesByDateJson()).isNull());
            assertThat(rows).filteredOn(r -> r.sourceBusinessKey().equals("LEAVE:cancelled")).singleElement()
                    .satisfies(r -> assertThat(r.recognizedMinutesByDateJson()).isEqualTo("{}"));
        }
    }
    /**
     * Scoped pin read must equal the company-wide read filtered by employee,
     * with identical rows and ordering, across months and employees.
     */
    @Test void employeeScopedPinReadEqualsCompanyReadFilteredByEmployee() throws Exception {
        try (SqlSession session = session()) {
            execute(session, "INSERT INTO employee VALUES ('e2', 'TEST2'), ('e3', 'TEST3')");
            // e: dependencies in August and September (two months), plus overtime with a daily fact.
            insertFor(session, "e", "e-aug", "LEAVE", "PERSONAL", "2026-08-20 01:00:00", "2026-08-20 09:00:00");
            insertFor(session, "e", "e-sep", "LEAVE", "SICK", "2026-09-10 01:00:00", "2026-09-10 09:00:00");
            insertFor(session, "e", "e-ot", "OVERTIME", "PAID", "2026-09-29 16:30:00", "2026-09-30 02:00:00");
            execute(session, "INSERT INTO attendance_report_daily_fact VALUES ('p', 'e', DATE '2026-09-30', 120, 120, 0, 0)");
            // e2: cross-month interval (Aug 31 -> Sep 1) and a September overtime.
            insertFor(session, "e2", "e2-cross", "LEAVE", "PERSONAL", "2026-08-31 01:00:00", "2026-09-01 09:00:00");
            insertFor(session, "e2", "e2-ot", "OVERTIME", "PAID", "2026-09-29 16:30:00", "2026-09-30 02:00:00");
            // e3: never in scope; must be excluded by the SQL predicate.
            insertFor(session, "e3", "e3-sep", "LEAVE", "PERSONAL", "2026-09-05 01:00:00", "2026-09-05 09:00:00");
            // A different projection for e must never leak into pin 'p'.
            execute(session, "INSERT INTO attendance_report_projection VALUES ('p-old', 'company', 'pin-old', 'PUBLISHED', TIMESTAMP '2026-09-01 00:00:00')");
            execute(session, "INSERT INTO oa_attendance_document VALUES ('old', 'LEAVE:old', TIMESTAMP '2026-09-01 00:00:00', 'serial', NULL, 'source')");
            execute(session, "INSERT INTO attendance_report_oa_fact (attendance_report_projection_id,company_id,oa_attendance_document_id,employee_id,document_type,leave_type_code,interval_start,interval_end,point_instant) VALUES ('p-old', 'company', 'old', 'e', 'LEAVE', 'PERSONAL', TIMESTAMP '2026-08-01 01:00:00', TIMESTAMP '2026-08-01 09:00:00', NULL)");

            List<OaDocumentRow> company = rows(session);
            assertThat(company).extracting(OaDocumentRow::employeeId).contains("e", "e2", "e3");
            assertThat(company).extracting(OaDocumentRow::sourceBusinessKey).doesNotContain("LEAVE:old");

            Map<String, Object> nullScope = new HashMap<>();
            nullScope.put("companyId", "company");
            nullScope.put("projectionVersion", "pin");
            nullScope.put("employeeIds", null);
            assertThat(session.<OaDocumentRow>selectList(NS + "findProjectedOaDependencies", nullScope))
                    .containsExactlyElementsOf(company);

            for (Set<String> scope : List.of(Set.of("e"), Set.of("e2"), new LinkedHashSet<>(List.of("e", "e2")),
                    Set.of("e", "unknown"))) {
                List<OaDocumentRow> scoped = session.selectList(NS + "findProjectedOaDependencies",
                        Map.of("companyId", "company", "projectionVersion", "pin", "employeeIds", scope));
                assertThat(scoped).as("scope %s", scope)
                        .containsExactlyElementsOf(company.stream()
                                .filter(row -> scope.contains(row.employeeId())).toList());
            }
            // e keeps both months and the overtime's effective-candidate flag from the daily fact join.
            List<OaDocumentRow> onlyE = session.selectList(NS + "findProjectedOaDependencies",
                    Map.of("companyId", "company", "projectionVersion", "pin", "employeeIds", Set.of("e")));
            assertThat(onlyE).extracting(OaDocumentRow::sourceBusinessKey)
                    .containsExactlyInAnyOrder("LEAVE:e-aug", "LEAVE:e-sep", "OVERTIME:e-ot");
            assertThat(onlyE).filteredOn(r -> r.sourceBusinessKey().equals("OVERTIME:e-ot"))
                    .singleElement().satisfies(r -> assertThat(r.effectiveCandidate()).isTrue());

            assertThat(session.selectList(NS + "findProjectedOaDependencies",
                    Map.of("companyId", "company", "projectionVersion", "pin", "employeeIds", Set.of())))
                    .as("explicit empty collection at SQL level").isEmpty();
            assertThat(session.selectList(NS + "findProjectedOaDependencies",
                    Map.of("companyId", "other", "projectionVersion", "pin", "employeeIds", Set.of("e"))))
                    .as("company boundary still applies when scoped").isEmpty();
        }
    }
    private static void insertFor(SqlSession session, String employee, String id, String type, String code,
            String start, String end) throws Exception {
        execute(session, "INSERT INTO oa_attendance_document VALUES ('" + id + "', '" + type + ":" + id
                + "', TIMESTAMP '2026-09-01 00:00:00', 'serial', NULL, 'source')");
        execute(session, "INSERT INTO attendance_report_oa_fact (attendance_report_projection_id,company_id,oa_attendance_document_id,employee_id,document_type,leave_type_code,interval_start,interval_end,point_instant) VALUES ('p', 'company', '"
                + id + "', '" + employee + "', '" + type + "', '" + code + "', TIMESTAMP '" + start + "', TIMESTAMP '" + end + "', NULL)");
    }
    private static List<OaDocumentRow> rows(SqlSession session) {
        return session.selectList(NS + "findProjectedOaDependencies",
                Map.of("companyId", "company", "projectionVersion", "pin"));
    }
    private static void insert(SqlSession session, String id, String type, String code, String start, String end) throws Exception {
        execute(session, "INSERT INTO oa_attendance_document VALUES ('" + id + "', '" + type + ":" + id
                + "', TIMESTAMP '2026-09-01 00:00:00', 'serial', NULL, 'source')");
        execute(session, "INSERT INTO attendance_report_oa_fact (attendance_report_projection_id,company_id,oa_attendance_document_id,employee_id,document_type,leave_type_code,interval_start,interval_end,point_instant) VALUES ('p', 'company', '" + id + "', 'e', '" + type
                + "', '" + code + "', TIMESTAMP '" + start + "', TIMESTAMP '" + end + "', NULL)");
    }
    private static void execute(SqlSession session, String sql) throws Exception {
        try (var statement = session.getConnection().createStatement()) { statement.execute(sql); }
    }
    private static SqlSession session() throws Exception {
        Configuration configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:dependency-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")));
        String resource = "mappers/AttendanceReportCalculationMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        execute(session, "SET TIME ZONE 'UTC'");
        for (String ddl : List.of(
                "CREATE TABLE attendance_report_projection (attendance_report_projection_id VARCHAR(64), company_id VARCHAR(64), projection_version VARCHAR(128), status VARCHAR(32), data_as_of TIMESTAMP WITH TIME ZONE)",
                "CREATE TABLE attendance_report_oa_fact (attendance_report_projection_id VARCHAR(64), company_id VARCHAR(64), oa_attendance_document_id VARCHAR(64), employee_id VARCHAR(64), document_type VARCHAR(32), leave_type_code VARCHAR(32), interval_start TIMESTAMP WITH TIME ZONE, interval_end TIMESTAMP WITH TIME ZONE, point_instant TIMESTAMP WITH TIME ZONE, recognized_minutes BIGINT DEFAULT 0, recognized_minutes_by_date_json VARCHAR(4096), allocation_semantics VARCHAR(32))",
                "CREATE TABLE oa_attendance_document (oa_attendance_document_id VARCHAR(64), source_business_key VARCHAR(128), first_submitted_at TIMESTAMP WITH TIME ZONE, leave_serial VARCHAR(64), original_leave_serial VARCHAR(64), attendance_source_id VARCHAR(64))",
                "CREATE TABLE employee (employee_id VARCHAR(64), employee_number VARCHAR(64))",
                "CREATE TABLE attendance_report_daily_fact (attendance_report_projection_id VARCHAR(64), employee_id VARCHAR(64), business_date DATE, recognized_overtime_minutes BIGINT, paid_overtime_minutes BIGINT, compensatory_overtime_minutes BIGINT, voluntary_overtime_minutes BIGINT)",
                "INSERT INTO attendance_report_projection VALUES ('p', 'company', 'pin', 'PUBLISHED', TIMESTAMP '2026-10-03 04:10:00')",
                "INSERT INTO employee VALUES ('e', 'TEST')")) execute(session, ddl);
        return session;
    }
}
