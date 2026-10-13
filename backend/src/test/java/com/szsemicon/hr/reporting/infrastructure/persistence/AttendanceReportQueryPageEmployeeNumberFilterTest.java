package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.QueryPageRows;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceReportQueryPageEmployeeNumberFilterTest {

    private static final String NAMESPACE = AttendanceReportQueryPageMapper.class.getName() + ".";

    @Test
    void listEmployeeDailyAggregatesFiltersCorrectEmployeeByNumberWithHistoricalDepartmentChange()
            throws Exception {
        try (SqlSession session = session()) {
            seedEmployeesAndOrganizations(session);
            seedDailyFacts(session);

            Map<String, Object> params = new HashMap<>();
            params.put("projectionIds", List.of("proj1"));
            params.put("companyId", "company1");
            params.put("fromDate", LocalDate.of(2026, 9, 1));
            params.put("toDateExclusive", LocalDate.of(2026, 9, 30));
            params.put("employeeNumber", "EMP002");
            params.put("employeeIds", List.of("emp1", "emp2", "emp3"));
            params.put("organizationIds", List.of("org1", "org2", "org3"));
            params.put("offset", 0);
            params.put("limit", 100);

            List<QueryPageRows.EmployeeDailyAggregateRow> rows = session.selectList(
                    NAMESPACE + "listEmployeeDailyAggregates", params);

            assertThat(rows).hasSize(1);
            QueryPageRows.EmployeeDailyAggregateRow row = rows.getFirst();
            assertThat(row.employeeNumber()).isEqualTo("EMP002");
            assertThat(row.employeeId()).isEqualTo("emp2");
            assertThat(row.organizationName()).isEqualTo("Org Two");
            assertThat(row.scheduledMinutes()).isEqualTo(960);
            assertThat(row.actualAttendanceDays()).isEqualByComparingTo("2.00");

            Long count = session.selectOne(NAMESPACE + "countEmployeeDailyAggregates", params);
            assertThat(count).isEqualTo(1L);
        }
    }

    @Test
    void listMatrixEmployeesFiltersCorrectEmployeeByNumberExcludingUnauthorizedOrganization()
            throws Exception {
        try (SqlSession session = session()) {
            seedEmployeesAndOrganizations(session);
            seedDailyFacts(session);

            Map<String, Object> params = new HashMap<>();
            params.put("projectionIds", List.of("proj1"));
            params.put("companyId", "company1");
            params.put("fromDate", LocalDate.of(2026, 9, 1));
            params.put("toDateExclusive", LocalDate.of(2026, 9, 30));
            params.put("employeeNumber", "EMP002");
            params.put("employeeIds", List.of("emp1", "emp2"));
            params.put("organizationIds", List.of("org1", "org2"));
            params.put("offset", 0);
            params.put("limit", 100);

            List<QueryPageRows.DirectoryEmployeeRow> rows = session.selectList(
                    NAMESPACE + "listMatrixEmployees", params);

            assertThat(rows).hasSize(1);
            QueryPageRows.DirectoryEmployeeRow row = rows.getFirst();
            assertThat(row.employeeNumber()).isEqualTo("EMP002");
            assertThat(row.employeeId()).isEqualTo("emp2");
            assertThat(row.organizationName()).isEqualTo("Org Two");

            Long count = session.selectOne(NAMESPACE + "countMatrixEmployees", params);
            assertThat(count).isEqualTo(1L);
        }
    }

    @Test
    void listEmployeeDailyAggregatesReturnsEmptyForNonExistentEmployeeNumber() throws Exception {
        try (SqlSession session = session()) {
            seedEmployeesAndOrganizations(session);
            seedDailyFacts(session);

            Map<String, Object> params = new HashMap<>();
            params.put("projectionIds", List.of("proj1"));
            params.put("companyId", "company1");
            params.put("fromDate", LocalDate.of(2026, 9, 1));
            params.put("toDateExclusive", LocalDate.of(2026, 9, 30));
            params.put("employeeNumber", "NONEXISTENT");
            params.put("employeeIds", List.of("emp1", "emp2", "emp3"));
            params.put("organizationIds", List.of("org1", "org2", "org3"));
            params.put("offset", 0);
            params.put("limit", 100);

            List<QueryPageRows.EmployeeDailyAggregateRow> rows = session.selectList(
                    NAMESPACE + "listEmployeeDailyAggregates", params);
            assertThat(rows).isEmpty();

            Long count = session.selectOne(NAMESPACE + "countEmployeeDailyAggregates", params);
            assertThat(count).isZero();
        }
    }

    private static void seedEmployeesAndOrganizations(SqlSession session) throws Exception {
        try (var sql = session.getConnection().createStatement()) {
            sql.execute("INSERT INTO employee_version VALUES ('emp1-v1', 'EMP001', 'Employee One')");
            sql.execute("INSERT INTO employee_version VALUES ('emp2-v1', 'EMP002', 'Employee Two V1')");
            sql.execute("INSERT INTO employee_version VALUES ('emp2-v2', 'EMP002', 'Employee Two V2')");
            sql.execute("INSERT INTO employee_version VALUES ('emp3-v1', 'EMP003', 'Employee Three')");
            sql.execute("INSERT INTO organization_version VALUES ('org1-v1', 'Org One')");
            sql.execute("INSERT INTO organization_version VALUES ('org2-v1', 'Org Two')");
            sql.execute("INSERT INTO organization_version VALUES ('org3-v1', 'Org Three')");
        }
    }

    private static void seedDailyFacts(SqlSession session) throws Exception {
        try (var sql = session.getConnection().prepareStatement("""
                INSERT INTO attendance_report_daily_fact VALUES
                (?, 'proj1', 'company1', ?, ?, ?, ?, ?,
                 0, 0, 0, 480, 0, 0, 0, 0, NULL, 480, 1, 1, 'Normal')
                """)) {
            // EMP001 - one day
            sql.setString(1, "fact-1");
            sql.setString(2, "emp1");
            sql.setString(3, "emp1-v1");
            sql.setString(4, "org1");
            sql.setString(5, "org1-v1");
            sql.setObject(6, LocalDate.of(2026, 9, 5));
            sql.executeUpdate();

            // EMP002 - two days, different versions (department change)
            sql.setString(1, "fact-2");
            sql.setString(2, "emp2");
            sql.setString(3, "emp2-v1");
            sql.setString(4, "org1");
            sql.setString(5, "org1-v1");
            sql.setObject(6, LocalDate.of(2026, 9, 10));
            sql.executeUpdate();

            sql.setString(1, "fact-3");
            sql.setString(2, "emp2");
            sql.setString(3, "emp2-v2");
            sql.setString(4, "org2");
            sql.setString(5, "org2-v1");
            sql.setObject(6, LocalDate.of(2026, 9, 20));
            sql.executeUpdate();

            // EMP003 - one day, unauthorized organization
            sql.setString(1, "fact-4");
            sql.setString(2, "emp3");
            sql.setString(3, "emp3-v1");
            sql.setString(4, "org3");
            sql.setString(5, "org3-v1");
            sql.setObject(6, LocalDate.of(2026, 9, 15));
            sql.executeUpdate();
        }
    }

    private static SqlSession session() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("employee-number-filter",
                new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver",
                        "jdbc:h2:mem:emp-filter-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")));
        String resource = "mappers/AttendanceReportQueryPageMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        SqlSession session = new SqlSessionFactoryBuilder()
                .build(configuration).openSession(true);
        try (var sql = session.getConnection().createStatement()) {
            for (String ddl : List.of(
                    "SET TIME ZONE 'UTC'",
                    """
                    CREATE TABLE employee_version (
                        employee_version_id VARCHAR(64) PRIMARY KEY,
                        employee_number VARCHAR(64),
                        display_name VARCHAR(128))
                    """,
                    """
                    CREATE TABLE organization_version (
                        organization_version_id VARCHAR(64) PRIMARY KEY,
                        name VARCHAR(128))
                    """,
                    """
                    CREATE TABLE attendance_report_daily_fact (
                        attendance_report_daily_fact_id VARCHAR(64) PRIMARY KEY,
                        attendance_report_projection_id VARCHAR(64),
                        company_id VARCHAR(64),
                        employee_id VARCHAR(64),
                        employee_version_id VARCHAR(64),
                        organization_id VARCHAR(64),
                        organization_version_id VARCHAR(64),
                        business_date DATE,
                        late_minutes BIGINT,
                        penalized_late_minutes BIGINT,
                        missing_punch_count BIGINT,
                        scheduled_minutes BIGINT,
                        paid_overtime_minutes BIGINT,
                        compensatory_overtime_minutes BIGINT,
                        voluntary_overtime_minutes BIGINT,
                        leave_or_time_off_minutes BIGINT,
                        leave_type VARCHAR(64),
                        actual_work_minutes BIGINT,
                        scheduled_attendance_days DECIMAL(10,2),
                        actual_attendance_days DECIMAL(10,2),
                        shift_label VARCHAR(64))
                    """)) {
                sql.execute(ddl);
            }
        }
        return session;
    }
}
