package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionPublicationUseCase;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.CompanyOption;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.RealtimeAuthorization;
import com.szsemicon.hr.reporting.application.RealtimeAttendanceReportSnapshotService;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.AttendancePolicyRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.EmployeeIdentityIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchExemptionRoleIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.SourceInputVersionRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 回归：真实 kernel 日计算失败（重叠班段触发 assertNoSegmentOverlap）不得发布任何 pin。
 *
 * <p>旧 WARN+continue 逻辑会跳过坏日后仍返回 PublishCommand，进而写入残缺 pin。
 * 此测试使用真实 FullCalculationEngineOrchestrator + mock mapper，确保日 catch
 * 抛出的 IllegalStateException 向上传播，publisher 零调用，旧 pin 仍可读。
 */
@ExtendWith(MockitoExtension.class)
class KernelFailurePublicationTest {

    private static final String COMPANY = "company-kernel-test";
    private static final String EMPLOYEE = "emp-kernel-001";
    private static final String EMPLOYEE_NUMBER = "SZTEST999";
    private static final YearMonth PERIOD = YearMonth.of(2026, 8);
    private static final Instant CUTOFF = Instant.parse("2026-08-31T18:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PRINCIPAL = "principal-kernel-test";
    private static final String CAPABILITY = CapabilityCodes.ATTENDANCE_REPORT_REFRESH;

    @Mock private AttendanceReportCalculationMapper mapper;
    @Mock private AttendanceReportSourceRepository repository;
    @Mock private AttendanceReportProjectionPublicationUseCase publisher;

    private FullCalculationEngineOrchestrator orchestrator;
    private RealtimeAttendanceReportSnapshotService service;

    @BeforeEach
    void setUp() {
        orchestrator = new FullCalculationEngineOrchestrator(mapper);
        service = new RealtimeAttendanceReportSnapshotService(
                orchestrator, repository, new SimpleMeterRegistry(), null, publisher);
    }

    /**
     * 先用好日窗口（8/3 单独，无重叠）确认 assemble 可产生正常日事实，防止无关 fixture 导致假通过。
     */
    @Test
    void goodDayWindowProducesNormalDailyFact() {
        // 8/3 工作日：正常班段 08:30–12 / 13:30–18
        LocalDate goodDay = LocalDate.of(2026, 8, 3);
        setupFixture(List.of(goodDay), false);

        var result = orchestrator.assemble(COMPANY, PERIOD, PeriodState.OPEN,
                PRINCIPAL, CUTOFF, goodDay, goodDay.plusDays(1));

        assertThat(result.calculatedFacts()).hasSize(1);
        var fact = result.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(fact.employeeId()).isEqualTo(EMPLOYEE);
        assertThat(fact.businessDate()).isEqualTo(goodDay);
        assertThat(fact.scheduledMinutes()).isEqualTo(480);
    }

    /**
     * 8/4 重叠班段（10:00 < 12:00）触发 assertNoSegmentOverlap。分两层断言：
     * 1) 直接调用 orchestrator.assemble 整月，断言根因 IllegalArgumentException + overlap message + 8/4 坐标
     * 2) 调用真实 service.recalculate，断言 publisher 零调用 + 旧 pin 仍可读
     */
    @Test
    void overlappingSegmentsDayFailureDoesNotPublishAndPreservesOldPin() {
        // 准备：授权 + 旧 pin
        authorize();
        var oldSnapshot = snapshotFromDailyFact(
                fact(EMPLOYEE, LocalDate.of(2026, 8, 1), DayType.WEEKDAY, 480));
        when(repository.loadAuthorizedSnapshot(any(), any(), any(), any()))
                .thenReturn(Optional.of(oldSnapshot));

        // 8/3 好日 + 8/4 坏日（重叠班段）
        LocalDate goodDay = LocalDate.of(2026, 8, 3);
        LocalDate badDay = LocalDate.of(2026, 8, 4);
        setupFixture(List.of(goodDay, badDay), true);

        // 第一层：直接调用 orchestrator，断言内核根因
        assertThatThrownBy(() -> orchestrator.assemble(
                        COMPANY, PERIOD, PeriodState.OPEN, PRINCIPAL, CUTOFF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to calculate day")
                .hasMessageContaining("company=" + COMPANY)
                .hasMessageContaining("employee=" + EMPLOYEE)
                .hasMessageContaining("date=" + badDay)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .cause()
                .hasMessageContaining("scheduled work segments must not overlap");

        // 第二层：通过真实 service，断言 publisher 零调用 + 旧 pin 可读
        assertThatThrownBy(() -> service.recalculate(
                        PRINCIPAL, CAPABILITY,
                        filter(COMPANY, null, null), CUTOFF))
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).contains("company=" + COMPANY);
                    assertThat(ex.getMessage()).contains("employee=" + EMPLOYEE);
                    assertThat(ex.getMessage()).contains("date=" + badDay);
                });

        // publisher 所有重载零调用
        org.mockito.Mockito.verifyNoInteractions(publisher);

        // 旧 pin 仍可读
        var afterFailure = service.loadAuthorizedSnapshot(
                        PRINCIPAL, CAPABILITY,
                        filter(COMPANY, null, null), null, CUTOFF)
                .orElseThrow();
        assertThat(afterFailure.projectionVersion())
                .isEqualTo(oldSnapshot.projectionVersion());
        assertThat(afterFailure.dailyFacts()).hasSize(1);
        assertThat(afterFailure.dailyFacts().get(0).businessDate())
                .isEqualTo(LocalDate.of(2026, 8, 1));
    }

