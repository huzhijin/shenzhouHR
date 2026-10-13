package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.QueryPageRows;
import static org.assertj.core.api.Assertions.assertThat;

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

/** Executes production SQL using UTC assignment instants and Shanghai business dates. */
class AttendanceReportDailyJournalExemptionSqlTest {
    private static final String NS = AttendanceReportQueryPageMapper.class.getName() + ".";

    @Test
    void openEndedAssignmentWinsOverExpiredDuplicateRolesAndStandingEntries() throws Exception {
        try (SqlSession session = session()) {
            employee(session, "exempt", "company", "E001", "E001");
            employee(session, "ordinary", "company", "E002", "E002");
            role(session, "exempt", "EXECUTIVE", "2026-09-01 00:00:00");
            role(session, "exempt", "EXECUTIVE", null);
            standing(session, "exempt", "E001", "2026-09-16 00:00:00");
            standing(session, "exempt", "E001", null);
            fact(session, "exempt", "2026-09-17");
            fact(session, "ordinary", "2026-09-17");

            assertRows(session, query(), "ordinary:2026-09-17");
        }
    }

    @Test
    void maximumFiniteExpiryAcrossBothListsKeepsEveryDayBoundary() throws Exception {
        try (SqlSession session = session()) {
            employee(session, "employee", "company", "E001", "E001");
            role(session, "employee", "EXECUTIVE", "2026-09-16 16:00:00");
            standing(session, "employee", "E001", "2026-09-18 16:00:00");
            for (int day : List.of(17, 18, 19, 20)) fact(session, "employee", "2026-09-" + day);

            assertRows(session, query(), "employee:2026-09-19", "employee:2026-09-20");
        }
    }

    @Test
    void midnightExpiryIsExclusiveButMiddayExpiryStillCoversThatBusinessDate() throws Exception {
        try (SqlSession session = session()) {
            employee(session, "midnight", "company", "E001", "E001");
            employee(session, "midday", "company", "E002", "E002");
            role(session, "midnight", "EXECUTIVE", "2026-09-16 16:00:00");
            role(session, "midday", "EXECUTIVE", "2026-09-17 12:00:00");
            fact(session, "midnight", "2026-09-17");
            fact(session, "midday", "2026-09-17");

            assertRows(session, query(), "midnight:2026-09-17");
        }
    }

    @Test
    void futureStartDoesNotHideEarlierDaysAndCurrentNumberCrossCompanyRuleStillApplies()
            throws Exception {
        try (SqlSession session = session()) {
            employee(session, "target", "company", "CURRENT001", "HISTORICAL001");
            employee(session, "foreign-role", "other-company", "CURRENT001", "OTHER-HISTORY");
            // Principal status is deliberately INACTIVE; existing number matching is unchanged.
            role(session, "foreign-role", "EXECUTIVE", "2026-09-17 16:00:00", null);
            fact(session, "target", "2026-09-17");
            fact(session, "target", "2026-09-18");

            assertRows(session, query(), "target:2026-09-17");
        }
    }

    @Test
    void standingListMatchesCurrentEmployeeNumberNotItsStoredListedNumber() throws Exception {
        try (SqlSession session = session()) {
            employee(session, "target", "company", "CURRENT001", "HISTORICAL001");
            employee(session, "foreign-listed", "other-company", "CURRENT001", "OTHER-HISTORY");
            standing(session, "foreign-listed", "OUTDATED-LISTED-NUMBER", null);
            fact(session, "target", "2026-09-17");

            assertRows(session, query());
        }
    }

    @Test
    void matchingOnlyHistoricalOrListedNumbersAndOtherRolesDoesNotExcludeAnEmployee()
            throws Exception {
        try (SqlSession session = session()) {
            employee(session, "target", "company", "CURRENT001", "HISTORICAL001");
            employee(session, "foreign-role", "other-company", "HISTORICAL001", "CURRENT001");
            employee(session, "foreign-listed", "other-company", "UNRELATED", "CURRENT001");
            role(session, "foreign-role", "EXECUTIVE", null);
            standing(session, "foreign-listed", "CURRENT001", null);
            role(session, "target", "ORDINARY", null);
            fact(session, "target", "2026-09-17");

            assertRows(session, query(), "target:2026-09-17");
        }
    }

