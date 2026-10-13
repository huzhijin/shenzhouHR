package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.RealtimeAuthorization;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttendanceReportHistoricalAuthorizationIntegrationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String PRINCIPAL_ID = "principal-hist-001";
    private static final String COMPANY_ID = "company-hist-001";
    private static final String ORG_A = "org-hist-a";
    private static final String ORG_B = "org-hist-b";
    private static final String ORG_OTHER_COMPANY = "org-other-co";

    private static final String EMPLOYEE_TRANSFER = "emp-hist-transfer";
    private static final String EMPLOYEE_TERMINATED = "emp-hist-terminated";
    private static final String EMPLOYEE_SUPERSEDED = "emp-hist-superseded";
    private static final String EMPLOYEE_OTHER_COMPANY = "emp-hist-other-co";
    private static final String EMPLOYEE_BOUNDARY = "emp-hist-boundary";

    private static final YearMonth PERIOD = YearMonth.of(2026, 3);
    private static final Instant AUTH_TIME = Instant.parse("2026-04-15T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AttendanceReportSourceRepository repository;

    @BeforeAll
    void createSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS auth_principal (
                principal_id VARCHAR(36) PRIMARY KEY,
                employee_id VARCHAR(36),
                status VARCHAR(32)
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS auth_role (
                role_id VARCHAR(36) PRIMARY KEY,
                role_code VARCHAR(64),
                role_name VARCHAR(100),
                permission_domain VARCHAR(32)
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS auth_capability (
                capability_id VARCHAR(36) PRIMARY KEY,
                capability_code VARCHAR(96)
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS auth_role_capability (
                role_id VARCHAR(36),
                capability_id VARCHAR(36)
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS auth_data_scope (
                scope_id VARCHAR(36) PRIMARY KEY,
                scope_type VARCHAR(32),
                company_id VARCHAR(36),
                organization_id VARCHAR(36),
                include_descendants BOOLEAN,
                valid_from TIMESTAMP,
                valid_to TIMESTAMP
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS auth_principal_role_assignment (
                assignment_id VARCHAR(36) PRIMARY KEY,
                principal_id VARCHAR(36),
                role_id VARCHAR(36),
                data_scope_id VARCHAR(36),
                valid_from TIMESTAMP,
                valid_to TIMESTAMP
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS organization_identity (
                organization_id VARCHAR(36) PRIMARY KEY,
                company_id VARCHAR(36),
                identity_status VARCHAR(32)
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS organization_version (
                organization_version_id VARCHAR(36) PRIMARY KEY,
                organization_id VARCHAR(36),
                parent_organization_id VARCHAR(36),
                code VARCHAR(64),
                name VARCHAR(128),
                org_type VARCHAR(32),
                effective_from TIMESTAMP,
                effective_to TIMESTAMP,
                status VARCHAR(32) DEFAULT 'ACTIVE'
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS organization_current_projection (
                organization_id VARCHAR(36) PRIMARY KEY,
                current_version_id VARCHAR(36)
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS organization_current_closure (
                ancestor_organization_id VARCHAR(36),
                descendant_organization_id VARCHAR(36),
                depth INTEGER
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS employee (
                employee_id VARCHAR(36) PRIMARY KEY,
                company_id VARCHAR(36),
                employee_number VARCHAR(64),
                display_name VARCHAR(128),
                employment_status VARCHAR(32)
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS employee_version (
                employee_version_id VARCHAR(36) PRIMARY KEY,
                employee_id VARCHAR(36),
                employee_number VARCHAR(64),
                display_name VARCHAR(128),
                status VARCHAR(32),
                effective_from DATE,
                source_authority VARCHAR(32),
                change_reason VARCHAR(500),
                created_at TIMESTAMP
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS employment_assignment (
                assignment_id VARCHAR(36) PRIMARY KEY,
                employment_period_id VARCHAR(36),
                employee_id VARCHAR(36),
                organization_id VARCHAR(36),
                effective_from TIMESTAMP,
                effective_to TIMESTAMP,
                record_status VARCHAR(32),
                version_valid_to TIMESTAMP
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS company (
                company_id VARCHAR(36) PRIMARY KEY,
                name VARCHAR(128),
                code VARCHAR(64) NOT NULL,
                status VARCHAR(32)
            )
            """);
    }

    @BeforeEach
    void setupFixture() {
        cleanupTables();

        jdbc.update("INSERT INTO company (company_id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                COMPANY_ID, "COMP001", "Test Company");
        jdbc.update("INSERT INTO company (company_id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                "company-hist-002", "COMP002", "Other Company");

        jdbc.update("INSERT INTO auth_principal (principal_id, employee_id, status) " +
                "VALUES (?, NULL, 'ACTIVE')", PRINCIPAL_ID);

        String roleId = "role-hist-001";
        jdbc.update("INSERT INTO auth_role (role_id, role_code, role_name, permission_domain) " +
                "VALUES (?, 'REPORT_VIEWER', 'Report Viewer', 'ATTENDANCE_REPORT')", roleId);

        String capabilityId = "cap-hist-001";
        jdbc.update("INSERT INTO auth_capability (capability_id, capability_code) VALUES (?, ?)",
                capabilityId, CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ);

        jdbc.update("INSERT INTO auth_role_capability (role_id, capability_id) VALUES (?, ?)",
                roleId, capabilityId);

        String scopeId = "scope-hist-001";
        jdbc.update("INSERT INTO auth_data_scope (scope_id, scope_type, company_id, " +
                "organization_id, include_descendants, valid_from, valid_to) " +
                "VALUES (?, 'ORGANIZATION', NULL, ?, FALSE, ?, NULL)",
                scopeId, ORG_A, Timestamp.from(AUTH_TIME.minusSeconds(86400)));

        String assignmentId = "assignment-hist-001";
        jdbc.update("INSERT INTO auth_principal_role_assignment (assignment_id, principal_id, " +
                "role_id, data_scope_id, valid_from, valid_to) " +
                "VALUES (?, ?, ?, ?, ?, NULL)",
                assignmentId, PRINCIPAL_ID, roleId, scopeId,
                Timestamp.from(AUTH_TIME.minusSeconds(86400)));

        createOrganization(ORG_A, COMPANY_ID, "Org A");
        createOrganization(ORG_B, COMPANY_ID, "Org B");
        createOrganization(ORG_OTHER_COMPANY, "company-hist-002", "Other Org");

        jdbc.update("INSERT INTO organization_current_closure (ancestor_organization_id, " +
                "descendant_organization_id, depth) VALUES (?, ?, 0)", ORG_A, ORG_A);
        jdbc.update("INSERT INTO organization_current_closure (ancestor_organization_id, " +
                "descendant_organization_id, depth) VALUES (?, ?, 0)", ORG_B, ORG_B);

        createEmployee(EMPLOYEE_TRANSFER, COMPANY_ID, "ACTIVE", "Transfer Employee");
        createEmployee(EMPLOYEE_TERMINATED, COMPANY_ID, "TERMINATED", "Terminated Employee");
        createEmployee(EMPLOYEE_SUPERSEDED, COMPANY_ID, "ACTIVE", "Superseded Employee");
        createEmployee(EMPLOYEE_OTHER_COMPANY, "company-hist-002", "ACTIVE", "Other Company Employee");
        createEmployee(EMPLOYEE_BOUNDARY, COMPANY_ID, "TERMINATED", "Boundary Employee");

        Instant periodStart = PERIOD.atDay(1).atStartOfDay(SHANGHAI).toInstant();
        Instant periodEnd = PERIOD.plusMonths(1).atDay(1).atStartOfDay(SHANGHAI).toInstant();
        Instant midMonth = PERIOD.atDay(15).atStartOfDay(SHANGHAI).toInstant();

        createAssignment(EMPLOYEE_TRANSFER, ORG_A, periodStart.minusSeconds(86400*30),
                midMonth, null);
        createAssignment(EMPLOYEE_TRANSFER, ORG_B, midMonth, null, null);

        createAssignment(EMPLOYEE_TERMINATED, ORG_A, periodStart.minusSeconds(86400*60),
                periodEnd, null);

        Instant supersededTime = periodStart.minusSeconds(86400*10);
        createAssignment(EMPLOYEE_SUPERSEDED, ORG_A, periodStart.minusSeconds(86400*90),
                periodEnd, supersededTime);
        createAssignment(EMPLOYEE_SUPERSEDED, ORG_B, periodStart.minusSeconds(86400*90),
                periodEnd, null);

        createAssignment(EMPLOYEE_OTHER_COMPANY, ORG_OTHER_COMPANY, periodStart.minusSeconds(86400*30),
                null, null);

        createAssignment(EMPLOYEE_BOUNDARY, ORG_A, periodEnd.minusSeconds(86400*30),
                periodEnd, null);
    }

    @Test
    void terminatedEmployeeWithHistoricalAssignmentInAuthorizedOrgIsVisible() {
        Optional<RealtimeAuthorization> auth = repository.resolveRealtimeAuthorizationForPeriod(
                PRINCIPAL_ID,
                CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                COMPANY_ID,
                PERIOD,
                AUTH_TIME);

        assertThat(auth).isPresent();
        Set<String> employeeIds = auth.get().employeeIds();

        assertThat(employeeIds).contains(EMPLOYEE_TERMINATED);
    }

    @Test
    void activeEmployeeWhoTransferredAwayLosesHistoricalAccess() {
        Optional<RealtimeAuthorization> auth = repository.resolveRealtimeAuthorizationForPeriod(
                PRINCIPAL_ID,
                CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                COMPANY_ID,
                PERIOD,
                AUTH_TIME);

        assertThat(auth).isPresent();
        Set<String> employeeIds = auth.get().employeeIds();

        assertThat(employeeIds).doesNotContain(EMPLOYEE_TRANSFER);
    }

    @Test
    void supersededAssignmentDoesNotGrantAccess() {
        Optional<RealtimeAuthorization> auth = repository.resolveRealtimeAuthorizationForPeriod(
                PRINCIPAL_ID,
                CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                COMPANY_ID,
                PERIOD,
                AUTH_TIME);

        assertThat(auth).isPresent();
        Set<String> employeeIds = auth.get().employeeIds();

        assertThat(employeeIds).doesNotContain(EMPLOYEE_SUPERSEDED);
    }

    @Test
    void crossCompanyAssignmentIsNotVisible() {
        Optional<RealtimeAuthorization> auth = repository.resolveRealtimeAuthorizationForPeriod(
                PRINCIPAL_ID,
                CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                COMPANY_ID,
                PERIOD,
                AUTH_TIME);

        assertThat(auth).isPresent();
        Set<String> employeeIds = auth.get().employeeIds();

        assertThat(employeeIds).doesNotContain(EMPLOYEE_OTHER_COMPANY);
    }

    @Test
    void periodBoundariesUseShanghaiMidnight() {
        Optional<RealtimeAuthorization> auth = repository.resolveRealtimeAuthorizationForPeriod(
                PRINCIPAL_ID,
                CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                COMPANY_ID,
                PERIOD,
                AUTH_TIME);

        assertThat(auth).isPresent();
        assertThat(auth.get().employeeIds()).contains(EMPLOYEE_BOUNDARY);
    }

    private void createOrganization(String orgId, String companyId, String name) {
        jdbc.update("INSERT INTO organization_identity (organization_id, company_id, " +
                "identity_status) VALUES (?, ?, 'ACTIVE')", orgId, companyId);

        String versionId = orgId + "-ver";
        jdbc.update("INSERT INTO organization_version (organization_version_id, " +
                "organization_id, parent_organization_id, code, name, org_type, " +
                "effective_from, effective_to, status) VALUES (?, ?, NULL, ?, ?, 'DEPARTMENT', ?, NULL, 'ACTIVE')",
                versionId, orgId, orgId, name, Timestamp.from(AUTH_TIME.minusSeconds(86400*90)));

        jdbc.update("INSERT INTO organization_current_projection (organization_id, " +
                "current_version_id) VALUES (?, ?)", orgId, versionId);
    }

    private void createEmployee(String empId, String companyId, String status, String name) {
        jdbc.update("INSERT INTO employee (employee_id, company_id, employee_number, " +
                "display_name, employment_status) VALUES (?, ?, ?, ?, ?)",
                empId, companyId, empId, name, status);

        jdbc.update("INSERT INTO employee_version (employee_version_id, employee_id, " +
                "employee_number, display_name, status, effective_from, source_authority, " +
                "change_reason, created_at) VALUES (?, ?, ?, ?, ?, ?, 'LOCAL', 'test', ?)",
                empId + "-ver", empId, empId, name, status,
                Date.valueOf(LocalDate.of(2020, 1, 1)),
                Timestamp.from(AUTH_TIME.minusSeconds(86400*90)));
    }

    private void createAssignment(String empId, String orgId, Instant effectiveFrom,
                                   Instant effectiveTo, Instant versionValidTo) {
        String assignmentId = "assign-" + Math.abs((empId + orgId + effectiveFrom.toString()).hashCode());
        jdbc.update("INSERT INTO employment_assignment (assignment_id, employment_period_id, " +
                "employee_id, organization_id, effective_from, effective_to, record_status, " +
                "version_valid_to) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                assignmentId, assignmentId, empId, orgId, Timestamp.from(effectiveFrom),
                effectiveTo != null ? Timestamp.from(effectiveTo) : null,
                versionValidTo != null ? Timestamp.from(versionValidTo) : null);
    }

    private void cleanupTables() {
        jdbc.execute("DELETE FROM employment_assignment");
        jdbc.execute("DELETE FROM employee_version");
        jdbc.execute("DELETE FROM employee");
        jdbc.execute("DELETE FROM organization_current_closure");
        jdbc.execute("DELETE FROM organization_current_projection");
        jdbc.execute("DELETE FROM organization_version");
        jdbc.execute("DELETE FROM organization_identity");
        jdbc.execute("DELETE FROM auth_principal_role_assignment");
        jdbc.execute("DELETE FROM auth_data_scope");
        jdbc.execute("DELETE FROM auth_role_capability");
        jdbc.execute("DELETE FROM auth_capability");
        jdbc.execute("DELETE FROM auth_role");
        jdbc.execute("DELETE FROM auth_principal");
        jdbc.execute("DELETE FROM company");
    }
}
