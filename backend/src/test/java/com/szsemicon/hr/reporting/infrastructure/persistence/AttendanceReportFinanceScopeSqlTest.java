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

/** Real mapper regression: employee selection and calendar cells share the requested scope. */
class AttendanceReportFinanceScopeSqlTest {

    private static final String NS = AttendanceReportQueryPageMapper.class.getName() + ".";

    @Test
    void selectedOccurrenceDayDoesNotLeakTheOtherSevenRecognizedDays() throws Exception {
        try (SqlSession session = session()) {
            int[] dates = {17, 19, 20, 25, 26, 27, 29, 30};
            long[] minutes = {150, 540, 180, 510, 540, 510, 330, 30};
            for (int i = 0; i < dates.length; i++) {
                fact(session, "projection", "company", "he", "department", "2026-09-" + dates[i],
                        minutes[i], 0, 0);
            }
            Map<String, Object> query = query();
            query.put("occurrenceDay", 25);

            assertThat((Long) session.selectOne(NS + "countFinanceOvertimePeople", query)).isEqualTo(1);
            assertThat(people(session, query)).extracting(QueryPageRows.DirectoryEmployeeRow::employeeNumber)
                    .containsExactly("SZST0716");
            List<QueryPageRows.FinanceOvertimeCellRow> rows = cells(session, query);
            assertThat(rows).extracting(QueryPageRows.FinanceOvertimeCellRow::businessDate)
                    .containsExactly(LocalDate.of(2026, 9, 25));
            assertThat(rows.getFirst().paidOvertimeMinutes()).isEqualTo(510);
        }
    }

