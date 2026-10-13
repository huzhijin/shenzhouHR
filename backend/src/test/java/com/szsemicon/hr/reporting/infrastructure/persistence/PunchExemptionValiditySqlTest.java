package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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

/** Executes both current-status and dashboard mappers, without substituting their predicates. */
class PunchExemptionValiditySqlTest {
    private static final String STATUS = "com.szsemicon.hr.employee.infrastructure.persistence.PunchExemptionMapper.";
    private static final String DASH = "com.szsemicon.hr.reporting.infrastructure.persistence.AttendanceDashboardWorkbenchMapper.listPunchExemptEmployeeIds";

    @ParameterizedTest
    @ValueSource(strings = {"EXECUTIVE", "STANDING_LIST"})
    void currentStatusRecognizesFiniteActiveAndOpenEndedButNotFutureOrExpired(String kind) throws Exception {
        try (SqlSession session = session()) {
            for (String id : List.of("finite", "open", "future", "expired", "gap")) employee(session, id, "company", id);
            relativeAssignment(session, kind, "finite", -1, 1);
            relativeAssignment(session, kind, "open", -1, null);
            relativeAssignment(session, kind, "future", 1, null);
            relativeAssignment(session, kind, "expired", -2, -1);
            relativeAssignment(session, kind, "gap", -4, -1);
            relativeAssignment(session, kind, "gap", 1, 4);
            String statement = STATUS + (kind.equals("EXECUTIVE") ? "hasExecutiveRole" : "hasStandingExemption");
            assertThat((Boolean) session.selectOne(statement, Map.of("employeeId", "finite"))).isTrue();
            assertThat((Boolean) session.selectOne(statement, Map.of("employeeId", "open"))).isTrue();
            for (String id : List.of("future", "expired", "gap")) {
                assertThat((Boolean) session.selectOne(statement, Map.of("employeeId", id))).isFalse();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXECUTIVE", "STANDING_LIST"})
    void dashboardUsesInclusiveStartExclusiveEndAndDoesNotBridgeSeparateRanges(String kind) throws Exception {
        try (SqlSession session = session()) {
            employee(session, "target", "company", "SAME");
            employee(session, "holder", "other-company", "SAME");
            absoluteAssignment(session, kind, "holder", "2026-08-31 16:00:00", "2026-09-10 16:00:00");
            absoluteAssignment(session, kind, "holder", "2026-09-13 16:00:00", "2026-09-30 16:00:00");
            assertDashboard(session, "2026-08-31T15:59:59.999999");
            assertDashboard(session, "2026-08-31T16:00:00", "target");
            assertDashboard(session, "2026-09-10T15:59:59.999999", "target");
            assertDashboard(session, "2026-09-10T16:00:00");
            assertDashboard(session, "2026-09-13T15:59:59.999999");
            assertDashboard(session, "2026-09-13T16:00:00", "target");
            assertDashboard(session, "2026-09-30T16:00:00");
        }
    }

    @Test
    void dashboardUnionsDuplicateAssignmentsAndLeavesJanuaryStandingRosterUnchanged() throws Exception {
        try (SqlSession session = session()) {
            employee(session, "target", "company", "E001");
            employee(session, "other", "company", "E002");
            for (int i = 0; i < 2; i++) {
                absoluteAssignment(session, "EXECUTIVE", "target", "2025-12-31 16:00:00", null);
                absoluteAssignment(session, "STANDING_LIST", "target", "2025-12-31 16:00:00", null);
            }
            assertDashboard(session, "2026-09-16T16:00:00", "target");
        }
    }

    private static void assertDashboard(SqlSession session, String instant, String... expected) {
        List<String> ids = session.selectList(DASH, Map.of("companyId", "company", "asOf", LocalDateTime.parse(instant)));
        assertThat(ids).containsExactlyInAnyOrder(expected);
    }

    private static void employee(SqlSession session, String id, String company, String number) throws Exception {
        execute(session, "INSERT INTO employee VALUES ('" + id + "','" + company + "','" + number + "')");
        execute(session, "INSERT INTO auth_principal VALUES ('p-" + id + "','" + id + "','INACTIVE')");
    }

    private static void absoluteAssignment(SqlSession session, String kind, String employee, String start, String end) throws Exception {
        assignment(session, kind, employee, "TIMESTAMP '" + start + "'", end == null ? "NULL" : "TIMESTAMP '" + end + "'");
    }

    private static void relativeAssignment(SqlSession session, String kind, String employee, int startDays, Integer endDays) throws Exception {
        assignment(session, kind, employee, "TIMESTAMPADD(DAY," + startDays + ",CURRENT_TIMESTAMP)",
                endDays == null ? "NULL" : "TIMESTAMPADD(DAY," + endDays + ",CURRENT_TIMESTAMP)");
    }

    private static void assignment(SqlSession session, String kind, String employee, String start, String end) throws Exception {
        String id = UUID.randomUUID().toString();
        if (kind.equals("EXECUTIVE")) {
            execute(session, "INSERT INTO auth_principal_role_assignment VALUES ('" + id + "','p-" + employee + "','executive'," + start + "," + end + ")");
        } else {
            execute(session, "INSERT INTO punch_exemption_assignment VALUES ('" + id + "','" + employee + "','IGNORED-STORED-NUMBER','STANDING_LIST'," + start + "," + end + ")");
        }
    }

    private static void execute(SqlSession session, String sql) throws Exception {
        try (var statement = session.getConnection().createStatement()) { statement.execute(sql); }
    }

    private static SqlSession session() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("exemption-validity", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:validity-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")));
        for (String resource : List.of("mappers/PunchExemptionMapper.xml", "mappers/AttendanceDashboardWorkbenchMapper.xml")) {
            try (var input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        for (String ddl : List.of(
                "SET TIME ZONE 'UTC'",
                "CREATE TABLE employee (employee_id VARCHAR(64) PRIMARY KEY, company_id VARCHAR(64), employee_number VARCHAR(64))",
                "CREATE TABLE auth_principal (principal_id VARCHAR(64) PRIMARY KEY, employee_id VARCHAR(64), status VARCHAR(32))",
                "CREATE TABLE auth_role (role_id VARCHAR(64) PRIMARY KEY, role_code VARCHAR(32))",
                "CREATE TABLE auth_principal_role_assignment (assignment_id VARCHAR(64), principal_id VARCHAR(64), role_id VARCHAR(64), valid_from TIMESTAMP, valid_to TIMESTAMP)",
                "CREATE TABLE punch_exemption_assignment (exemption_id VARCHAR(64), employee_id VARCHAR(64), listed_employee_number VARCHAR(64), source VARCHAR(32), valid_from TIMESTAMP, valid_to TIMESTAMP)",
                "INSERT INTO auth_role VALUES ('executive','EXECUTIVE')")) execute(session, ddl);
        return session;
    }
}
