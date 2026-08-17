package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.DashboardSnapshot;
import com.szsemicon.hr.reporting.application.AttendanceReportExportEncoder.EncodedExport;
import com.szsemicon.hr.reporting.application.AttendanceReportExportEncoder.ExportContext;
import com.szsemicon.hr.reporting.application.AttendanceReportExportService.RequestedExportBinding;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportJob;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.DepartmentAttendanceRate;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.EmployeeDepartmentAttendancePeriod;
import com.szsemicon.hr.reporting.domain.AttendanceReportCalculator;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttendanceReportMultiScopePersistenceIntegrationTest {

    private static final String PRINCIPAL =
            "8f000000-0000-0000-0000-000000000001";
    private static final String ROLE =
            "1f000000-0000-0000-0000-000000000001";
    private static final String COMPANY_A =
            "30000000-0000-0000-0000-000000000001";
    private static final String COMPANY_B =
            "30000000-0000-0000-0000-000000000002";
    private static final String ORGANIZATION_A =
            "40000000-0000-0000-0000-000000000002";
    private static final String UNAUTHORIZED_ORGANIZATION_A =
            "40000000-0000-0000-0000-000000000003";
    private static final String ORGANIZATION_B =
            "4f000000-0000-0000-0000-000000000001";
    private static final String CHILD_ORGANIZATION_B =
            "4f000000-0000-0000-0000-000000000002";
    private static final String UNAUTHORIZED_ORGANIZATION_B =
            "4f000000-0000-0000-0000-000000000003";
    private static final String SCOPE_A =
            "9f000000-0000-0000-0000-000000000001";
    private static final String ASSIGNMENT_A =
            "af000000-0000-0000-0000-000000000001";
    private static final String PROJECTION_A =
            "6f000000-0000-0000-0000-000000000001";
    private static final String PROJECTION_B =
            "6f000000-0000-0000-0000-000000000002";
    private static final YearMonth PERIOD = YearMonth.of(2026, 8);
    private static final LocalDate BUSINESS_DATE =
            LocalDate.of(2026, 8, 5);
    private static final Instant AUTHORIZATION_TIME =
            Instant.parse("2026-08-05T10:00:00Z");
    private static final Clock CLOCK =
            Clock.fixed(AUTHORIZATION_TIME, ZoneOffset.UTC);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AttendanceReportSourceRepository reportRepository;

    @Autowired
    private AttendanceDashboardRepository dashboardRepository;

    @BeforeAll
    void createReportingTables() {
        for (String ddl : REPORTING_SCHEMA) {
            jdbc.execute(ddl);
        }
    }

    @BeforeEach
    void seedCrossCompanyScopesAndFacts() {
        seedCompanyBOrganizationsAndEmployees();
        seedPrincipalScopes();
        seedProjectionsAndFacts();
    }

    @Test
    void reportAndDashboardApplyIndependentCrossCompanyOrganizationScopes() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                CapabilityCodes.ATTENDANCE_REPORT_READ));
        CurrentPrincipalProvider principal = () -> PRINCIPAL;
        var reportService = new AttendanceReportQueryService(
                capabilities, principal, reportRepository, CLOCK);
        var dashboardService = new AttendanceDashboardService(
                capabilities, principal, dashboardRepository, CLOCK);

        assertThat(reportService.companies(PERIOD))
                .extracting(AttendanceReportSourceRepository.CompanyOption::companyId)
                .containsExactlyInAnyOrder(COMPANY_A, COMPANY_B);

        AttendanceReportPage companyA = reportService.query(
                ReportType.ATTENDANCE_DETAIL,
                PERIOD,
                COMPANY_A,
                null,
                null,
                null,
                0,
                20);
        AttendanceReportPage companyB = reportService.query(
                ReportType.ATTENDANCE_DETAIL,
                PERIOD,
                COMPANY_B,
                null,
                null,
                null,
                0,
                20);

        assertThat(companyA.rows())
                .extracting(row -> row.rowReference())
                .containsExactly("attendance:7f000000-0000-0000-0000-000000000001");
        assertThat(companyB.rows())
                .extracting(row -> row.rowReference())
                .containsExactly("attendance:7f000000-0000-0000-0000-000000000003");
        assertThat(companyA.totalRows() + companyB.totalRows()).isEqualTo(2);

        assertThat(reportService.query(
                        ReportType.ATTENDANCE_DETAIL,
                        PERIOD,
                        COMPANY_A,
                        UNAUTHORIZED_ORGANIZATION_A,
                        null,
                        null,
                        0,
                        20)
                .rows()).isEmpty();
        assertThat(reportService.query(
                        ReportType.ATTENDANCE_DETAIL,
                        PERIOD,
                        COMPANY_B,
                        UNAUTHORIZED_ORGANIZATION_B,
                        null,
                        null,
                        0,
                        20)
                .rows()).isEmpty();

        var dashboardA = (AttendanceDashboardService.Ready)
                dashboardService.query(COMPANY_A);
        var dashboardB = (AttendanceDashboardService.Ready)
                dashboardService.query(COMPANY_B);
        assertAuthorizedDashboard(dashboardA.snapshot(), "LEGACY-b0000000-0000-0000-0000-000000000002");
        assertAuthorizedDashboard(dashboardB.snapshot(), "B-CHILD-001");
        assertThat(dashboardA.snapshot().scope().authorizationDigest())
                .isEqualTo(companyA.scope().authorizationDigest());
        assertThat(dashboardB.snapshot().scope().authorizationDigest())
                .isEqualTo(companyB.scope().authorizationDigest());
    }

    @Test
    void persistedAttendanceRateUsesDayFieldsInsteadOfMinuteFields() {
        jdbc.update(
                """
                UPDATE attendance_report_daily_fact
                SET confirmed_scheduled_work_minutes = 240,
                    actual_work_minutes = 240
                WHERE attendance_report_daily_fact_id = ?
                """,
                "7f000000-0000-0000-0000-000000000001");
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        when(capabilities.currentCapabilities()).thenReturn(
                Set.of(CapabilityCodes.ATTENDANCE_REPORT_READ));
        var service = new AttendanceReportQueryService(
                capabilities,
                () -> PRINCIPAL,
                reportRepository,
                CLOCK);

        AttendanceReportPage report = service.query(
                ReportType.ATTENDANCE_RATE,
                PERIOD,
                COMPANY_A,
                ORGANIZATION_A,
                null,
                null,
                0,
                20);

        assertThat(report.rows()).singleElement().satisfies(row ->
                assertThat(row.values())
                        .containsEntry(
                                ReportField.SCHEDULED_ATTENDANCE_DAYS, "1")
                        .containsEntry(
                                ReportField.ACTUAL_ATTENDANCE_DAYS, "1")
                        .containsEntry(ReportField.ATTENDANCE_RATE, "100.00"));
    }

    @Test
    void persistedSevereLateAbsenceAndMissingPunchRemainDistinctInReports() {
        jdbc.update(
                """
                UPDATE attendance_report_daily_fact
                SET confirmed_scheduled_work_minutes = 0,
                    absence_minutes = 480, actual_work_minutes = 0,
                    actual_attendance_days = 0, late_minutes = 0,
                    penalized_late_minutes = 0, missing_punch_count = 2
                WHERE attendance_report_daily_fact_id = ?
                """,
                "7f000000-0000-0000-0000-000000000001");
        jdbc.update(
                """
                UPDATE attendance_report_exception_fact
                SET exception_type = 'LATE_CONVERTED_TO_ABSENCE',
                    exception_minutes = 480
                WHERE attendance_report_exception_fact_id = ?
                """,
                "7e000000-0000-0000-0000-000000000001");
        AttendanceReportQueryService service = readableReportService();

        AttendanceReportPage detail = query(service, ReportType.ATTENDANCE_DETAIL);
        assertThat(detail.rows()).singleElement().satisfies(row ->
                assertThat(row.values())
                        .containsEntry(ReportField.ABSENCE_HOURS, "8.00")
                        .containsEntry(ReportField.LATE_MINUTES, "0")
                        .containsEntry(ReportField.MISSING_PUNCH_COUNT, "2"));
        assertThat(query(service, ReportType.ATTENDANCE_RATE).rows())
                .singleElement().satisfies(row -> assertThat(row.values())
                        .containsEntry(ReportField.SCHEDULED_ATTENDANCE_DAYS, "1")
                        .containsEntry(ReportField.ACTUAL_ATTENDANCE_DAYS, "0")
                        .containsEntry(ReportField.ATTENDANCE_RATE, "0.00"));
        assertThat(query(service, ReportType.MISSED_PUNCH).rows())
                .singleElement().satisfies(row -> assertThat(row.values())
                        .containsEntry(ReportField.MISSING_PUNCH_COUNT, "2"));
        assertThat(query(service, ReportType.LATE).rows()).isEmpty();
        assertThat(query(service, ReportType.EXCEPTIONS).rows())
                .singleElement().satisfies(row -> assertThat(row.values())
                        .containsEntry(ReportField.EXCEPTION_TYPE,
                                "LATE_CONVERTED_TO_ABSENCE")
                        .containsEntry(ReportField.EXCEPTION_MINUTES, "480"));
    }

    @Test
    void persistedLeaveTypesUseDayRateAndExposeSickLeaveSeparately() {
        jdbc.update(
                "UPDATE attendance_report_daily_fact SET leave_type = 'SICK',"
                        + " leave_or_time_off_minutes = 480,"
                        + " confirmed_scheduled_work_minutes = 0,"
                        + " actual_work_minutes = 0"
                        + " WHERE attendance_report_daily_fact_id = ?",
                "7f000000-0000-0000-0000-000000000001");
        insertDepartmentAttendanceDay(
                "leave-personal", "b0000000-0000-0000-0000-000000000002",
                "e0000000-0000-0000-0000-000000000002", 6, 1, 0);
        jdbc.update("UPDATE attendance_report_daily_fact SET leave_type = 'PERSONAL',"
                        + " leave_or_time_off_minutes = 480, absence_minutes = 0"
                        + " WHERE attendance_report_daily_fact_id = ?",
                "leave-personal");
        insertDepartmentAttendanceDay(
                "leave-annual", "b0000000-0000-0000-0000-000000000002",
                "e0000000-0000-0000-0000-000000000002", 7, 1, 1);
        jdbc.update("UPDATE attendance_report_daily_fact SET leave_type = 'ANNUAL',"
                        + " leave_or_time_off_minutes = 480,"
                        + " confirmed_scheduled_work_minutes = 0,"
                        + " actual_work_minutes = 0"
                        + " WHERE attendance_report_daily_fact_id = ?",
                "leave-annual");
        AttendanceReportQueryService service = readableReportService();

        assertThat(query(service, ReportType.ATTENDANCE_RATE).rows())
                .singleElement().satisfies(row -> assertThat(row.values())
                        .containsEntry(ReportField.SCHEDULED_ATTENDANCE_DAYS, "3")
                        .containsEntry(ReportField.ACTUAL_ATTENDANCE_DAYS, "2")
                        .containsEntry(ReportField.SICK_LEAVE_DAYS, "1")
                        .containsEntry(ReportField.ATTENDANCE_RATE, "66.67"));
        assertThat(reportRepository.listAuthorizedEmployeeSickLeaveDays(
                PRINCIPAL, CapabilityCodes.ATTENDANCE_REPORT_READ,
                new ReportFilter(PERIOD, COMPANY_A, ORGANIZATION_A, null, null),
                AUTHORIZATION_TIME)).singleElement().satisfies(value ->
                        assertThat(value.sickLeaveDays()).isEqualTo(1));
        assertThat(jdbc.queryForObject(
                "SELECT leave_type FROM attendance_report_daily_fact"
                        + " WHERE attendance_report_daily_fact_id = ?",
                String.class, "leave-annual")).isEqualTo("ANNUAL");
        insertOaLeaveFact("leave-oa-sick", "SICK_LEAVE", 480);
        insertOaLeaveFact("leave-oa-annual", "ANNUAL_LEAVE", 480);
        assertThat(query(service, ReportType.LEAVE).rows())
                .extracting(row -> row.values().get(ReportField.DOCUMENT_TYPE))
                .containsExactlyInAnyOrder("SICK_LEAVE", "ANNUAL_LEAVE");
    }

    @Test
    void persistedOvertimeClassificationUsesOnlyTheFourNewMetrics() {
        jdbc.update(
                """
                UPDATE attendance_report_daily_fact
                SET recognized_overtime_minutes = 210,
                    paid_overtime_minutes = 120,
                    compensatory_overtime_minutes = 60,
                    voluntary_overtime_minutes = 30,
                    total_overtime_minutes = 210,
                    actual_work_minutes = 690,
                    unexcused_overtime_minutes = 999,
                    excused_overtime_minutes = 999
                WHERE attendance_report_daily_fact_id = ?
                """,
                "7f000000-0000-0000-0000-000000000001");

        assertThat(query(readableReportService(), ReportType.OVERTIME).rows())
                .singleElement().satisfies(row -> assertThat(row.values())
                        .containsEntry(ReportField.PAID_OVERTIME_HOURS, "2.00")
                        .containsEntry(ReportField.COMPENSATORY_OVERTIME_HOURS, "1.00")
                        .containsEntry(ReportField.VOLUNTARY_OVERTIME_HOURS, "0.50")
                        .containsEntry(ReportField.TOTAL_OVERTIME_HOURS, "3.50")
                        .containsEntry(ReportField.RECOGNIZED_OVERTIME_HOURS, "3.50"));
    }

    @Test
    void persistedExemptionAndOutingResultsKeepScheduledDaysAndPunchOutcomes() {
        insertDepartmentAttendanceDay(
                "exempt-attended", "b0000000-0000-0000-0000-000000000002",
                "e0000000-0000-0000-0000-000000000002", 6, 1, 1);
        insertDepartmentAttendanceDay(
                "outing-no-punch", "b0000000-0000-0000-0000-000000000002",
                "e0000000-0000-0000-0000-000000000002", 7, 1, 0);
        jdbc.update("UPDATE attendance_report_daily_fact SET missing_punch_count = 2"
                        + " WHERE attendance_report_daily_fact_id = ?",
                "outing-no-punch");
        AttendanceReportQueryService service = readableReportService();

        assertThat(query(service, ReportType.ATTENDANCE_RATE).rows())
                .singleElement().satisfies(row -> assertThat(row.values())
                        .containsEntry(ReportField.SCHEDULED_ATTENDANCE_DAYS, "3")
                        .containsEntry(ReportField.ACTUAL_ATTENDANCE_DAYS, "2")
                        .containsEntry(ReportField.ATTENDANCE_RATE, "66.67"));
        assertThat(query(service, ReportType.MISSED_PUNCH).rows())
                .singleElement().satisfies(row -> assertThat(row.values())
                        .containsEntry(ReportField.MISSING_PUNCH_COUNT, "2"));
    }

    @Test
    void departmentRateIsDayWeightedExcludesZeroScheduleAndStaysInCompany() {
        jdbc.update(
                "DELETE FROM attendance_report_daily_fact"
                        + " WHERE attendance_report_projection_id = ?"
                        + " AND company_id = ?",
                PROJECTION_A,
                COMPANY_A);
        jdbc.update(
                "UPDATE employment_assignment"
                        + " SET organization_id = ?, effective_to = NULL"
                        + " WHERE employee_id IN (?, ?)",
                ORGANIZATION_A,
                "b0000000-0000-0000-0000-000000000001",
                "b0000000-0000-0000-0000-000000000003");

        for (int day = 1; day <= 20; day++) {
            insertDepartmentAttendanceDay(
                    "rate-bob-" + day,
                    "b0000000-0000-0000-0000-000000000002",
                    "e0000000-0000-0000-0000-000000000002",
                    day,
                    1,
                    1);
        }
        for (int day = 1; day <= 22; day++) {
            insertDepartmentAttendanceDay(
                    "rate-carol-" + day,
                    "b0000000-0000-0000-0000-000000000003",
                    "e0000000-0000-0000-0000-000000000003",
                    day,
                    1,
                    day <= 20 ? 1 : 0);
        }
        for (int day = 1; day <= 3; day++) {
            insertDepartmentAttendanceDay(
                    "rate-alice-" + day,
                    "b0000000-0000-0000-0000-000000000001",
                    "e0000000-0000-0000-0000-000000000001",
                    day,
                    0,
                    1);
        }
        jdbc.update(
                "UPDATE attendance_report_daily_fact SET leave_type = 'SICK'"
                        + " WHERE attendance_report_daily_fact_id IN (?, ?)",
                "rate-bob-1",
                "rate-bob-2");

        List<DepartmentAttendanceRate> rates =
                reportRepository.listAuthorizedDepartmentAttendanceRates(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        new ReportFilter(
                                PERIOD, COMPANY_A, null, null, null),
                        AUTHORIZATION_TIME);

        assertThat(rates).singleElement().satisfies(rate -> {
            assertThat(rate.companyId()).isEqualTo(COMPANY_A);
            assertThat(rate.organizationId()).isEqualTo(ORGANIZATION_A);
            assertThat(rate.actualAttendanceDays()).isEqualTo(40);
            assertThat(rate.scheduledAttendanceDays()).isEqualTo(42);
            assertThat(rate.sickLeaveDays()).isEqualTo(2);
            assertThat(rate.attendanceRate())
                    .isEqualByComparingTo(new BigDecimal("95.24"));
        });
        assertThat(reportRepository.listAuthorizedEmployeeSickLeaveDays(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                new ReportFilter(PERIOD, COMPANY_A, null, null, null),
                AUTHORIZATION_TIME)).singleElement().satisfies(sickLeave -> {
                    assertThat(sickLeave.employeeId()).isEqualTo(
                            "b0000000-0000-0000-0000-000000000002");
                    assertThat(sickLeave.sickLeaveDays()).isEqualTo(2);
                });
    }

    @Test
    void augustFifteenthTransferSplitsPeriodsWithoutDuplicateDays() {
        String employeeId =
                "b0000000-0000-0000-0000-000000000002";
        String employeeVersionId =
                "e0000000-0000-0000-0000-000000000002";
        String departmentAAssignment =
                "c0000000-0000-0000-0000-000000000002";
        String departmentBAssignment =
                "c0000000-0000-0000-0000-000000000020";

        jdbc.update(
                "UPDATE auth_data_scope"
                        + " SET scope_type = 'COMPANY', company_id = ?,"
                        + " organization_id = NULL, include_descendants = FALSE"
                        + " WHERE scope_id = ?",
                COMPANY_A,
                SCOPE_A);
        jdbc.update(
                "DELETE FROM attendance_report_daily_fact"
                        + " WHERE attendance_report_projection_id = ?"
                        + " AND company_id = ?",
                PROJECTION_A,
                COMPANY_A);
        jdbc.update(
                "UPDATE employment_assignment"
                        + " SET effective_from = ?, effective_to = ?"
                        + " WHERE assignment_id = ?",
                Timestamp.valueOf("2026-08-01 00:00:00"),
                Timestamp.valueOf("2026-08-15 00:00:00"),
                departmentAAssignment);
        jdbc.update(
                """
                INSERT INTO employment_assignment (
                    assignment_id, employment_period_id, employee_id,
                    organization_id, effective_from, effective_to
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                departmentBAssignment,
                departmentBAssignment,
                employeeId,
                UNAUTHORIZED_ORGANIZATION_A,
                Timestamp.valueOf("2026-08-15 00:00:00"),
                Timestamp.valueOf("2026-09-01 00:00:00"));

        for (int day = 1; day <= 14; day++) {
            int attendanceDay = day <= 10 ? 1 : 0;
            insertTransferredAttendanceDay(
                    "transfer-a-" + day,
                    employeeId,
                    employeeVersionId,
                    departmentAAssignment,
                    ORGANIZATION_A,
                    "50000000-0000-0000-0000-000000000002",
                    day,
                    attendanceDay);
        }
        for (int day = 15; day <= 31; day++) {
            int attendanceDay = day <= 26 ? 1 : 0;
            insertTransferredAttendanceDay(
                    "transfer-b-" + day,
                    employeeId,
                    employeeVersionId,
                    departmentBAssignment,
                    UNAUTHORIZED_ORGANIZATION_A,
                    "50000000-0000-0000-0000-000000000003",
                    day,
                    attendanceDay);
        }

        List<EmployeeDepartmentAttendancePeriod> periods =
                reportRepository
                        .listAuthorizedEmployeeDepartmentAttendancePeriods(
                                PRINCIPAL,
                                CapabilityCodes.ATTENDANCE_REPORT_READ,
                                new ReportFilter(
                                        PERIOD,
                                        COMPANY_A,
                                        null,
                                        employeeId,
                                        null),
                                AUTHORIZATION_TIME);

        assertThat(periods).hasSize(2);
        EmployeeDepartmentAttendancePeriod departmentA = periods.get(0);
        assertThat(departmentA.employeeId()).isEqualTo(employeeId);
        assertThat(departmentA.employeeName()).isEqualTo("Bob");
        assertThat(departmentA.organizationId()).isEqualTo(ORGANIZATION_A);
        assertThat(departmentA.organizationName()).isEqualTo("制造中心");
        assertThat(departmentA.periodStart()).isEqualTo(PERIOD.atDay(1));
        assertThat(departmentA.periodEnd()).isEqualTo(PERIOD.atDay(14));
        assertThat(departmentA.actualAttendanceDays()).isEqualTo(10);
        assertThat(departmentA.scheduledAttendanceDays()).isEqualTo(10);
        assertThat(departmentA.attendanceRate())
                .isEqualByComparingTo("100.00");

        EmployeeDepartmentAttendancePeriod departmentB = periods.get(1);
        assertThat(departmentB.organizationId())
                .isEqualTo(UNAUTHORIZED_ORGANIZATION_A);
        assertThat(departmentB.organizationName()).isEqualTo("封装部");
        assertThat(departmentB.periodStart()).isEqualTo(PERIOD.atDay(15));
        assertThat(departmentB.periodEnd()).isEqualTo(PERIOD.atEndOfMonth());
        assertThat(departmentB.actualAttendanceDays()).isEqualTo(12);
        assertThat(departmentB.scheduledAttendanceDays()).isEqualTo(12);
        assertThat(departmentB.attendanceRate())
                .isEqualByComparingTo("100.00");
        assertThat(periods.stream()
                        .mapToLong(EmployeeDepartmentAttendancePeriod
                                ::scheduledAttendanceDays)
                        .sum())
                .isEqualTo(22);
        assertThat(periods.stream()
                        .mapToLong(EmployeeDepartmentAttendancePeriod
                                ::actualAttendanceDays)
                        .sum())
                .isEqualTo(22);

        List<EmployeeDepartmentAttendancePeriod> departmentAOnly =
                reportRepository
                        .listAuthorizedEmployeeDepartmentAttendancePeriods(
                                PRINCIPAL,
                                CapabilityCodes.ATTENDANCE_REPORT_READ,
                                new ReportFilter(
                                        PERIOD,
                                        COMPANY_A,
                                        ORGANIZATION_A,
                                        employeeId,
                                        null),
                                AUTHORIZATION_TIME);
        assertThat(departmentAOnly).containsExactly(departmentA);

        List<DepartmentAttendanceRate> departmentRates =
                reportRepository.listAuthorizedDepartmentAttendanceRates(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        new ReportFilter(
                                PERIOD, COMPANY_A, null, null, null),
                        AUTHORIZATION_TIME);
        assertThat(departmentRates)
                .extracting(
                        DepartmentAttendanceRate::organizationId,
                        DepartmentAttendanceRate::actualAttendanceDays,
                        DepartmentAttendanceRate::scheduledAttendanceDays)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                ORGANIZATION_A, 10L, 10L),
                        org.assertj.core.groups.Tuple.tuple(
                                UNAUTHORIZED_ORGANIZATION_A, 12L, 12L));
    }

    @Test
    void dashboardEmployeeDetailsRequireIntersectingReportScopePerCompany() {
        jdbc.update(
                "DELETE FROM auth_role_capability"
                        + " WHERE role_id = ? AND capability_id = ?",
                ROLE,
                "2f000000-0000-0000-0000-000000000001");
        jdbc.update(
                """
                INSERT INTO auth_role (
                    role_id, role_code, role_name, permission_domain
                ) VALUES (
                    '1f000000-0000-0000-0000-000000000002',
                    'REPORT_DISJOINT_SCOPE_TEST',
                    '报表独立范围集成测试', 'ATTENDANCE_REPORT'
                )
                """);
        jdbc.update(
                """
                INSERT INTO auth_role_capability (role_id, capability_id)
                VALUES (
                    '1f000000-0000-0000-0000-000000000002',
                    '2f000000-0000-0000-0000-000000000001'
                )
                """);
        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, company_id, organization_id,
                    include_descendants, valid_from, valid_to
                ) VALUES
                    ('9f000000-0000-0000-0000-000000000003',
                        'ORGANIZATION', NULL, ?, FALSE, ?, NULL),
                    ('9f000000-0000-0000-0000-000000000004',
                        'COMPANY', ?, NULL, FALSE, ?, NULL)
                """,
                UNAUTHORIZED_ORGANIZATION_A,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                COMPANY_B,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to
                ) VALUES
                    ('af000000-0000-0000-0000-000000000003', ?,
                        '1f000000-0000-0000-0000-000000000002',
                        '9f000000-0000-0000-0000-000000000003', ?, NULL),
                    ('af000000-0000-0000-0000-000000000004', ?,
                        '1f000000-0000-0000-0000-000000000002',
                        '9f000000-0000-0000-0000-000000000004', ?, NULL)
                """,
                PRINCIPAL,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                PRINCIPAL,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        CurrentPrincipalProvider principal = () -> PRINCIPAL;
        var service = new AttendanceDashboardService(
                capabilities, principal, dashboardRepository, CLOCK);

        var companyA = (AttendanceDashboardService.Ready)
                service.query(COMPANY_A);
        var companyB = (AttendanceDashboardService.Ready)
                service.query(COMPANY_B);

        assertThat(companyA.snapshot().summary().unresolvedCount()).isOne();
        assertThat(companyA.snapshot().exceptions()).isEmpty();
        assertThat(companyA.allowedActions()).isEmpty();
        assertAuthorizedDashboard(
                companyB.snapshot(), "B-CHILD-001");
        assertThat(companyB.allowedActions())
                .containsExactly("DASHBOARD_DRILL_DOWN");
    }

    @Test
    void exportAuthorizationUsesCurrentAssignmentForHistoricalOrganizationFacts() {
        String transferredEmployee =
                "b0000000-0000-0000-0000-000000000002";
        jdbc.update(
                "UPDATE auth_data_scope SET organization_id = ?"
                        + " WHERE scope_id = ?",
                UNAUTHORIZED_ORGANIZATION_A,
                SCOPE_A);
        jdbc.update(
                "UPDATE employment_assignment SET organization_id = ?"
                        + " WHERE employee_id = ?"
                        + " AND version_valid_to IS NULL",
                UNAUTHORIZED_ORGANIZATION_A,
                transferredEmployee);
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE));
        CurrentPrincipalProvider principal = () -> PRINCIPAL;
        var queryService = new AttendanceReportQueryService(
                capabilities, principal, reportRepository, CLOCK);
        AttendanceReportExportStore store = mock(AttendanceReportExportStore.class);
        AttendanceReportExportEncoder encoder =
                mock(AttendanceReportExportEncoder.class);
        when(encoder.encode(any(), any(ExportContext.class))).thenReturn(
                new EncodedExport(
                        "application/octet-stream",
                        "xlsx",
                        new byte[] {1, 2, 3}));
        var exportService = new AttendanceReportExportService(
                capabilities,
                principal,
                reportRepository,
                store,
                encoder,
                new AttendanceReportCalculator(),
                new AttendanceReportExportTransactions(),
                mock(AuditService.class),
                CLOCK,
                Duration.ofHours(24));

        AttendanceReportPage historicalOrganization = queryService.query(
                ReportType.ATTENDANCE_DETAIL,
                PERIOD,
                COMPANY_A,
                ORGANIZATION_A,
                null,
                null,
                0,
                20);

        assertThat(historicalOrganization.totalRows()).isOne();
        assertThat(historicalOrganization.allowedActions())
                .containsExactly(
                        "REPORT_DRILL_DOWN",
                        "REPORT_EXPORT_CREATE");
        exportService.create(
                historicalOrganization.reportType(),
                historicalOrganization.filters(),
                binding(historicalOrganization),
                "调岗历史组织导出");
        verify(store).insert(any(), any(byte[].class));
    }

    @Test
    void exportRequiresFullCreateScopeCoverageIncludingEmptyFilters() {
        jdbc.update(
                "DELETE FROM auth_role_capability"
                        + " WHERE role_id = ? AND capability_id = ?",
                ROLE,
                "2f000000-0000-0000-0000-000000000003");
        jdbc.update(
                """
                INSERT INTO auth_role (
                    role_id, role_code, role_name, permission_domain
                ) VALUES (
                    '1f000000-0000-0000-0000-000000000003',
                    'EXPORT_NARROW_SCOPE_TEST',
                    '导出狭范围集成测试', 'ATTENDANCE_REPORT'
                )
                """);
        jdbc.update(
                """
                INSERT INTO auth_role_capability (role_id, capability_id)
                VALUES (
                    '1f000000-0000-0000-0000-000000000003',
                    '2f000000-0000-0000-0000-000000000003'
                )
                """);
        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, company_id, organization_id,
                    include_descendants, valid_from, valid_to
                ) VALUES (
                    '9f000000-0000-0000-0000-000000000005',
                    'ORGANIZATION', NULL, ?, FALSE, ?, NULL
                )
                """,
                UNAUTHORIZED_ORGANIZATION_A,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to
                ) VALUES
                    ('af000000-0000-0000-0000-000000000005', ?,
                        '1f000000-0000-0000-0000-000000000003', ?, ?, NULL),
                    ('af000000-0000-0000-0000-000000000006', ?, ?,
                        '9f000000-0000-0000-0000-000000000005', ?, NULL)
                """,
                PRINCIPAL,
                SCOPE_A,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                PRINCIPAL,
                ROLE,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE));
        CurrentPrincipalProvider principal = () -> PRINCIPAL;
        var queryService = new AttendanceReportQueryService(
                capabilities, principal, reportRepository, CLOCK);
        AttendanceReportExportStore store = mock(AttendanceReportExportStore.class);
        AttendanceReportExportEncoder encoder =
                mock(AttendanceReportExportEncoder.class);
        var exportService = new AttendanceReportExportService(
                capabilities,
                principal,
                reportRepository,
                store,
                encoder,
                new AttendanceReportCalculator(),
                new AttendanceReportExportTransactions(),
                mock(AuditService.class),
                CLOCK,
                Duration.ofHours(24));

        AttendanceReportPage fullRead = queryService.query(
                ReportType.ATTENDANCE_DETAIL,
                PERIOD,
                COMPANY_A,
                null,
                null,
                null,
                0,
                20);

        assertThat(fullRead.totalRows()).isEqualTo(2);
        assertThat(fullRead.allowedActions())
                .containsExactly("REPORT_DRILL_DOWN");
        assertExportScopeDenied(exportService, fullRead);

        String employeeOutsideCreateScope =
                "b0000000-0000-0000-0000-000000000003";
        jdbc.update(
                "DELETE FROM attendance_report_daily_fact"
                        + " WHERE employee_id = ?",
                employeeOutsideCreateScope);
        jdbc.update(
                "DELETE FROM attendance_report_exception_fact"
                        + " WHERE employee_id = ?",
                employeeOutsideCreateScope);
        AttendanceReportPage emptyEmployee = queryService.query(
                ReportType.ATTENDANCE_DETAIL,
                PERIOD,
                COMPANY_A,
                null,
                employeeOutsideCreateScope,
                null,
                0,
                20);
        AttendanceReportPage emptyOrganization = queryService.query(
                ReportType.ATTENDANCE_DETAIL,
                PERIOD,
                COMPANY_A,
                UNAUTHORIZED_ORGANIZATION_A,
                null,
                null,
                0,
                20);

        for (AttendanceReportPage empty :
                List.of(emptyEmployee, emptyOrganization)) {
            assertThat(empty.totalRows()).isZero();
            assertThat(empty.allowedActions())
                    .containsExactly("REPORT_DRILL_DOWN");
            assertExportScopeDenied(exportService, empty);
        }
        verify(store, never()).insert(any(), any());
        verifyNoInteractions(encoder);
    }

    @Test
    void exportStatusFailsClosedAfterThePersistedScopeIsRevoked() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportExportStore store = mock(AttendanceReportExportStore.class);
        AttendanceReportExportEncoder encoder =
                mock(AttendanceReportExportEncoder.class);
        AuditService audit = mock(AuditService.class);
        CurrentPrincipalProvider principal = () -> PRINCIPAL;
        when(encoder.encode(any(), any(ExportContext.class))).thenReturn(
                new EncodedExport(
                        "application/octet-stream",
                        "xlsx",
                        new byte[] {1, 2, 3}));
        var service = new AttendanceReportExportService(
                capabilities,
                principal,
                reportRepository,
                store,
                encoder,
                new AttendanceReportCalculator(),
                new AttendanceReportExportTransactions(),
                audit,
                CLOCK,
                Duration.ofHours(24));
        ReportFilter exportFilter = new ReportFilter(
                PERIOD, COMPANY_A, null, null, null);
        var exportSnapshot = reportRepository.loadAuthorizedSnapshot(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        exportFilter,
                        AUTHORIZATION_TIME)
                .orElseThrow();
        String expectedScopeDigest =
                exportSnapshot.scope().authorizationDigest();
        var exportDataSet = new AttendanceReportCalculator().calculate(
                ReportType.ATTENDANCE_DETAIL, exportSnapshot);
        String exportFingerprint = AttendanceReportQueryService.fingerprint(
                ReportType.ATTENDANCE_DETAIL,
                exportSnapshot.filter(),
                exportSnapshot.projectionVersion(),
                exportSnapshot.scope().authorizationDigest(),
                exportDataSet.calculationFormulaVersion());
        var exportBinding = new RequestedExportBinding(
                exportSnapshot.projectionVersion(),
                exportFingerprint,
                exportSnapshot.scope().reference(),
                exportSnapshot.scope().reference(),
                exportDataSet.exportAllowlist().stream()
                        .map(field -> field.key())
                        .toList());
        ArgumentCaptor<ExportJob> jobCaptor =
                ArgumentCaptor.forClass(ExportJob.class);

        service.create(
                ReportType.ATTENDANCE_DETAIL,
                exportFilter,
                exportBinding,
                "跨公司范围导出复核");

        verify(store).insert(jobCaptor.capture(), any(byte[].class));
        ExportJob persisted = jobCaptor.getValue();
        assertThat(persisted.authorizationDigest())
                .isEqualTo(expectedScopeDigest);
        when(store.findOwnedJob(persisted.exportId(), PRINCIPAL))
                .thenReturn(Optional.of(persisted));
        jdbc.update(
                "UPDATE auth_principal_role_assignment SET valid_to = ? WHERE assignment_id = ?",
                Timestamp.from(AUTHORIZATION_TIME),
                ASSIGNMENT_A);

        assertThatThrownBy(() -> service.status(persisted.exportId()))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("RESOURCE_NOT_AVAILABLE");
        verify(audit).recordFailure(
                PRINCIPAL,
                "ATTENDANCE_REPORT_EXPORT_STATUS_DENIED",
                "ATTENDANCE_REPORT_EXPORT",
                persisted.exportId(),
                "DENIED",
                "AUTHORIZATION_OR_SOURCE_CHANGED");
    }

    private static void assertAuthorizedDashboard(
            DashboardSnapshot snapshot,
            String expectedEmployeeNumber) {
        assertThat(snapshot.summary().unresolvedCount()).isOne();
        assertThat(snapshot.summary().affectedEmployeeCount()).isOne();
        assertThat(snapshot.exceptions())
                .extracting(AttendanceDashboardRepository.ExceptionItem::employeeNumber)
                .containsExactly(expectedEmployeeNumber);
    }

    private static void assertExportScopeDenied(
            AttendanceReportExportService service,
            AttendanceReportPage page) {
        RequestedExportBinding binding = binding(page);

        assertThatThrownBy(() -> service.create(
                page.reportType(),
                page.filters(),
                binding,
                "范围分离导出测试"))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("RESOURCE_NOT_AVAILABLE");
    }

    private static RequestedExportBinding binding(
            AttendanceReportPage page) {
        return new RequestedExportBinding(
                page.projectionVersion(),
                page.queryFingerprint(),
                page.scope().reference(),
                page.scope().reference(),
                page.exportAllowlist().stream()
                        .map(field -> field.key())
                        .toList());
    }

    private AttendanceReportQueryService readableReportService() {
        CurrentCapabilityService capabilities = mock(CurrentCapabilityService.class);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_REPORT_READ));
        return new AttendanceReportQueryService(
                capabilities, () -> PRINCIPAL, reportRepository, CLOCK);
    }

    private static AttendanceReportPage query(
            AttendanceReportQueryService service, ReportType type) {
        return service.query(
                type, PERIOD, COMPANY_A, ORGANIZATION_A, null, null, 0, 20);
    }

    private void seedCompanyBOrganizationsAndEmployees() {
        jdbc.update(
                """
                INSERT INTO organization_identity (
                    organization_id, company_id, identity_status
                ) VALUES
                    (?, ?, 'ACTIVE'),
                    (?, ?, 'ACTIVE'),
                    (?, ?, 'ACTIVE')
                """,
                ORGANIZATION_B, COMPANY_B,
                CHILD_ORGANIZATION_B, COMPANY_B,
                UNAUTHORIZED_ORGANIZATION_B, COMPANY_B);
        jdbc.update(
                """
                INSERT INTO organization_version (
                    organization_version_id, organization_id,
                    parent_organization_id, code, name, org_type,
                    effective_from, effective_to
                ) VALUES
                    ('5f000000-0000-0000-0000-000000000001', ?,
                        '40000000-0000-0000-0000-000000000004',
                        'B-Y', '公司B部门Y', 'DEPARTMENT', ?, NULL),
                    ('5f000000-0000-0000-0000-000000000002', ?, ?,
                        'B-Y-CHILD', '公司B部门Y下级', 'TEAM', ?, NULL),
                    ('5f000000-0000-0000-0000-000000000003', ?,
                        '40000000-0000-0000-0000-000000000004',
                        'B-OTHER', '公司B未授权部门', 'DEPARTMENT', ?, NULL)
                """,
                ORGANIZATION_B,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                CHILD_ORGANIZATION_B,
                ORGANIZATION_B,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                UNAUTHORIZED_ORGANIZATION_B,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
        jdbc.update(
                """
                INSERT INTO organization_current_projection (
                    organization_id, current_version_id
                ) VALUES
                    (?, '5f000000-0000-0000-0000-000000000001'),
                    (?, '5f000000-0000-0000-0000-000000000002'),
                    (?, '5f000000-0000-0000-0000-000000000003')
                """,
                ORGANIZATION_B,
                CHILD_ORGANIZATION_B,
                UNAUTHORIZED_ORGANIZATION_B);
        jdbc.update(
                """
                INSERT INTO organization_current_closure (
                    ancestor_organization_id, descendant_organization_id, depth
                ) VALUES
                    (?, ?, 0), (?, ?, 1), (?, ?, 0), (?, ?, 0)
                """,
                ORGANIZATION_B, ORGANIZATION_B,
                ORGANIZATION_B, CHILD_ORGANIZATION_B,
                CHILD_ORGANIZATION_B, CHILD_ORGANIZATION_B,
                UNAUTHORIZED_ORGANIZATION_B, UNAUTHORIZED_ORGANIZATION_B);
        jdbc.update(
                """
                INSERT INTO employee (
                    employee_id, company_id, display_name,
                    employment_status, employee_number
                ) VALUES
                    ('bf000000-0000-0000-0000-000000000001', ?,
                        '公司B授权员工', 'ACTIVE', 'B-CHILD-001'),
                    ('bf000000-0000-0000-0000-000000000002', ?,
                        '公司B未授权员工', 'ACTIVE', 'B-OTHER-001')
                """,
                COMPANY_B,
                COMPANY_B);
        jdbc.update(
                """
                INSERT INTO employee_version (
                    employee_version_id, employee_id, employee_number,
                    display_name, status, effective_from, source_authority,
                    change_reason, created_at
                ) VALUES
                    ('ef000000-0000-0000-0000-000000000001',
                        'bf000000-0000-0000-0000-000000000001',
                        'B-CHILD-001', '公司B授权员工', 'ACTIVE', ?,
                        'LOCAL', '报表范围集成测试', ?),
                    ('ef000000-0000-0000-0000-000000000002',
                        'bf000000-0000-0000-0000-000000000002',
                        'B-OTHER-001', '公司B未授权员工', 'ACTIVE', ?,
                        'LOCAL', '报表范围集成测试', ?)
                """,
                Date.valueOf("2020-01-01"),
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                Date.valueOf("2020-01-01"),
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
        jdbc.update(
                """
                INSERT INTO employment_assignment (
                    assignment_id, employment_period_id, employee_id,
                    organization_id, effective_from
                ) VALUES
                    ('cf000000-0000-0000-0000-000000000001',
                        'cf000000-0000-0000-0000-000000000001',
                        'bf000000-0000-0000-0000-000000000001', ?, ?),
                    ('cf000000-0000-0000-0000-000000000002',
                        'cf000000-0000-0000-0000-000000000002',
                        'bf000000-0000-0000-0000-000000000002', ?, ?)
                """,
                CHILD_ORGANIZATION_B,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                UNAUTHORIZED_ORGANIZATION_B,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
    }

    private void seedPrincipalScopes() {
        jdbc.update(
                "INSERT INTO auth_principal (principal_id, status) VALUES (?, 'ACTIVE')",
                PRINCIPAL);
        jdbc.update(
                """
                INSERT INTO auth_role (
                    role_id, role_code, role_name, permission_domain
                ) VALUES (?, 'REPORT_MULTI_SCOPE_TEST',
                    '报表组合范围集成测试', 'ATTENDANCE_REPORT')
                """,
                ROLE);
        jdbc.update(
                """
                INSERT INTO auth_capability (
                    capability_id, capability_code
                ) VALUES
                    ('2f000000-0000-0000-0000-000000000001',
                        'ATTENDANCE_REPORT:READ'),
                    ('2f000000-0000-0000-0000-000000000002',
                        'ATTENDANCE_DASHBOARD:READ'),
                    ('2f000000-0000-0000-0000-000000000003',
                        'ATTENDANCE_REPORT:EXPORT_CREATE')
                """);
        jdbc.update(
                """
                INSERT INTO auth_role_capability (role_id, capability_id)
                VALUES
                    (?, '2f000000-0000-0000-0000-000000000001'),
                    (?, '2f000000-0000-0000-0000-000000000002'),
                    (?, '2f000000-0000-0000-0000-000000000003')
                """,
                ROLE, ROLE, ROLE);
        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, company_id, organization_id,
                    include_descendants, valid_from, valid_to
                ) VALUES
                    (?, 'ORGANIZATION', NULL, ?, FALSE, ?, NULL),
                    ('9f000000-0000-0000-0000-000000000002',
                        'ORGANIZATION', NULL, ?, TRUE, ?, NULL)
                """,
                SCOPE_A,
                ORGANIZATION_A,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                ORGANIZATION_B,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to
                ) VALUES
                    (?, ?, ?, ?, ?, NULL),
                    ('af000000-0000-0000-0000-000000000002', ?, ?,
                        '9f000000-0000-0000-0000-000000000002', ?, NULL)
                """,
                ASSIGNMENT_A,
                PRINCIPAL,
                ROLE,
                SCOPE_A,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                PRINCIPAL,
                ROLE,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
    }

    private void seedProjectionsAndFacts() {
        jdbc.update(
                """
                INSERT INTO attendance_report_projection (
                    attendance_report_projection_id, company_id,
                    period_start, period_end_exclusive, projection_version,
                    period_state, source_versions_json, data_as_of,
                    status, published_at
                ) VALUES
                    (?, ?, ?, ?, 'projection-a-v1', 'OPEN',
                        '["source-a"]', ?, 'PUBLISHED', ?),
                    (?, ?, ?, ?, 'projection-b-v1', 'OPEN',
                        '["source-b"]', ?, 'PUBLISHED', ?)
                """,
                PROJECTION_A,
                COMPANY_A,
                Date.valueOf(PERIOD.atDay(1)),
                Date.valueOf(PERIOD.plusMonths(1).atDay(1)),
                Timestamp.from(AUTHORIZATION_TIME.minusSeconds(60)),
                Timestamp.from(AUTHORIZATION_TIME.minusSeconds(120)),
                PROJECTION_B,
                COMPANY_B,
                Date.valueOf(PERIOD.atDay(1)),
                Date.valueOf(PERIOD.plusMonths(1).atDay(1)),
                Timestamp.from(AUTHORIZATION_TIME.minusSeconds(60)),
                Timestamp.from(AUTHORIZATION_TIME.minusSeconds(120)));
        insertDailyFact(
                "7f000000-0000-0000-0000-000000000001",
                PROJECTION_A,
                COMPANY_A,
                "b0000000-0000-0000-0000-000000000002",
                "e0000000-0000-0000-0000-000000000002",
                ORGANIZATION_A,
                "50000000-0000-0000-0000-000000000002");
        insertDailyFact(
                "7f000000-0000-0000-0000-000000000002",
                PROJECTION_A,
                COMPANY_A,
                "b0000000-0000-0000-0000-000000000003",
                "e0000000-0000-0000-0000-000000000003",
                UNAUTHORIZED_ORGANIZATION_A,
                "50000000-0000-0000-0000-000000000003");
        insertDailyFact(
                "7f000000-0000-0000-0000-000000000003",
                PROJECTION_B,
                COMPANY_B,
                "bf000000-0000-0000-0000-000000000001",
                "ef000000-0000-0000-0000-000000000001",
                CHILD_ORGANIZATION_B,
                "5f000000-0000-0000-0000-000000000002");
        insertDailyFact(
                "7f000000-0000-0000-0000-000000000004",
                PROJECTION_B,
                COMPANY_B,
                "bf000000-0000-0000-0000-000000000002",
                "ef000000-0000-0000-0000-000000000002",
                UNAUTHORIZED_ORGANIZATION_B,
                "5f000000-0000-0000-0000-000000000003");
        insertExceptionFact(
                "7e000000-0000-0000-0000-000000000001",
                "case-a-authorized",
                PROJECTION_A,
                COMPANY_A,
                "b0000000-0000-0000-0000-000000000002",
                "e0000000-0000-0000-0000-000000000002",
                ORGANIZATION_A,
                "50000000-0000-0000-0000-000000000002");
        insertExceptionFact(
                "7e000000-0000-0000-0000-000000000002",
                "case-a-unauthorized",
                PROJECTION_A,
                COMPANY_A,
                "b0000000-0000-0000-0000-000000000003",
                "e0000000-0000-0000-0000-000000000003",
                UNAUTHORIZED_ORGANIZATION_A,
                "50000000-0000-0000-0000-000000000003");
        insertExceptionFact(
                "7e000000-0000-0000-0000-000000000003",
                "case-b-authorized",
                PROJECTION_B,
                COMPANY_B,
                "bf000000-0000-0000-0000-000000000001",
                "ef000000-0000-0000-0000-000000000001",
                CHILD_ORGANIZATION_B,
                "5f000000-0000-0000-0000-000000000002");
        insertExceptionFact(
                "7e000000-0000-0000-0000-000000000004",
                "case-b-unauthorized",
                PROJECTION_B,
                COMPANY_B,
                "bf000000-0000-0000-0000-000000000002",
                "ef000000-0000-0000-0000-000000000002",
                UNAUTHORIZED_ORGANIZATION_B,
                "5f000000-0000-0000-0000-000000000003");
    }

    private void insertDailyFact(
            String factId,
            String projectionId,
            String companyId,
            String employeeId,
            String employeeVersionId,
            String organizationId,
            String organizationVersionId) {
        jdbc.update(
                """
                INSERT INTO attendance_report_daily_fact (
                    attendance_report_daily_fact_id,
                    attendance_report_projection_id, company_id,
                    employee_id, employee_version_id, organization_id,
                    organization_version_id, business_date, day_type,
                    shift_label, scheduled_minutes,
                    confirmed_scheduled_work_minutes,
                    recognized_overtime_minutes, leave_or_time_off_minutes,
                    absence_minutes, actual_work_minutes,
                    scheduled_attendance_days, actual_attendance_days,
                    late_minutes,
                    penalized_late_minutes, early_departure_minutes,
                    missing_punch_count, first_punch_at, last_punch_at,
                    unexcused_overtime_minutes, excused_overtime_minutes,
                    calculation_version_id, result_digest
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'WEEKDAY', '标准班',
                    480, 480, 0, 0, 0, 480, 1, 1, 0, 0, 0, 0,
                    ?, ?, 0, 0, 'calculation-v1', ?)
                """,
                factId,
                projectionId,
                companyId,
                employeeId,
                employeeVersionId,
                organizationId,
                organizationVersionId,
                Date.valueOf(BUSINESS_DATE),
                Timestamp.from(Instant.parse("2026-08-05T01:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-05T09:00:00Z")),
                "result-" + factId);
    }

    private void insertDepartmentAttendanceDay(
            String factId,
            String employeeId,
            String employeeVersionId,
            int dayOfMonth,
            int scheduledAttendanceDays,
            int actualAttendanceDays) {
        long scheduledMinutes = scheduledAttendanceDays * 480L;
        long confirmedMinutes = scheduledAttendanceDays == 1
                        && actualAttendanceDays == 1
                ? 480L
                : 0L;
        long overtimeMinutes = scheduledAttendanceDays == 0
                        && actualAttendanceDays == 1
                ? 480L
                : 0L;
        long absenceMinutes = scheduledAttendanceDays == 1
                        && actualAttendanceDays == 0
                ? 480L
                : 0L;
        jdbc.update(
                """
                INSERT INTO attendance_report_daily_fact (
                    attendance_report_daily_fact_id,
                    attendance_report_projection_id, company_id,
                    employee_id, employee_version_id, organization_id,
                    organization_version_id, business_date, day_type,
                    shift_label, scheduled_minutes,
                    confirmed_scheduled_work_minutes,
                    recognized_overtime_minutes, leave_or_time_off_minutes,
                    absence_minutes, actual_work_minutes,
                    scheduled_attendance_days, actual_attendance_days,
                    late_minutes, penalized_late_minutes,
                    early_departure_minutes, missing_punch_count,
                    first_punch_at, last_punch_at,
                    unexcused_overtime_minutes, excused_overtime_minutes,
                    calculation_version_id, result_digest
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'WEEKDAY', '标准班',
                    ?, ?, ?, 0, ?, ?, ?, ?, 0, 0, 0, 0,
                    NULL, NULL, 0, 0, 'department-rate-v1', ?)
                """,
                factId,
                PROJECTION_A,
                COMPANY_A,
                employeeId,
                employeeVersionId,
                ORGANIZATION_A,
                "50000000-0000-0000-0000-000000000002",
                Date.valueOf(PERIOD.atDay(dayOfMonth)),
                scheduledMinutes,
                confirmedMinutes,
                overtimeMinutes,
                absenceMinutes,
                confirmedMinutes + overtimeMinutes,
                scheduledAttendanceDays,
                actualAttendanceDays,
                "result-" + factId);
    }

    private void insertTransferredAttendanceDay(
            String factId,
            String employeeId,
            String employeeVersionId,
            String employmentAssignmentId,
            String organizationId,
            String organizationVersionId,
            int dayOfMonth,
            int attendanceDay) {
        long attendanceMinutes = attendanceDay * 480L;
        jdbc.update(
                """
                INSERT INTO attendance_report_daily_fact (
                    attendance_report_daily_fact_id,
                    attendance_report_projection_id, company_id,
                    employee_id, employee_version_id, employment_period_id,
                    organization_id, organization_version_id,
                    business_date, day_type, shift_label,
                    scheduled_minutes, confirmed_scheduled_work_minutes,
                    recognized_overtime_minutes, leave_or_time_off_minutes,
                    absence_minutes, actual_work_minutes,
                    scheduled_attendance_days, actual_attendance_days,
                    late_minutes, penalized_late_minutes,
                    early_departure_minutes, missing_punch_count,
                    first_punch_at, last_punch_at,
                    unexcused_overtime_minutes, excused_overtime_minutes,
                    calculation_version_id, result_digest
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'WEEKDAY', '标准班',
                    ?, ?, 0, 0, 0, ?, ?, ?, 0, 0, 0, 0,
                    NULL, NULL, 0, 0, 'transfer-allocation-v1', ?)
                """,
                factId,
                PROJECTION_A,
                COMPANY_A,
                employeeId,
                employeeVersionId,
                employmentAssignmentId,
                organizationId,
                organizationVersionId,
                Date.valueOf(PERIOD.atDay(dayOfMonth)),
                attendanceMinutes,
                attendanceMinutes,
                attendanceMinutes,
                attendanceDay,
                attendanceDay,
                "result-" + factId);
    }

    private void insertOaLeaveFact(
            String factId, String leaveTypeCode, long recognizedMinutes) {
        jdbc.update(
                """
                INSERT INTO attendance_report_oa_fact (
                    attendance_report_oa_fact_id,
                    attendance_report_projection_id, company_id,
                    oa_attendance_document_id, employee_id,
                    employee_version_id, organization_id,
                    organization_version_id, document_type, leave_type_code,
                    interval_start, interval_end, recognized_minutes,
                    source_status, source_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'LEAVE', ?, ?, ?, ?,
                    'APPROVED', 'oa-test-v1')
                """,
                factId,
                PROJECTION_A,
                COMPANY_A,
                "oa-document-" + factId,
                "b0000000-0000-0000-0000-000000000002",
                "e0000000-0000-0000-0000-000000000002",
                ORGANIZATION_A,
                "50000000-0000-0000-0000-000000000002",
                leaveTypeCode,
                Timestamp.from(Instant.parse("2026-08-05T01:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-05T09:00:00Z")),
                recognizedMinutes);
    }

    private void insertExceptionFact(
            String factId,
            String caseId,
            String projectionId,
            String companyId,
            String employeeId,
            String employeeVersionId,
            String organizationId,
            String organizationVersionId) {
        jdbc.update(
                """
                INSERT INTO attendance_report_exception_fact (
                    attendance_report_exception_fact_id,
                    attendance_report_projection_id, company_id,
                    exception_case_id, employee_id, employee_version_id,
                    organization_id, organization_version_id, business_date,
                    exception_type, severity, state, exception_minutes,
                    safe_evidence_summary, calculation_version_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'LATE', 'ERROR',
                    'OPEN', 10, '已脱敏考勤证据', 'calculation-v1')
                """,
                factId,
                projectionId,
                companyId,
                caseId,
                employeeId,
                employeeVersionId,
                organizationId,
                organizationVersionId,
                Date.valueOf(BUSINESS_DATE));
    }

    private static final List<String> REPORTING_SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS attendance_report_projection (
                attendance_report_projection_id VARCHAR(36) PRIMARY KEY,
                company_id VARCHAR(36) NOT NULL,
                period_start DATE NOT NULL,
                period_end_exclusive DATE NOT NULL,
                projection_version VARCHAR(128) NOT NULL,
                formula_catalog_version VARCHAR(128) NOT NULL DEFAULT
                    'FULL_CALCULATION_OVERTIME_CLASSIFICATION_V2',
                period_state VARCHAR(32) NOT NULL,
                source_versions_json CLOB NOT NULL,
                data_as_of TIMESTAMP NOT NULL,
                status VARCHAR(32) NOT NULL,
                published_at TIMESTAMP
            )
            """,
            """
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
            """,
            """
            CREATE TABLE IF NOT EXISTS attendance_report_oa_fact (
                attendance_report_oa_fact_id VARCHAR(36) PRIMARY KEY,
                attendance_report_projection_id VARCHAR(36) NOT NULL,
                company_id VARCHAR(36) NOT NULL,
                oa_attendance_document_id VARCHAR(36) NOT NULL,
                employee_id VARCHAR(36) NOT NULL,
                employee_version_id VARCHAR(36) NOT NULL,
                organization_id VARCHAR(36) NOT NULL,
                organization_version_id VARCHAR(36) NOT NULL,
                document_type VARCHAR(32) NOT NULL,
                leave_type_code VARCHAR(64),
                point_instant TIMESTAMP,
                interval_start TIMESTAMP,
                interval_end TIMESTAMP,
                recognized_minutes BIGINT NOT NULL,
                source_status VARCHAR(32) NOT NULL,
                source_version VARCHAR(128) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS attendance_report_exception_fact (
                attendance_report_exception_fact_id VARCHAR(36) PRIMARY KEY,
                attendance_report_projection_id VARCHAR(36) NOT NULL,
                company_id VARCHAR(36) NOT NULL,
                exception_case_id VARCHAR(128) NOT NULL,
                employee_id VARCHAR(36) NOT NULL,
                employee_version_id VARCHAR(36) NOT NULL,
                organization_id VARCHAR(36) NOT NULL,
                organization_version_id VARCHAR(36) NOT NULL,
                business_date DATE NOT NULL,
                exception_type VARCHAR(64) NOT NULL,
                severity VARCHAR(16) NOT NULL,
                state VARCHAR(32) NOT NULL,
                exception_minutes BIGINT NOT NULL,
                safe_evidence_summary VARCHAR(500) NOT NULL,
                calculation_version_id VARCHAR(128) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS attendance_report_time_account_fact (
                attendance_report_time_account_fact_id VARCHAR(36) PRIMARY KEY,
                attendance_report_projection_id VARCHAR(36) NOT NULL,
                company_id VARCHAR(36) NOT NULL,
                account_id VARCHAR(128) NOT NULL,
                employee_id VARCHAR(36) NOT NULL,
                employee_version_id VARCHAR(36) NOT NULL,
                organization_id VARCHAR(36) NOT NULL,
                organization_version_id VARCHAR(36) NOT NULL,
                account_type VARCHAR(32) NOT NULL,
                opening_hours DECIMAL(16, 2) NOT NULL,
                granted_hours DECIMAL(16, 2) NOT NULL,
                overtime_credit_hours DECIMAL(16, 2) NOT NULL,
                manual_increase_hours DECIMAL(16, 2) NOT NULL,
                used_hours DECIMAL(16, 2) NOT NULL,
                expired_hours DECIMAL(16, 2) NOT NULL,
                returned_hours DECIMAL(16, 2) NOT NULL,
                manual_deduction_hours DECIMAL(16, 2) NOT NULL,
                ledger_version VARCHAR(128) NOT NULL
            )
            """);
}
