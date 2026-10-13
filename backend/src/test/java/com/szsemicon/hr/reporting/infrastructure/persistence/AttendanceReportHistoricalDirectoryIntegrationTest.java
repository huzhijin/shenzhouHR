package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.application.QueryPageRows;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
class AttendanceReportHistoricalDirectoryIntegrationTest {

    private static final String COMPANY_ID = "30000000-0000-0000-0000-000000000001";
    private static final String ORG_ID = "40000000-0000-0000-0000-000000000001";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private AttendanceReportQueryPageMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private String terminatedEmployeeId;
    private String activeEmployeeId;
    private String futureEmployeeId;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-01T00:00:00Z");
        terminatedEmployeeId = UUID.randomUUID().toString();
        activeEmployeeId = UUID.randomUUID().toString();
        futureEmployeeId = UUID.randomUUID().toString();

        createOrganization(ORG_ID, "Test Org",
                LocalDate.of(2026, 1, 1).atStartOfDay(BUSINESS_ZONE).toInstant(), null);

        // Terminated employee: last work day Sept 11, termination effective Sept 12
        createEmployee(terminatedEmployeeId, "E001", "Terminated Employee",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 12),
                "ACTIVE", "TERMINATED");

        // Active employee: still working
        createEmployee(activeEmployeeId, "E002", "Active Employee",
                LocalDate.of(2026, 9, 1), null,
                "ACTIVE", null);

        // Future employee: starts in October
        createEmployee(futureEmployeeId, "E003", "Future Employee",
                LocalDate.of(2026, 10, 15), null,
                "ACTIVE", null);
    }

    @Test
    @Transactional
    void terminatedEmployeeAppearsInSeptemberDirectory() {
        Instant septStart = LocalDate.of(2026, 9, 1).atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant octStart = LocalDate.of(2026, 10, 1).atStartOfDay(BUSINESS_ZONE).toInstant();

        List<QueryPageRows.DirectoryEmployeeRow> septResult = mapper.listDirectoryEmployees(
                COMPANY_ID, null, true, null, septStart, octStart, now);

        assertThat(septResult).extracting(QueryPageRows.DirectoryEmployeeRow::employeeId)
                .contains(terminatedEmployeeId, activeEmployeeId)
                .doesNotContain(futureEmployeeId);
    }

    @Test
    @Transactional
    void terminatedEmployeeDoesNotAppearInOctoberDirectory() {
        Instant octStart = LocalDate.of(2026, 10, 1).atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant novStart = LocalDate.of(2026, 11, 1).atStartOfDay(BUSINESS_ZONE).toInstant();

        List<QueryPageRows.DirectoryEmployeeRow> octResult = mapper.listDirectoryEmployees(
                COMPANY_ID, null, true, null, octStart, novStart, now);

        assertThat(octResult).extracting(QueryPageRows.DirectoryEmployeeRow::employeeId)
                .contains(activeEmployeeId, futureEmployeeId)
                .doesNotContain(terminatedEmployeeId);
    }

    @Test
    @Transactional
    void transferredEmployeeAppearsOnceWithLatestDepartment() {
        String transferredId = UUID.randomUUID().toString();
        String oldOrgId = "40000000-0000-0000-0000-000000000002";
        String newOrgId = "40000000-0000-0000-0000-000000000003";

        createOrganization(oldOrgId, "Old Dept",
                LocalDate.of(2026, 1, 1).atStartOfDay(BUSINESS_ZONE).toInstant(), null);
        createOrganization(newOrgId, "New Dept",
                LocalDate.of(2026, 1, 1).atStartOfDay(BUSINESS_ZONE).toInstant(), null);

        // Employee with transfer mid-September
        jdbc.update("""
                INSERT INTO employee (employee_id, company_id, display_name, employment_status)
                VALUES (?, ?, 'Transferred Employee', 'ACTIVE')
                """, transferredId, COMPANY_ID);

        jdbc.update("""
                INSERT INTO employee_version (
                    employee_version_id, employee_id, employee_number, display_name,
                    status, effective_from, effective_to, source_authority, row_version,
                    change_reason, created_at
                ) VALUES (?, ?, 'E004', 'Transferred Employee', 'ACTIVE', ?, NULL,
                          'LOCAL', 0, 'test', ?)
                """, UUID.randomUUID().toString(), transferredId,
                LocalDate.of(2026, 9, 1), now);

        // Old assignment (Sept 1 to Sept 15)
        jdbc.update("""
                INSERT INTO employment_assignment (
                    assignment_id, employee_id, organization_id, effective_from, effective_to,
                    version_valid_to, record_status, row_version, created_at
                ) VALUES (?, ?, ?, ?, ?, NULL, 'ACTIVE', 0, ?)
                """, UUID.randomUUID().toString(), transferredId, oldOrgId,
                LocalDate.of(2026, 9, 1).atStartOfDay(BUSINESS_ZONE).toInstant(),
                LocalDate.of(2026, 9, 15).atStartOfDay(BUSINESS_ZONE).toInstant(), now);

        // New assignment (Sept 15 onwards)
        jdbc.update("""
                INSERT INTO employment_assignment (
                    assignment_id, employee_id, organization_id, effective_from, effective_to,
                    version_valid_to, record_status, row_version, created_at
                ) VALUES (?, ?, ?, ?, NULL, NULL, 'ACTIVE', 0, ?)
                """, UUID.randomUUID().toString(), transferredId, newOrgId,
                LocalDate.of(2026, 9, 15).atStartOfDay(BUSINESS_ZONE).toInstant(), now);

        Instant septStart = LocalDate.of(2026, 9, 1).atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant octStart = LocalDate.of(2026, 10, 1).atStartOfDay(BUSINESS_ZONE).toInstant();

        List<QueryPageRows.DirectoryEmployeeRow> septResult = mapper.listDirectoryEmployees(
                COMPANY_ID, null, true, null, septStart, octStart, now);

        List<QueryPageRows.DirectoryEmployeeRow> transferred = septResult.stream()
                .filter(row -> row.employeeId().equals(transferredId))
                .toList();

        assertThat(transferred).hasSize(1);
        assertThat(transferred.get(0).organizationId()).isEqualTo(newOrgId);
        assertThat(transferred.get(0).organizationName()).isEqualTo("New Dept");
    }

    @Test
    @Transactional
    void authorizedOrgADoesNotLeakUnauthorizedOrgBAfterTransfer() {
        String transferredId = UUID.randomUUID().toString();
        String authorizedOrgId = "40000000-0000-0000-0000-000000000004";
        String unauthorizedOrgId = "40000000-0000-0000-0000-000000000005";

        createOrganization(authorizedOrgId, "Authorized Dept",
                LocalDate.of(2026, 1, 1).atStartOfDay(BUSINESS_ZONE).toInstant(), null);
        createOrganization(unauthorizedOrgId, "Unauthorized Dept",
                LocalDate.of(2026, 1, 1).atStartOfDay(BUSINESS_ZONE).toInstant(), null);

        jdbc.update("""
                INSERT INTO employee (employee_id, company_id, display_name, employment_status)
                VALUES (?, ?, 'Transfer Test Employee', 'ACTIVE')
                """, transferredId, COMPANY_ID);

        jdbc.update("""
                INSERT INTO employee_version (
                    employee_version_id, employee_id, employee_number, display_name,
                    status, effective_from, effective_to, source_authority, row_version,
                    change_reason, created_at
                ) VALUES (?, ?, 'E005', 'Transfer Test Employee', 'ACTIVE', ?, NULL,
                          'LOCAL', 0, 'test', ?)
                """, UUID.randomUUID().toString(), transferredId,
                LocalDate.of(2026, 9, 1), now);

        // Started in authorized org (Sept 1 to Sept 10)
        jdbc.update("""
                INSERT INTO employment_assignment (
                    assignment_id, employee_id, organization_id, effective_from, effective_to,
                    version_valid_to, record_status, row_version, created_at
                ) VALUES (?, ?, ?, ?, ?, NULL, 'ACTIVE', 0, ?)
                """, UUID.randomUUID().toString(), transferredId, authorizedOrgId,
                LocalDate.of(2026, 9, 1).atStartOfDay(BUSINESS_ZONE).toInstant(),
                LocalDate.of(2026, 9, 10).atStartOfDay(BUSINESS_ZONE).toInstant(), now);

        // Transferred to unauthorized org (Sept 10 onwards)
        jdbc.update("""
                INSERT INTO employment_assignment (
                    assignment_id, employee_id, organization_id, effective_from, effective_to,
                    version_valid_to, record_status, row_version, created_at
                ) VALUES (?, ?, ?, ?, NULL, NULL, 'ACTIVE', 0, ?)
                """, UUID.randomUUID().toString(), transferredId, unauthorizedOrgId,
                LocalDate.of(2026, 9, 10).atStartOfDay(BUSINESS_ZONE).toInstant(), now);

        Instant septStart = LocalDate.of(2026, 9, 1).atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant octStart = LocalDate.of(2026, 10, 1).atStartOfDay(BUSINESS_ZONE).toInstant();

        // Query with authorization only for authorizedOrgId
        List<QueryPageRows.DirectoryEmployeeRow> result = mapper.listDirectoryEmployees(
                COMPANY_ID, List.of(authorizedOrgId), false, null, septStart, octStart, now);

        List<QueryPageRows.DirectoryEmployeeRow> employee = result.stream()
                .filter(row -> row.employeeId().equals(transferredId))
                .toList();

        // Should show the employee with authorized org only, not leak unauthorized org
        assertThat(employee).hasSize(1);
        assertThat(employee.get(0).organizationId()).isEqualTo(authorizedOrgId);
        assertThat(employee.get(0).organizationName()).isEqualTo("Authorized Dept");
        // Must NOT show unauthorizedOrgId or "Unauthorized Dept"
    }

    @Test
    @Transactional
    void longTermEmployeeAppearsInLaterMonth() {
        String longTermId = UUID.randomUUID().toString();

        // Employee hired in January and still working
        createEmployee(longTermId, "E006", "Long Term Employee",
                LocalDate.of(2026, 1, 15), null,
                "ACTIVE", null);

        Instant septStart = LocalDate.of(2026, 9, 1).atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant octStart = LocalDate.of(2026, 10, 1).atStartOfDay(BUSINESS_ZONE).toInstant();

        List<QueryPageRows.DirectoryEmployeeRow> septResult = mapper.listDirectoryEmployees(
                COMPANY_ID, null, true, null, septStart, octStart, now);

        assertThat(septResult).extracting(QueryPageRows.DirectoryEmployeeRow::employeeId)
                .contains(longTermId);
    }

    @Test
    @Transactional
    void crossDepartmentMultiDayAggregateUsesLastDayDepartmentAndSumsWorkMinutes() {
        createReportingTables();

        String transferredId = UUID.randomUUID().toString();
        String deptAId = "40000000-0000-0000-0000-000000000010";
        String deptBId = "40000000-0000-0000-0000-000000000011";
        String projectionId = UUID.randomUUID().toString();

        createOrganization(deptAId, "Dept A",
                LocalDate.of(2026, 1, 1).atStartOfDay(BUSINESS_ZONE).toInstant(), null);
        createOrganization(deptBId, "Dept B",
                LocalDate.of(2026, 1, 1).atStartOfDay(BUSINESS_ZONE).toInstant(), null);

        jdbc.update("""
                INSERT INTO employee (employee_id, company_id, display_name, employment_status)
                VALUES (?, ?, 'Transfer Worker', 'ACTIVE')
                """, transferredId, COMPANY_ID);

        String versionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO employee_version (
                    employee_version_id, employee_id, employee_number, display_name,
                    status, effective_from, effective_to, source_authority, row_version,
                    change_reason, created_at
                ) VALUES (?, ?, 'E100', 'Transfer Worker', 'ACTIVE', ?, NULL,
                          'LOCAL', 0, 'test', ?)
                """, versionId, transferredId, LocalDate.of(2026, 9, 1), now);

        // Assignment: Dept A from Sept 1-15, Dept B from Sept 15 onwards
        jdbc.update("""
                INSERT INTO employment_assignment (
                    assignment_id, employee_id, organization_id, effective_from, effective_to,
                    version_valid_to, record_status, row_version, created_at
                ) VALUES (?, ?, ?, ?, ?, NULL, 'ACTIVE', 0, ?)
                """, UUID.randomUUID().toString(), transferredId, deptAId,
                LocalDate.of(2026, 9, 1).atStartOfDay(BUSINESS_ZONE).toInstant(),
                LocalDate.of(2026, 9, 15).atStartOfDay(BUSINESS_ZONE).toInstant(), now);

        jdbc.update("""
                INSERT INTO employment_assignment (
                    assignment_id, employee_id, organization_id, effective_from, effective_to,
                    version_valid_to, record_status, row_version, created_at
                ) VALUES (?, ?, ?, ?, NULL, NULL, 'ACTIVE', 0, ?)
                """, UUID.randomUUID().toString(), transferredId, deptBId,
                LocalDate.of(2026, 9, 15).atStartOfDay(BUSINESS_ZONE).toInstant(), now);

        String orgVersionAId = UUID.randomUUID().toString();
        String orgVersionBId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO organization_version (
                    organization_version_id, organization_id, code, name,
                    org_type, status, effective_from, effective_to,
                    source_authority, row_version, change_reason, created_at
                ) VALUES (?, ?, 'A', 'Dept A', 'DEPARTMENT', 'ACTIVE', ?, NULL, 'LOCAL', 0, 'test', ?)
                """, orgVersionAId, deptAId,
                LocalDate.of(2026, 9, 1).atStartOfDay(BUSINESS_ZONE).toInstant(), now);

        jdbc.update("""
                INSERT INTO organization_version (
                    organization_version_id, organization_id, code, name,
                    org_type, status, effective_from, effective_to,
                    source_authority, row_version, change_reason, created_at
                ) VALUES (?, ?, 'B', 'Dept B', 'DEPARTMENT', 'ACTIVE', ?, NULL, 'LOCAL', 0, 'test', ?)
                """, orgVersionBId, deptBId,
                LocalDate.of(2026, 9, 1).atStartOfDay(BUSINESS_ZONE).toInstant(), now);

        // Create projection
        jdbc.update("""
                INSERT INTO attendance_report_projection (
                    attendance_report_projection_id, company_id, period_start, period_end_exclusive,
                    projection_version, formula_catalog_version, period_state, source_versions_json,
                    data_as_of, status, published_at
                ) VALUES (?, ?, ?, ?, '1', 'FULL_CALCULATION_OA_FORM_HOURS_V8', 'CLOSED', '{}',
                          ?, 'PUBLISHED', ?)
                """, projectionId, COMPANY_ID,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                now, now);

        // Day 1 in Dept A: 480 scheduled, 480 actual, 10 late
        jdbc.update("""
                INSERT INTO attendance_report_daily_fact (
                    attendance_report_daily_fact_id, attendance_report_projection_id, company_id,
                    employee_id, employee_version_id, organization_id, organization_version_id,
                    business_date, day_type, shift_label, scheduled_minutes,
                    confirmed_scheduled_work_minutes, recognized_overtime_minutes,
                    paid_overtime_minutes, compensatory_overtime_minutes, voluntary_overtime_minutes,
                    total_overtime_minutes, leave_or_time_off_minutes, absence_minutes,
                    actual_work_minutes, scheduled_attendance_days, actual_attendance_days,
                    unexcused_overtime_minutes, excused_overtime_minutes, late_minutes,
                    penalized_late_minutes, early_departure_minutes, missing_punch_count,
                    calculation_version_id, result_digest
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'WORKDAY', 'day', 480, 480, 0, 0, 0, 0, 0, 0, 0,
                          480, 1, 1, 0, 0, 10, 0, 0, 0, 'calc-v1', 'digest-1')
                """, UUID.randomUUID().toString(), projectionId, COMPANY_ID,
                transferredId, versionId, deptAId, orgVersionAId, LocalDate.of(2026, 9, 10));

        // Day 2 in Dept B: 480 scheduled, 420 actual, 5 late
        jdbc.update("""
                INSERT INTO attendance_report_daily_fact (
                    attendance_report_daily_fact_id, attendance_report_projection_id, company_id,
                    employee_id, employee_version_id, organization_id, organization_version_id,
                    business_date, day_type, shift_label, scheduled_minutes,
                    confirmed_scheduled_work_minutes, recognized_overtime_minutes,
                    paid_overtime_minutes, compensatory_overtime_minutes, voluntary_overtime_minutes,
                    total_overtime_minutes, leave_or_time_off_minutes, absence_minutes,
                    actual_work_minutes, scheduled_attendance_days, actual_attendance_days,
                    unexcused_overtime_minutes, excused_overtime_minutes, late_minutes,
                    penalized_late_minutes, early_departure_minutes, missing_punch_count,
                    calculation_version_id, result_digest
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'WORKDAY', 'day', 480, 480, 0, 0, 0, 0, 0, 0, 0,
                          420, 1, 1, 0, 0, 5, 0, 0, 0, 'calc-v1', 'digest-2')
                """, UUID.randomUUID().toString(), projectionId, COMPANY_ID,
                transferredId, versionId, deptBId, orgVersionBId, LocalDate.of(2026, 9, 20));

        Instant septStart = LocalDate.of(2026, 9, 1).atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant octStart = LocalDate.of(2026, 10, 1).atStartOfDay(BUSINESS_ZONE).toInstant();

        // Query with filters to exercise dailyAggregateHaving fragment
        var query = new QueryPageRows.FactQuery(
                List.of(projectionId),
                COMPANY_ID,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 1),
                null,
                List.of(transferredId),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "2次及以上",
                "11-30分钟",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                100,
                "late");

        long countResult = mapper.countEmployeeDailyAggregates(query);
        assertThat(countResult).isEqualTo(1);

        var result = mapper.listEmployeeDailyAggregates(query);
        assertThat(result).hasSize(1);
        var aggregate = result.get(0);

        // Department must be from last day (Sept 20), not first day
        assertThat(aggregate.organizationId()).isEqualTo(deptBId);
        assertThat(aggregate.organizationName()).isEqualTo("Dept B");

        // Work minutes must sum correctly: 480 + 420 = 900
        assertThat(aggregate.actualWorkMinutes()).isEqualTo(900L);

        // Late minutes sum: 10 + 5 = 15
        assertThat(aggregate.lateMinutes()).isEqualTo(15L);
    }

    private void createReportingTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS attendance_report_projection (
                    attendance_report_projection_id VARCHAR(36) PRIMARY KEY,
                    company_id VARCHAR(36) NOT NULL,
                    period_start DATE NOT NULL,
                    period_end_exclusive DATE NOT NULL,
                    projection_version VARCHAR(128) NOT NULL,
                    formula_catalog_version VARCHAR(128) NOT NULL DEFAULT
                        'FULL_CALCULATION_OA_FORM_HOURS_V8',
                    period_state VARCHAR(32) NOT NULL,
                    source_versions_json CLOB NOT NULL,
                    data_as_of TIMESTAMP NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    published_at TIMESTAMP
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS attendance_report_daily_fact (
                    attendance_report_daily_fact_id VARCHAR(36) PRIMARY KEY,
                    attendance_report_projection_id VARCHAR(36) NOT NULL,
                    company_id VARCHAR(36) NOT NULL,
                    employee_id VARCHAR(36) NOT NULL,
                    employee_version_id VARCHAR(36) NOT NULL,
                    employment_period_id VARCHAR(36),
                    organization_id VARCHAR(36) NOT NULL,
                    organization_version_id VARCHAR(36) NOT NULL,
                    business_date DATE NOT NULL,
                    day_type VARCHAR(32) NOT NULL,
                    shift_label VARCHAR(200) NOT NULL,
                    scheduled_minutes BIGINT NOT NULL,
                    confirmed_scheduled_work_minutes BIGINT NOT NULL,
                    recognized_overtime_minutes BIGINT NOT NULL,
                    paid_overtime_minutes BIGINT NOT NULL DEFAULT 0,
                    compensatory_overtime_minutes BIGINT NOT NULL DEFAULT 0,
                    voluntary_overtime_minutes BIGINT NOT NULL DEFAULT 0,
                    total_overtime_minutes BIGINT NOT NULL DEFAULT 0,
                    leave_or_time_off_minutes BIGINT NOT NULL,
                    leave_type VARCHAR(32),
                    absence_minutes BIGINT NOT NULL,
                    actual_work_minutes BIGINT NOT NULL,
                    scheduled_attendance_days INTEGER NOT NULL,
                    actual_attendance_days INTEGER NOT NULL,
                    unexcused_overtime_minutes BIGINT NOT NULL,
                    excused_overtime_minutes BIGINT NOT NULL,
                    late_minutes BIGINT NOT NULL,
                    penalized_late_minutes BIGINT NOT NULL,
                    early_departure_minutes BIGINT NOT NULL,
                    missing_punch_count INTEGER NOT NULL,
                    first_punch_at TIMESTAMP,
                    last_punch_at TIMESTAMP,
                    calculation_version_id VARCHAR(128) NOT NULL,
                    result_digest VARCHAR(128) NOT NULL
                )
                """);
    }

    private void createEmployee(String employeeId, String employeeNumber, String displayName,
                                LocalDate hireDate, LocalDate terminationDate,
                                String initialStatus, String terminatedStatus) {
        String currentStatus = terminationDate == null ? initialStatus : terminatedStatus;
        jdbc.update("""
                INSERT INTO employee (employee_id, company_id, display_name, employment_status)
                VALUES (?, ?, ?, ?)
                """, employeeId, COMPANY_ID, displayName, currentStatus);

        // Active version from hire date until termination (if any)
        jdbc.update("""
                INSERT INTO employee_version (
                    employee_version_id, employee_id, employee_number, display_name,
                    status, effective_from, effective_to, source_authority, row_version,
                    change_reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'LOCAL', 0, 'test', ?)
                """, UUID.randomUUID().toString(), employeeId, employeeNumber, displayName,
                initialStatus, hireDate, terminationDate, now);

        // If terminated, create TERMINATED version from termination date onwards
        if (terminationDate != null && terminatedStatus != null) {
            jdbc.update("""
                    INSERT INTO employee_version (
                        employee_version_id, employee_id, employee_number, display_name,
                        status, effective_from, effective_to, source_authority, row_version,
                        change_reason, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, NULL, 'LOCAL', 1, 'termination', ?)
                    """, UUID.randomUUID().toString(), employeeId, employeeNumber, displayName,
                    terminatedStatus, terminationDate, now);
        }

        // Employment assignment matches the active period
        jdbc.update("""
                INSERT INTO employment_assignment (
                    assignment_id, employee_id, organization_id, effective_from, effective_to,
                    version_valid_to, record_status, row_version, created_at
                ) VALUES (?, ?, ?, ?, ?, NULL, 'ACTIVE', 0, ?)
                """, UUID.randomUUID().toString(), employeeId, ORG_ID,
                hireDate.atStartOfDay(BUSINESS_ZONE).toInstant(),
                terminationDate != null ? terminationDate.atStartOfDay(BUSINESS_ZONE).toInstant() : null,
                now);
    }

    private void createOrganization(String orgId, String name, Instant effectiveFrom, Instant effectiveTo) {
        jdbc.update("""
                MERGE INTO organization_identity (organization_id, company_id, identity_status, created_at)
                KEY (organization_id)
                VALUES (?, ?, 'ACTIVE', ?)
                """, orgId, COMPANY_ID, now);

        jdbc.update("""
                MERGE INTO organization_version (
                    organization_version_id, organization_id, code, name,
                    org_type, status, effective_from, effective_to,
                    source_authority, row_version, change_reason, created_at
                ) KEY (organization_version_id)
                VALUES (?, ?, ?, ?, 'DEPARTMENT', 'ACTIVE', ?, ?, 'LOCAL', 0, 'test', ?)
                """, UUID.randomUUID().toString(), orgId, "CODE", name,
                effectiveFrom, effectiveTo, now);
    }
}