    @Test
    void scopeHalfOpenDatesAndPaginationRemainIntactAfterMaterializingTheLists() throws Exception {
        try (SqlSession session = session()) {
            employee(session, "a", "company", "E001", "E001");
            employee(session, "b", "company", "E002", "E002");
            employee(session, "c", "company", "E003", "E003");
            employee(session, "foreign", "other-company", "E004", "E004");
            fact(session, "a", "2026-09-17");
            fact(session, "a", "2026-09-18");
            fact(session, "b", "2026-09-17");
            fact(session, "c", "2026-09-17");
            fact(session, "foreign", "2026-09-17", "projection", "other-company", "department");
            fact(session, "a", "2026-09-19", "old-projection", "company", "department");
            fact(session, "a", "2026-09-20", "projection", "company", "other-department");
            fact(session, "a", "2026-08-31");
            fact(session, "a", "2026-10-01");
            Map<String, Object> query = query();
            query.put("employeeIds", List.of("a", "b"));
            query.put("organizationIds", List.of("department"));
            assertRows(session, query, "a:2026-09-17", "a:2026-09-18", "b:2026-09-17");

            query.put("offset", 1);
            query.put("limit", 1);
            assertThat(count(session, query)).isEqualTo(3);
            assertThat(keys(session, query)).containsExactly("a:2026-09-18");

            query.put("offset", 0);
            query.put("employeeId", "b");
            query.put("employeeNumber", "E001");
            assertRows(session, query);
            query.put("employeeNumber", "E002");
            assertRows(session, query, "b:2026-09-17");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"countDailyJournal", "listDailyJournal"})
    void bothQueriesMaterializeDistinctEmployeeDaysWithoutCollapsingDisjointIntervals(String statement)
            throws Exception {
        try (SqlSession session = session()) {
            String sql = session.getConfiguration().getMappedStatement(NS + statement)
                    .getBoundSql(query()).getSql().replaceAll("\\s+", " ");
            assertThat(sql).contains("UNION ALL", "journal_dates AS", "SELECT DISTINCT fact.business_date",
                            "SELECT DISTINCT roster.employee_id, journal_day.business_date",
                            "LEFT JOIN exempt_employee_days exemption",
                            "exemption.business_date = fact.business_date",
                            "exemption.valid_from <= TIMESTAMPADD(HOUR, -8, CAST(journal_day.business_date AS DATETIME))",
                            "exemption.valid_to > TIMESTAMPADD(HOUR, -8, CAST(journal_day.business_date AS DATETIME))",
                            "exemption.employee_id IS NULL")
                    .doesNotContain("NOT EXISTS", "MIN(exemption.valid_from)", "MAX(exemption.valid_to)");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXECUTIVE", "STANDING_LIST"})
    void septemberOnlyAssignmentNeverHidesAugustOrOctober(String kind) throws Exception {
        try (SqlSession session = session()) {
            employee(session, "target", "company", "E001", "E001");
            assignment(session, kind, "target", "2026-08-31 16:00:00", "2026-09-30 16:00:00");
            for (String day : List.of("2026-08-31", "2026-09-01", "2026-09-30", "2026-10-01")) {
                fact(session, "target", day);
            }
            Map<String, Object> params = query();
            params.put("fromDate", LocalDate.of(2026, 8, 31));
            params.put("toDateExclusive", LocalDate.of(2026, 10, 2));
            assertRows(session, params, "target:2026-08-31", "target:2026-10-01");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXECUTIVE", "STANDING_LIST"})
    void employeeHiredSeptember14KeepsEarlierDaysVisible(String kind) throws Exception {
        try (SqlSession session = session()) {
            employee(session, "target", "company", "SZST0748", "SZST0748");
            assignment(session, kind, "target", "2026-09-13 16:00:00", "2026-09-30 16:00:00");
            for (String day : List.of("2026-09-01", "2026-09-13", "2026-09-14", "2026-09-30")) {
                fact(session, "target", day);
            }
            assertRows(session, query(), "target:2026-09-01", "target:2026-09-13");
        }
    }

    @Test
    void twoDisjointRangesAcrossBothListsDoNotFillTheGap() throws Exception {
        try (SqlSession session = session()) {
            employee(session, "target", "company", "E001", "E001");
            role(session, "target", "EXECUTIVE", "2026-09-09 16:00:00", "2026-09-11 16:00:00");
            standing(session, "target", "E001", "2026-09-13 16:00:00", "2026-09-15 16:00:00");
            for (int day = 10; day <= 16; day++) fact(session, "target", "2026-09-" + day);
            assertRows(session, query(), "target:2026-09-12", "target:2026-09-13", "target:2026-09-16");
        }
    }

    @Test
    void duplicateOpenEndedAndFiniteAssignmentsDoNotMultiplyDailyRows() throws Exception {
        try (SqlSession session = session()) {
            employee(session, "target", "company", "E001", "E001");
            employee(session, "ordinary", "company", "E002", "E002");
            for (int i = 0; i < 3; i++) {
                role(session, "target", "EXECUTIVE", "2026-09-15 16:00:00", null);
                standing(session, "target", "E001", "2026-09-16 16:00:00", "2026-09-18 16:00:00");
            }
            for (String day : List.of("2026-09-15", "2026-09-16", "2026-09-17")) {
                fact(session, "target", day);
                fact(session, "ordinary", day);
            }
            assertRows(session, query(), "target:2026-09-15", "ordinary:2026-09-15",
                    "ordinary:2026-09-16", "ordinary:2026-09-17");
        }
    }

    @Test
    void startOneMicrosecondAfterShanghaiMidnightDoesNotCoverThatBusinessDate() throws Exception {
        try (SqlSession session = session()) {
            employee(session, "target", "company", "E001", "E001");
            role(session, "target", "EXECUTIVE", "2026-09-16 16:00:00.000001", "2026-09-18 16:00:00");
            for (int day : List.of(17, 18, 19)) fact(session, "target", "2026-09-" + day);
            assertRows(session, query(), "target:2026-09-17", "target:2026-09-19");
        }
    }

    @Test
    void januaryStandingAssignmentKeepsItsExistingSeptemberExclusion() throws Exception {
        try (SqlSession session = session()) {
            employee(session, "target", "company", "E001", "E001");
            standing(session, "target", "E001", "2025-12-31 16:00:00", null);
            for (int day = 1; day <= 30; day++) fact(session, "target", String.format("2026-09-%02d", day));
            assertRows(session, query());
        }
    }

    private static void assignment(SqlSession session, String kind, String employee, String start, String end)
            throws Exception {
        if ("EXECUTIVE".equals(kind)) role(session, employee, kind, start, end);
        else standing(session, employee, "E001", start, end);
    }

    private static void role(SqlSession session, String employee, String role, String start, String end)
            throws Exception {
        insert(session, "INSERT INTO auth_principal_role_assignment VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), employee + "-principal", role, start, end);
    }

    private static void standing(SqlSession session, String employee, String listedNumber, String start, String end)
            throws Exception {
        insert(session, "INSERT INTO punch_exemption_assignment VALUES (?, ?, ?, 'STANDING_LIST', ?, ?)",
                UUID.randomUUID().toString(), employee, listedNumber, start, end);
    }

    private static long count(SqlSession session, Map<String, Object> query) {
        return session.selectOne(NS + "countDailyJournal", query);
    }

    private static List<String> keys(SqlSession session, Map<String, Object> query) {
        List<QueryPageRows.DailyJournalRow> rows = session.selectList(NS + "listDailyJournal", query);
        return rows.stream().map(row -> row.employeeId() + ":" + row.businessDate()).toList();
    }

    private static void assertRows(SqlSession session, Map<String, Object> query, String... expected) {
        assertThat(count(session, query)).isEqualTo(expected.length);
        assertThat(keys(session, query)).containsExactly(expected);
    }

    private static Map<String, Object> query() {
        Map<String, Object> result = new HashMap<>();
        result.put("projectionIds", List.of("projection"));
        result.put("companyId", "company");
        result.put("fromDate", LocalDate.of(2026, 9, 1));
        result.put("toDateExclusive", LocalDate.of(2026, 10, 1));
        result.put("offset", 0);
        result.put("limit", 100);
        return result;
    }

    private static void employee(SqlSession session, String id, String company,
            String currentNumber, String historicalNumber) throws Exception {
        insert(session, "INSERT INTO employee VALUES (?, ?, ?)", id, company, currentNumber);
        insert(session, "INSERT INTO employee_version VALUES (?, ?, ?)", id + "-version", historicalNumber, id);
        insert(session, "INSERT INTO auth_principal VALUES (?, ?, 'INACTIVE')", id + "-principal", id);
    }

    private static void role(SqlSession session, String employee, String role, String end) throws Exception {
        insert(session, "INSERT INTO auth_principal_role_assignment VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), employee + "-principal", role,
                "2026-01-01 00:00:00", end);
    }

    private static void standing(SqlSession session, String employee, String listedNumber, String end)
            throws Exception {
        insert(session, "INSERT INTO punch_exemption_assignment VALUES (?, ?, ?, 'STANDING_LIST', ?, ?)",
                UUID.randomUUID().toString(), employee, listedNumber,
                "2026-01-01 00:00:00", end);
    }

    private static void fact(SqlSession session, String employee, String date) throws Exception {
        fact(session, employee, date, "projection", "company", "department");
    }

    private static void fact(SqlSession session, String employee, String date, String projection,
            String company, String department) throws Exception {
        insert(session, """
                INSERT INTO attendance_report_daily_fact (
                    attendance_report_projection_id, company_id, employee_id, employee_version_id,
                    organization_id, organization_version_id, business_date)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, projection, company, employee, employee + "-version", department,
                department + "-version", date);
    }

    private static void insert(SqlSession session, String query, String... values) throws Exception {
        try (var sql = session.getConnection().prepareStatement(query)) {
            for (int i = 0; i < values.length; i++) sql.setString(i + 1, values[i]);
            sql.executeUpdate();
        }
    }

    private static SqlSession session() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("journal-exemption", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver",
                        "jdbc:h2:mem:journal-exemption-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")));
        String resource = "mappers/AttendanceReportQueryPageMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        try (var sql = session.getConnection().createStatement()) {
            for (String ddl : List.of(
                    "SET TIME ZONE 'UTC'",
                    "CREATE TABLE employee (employee_id VARCHAR(64) PRIMARY KEY, company_id VARCHAR(64), employee_number VARCHAR(64))",
                    "CREATE TABLE employee_version (employee_version_id VARCHAR(64) PRIMARY KEY, employee_number VARCHAR(64), display_name VARCHAR(64))",
                    "CREATE TABLE organization_version (organization_version_id VARCHAR(64) PRIMARY KEY, name VARCHAR(64))",
                    "CREATE TABLE auth_principal (principal_id VARCHAR(64) PRIMARY KEY, employee_id VARCHAR(64), status VARCHAR(32))",
                    "CREATE TABLE auth_role (role_id VARCHAR(64) PRIMARY KEY, role_code VARCHAR(32))",
                    "CREATE TABLE auth_principal_role_assignment (assignment_id VARCHAR(64) PRIMARY KEY, principal_id VARCHAR(64), role_id VARCHAR(64), valid_from TIMESTAMP, valid_to TIMESTAMP CHECK (valid_to IS NULL OR valid_to > valid_from))",
                    "CREATE TABLE punch_exemption_assignment (exemption_id VARCHAR(64) PRIMARY KEY, employee_id VARCHAR(64), listed_employee_number VARCHAR(64), source VARCHAR(32), valid_from TIMESTAMP, valid_to TIMESTAMP CHECK (valid_to IS NULL OR valid_to > valid_from))",
                    """
                    CREATE TABLE attendance_report_daily_fact (
                        attendance_report_projection_id VARCHAR(64), company_id VARCHAR(64),
                        employee_id VARCHAR(64), employee_version_id VARCHAR(64), organization_id VARCHAR(64),
                        organization_version_id VARCHAR(64), business_date DATE,
                        day_type VARCHAR(32) DEFAULT 'WORKDAY', shift_label VARCHAR(32) DEFAULT '白班',
                        leave_type VARCHAR(32), first_punch_at TIMESTAMP, last_punch_at TIMESTAMP,
                        late_minutes BIGINT DEFAULT 0, early_departure_minutes BIGINT DEFAULT 0,
                        absence_minutes BIGINT DEFAULT 0, leave_or_time_off_minutes BIGINT DEFAULT 0,
                        recognized_overtime_minutes BIGINT DEFAULT 60, scheduled_minutes BIGINT DEFAULT 480,
                        missing_punch_count INT DEFAULT 0, paid_overtime_minutes BIGINT DEFAULT 60,
                        compensatory_overtime_minutes BIGINT DEFAULT 0, voluntary_overtime_minutes BIGINT DEFAULT 0)
                    """,
                    "INSERT INTO auth_role VALUES ('EXECUTIVE', 'EXECUTIVE'), ('ORDINARY', 'ORDINARY')",
                    "INSERT INTO organization_version VALUES ('department-version', 'Department'), ('other-department-version', 'Other')")) {
                sql.execute(ddl);
            }
        }
        return session;
    }
}
