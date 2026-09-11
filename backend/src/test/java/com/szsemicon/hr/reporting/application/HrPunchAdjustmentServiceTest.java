package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.infrastructure.persistence.HrPunchAdjustmentMapper;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HrPunchAdjustmentServiceTest {

    @Mock
    private CurrentCapabilityService capabilities;
    @Mock
    private CurrentPrincipalProvider principals;
    @Mock
    private HrPunchAdjustmentMapper mapper;
    @Mock
    private AttendanceReportQueryService reports;

    @Test
    void savesPunchTimesAndRecalculatesTheMonth() {
        Instant now = Instant.parse("2026-08-26T10:00:00Z");
        var service = new HrPunchAdjustmentService(
                capabilities,
                principals,
                mapper,
                reports,
                Clock.fixed(now, ZoneOffset.UTC));
        when(principals.currentPrincipalId()).thenReturn("principal-1");
        when(mapper.findEmployeeCompanyId("employee-1")).thenReturn("company-a");

        Instant offDuty = Instant.parse("2026-08-09T13:00:00Z");
        var saved = service.save(new HrPunchAdjustmentService.SaveCommand(
                "company-a",
                "employee-1",
                LocalDate.of(2026, 8, 9),
                null,
                offDuty,
                "周末加班下班漏刷，人事补卡",
                null,
                null));

        assertThat(saved.companyId()).isEqualTo("company-a");
        assertThat(saved.employeeId()).isEqualTo("employee-1");
        verify(capabilities).require(CapabilityCodes.ATTENDANCE_ADJUST_MANAGE);
        verify(mapper).insert(
                any(),
                eq("company-a"),
                eq("employee-1"),
                eq(LocalDate.of(2026, 8, 9)),
                eq(null),
                eq(offDuty),
                eq("周末加班下班漏刷，人事补卡"),
                eq("principal-1"),
                eq(now),
                eq(null),
                eq(null),
                eq(null));
        ArgumentCaptor<Instant> dataAsOf = ArgumentCaptor.forClass(Instant.class);
        verify(reports).recalculateEmployeeDays(
                eq(YearMonth.of(2026, 8)),
                eq("company-a"),
                eq("employee-1"),
                eq(LocalDate.of(2026, 8, 9)),
                dataAsOf.capture());
        assertThat(dataAsOf.getValue())
                .isAfterOrEqualTo(now.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void skipsRecalculateWhenRequested() {
        Instant now = Instant.parse("2026-08-26T10:00:00Z");
        var service = new HrPunchAdjustmentService(
                capabilities,
                principals,
                mapper,
                reports,
                Clock.fixed(now, ZoneOffset.UTC));
        when(principals.currentPrincipalId()).thenReturn("principal-1");
        when(mapper.findEmployeeCompanyId("employee-1")).thenReturn("company-a");

        service.save(new HrPunchAdjustmentService.SaveCommand(
                "company-a",
                "employee-1",
                LocalDate.of(2026, 8, 9),
                null,
                Instant.parse("2026-08-09T13:00:00Z"),
                "批量预入账，稍后重算",
                null,
                null,
                List.of(),
                false));

        verify(mapper).insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(reports);
    }

    @Test
    void acceptsUndeclaredOvertimeClearAndDoesNotTouchLeaveAccounts() {
        Instant now = Instant.parse("2026-08-26T10:00:00Z");
        var service = new HrPunchAdjustmentService(
                capabilities,
                principals,
                mapper,
                reports,
                Clock.fixed(now, ZoneOffset.UTC));
        when(principals.currentPrincipalId()).thenReturn("principal-1");
        when(mapper.findEmployeeCompanyId("employee-1")).thenReturn("company-a");

        service.save(new HrPunchAdjustmentService.SaveCommand(
                "company-a",
                "employee-1",
                LocalDate.of(2026, 8, 12),
                null,
                null,
                "取消未报加班",
                null,
                List.of("OVERTIME_DOCUMENT_MISSING_OR_LATE", "LONG_PUNCH_SPAN_REVIEW"),
                List.of("ANNUAL_LEAVE")));

        verify(mapper).insert(
                any(),
                eq("company-a"),
                eq("employee-1"),
                eq(LocalDate.of(2026, 8, 12)),
                eq(null),
                eq(null),
                eq("取消未报加班"),
                eq("principal-1"),
                eq(now),
                eq(null),
                eq("OVERTIME_DOCUMENT_MISSING_OR_LATE,LONG_PUNCH_SPAN_REVIEW"),
                eq("ANNUAL_LEAVE"));
        verify(reports).recalculateEmployeeDays(
                eq(YearMonth.of(2026, 8)),
                eq("company-a"),
                eq("employee-1"),
                eq(LocalDate.of(2026, 8, 12)),
                any());
    }

    @Test
    void savesOvertimeHourOverrideWithoutPunchTimes() {
        Instant now = Instant.parse("2026-08-26T10:00:00Z");
        var service = new HrPunchAdjustmentService(
                capabilities,
                principals,
                mapper,
                reports,
                Clock.fixed(now, ZoneOffset.UTC));
        when(principals.currentPrincipalId()).thenReturn("principal-1");
        when(mapper.findEmployeeCompanyId("employee-1")).thenReturn("company-a");

        service.save(new HrPunchAdjustmentService.SaveCommand(
                "company-a",
                "employee-1",
                LocalDate.of(2026, 8, 4),
                null,
                null,
                "核对后覆盖加班小时",
                java.math.BigDecimal.valueOf(2.5),
                List.of("LATE")));

        verify(mapper).insert(
                any(),
                eq("company-a"),
                eq("employee-1"),
                eq(LocalDate.of(2026, 8, 4)),
                eq(null),
                eq(null),
                eq("核对后覆盖加班小时"),
                eq("principal-1"),
                eq(now),
                eq(150),
                eq("LATE"),
                eq(null));
    }
}
