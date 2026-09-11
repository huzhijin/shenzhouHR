package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.CompanyOption;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.PrincipalHome;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.RealtimeAuthorization;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionSeverity;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import com.szsemicon.hr.reporting.infrastructure.persistence.AttendanceDashboardWorkbenchMapper;
import com.szsemicon.hr.reporting.infrastructure.persistence.DashboardWorkbenchRows;
import com.szsemicon.hr.reporting.infrastructure.persistence.DashboardWorkbenchRows.PunchRow;
import com.szsemicon.hr.reporting.infrastructure.persistence.DashboardWorkbenchRows.RosterRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AttendanceDashboardWorkbenchAssemblerTest {

    private static final Instant NOW =
            Instant.parse("2026-08-19T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 19);

    @Test
    void aggregatesSameRoleAcrossCompaniesAndKeepsOrgLabelsDistinct() {
        AttendanceReportSourceRepository sources =
                mock(AttendanceReportSourceRepository.class);
        AttendanceDashboardWorkbenchMapper mapper =
                mock(AttendanceDashboardWorkbenchMapper.class);
        when(sources.listAuthorizedCompanies(
                        "principal-1",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        YearMonth.of(2026, 8),
                        NOW))
                .thenReturn(List.of(
                        new CompanyOption("company-a", "神州半导体"),
                        new CompanyOption("company-b", "神州科技")));
        when(sources.resolvePrincipalHome("principal-1", TODAY))
                .thenReturn(Optional.empty());
        when(sources.resolveRealtimeAuthorization(
                        "principal-1",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "company-a",
                        NOW))
                .thenReturn(Optional.of(companyAuth("company-a")));
        when(sources.resolveRealtimeAuthorization(
                        "principal-1",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "company-b",
                        NOW))
                .thenReturn(Optional.of(companyAuth("company-b")));
        when(mapper.listRoster("company-a", TODAY)).thenReturn(List.of(
                new RosterRow(
                        "employee-1",
                        "SZST0004",
                        "丁书龙",
                        "org-sales-a",
                        "销售中心")));
        when(mapper.listRoster("company-b", TODAY)).thenReturn(List.of(
                new RosterRow(
                        "employee-2",
                        "SZKJ0001",
                        "李悦",
                        "org-sales-b",
                        "销售中心")));
        when(mapper.listEmployeeNumbers()).thenReturn(List.of(
                new DashboardWorkbenchRows.EmployeeNumberRow(
                        "employee-2", "SZKJ0001")));
        when(mapper.listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new PunchRow(
                        "employee-2",
                        TODAY.atTime(LocalTime.of(8, 58))
                                .atZone(AttendanceDashboardService.BUSINESS_ZONE)
                                .toInstant()),
                        new PunchRow(
                                "employee-2",
                                TODAY.atTime(LocalTime.of(18, 1))
                                        .atZone(AttendanceDashboardService
                                                .BUSINESS_ZONE)
                                        .toInstant())));

        var assembler = new AttendanceDashboardWorkbenchAssembler(
                sources, mapper);
        var assembled = assembler
                .assemble(
                        "principal-1",
                        YearMonth.of(2026, 8),
                        TODAY,
                        NOW,
                        true)
                .orElseThrow();

        assertThat(assembled.companies()).hasSize(2);
        assertThat(assembled.snapshot().exceptions())
                .extracting(item -> item.organizationName())
                .contains("销售中心 · 神州半导体");
        assertThat(assembled.todayPunches())
                .extracting(item -> item.employeeNumber()
                        + " "
                        + item.organizationName())
                .containsExactly("SZKJ0001 销售中心 · 神州科技");
        assertThat(assembled.metrics())
                .extracting(item -> item.key())
                .contains("attendance-rate", "exception-rate", "freshness");

        assembler.assemble(
                "principal-2", YearMonth.of(2026, 8), TODAY, NOW, true);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(1))
                .listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void organizationScopeDoesNotSeeOtherDepartmentEmployees() {
        AttendanceReportSourceRepository sources =
                mock(AttendanceReportSourceRepository.class);
        AttendanceDashboardWorkbenchMapper mapper =
                mock(AttendanceDashboardWorkbenchMapper.class);
        when(sources.listAuthorizedCompanies(
                        "principal-2",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        YearMonth.of(2026, 8),
                        NOW))
                .thenReturn(List.of(new CompanyOption("company-a", "神州半导体")));
        when(sources.resolvePrincipalHome("principal-2", TODAY))
                .thenReturn(Optional.empty());
        when(sources.resolveRealtimeAuthorization(
                        "principal-2",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "company-a",
                        NOW))
                .thenReturn(Optional.of(new RealtimeAuthorization(
                        new AuthorizedScope(
                                ScopeType.ORGANIZATION,
                                "org-sales",
                                "销售中心",
                                "c".repeat(64)),
                        "company-a",
                        false,
                        null,
                        Set.of("employee-1"),
                        Set.of("org-sales"))));
        when(mapper.listRoster("company-a", TODAY)).thenReturn(List.of(
                new RosterRow(
                        "employee-1",
                        "SZST0004",
                        "丁书龙",
                        "org-sales",
                        "销售中心"),
                new RosterRow(
                        "employee-9",
                        "SZST9999",
                        "外人",
                        "org-hr",
                        "人力资源部")));
        when(mapper.listEmployeeNumbers()).thenReturn(List.of());
        when(mapper.listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var assembled = new AttendanceDashboardWorkbenchAssembler(
                sources, mapper)
                .assemble(
                        "principal-2",
                        YearMonth.of(2026, 8),
                        TODAY,
                        NOW,
                        true)
                .orElseThrow();

        assertThat(assembled.snapshot().exceptions())
                .extracting(item -> item.employeeNumber())
                .containsOnly("SZST0004")
                .doesNotContain("SZST9999");
        assertThat(assembled.snapshot().exceptions())
                .extracting(item -> item.exceptionType())
                .containsExactlyInAnyOrder("MISSING_ON_DUTY", "MISSING_OFF_DUTY");
    }

    @Test
    void doesNotRetryAFailedCompanyRead() {
        AttendanceReportSourceRepository sources = twoCompanySources();
        AttendanceDashboardWorkbenchMapper mapper =
                mock(AttendanceDashboardWorkbenchMapper.class);
        when(mapper.listRoster("company-a", TODAY))
                .thenThrow(new RuntimeException("roster timed out"));
        when(mapper.listRoster("company-b", TODAY)).thenReturn(List.of(
                new RosterRow(
                        "employee-2",
                        "SZKJ0001",
                        "李悦",
                        "org-sales-b",
                        "销售中心")));
        when(mapper.listEmployeeNumbers()).thenReturn(List.of());
        when(mapper.listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var assembled = new AttendanceDashboardWorkbenchAssembler(
                sources, mapper)
                .assemble(
                        "principal-1",
                        YearMonth.of(2026, 8),
                        TODAY,
                        NOW,
                        true)
                .orElseThrow();

        verify(mapper, times(1)).listRoster("company-a", TODAY);
        assertThat(assembled.snapshot().exceptions())
                .extracting(item -> item.employeeNumber())
                .contains("SZKJ0001")
                .doesNotContain("SZST0004");
    }

    @Test
    void loadsHomeCompanyBeforeOtherAuthorizedCompanies() {
        AttendanceReportSourceRepository sources = twoCompanySources();
        when(sources.resolvePrincipalHome("principal-1", TODAY))
                .thenReturn(Optional.of(new PrincipalHome(
                        "employee-2",
                        "SZKJ0001",
                        "company-b",
                        "神州科技",
                        "org-sales-b",
                        "销售中心")));
        AttendanceDashboardWorkbenchMapper mapper =
                mock(AttendanceDashboardWorkbenchMapper.class);
        when(mapper.listRoster("company-a", TODAY)).thenReturn(List.of());
        when(mapper.listRoster("company-b", TODAY)).thenReturn(List.of());
        when(mapper.listEmployeeNumbers()).thenReturn(List.of());
        when(mapper.listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        new AttendanceDashboardWorkbenchAssembler(sources, mapper)
                .assemble(
                        "principal-1",
                        YearMonth.of(2026, 8),
                        TODAY,
                        NOW,
                        true);

        var order = inOrder(mapper);
        order.verify(mapper).listRoster("company-b", TODAY);
        order.verify(mapper).listRoster("company-a", TODAY);
    }

    @Test
    void adminWithoutEmployeeHomeLoadsJiangsuShenzhouFirst() {
        AttendanceReportSourceRepository sources =
                mock(AttendanceReportSourceRepository.class);
        AttendanceDashboardWorkbenchMapper mapper =
                mock(AttendanceDashboardWorkbenchMapper.class);
        when(sources.listAuthorizedCompanies(
                        "principal-admin",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        YearMonth.of(2026, 8),
                        NOW))
                .thenReturn(List.of(
                        new CompanyOption("company-shang", "上海昇州半导体科技有限公司"),
                        new CompanyOption(
                                "company-szsc", "江苏神州半导体科技股份有限公司")));
        when(sources.resolvePrincipalHome("principal-admin", TODAY))
                .thenReturn(Optional.empty());
        when(sources.resolveRealtimeAuthorization(
                        "principal-admin",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "company-shang",
                        NOW))
                .thenReturn(Optional.of(companyAuth("company-shang")));
        when(sources.resolveRealtimeAuthorization(
                        "principal-admin",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "company-szsc",
                        NOW))
                .thenReturn(Optional.of(companyAuth("company-szsc")));
        when(mapper.listRoster("company-szsc", TODAY)).thenReturn(List.of());
        when(mapper.listRoster("company-shang", TODAY)).thenReturn(List.of());
        when(mapper.listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var assembled = new AttendanceDashboardWorkbenchAssembler(
                sources, mapper)
                .assemble(
                        "principal-admin",
                        YearMonth.of(2026, 8),
                        TODAY,
                        NOW,
                        true)
                .orElseThrow();

        var order = inOrder(mapper);
        order.verify(mapper).listRoster("company-szsc", TODAY);
        order.verify(mapper).listRoster("company-shang", TODAY);
        assertThat(assembled.selected().companyId()).isEqualTo("company-szsc");
    }

    @Test
    void reportReadIsEnoughToAssembleWhenDashboardCapabilityIsMissing() {
        AttendanceReportSourceRepository sources =
                mock(AttendanceReportSourceRepository.class);
        AttendanceDashboardWorkbenchMapper mapper =
                mock(AttendanceDashboardWorkbenchMapper.class);
        when(sources.listAuthorizedCompanies(
                        "principal-admin",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        YearMonth.of(2026, 8),
                        NOW))
                .thenReturn(List.of());
        when(sources.listAuthorizedCompanies(
                        "principal-admin",
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        YearMonth.of(2026, 8),
                        NOW))
                .thenReturn(List.of(new CompanyOption(
                        "company-szsc", "江苏神州半导体科技股份有限公司")));
        when(sources.resolvePrincipalHome("principal-admin", TODAY))
                .thenReturn(Optional.empty());
        when(sources.resolveRealtimeAuthorization(
                        "principal-admin",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "company-szsc",
                        NOW))
                .thenReturn(Optional.empty());
        when(sources.resolveRealtimeAuthorization(
                        "principal-admin",
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        "company-szsc",
                        NOW))
                .thenReturn(Optional.of(companyAuth("company-szsc")));
        when(mapper.listRoster("company-szsc", TODAY)).thenReturn(List.of(
                new RosterRow(
                        "employee-1",
                        "SZ0001",
                        "张三",
                        "org-it",
                        "IT部")));
        when(mapper.listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var assembled = new AttendanceDashboardWorkbenchAssembler(
                sources, mapper)
                .assemble(
                        "principal-admin",
                        YearMonth.of(2026, 8),
                        TODAY,
                        NOW,
                        true)
                .orElseThrow();

        assertThat(assembled.selected().companyId()).isEqualTo("company-szsc");
        assertThat(assembled.snapshot().exceptions())
                .extracting(item -> item.employeeNumber())
                .containsOnly("SZ0001");
        assertThat(assembled.snapshot().exceptions())
                .extracting(item -> item.exceptionType())
                .containsExactlyInAnyOrder("MISSING_ON_DUTY", "MISSING_OFF_DUTY");
    }

    @Test
    void returnsPartialResultWhenAssembleBudgetExpires() {
        AttendanceReportSourceRepository sources = twoCompanySources();
        when(sources.resolvePrincipalHome("principal-1", TODAY))
                .thenReturn(Optional.of(new PrincipalHome(
                        "employee-2",
                        "SZKJ0001",
                        "company-b",
                        "神州科技",
                        "org-sales-b",
                        "销售中心")));
        AttendanceDashboardWorkbenchMapper mapper =
                mock(AttendanceDashboardWorkbenchMapper.class);
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        when(mapper.listRoster("company-b", TODAY)).thenAnswer(invocation -> {
            now.set(NOW.plusSeconds(20));
            return List.of(new RosterRow(
                    "employee-2",
                    "SZKJ0001",
                    "李悦",
                    "org-sales-b",
                    "销售中心"));
        });
        when(mapper.listEmployeeNumbers()).thenReturn(List.of());
        when(mapper.listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var assembled = new AttendanceDashboardWorkbenchAssembler(
                sources, mapper, clock, Duration.ofSeconds(12))
                .assemble(
                        "principal-1",
                        YearMonth.of(2026, 8),
                        TODAY,
                        NOW,
                        true)
                .orElseThrow();

        verify(mapper).listRoster("company-b", TODAY);
        verify(mapper, never()).listRoster("company-a", TODAY);
        assertThat(assembled.snapshot().exceptions())
                .extracting(item -> item.employeeNumber())
                .contains("SZKJ0001");
    }

    @Test
    void hardDeadlineStillReturnsTheFirstCompanyPage() throws Exception {
        AttendanceReportSourceRepository sources = twoCompanySources();
        AttendanceDashboardWorkbenchMapper mapper =
                mock(AttendanceDashboardWorkbenchMapper.class);
        when(mapper.listRoster("company-a", TODAY)).thenReturn(List.of(
                new RosterRow(
                        "employee-1",
                        "SZST0004",
                        "丁书龙",
                        "org-sales-a",
                        "销售中心")));
        when(mapper.listRoster("company-b", TODAY)).thenAnswer(invocation -> {
            Thread.sleep(2_000);
            return List.of();
        });
        when(mapper.listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var assembler = new AttendanceDashboardWorkbenchAssembler(
                    sources,
                    mapper,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    Duration.ofMillis(250),
                    executor);
            long started = System.nanoTime();
            var assembled = assembler
                    .assemble(
                            "principal-1",
                            YearMonth.of(2026, 8),
                            TODAY,
                            NOW,
                            true)
                    .orElseThrow();
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(1));
            assertThat(assembled.snapshot().exceptions())
                    .extracting(item -> item.employeeNumber())
                    .contains("SZST0004");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void punchExemptEmployeesDoNotAppearAsMissingPunchErrors() {
        AttendanceReportSourceRepository sources =
                mock(AttendanceReportSourceRepository.class);
        AttendanceDashboardWorkbenchMapper mapper =
                mock(AttendanceDashboardWorkbenchMapper.class);
        when(sources.listAuthorizedCompanies(
                        "principal-1",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        YearMonth.of(2026, 8),
                        NOW))
                .thenReturn(List.of(new CompanyOption("company-a", "神州半导体")));
        when(sources.resolvePrincipalHome("principal-1", TODAY))
                .thenReturn(Optional.empty());
        when(sources.resolveRealtimeAuthorization(
                        "principal-1",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "company-a",
                        NOW))
                .thenReturn(Optional.of(companyAuth("company-a")));
        when(mapper.listRoster("company-a", TODAY)).thenReturn(List.of(
                new RosterRow(
                        "employee-exec",
                        "SZST0000",
                        "陈觉晓",
                        "org-chairman",
                        "董事长"),
                new RosterRow(
                        "employee-1",
                        "SZST0004",
                        "丁书龙",
                        "org-sales",
                        "销售中心")));
        when(mapper.listPunchExemptEmployeeIds(
                        org.mockito.ArgumentMatchers.eq("company-a"),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("employee-exec"));
        when(mapper.listEmployeeNumbers()).thenReturn(List.of());
        when(mapper.listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var assembled = new AttendanceDashboardWorkbenchAssembler(
                sources, mapper)
                .assemble(
                        "principal-1",
                        YearMonth.of(2026, 8),
                        TODAY,
                        NOW,
                        true)
                .orElseThrow();

        assertThat(assembled.snapshot().exceptions())
                .extracting(item -> item.employeeNumber())
                .containsOnly("SZST0004")
                .doesNotContain("SZST0000");
    }

    @Test
    void workbenchOrganizationColumnUsesOfficialJoinedDepartmentPath() {
        AttendanceReportSourceRepository sources =
                mock(AttendanceReportSourceRepository.class);
        AttendanceDashboardWorkbenchMapper mapper =
                mock(AttendanceDashboardWorkbenchMapper.class);
        when(sources.listAuthorizedCompanies(
                        "principal-1",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        YearMonth.of(2026, 8),
                        NOW))
                .thenReturn(List.of(new CompanyOption("company-a", "神州半导体")));
        when(sources.resolvePrincipalHome("principal-1", TODAY))
                .thenReturn(Optional.empty());
        when(sources.resolveRealtimeAuthorization(
                        "principal-1",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "company-a",
                        NOW))
                .thenReturn(Optional.of(companyAuth("company-a")));
        when(sources.reportDepartmentPaths("company-a")).thenReturn(Map.of(
                "org-rf",
                DepartmentPathNames.fromRootToLeaf(
                        List.of("服务中心", "工程二部", "RF-B组"))
                        .reportDepartment()));
        when(mapper.listRoster("company-a", TODAY)).thenReturn(List.of(
                new RosterRow(
                        "employee-1",
                        "SZST0015",
                        "孙鹏",
                        "org-rf",
                        "工程二部")));
        when(mapper.listEmployeeNumbers()).thenReturn(List.of());
        when(mapper.listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var assembled = new AttendanceDashboardWorkbenchAssembler(
                sources, mapper)
                .assemble(
                        "principal-1",
                        YearMonth.of(2026, 8),
                        TODAY,
                        NOW,
                        true)
                .orElseThrow();

        assertThat(assembled.snapshot().exceptions())
                .extracting(item -> DepartmentPathNames.visibleDepartment(
                        item.organizationName()))
                .containsOnly("服务中心-工程二部-RF-B组");
    }

    @Test
    void beforeNoonDoesNotRaiseTodaysMissedPunch() {
        Instant morning = Instant.parse("2026-08-19T01:00:00Z");
        AttendanceReportSourceRepository sources =
                companiesAt("principal-1", morning);
        AttendanceDashboardWorkbenchMapper mapper =
                mock(AttendanceDashboardWorkbenchMapper.class);
        when(mapper.listRoster("company-a", TODAY)).thenReturn(List.of(
                new RosterRow(
                        "employee-1",
                        "SZST0004",
                        "丁书龙",
                        "org-sales",
                        "销售中心")));
        when(mapper.listEmployeeNumbers()).thenReturn(List.of());
        when(mapper.listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new PunchRow(
                                "employee-1",
                                TODAY.minusDays(1)
                                        .atTime(LocalTime.of(8, 30))
                                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                                        .toInstant(),
                                "SZST0004"),
                        new PunchRow(
                                "employee-1",
                                TODAY.minusDays(1)
                                        .atTime(LocalTime.of(18, 0))
                                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                                        .toInstant(),
                                "SZST0004")));

        var assembled = new AttendanceDashboardWorkbenchAssembler(
                sources, mapper)
                .assemble(
                        "principal-1",
                        YearMonth.of(2026, 8),
                        TODAY,
                        morning,
                        true)
                .orElseThrow();

        assertThat(assembled.snapshot().exceptions())
                .noneMatch(item -> TODAY.equals(item.businessDate())
                        && "MISSING_ON_DUTY".equals(item.exceptionType()));
    }

    @Test
    void afterNoonKeepsPublishedExceptionBoardOnYesterday() {
        Instant afternoon = Instant.parse("2026-08-19T05:30:00Z");
        AttendanceReportSourceRepository sources =
                companiesAt("principal-1", afternoon);
        AttendanceDashboardWorkbenchMapper mapper =
                mock(AttendanceDashboardWorkbenchMapper.class);
        when(mapper.listRoster("company-a", TODAY)).thenReturn(List.of(
                new RosterRow(
                        "employee-late",
                        "SZST0001",
                        "迟到人",
                        "org-sales",
                        "销售中心"),
                new RosterRow(
                        "employee-miss",
                        "SZST0002",
                        "漏签人",
                        "org-sales",
                        "销售中心")));
        when(mapper.listEmployeeNumbers()).thenReturn(List.of());
        when(mapper.listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new PunchRow(
                                "employee-late",
                                TODAY.atTime(LocalTime.of(9, 40))
                                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                                        .toInstant(),
                                "SZST0001")));

        var assembled = new AttendanceDashboardWorkbenchAssembler(
                sources, mapper)
                .assemble(
                        "principal-1",
                        YearMonth.of(2026, 8),
                        TODAY,
                        afternoon,
                        true)
                .orElseThrow();

        assertThat(assembled.snapshot().exceptions())
                .anyMatch(item -> TODAY.equals(item.businessDate())
                        && "LATE".equals(item.exceptionType()));
        assertThat(assembled.snapshot().exceptions())
                .noneMatch(item -> TODAY.equals(item.businessDate())
                        && "MISSING_OFF_DUTY".equals(item.exceptionType()));
        assertThat(assembled.snapshot().analytics().dailyTrend())
                .last()
                .extracting(point -> point.businessDate())
                .isEqualTo(TODAY);
    }

    @Test
    void monthWindowIncludesPinnedExceptionsFromEarlierInThePeriod() {
        Instant morning = Instant.parse("2026-08-19T01:00:00Z");
        AttendanceReportSourceRepository sources =
                companiesAt("principal-1", morning);
        ReportSourceSnapshot snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "company-a",
                        "神州半导体",
                        "a".repeat(64)),
                new ReportFilter(
                        YearMonth.of(2026, 8),
                        "company-a",
                        null,
                        null,
                        null),
                "ARP1-test",
                "OPEN",
                morning,
                List.of("ATTENDANCE.OA_DOCUMENT:V2"),
                List.of(),
                List.of(),
                List.of(new ExceptionFact(
                        "case-month",
                        "employee-1",
                        "SZST0004",
                        "丁书龙",
                        "org-sales",
                        "销售中心",
                        LocalDate.of(2026, 8, 5),
                        "LATE",
                        ExceptionSeverity.WARNING,
                        ExceptionState.OPEN,
                        18,
                        "迟到 09:18",
                        "ARP1-test")),
                List.of());
        when(sources.loadAuthorizedSnapshot(
                        org.mockito.ArgumentMatchers.eq("principal-1"),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(morning)))
                .thenReturn(Optional.of(snapshot));
        AttendanceDashboardWorkbenchMapper mapper =
                mock(AttendanceDashboardWorkbenchMapper.class);
        when(mapper.listRoster("company-a", TODAY)).thenReturn(List.of(
                new RosterRow(
                        "employee-1",
                        "SZST0004",
                        "丁书龙",
                        "org-sales",
                        "销售中心")));
        when(mapper.listEmployeeNumbers()).thenReturn(List.of());
        when(mapper.listPunchPoints(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var assembled = new AttendanceDashboardWorkbenchAssembler(
                sources, mapper)
                .assemble(
                        "principal-1",
                        YearMonth.of(2026, 8),
                        TODAY,
                        morning,
                        true)
                .orElseThrow();

        assertThat(assembled.snapshot().exceptions())
                .extracting(item -> item.businessDate()
                        + ":"
                        + item.exceptionType())
                .contains("2026-08-05:LATE");
    }

    private static AttendanceReportSourceRepository companiesAt(
            String principalId, Instant at) {
        AttendanceReportSourceRepository sources =
                mock(AttendanceReportSourceRepository.class);
        when(sources.listAuthorizedCompanies(
                        principalId,
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        YearMonth.of(2026, 8),
                        at))
                .thenReturn(List.of(new CompanyOption("company-a", "神州半导体")));
        when(sources.resolvePrincipalHome(principalId, TODAY))
                .thenReturn(Optional.empty());
        when(sources.resolveRealtimeAuthorization(
                        principalId,
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "company-a",
                        at))
                .thenReturn(Optional.of(companyAuth("company-a")));
        return sources;
    }

    private static AttendanceReportSourceRepository twoCompanySources() {
        AttendanceReportSourceRepository sources =
                mock(AttendanceReportSourceRepository.class);
        when(sources.listAuthorizedCompanies(
                        "principal-1",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        YearMonth.of(2026, 8),
                        NOW))
                .thenReturn(List.of(
                        new CompanyOption("company-a", "神州半导体"),
                        new CompanyOption("company-b", "神州科技")));
        when(sources.resolvePrincipalHome("principal-1", TODAY))
                .thenReturn(Optional.empty());
        when(sources.resolveRealtimeAuthorization(
                        "principal-1",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "company-a",
                        NOW))
                .thenReturn(Optional.of(companyAuth("company-a")));
        when(sources.resolveRealtimeAuthorization(
                        "principal-1",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "company-b",
                        NOW))
                .thenReturn(Optional.of(companyAuth("company-b")));
        return sources;
    }

    private static RealtimeAuthorization companyAuth(String companyId) {
        return new RealtimeAuthorization(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        companyId,
                        "公司授权范围",
                        "a".repeat(64)),
                companyId,
                true,
                null,
                Set.of(),
                Set.of());
    }
}
