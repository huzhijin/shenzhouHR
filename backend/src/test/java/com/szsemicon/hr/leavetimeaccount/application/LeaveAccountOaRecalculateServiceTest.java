package com.szsemicon.hr.leavetimeaccount.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.LedgerEntryRow;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.TimeAccountRow;
import com.szsemicon.hr.leavetimeaccount.infrastructure.persistence.LeaveAccountOaRecalculateMapper;
import com.szsemicon.hr.leavetimeaccount.infrastructure.persistence.LeaveAccountOaRecalculateMapper.EmployeeEmploymentRow;
import com.szsemicon.hr.leavetimeaccount.infrastructure.persistence.LeaveAccountOaRecalculateMapper.OaHourRow;
import com.szsemicon.hr.reporting.application.AttendanceReportQueryService;
import com.szsemicon.hr.reporting.application.RecalcWindow;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LeaveAccountOaRecalculateServiceTest {

    @Mock
    private CurrentCapabilityService capabilities;
    @Mock
    private CurrentPrincipalProvider principals;
    @Mock
    private LeaveAccountOaRecalculateMapper mapper;
    @Mock
    private AnnualLeaveManagementRepository accounts;
    @Mock
    private AttendanceReportQueryService reports;

    @Test
    void annualLeaveUsedHoursMatchOaEightHours() {
        Instant now = Instant.parse("2026-08-26T10:00:00Z");
        var service = new LeaveAccountOaRecalculateService(
                capabilities,
                principals,
                mapper,
                accounts,
                reports,
                Clock.fixed(now, ZoneOffset.UTC));
        when(principals.currentPrincipalId()).thenReturn("principal-1");
        when(mapper.listCompanyEmployments(eq("company-a"), any()))
                .thenReturn(List.of(new EmployeeEmploymentRow(
                        "employee-1", "period-1", "company-a")));
        when(mapper.listOaHours(eq("company-a"), any(), any()))
                .thenReturn(List.of(new OaHourRow("employee-1", "ANNUAL", 480L)));
        TimeAccountRow account = new TimeAccountRow(
                "account-1",
                "employee-1",
                "period-1",
                "company-a",
                2026,
                BigDecimal.valueOf(16),
                "policy-1",
                1L);
        when(accounts.lockTimeAccount("employee-1", "period-1", 2026, "ANNUAL_LEAVE"))
                .thenReturn(Optional.of(account));
        when(accounts.lockTimeAccount("employee-1", "period-1", 2026, "TIME_OFF"))
                .thenReturn(Optional.of(new TimeAccountRow(
                        "account-2",
                        "employee-1",
                        "period-1",
                        "company-a",
                        2026,
                        BigDecimal.ZERO,
                        "policy-1",
                        1L)));
        when(mapper.listUnreversedOaSyncEntries(any(), any(), any()))
                .thenReturn(List.of());
        when(accounts.nextSequenceNo(any())).thenReturn(2);
        when(accounts.updateBalance(any(), any(), anyLong())).thenReturn(true);

        var result = service.recalculate("company-a", 2026);

        assertThat(result.annualAccounts()).isEqualTo(1);
        ArgumentCaptor<LedgerEntryRow> captor = ArgumentCaptor.forClass(LedgerEntryRow.class);
        verify(accounts).insertLedgerEntry(captor.capture());
        assertThat(captor.getValue().entryType()).isEqualTo("USE");
        assertThat(captor.getValue().amountHours())
                .isEqualByComparingTo(BigDecimal.valueOf(-8).setScale(2));
        assertThat(captor.getValue().sourceType()).isEqualTo("OA_SYNC");
        verify(accounts).updateBalance(
                eq("account-1"),
                eq(BigDecimal.valueOf(8).setScale(2)),
                eq(1L));
        verify(reports).recalculate(
                eq(YearMonth.of(2026, 8)),
                eq("company-a"),
                eq(RecalcWindow.MONTH));
    }

    @Test
    void scheduledPathDoesNotRequireCapabilityOrRefreshReports() {
        Instant now = Instant.parse("2026-08-26T10:00:00Z");
        var service = new LeaveAccountOaRecalculateService(
                capabilities,
                principals,
                mapper,
                accounts,
                reports,
                Clock.fixed(now, ZoneOffset.UTC));
        when(mapper.listCompanyEmployments(eq("company-a"), any()))
                .thenReturn(List.of(new EmployeeEmploymentRow(
                        "employee-1", "period-1", "company-a")));
        when(mapper.listOaHours(eq("company-a"), any(), any()))
                .thenReturn(List.of());
        TimeAccountRow annual = new TimeAccountRow(
                "account-1",
                "employee-1",
                "period-1",
                "company-a",
                2026,
                BigDecimal.valueOf(16),
                "policy-1",
                1L);
        when(accounts.lockTimeAccount("employee-1", "period-1", 2026, "ANNUAL_LEAVE"))
                .thenReturn(Optional.of(annual));
        when(accounts.lockTimeAccount("employee-1", "period-1", 2026, "TIME_OFF"))
                .thenReturn(Optional.of(new TimeAccountRow(
                        "account-2",
                        "employee-1",
                        "period-1",
                        "company-a",
                        2026,
                        BigDecimal.ZERO,
                        "policy-1",
                        1L)));
        when(mapper.listUnreversedOaSyncEntries(any(), any(), any()))
                .thenReturn(List.of());

        var result = service.recalculateAsSystem("company-a", 2026);

        assertThat(result.annualAccounts()).isEqualTo(1);
        verify(capabilities, never()).require(any());
        verify(reports, never()).recalculate(any(), any(), any());
    }
}