    @Test
    void occurrenceDayAppliesToEverySelectedMonthInsteadOfJustTheFirstMonth() throws Exception {
        try (SqlSession session = session()) {
            fact(session, "august", "company", "he", "department", "2026-08-25", 60, 0, 0);
            fact(session, "august", "company", "he", "department", "2026-08-26", 120, 0, 0);
            fact(session, "projection", "company", "he", "department", "2026-09-25", 510, 0, 0);
            fact(session, "projection", "company", "he", "department", "2026-09-26", 540, 0, 0);
            Map<String, Object> query = query();
            query.put("fromDate", LocalDate.of(2026, 8, 1));
            query.put("projectionIds", List.of("august", "projection"));
            query.put("occurrenceDay", 25);

            assertThat(cells(session, query)).extracting(QueryPageRows.FinanceOvertimeCellRow::businessDate)
                    .containsExactly(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 25));
        }
    }

    @Test
    void oneEmployeesFactsOutsideTheSelectedDepartmentDoNotLeakIntoCells() throws Exception {
        try (SqlSession session = session()) {
            fact(session, "projection", "company", "he", "department", "2026-09-17", 150, 0, 0);
            fact(session, "projection", "company", "he", "other-department", "2026-09-18", 360, 0, 0);
            Map<String, Object> query = query();
            query.put("organizationIds", List.of("department"));

            assertThat(people(session, query)).hasSize(1);
            assertThat(cells(session, query)).extracting(QueryPageRows.FinanceOvertimeCellRow::businessDate)
                    .containsExactly(LocalDate.of(2026, 9, 17));
        }
    }

    @Test
    void companyProjectionPageEmployeesAndHalfOpenDatesAllRestrictCells() throws Exception {
        try (SqlSession session = session()) {
            fact(session, "projection", "company", "he", "department", "2026-09-17", 150, 0, 0);
            fact(session, "projection", "other-company", "he", "department", "2026-09-18", 240, 0, 0);
            fact(session, "old-projection", "company", "he", "department", "2026-09-19", 360, 0, 0);
            fact(session, "projection", "company", "other", "department", "2026-09-20", 480, 0, 0);
            fact(session, "projection", "company", "he", "department", "2026-08-31", 600, 0, 0);
            fact(session, "projection", "company", "he", "department", "2026-10-01", 720, 0, 0);

            List<QueryPageRows.FinanceOvertimeCellRow> rows = cells(session, query());
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().employeeId()).isEqualTo("he");
            assertThat(rows.getFirst().businessDate()).isEqualTo(LocalDate.of(2026, 9, 17));
            assertThat(rows.getFirst().paidOvertimeMinutes()).isEqualTo(150);
        }
    }

    @Test
    void exactEmployeeNumberAndEmployeeIdCannotBeBypassedByThePageIdList() throws Exception {
        try (SqlSession session = session()) {
            fact(session, "projection", "company", "he", "department", "2026-09-17", 150, 0, 0);
            fact(session, "projection", "company", "other", "department", "2026-09-18", 360, 0, 0);
            Map<String, Object> query = query();
            query.put("employeeIds", List.of("he", "other"));
            query.put("employeeId", "he");
            query.put("employeeNumber", "OTHER001");
            assertThat(people(session, query)).isEmpty();
            assertThat(cells(session, query)).isEmpty();

            query.put("employeeNumber", "SZST0716");
            assertThat(cells(session, query)).extracting(QueryPageRows.FinanceOvertimeCellRow::employeeId)
                    .containsExactly("he");
        }
    }

    @Test
    void treatmentSelectionPreservesAllThreeRawMinuteColumnsForTheSelectedPerson() throws Exception {
        try (SqlSession session = session()) {
            fact(session, "projection", "company", "he", "department", "2026-09-17", 60, 90, 30);
            Map<String, Object> query = query();
            query.put("overtimeTreatment", "加班费");
            assertThat(people(session, query)).hasSize(1);
            List<QueryPageRows.FinanceOvertimeCellRow> rows = cells(session, query);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().recognizedOvertimeMinutes()).isEqualTo(180);
            assertThat(rows.getFirst().paidOvertimeMinutes()).isEqualTo(60);
            assertThat(rows.getFirst().compensatoryOvertimeMinutes()).isEqualTo(90);
            assertThat(rows.getFirst().voluntaryOvertimeMinutes()).isEqualTo(30);
        }
    }

    private static List<QueryPageRows.FinanceOvertimeCellRow> cells(
            SqlSession session, Map<String, Object> query) {
        return session.selectList(NS + "listFinanceOvertimeCells", query);
    }

    private static List<QueryPageRows.DirectoryEmployeeRow> people(
            SqlSession session, Map<String, Object> query) {
        return session.selectList(NS + "listFinanceOvertimePeople", query);
    }

    private static Map<String, Object> query() {
        Map<String, Object> query = new HashMap<>();
        query.put("projectionIds", List.of("projection"));
        query.put("companyId", "company");
        query.put("fromDate", LocalDate.of(2026, 9, 1));
        query.put("toDateExclusive", LocalDate.of(2026, 10, 1));
        query.put("employeeIds", List.of("he"));
        query.put("offset", 0);
        query.put("limit", 200);
        return query;
    }

    private static void fact(SqlSession session, String projection, String company,
            String employee, String organization, String date, long paid, long comp, long voluntary)
            throws Exception {
        try (var sql = session.getConnection().prepareStatement("""
                INSERT INTO attendance_report_daily_fact VALUES
                (?, ?, ?, ?, ?, ?, ?, 'WORKDAY', ?, ?, ?, ?)
                """)) {
            sql.setString(1, projection);
            sql.setString(2, company);
            sql.setString(3, employee);
            sql.setString(4, employee + "-version");
            sql.setString(5, organization);
            sql.setString(6, organization + "-version");
            sql.setObject(7, LocalDate.parse(date));
            sql.setLong(8, paid + comp + voluntary);
            sql.setLong(9, paid);
            sql.setLong(10, comp);
            sql.setLong(11, voluntary);
            sql.executeUpdate();
        }
    }

    private static SqlSession session() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("finance-scope", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver",
                        "jdbc:h2:mem:finance-scope-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")));
        String resource = "mappers/AttendanceReportQueryPageMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        try (var sql = session.getConnection().createStatement()) {
            for (String ddl : List.of(
                    "CREATE TABLE employee_version (employee_version_id VARCHAR(64) PRIMARY KEY, employee_number VARCHAR(64), display_name VARCHAR(64))",
                    "CREATE TABLE organization_version (organization_version_id VARCHAR(64) PRIMARY KEY, name VARCHAR(64))",
                    """
                    CREATE TABLE attendance_report_daily_fact (
                        attendance_report_projection_id VARCHAR(64), company_id VARCHAR(64),
                        employee_id VARCHAR(64), employee_version_id VARCHAR(64), organization_id VARCHAR(64),
                        organization_version_id VARCHAR(64), business_date DATE, day_type VARCHAR(32),
                        recognized_overtime_minutes BIGINT, paid_overtime_minutes BIGINT,
                        compensatory_overtime_minutes BIGINT, voluntary_overtime_minutes BIGINT)
                    """,
                    "INSERT INTO employee_version VALUES ('he-version', 'SZST0716', '何伟豪'), ('other-version', 'OTHER001', 'Other')",
                    "INSERT INTO organization_version VALUES ('department-version', '现场服务部'), ('other-department-version', 'Other department')")) {
                sql.execute(ddl);
            }
        }
        return session;
    }
}