    private void setupFixture(List<LocalDate> days, boolean overlappingOnLast) {
        // 源版本
        when(mapper.findAttendanceSourceVersions(COMPANY, CUTOFF))
                .thenReturn(List.of(
                        new SourceInputVersionRow(
                                "oa", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), CUTOFF),
                        new SourceInputVersionRow(
                                "deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), CUTOFF)));

        // 员工身份
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        new EmployeeIdentityIntervalRow(
                                EMPLOYEE, "v1", EMPLOYEE_NUMBER, "内核测试员工", "assignment-kt",
                                "org-kt", "org-v1", "测试部",
                                LocalDate.of(2026, 1, 1), null,
                                LocalDate.of(2026, 1, 1), null,
                                LocalDate.of(2026, 1, 1), null)));

        // 日历：整个 8 月，8/3 和 8/4 都是 WEEKDAY
        List<CalendarDayRow> calendar = new ArrayList<>();
        for (LocalDate d = PERIOD.atDay(1); d.isBefore(PERIOD.atEndOfMonth().plusDays(1)); d = d.plusDays(1)) {
            boolean isWeekend = d.getDayOfWeek().getValue() > 5;
            calendar.add(new CalendarDayRow(EMPLOYEE, d, isWeekend ? "WEEKEND" : "WEEKDAY", 1, 1));
        }
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(calendar);

        // 班段：8/3 正常 08:30–12 / 13:30–18；8/4 重叠班段（如果 overlappingOnLast=true）
        // 所有其他工作日也补背景班段，避免缺班导致 No shift authority 提前中断
        List<ShiftSegmentRow> shifts = new ArrayList<>();
        for (LocalDate d = PERIOD.atDay(1); d.isBefore(PERIOD.atEndOfMonth().plusDays(1)); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() > 5) continue;
            boolean isTargetDay = days.contains(d);
            boolean isLast = isTargetDay && d.equals(days.get(days.size() - 1));
            if (isLast && overlappingOnLast) {
                Instant a_s = at(d, "08:30"), a_e = at(d, "12:00");
                Instant b_s = at(d, "10:00"), b_e = at(d, "16:00");
                shifts.add(segment(d, "seg-a", a_s, a_e));
                shifts.add(segment(d, "seg-b", b_s, b_e));
            } else {
                Instant am_s = at(d, "08:30"), am_e = at(d, "12:00");
                Instant pm_s = at(d, "13:30"), pm_e = at(d, "18:00");
                shifts.add(segment(d, "seg-am", am_s, am_e));
                shifts.add(segment(d, "seg-pm", pm_s, pm_e));
            }
        }
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(shifts);

        // 其余 mapper 返回空列表（不触发其他分支；复用 Weekend 真实 fixture 风格）
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of());
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of());
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of());
        when(mapper.findPunchExemptionRoleIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findStandingPunchExemptionIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        List<AttendancePolicyRow> policies = new ArrayList<>();
        for (LocalDate d = PERIOD.atDay(1); d.isBefore(PERIOD.atEndOfMonth().plusDays(1)); d = d.plusDays(1)) {
            policies.add(new AttendancePolicyRow(EMPLOYEE, d, 1, 1, true, 15, 1, true, 15, 1, false, 1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS", 1, true, 48));
        }
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(policies);
    }

    private void authorize() {
        when(repository.listAuthorizedCompanies(eq(PRINCIPAL), eq(CAPABILITY), eq(PERIOD), any()))
                .thenReturn(List.of(new CompanyOption(COMPANY, "测试公司")));
        when(repository.resolveRealtimeAuthorization(eq(PRINCIPAL), eq(CAPABILITY), eq(COMPANY), any()))
                .thenReturn(Optional.of(new RealtimeAuthorization(
                        scope(ScopeType.COMPANY), COMPANY, true, null,
                        Set.of(EMPLOYEE), Set.of("org-kt"))));
        org.mockito.Mockito.lenient().when(repository.loadAuthorizedSnapshot(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private static AuthorizedScope scope(ScopeType type) {
        return new AuthorizedScope(type, "scope-ref", "测试范围", "scope-digest");
    }

    private static ReportFilter filter(String companyId, String organizationId, String employeeId) {
        return new ReportFilter(PERIOD, companyId, organizationId, employeeId, null);
    }

    private static Instant at(LocalDate date, String clock) {
        return date.atTime(LocalTime.parse(clock)).atZone(ZONE).toInstant();
    }

    private static ShiftSegmentRow segment(LocalDate day, String key, Instant start, Instant end) {
        return new ShiftSegmentRow(
                EMPLOYEE, day, key + day, start, end,
                start.minusSeconds(3600), start.plusSeconds(3600),
                end.minusSeconds(3600), end.plusSeconds(3600));
    }

    private static DailyFact fact(String employeeId, LocalDate businessDate, DayType dayType, int scheduledMinutes) {
        return new DailyFact(
                "fact-" + businessDate, COMPANY, employeeId, EMPLOYEE_NUMBER, "测试员工",
                "org-kt", "org-v1", "测试部", businessDate, dayType, "白班",
                scheduledMinutes, scheduledMinutes, 0, 0, 0, 0, 0, 0, 0,
                scheduledMinutes, 1, 1, 0, 0, 0, 0,
                at(businessDate, "08:00"), at(businessDate, "18:00"),
                "ATTENDANCE.FULL_CALC:V2", "b".repeat(64), null);
    }

    private static com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot snapshotFromDailyFact(DailyFact fact) {
        return new com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot(
                scope(ScopeType.COMPANY),
                filter(COMPANY, null, null),
                "projection-old",
                "OPEN",
                CUTOFF,
                List.of("DELI:V1", "OA:V1"),
                List.of(fact),
                List.of(),
                List.of(),
                List.of());
    }

    private static String buildCauseChain(Throwable t) {
        var sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null) sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
