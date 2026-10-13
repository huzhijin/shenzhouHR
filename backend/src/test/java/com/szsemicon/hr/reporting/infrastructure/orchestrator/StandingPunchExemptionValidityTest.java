package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real assembly: a bounded standing list must not credit other months or its gaps. */
class StandingPunchExemptionValidityTest {
    private static final String COMPANY = "company";
    private static final String EMPLOYEE = "employee";
    private static final String NUMBER = "SZST0748";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant CUTOFF = Instant.parse("2026-11-05T06:00:00Z");

    @ParameterizedTest
    @CsvSource({
            "2026-08-31, 0", "2026-09-01, 480", "2026-09-30, 480", "2026-10-01, 0"})
    void septemberAssignmentDoesNotGrantScheduledWorkBeforeOrAfterSeptember(String date, long minutes) {
        LocalDate day = LocalDate.parse(date);
        var result = assemble(day, List.of(interval("2026-09-01", "2026-10-01")), false,
                LocalDate.of(2020, 1, 1), false);
        assertScheduled(result, day, minutes);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothStandingAndExecutiveHonorTheSameShanghaiStartAndExclusiveEnd(boolean executive) {
        var interval = interval("2026-09-14", "2026-10-01");
        // Sep 14 local midnight is Sep 13 16:00 UTC, not UTC midnight.
        assertThat(interval.validFrom()).isEqualTo(Instant.parse("2026-09-13T16:00:00Z"));
        for (String date : List.of("2026-09-13", "2026-09-14", "2026-09-30", "2026-10-01")) {
            LocalDate day = LocalDate.parse(date);
            boolean active = !day.isBefore(LocalDate.of(2026, 9, 14))
                    && day.isBefore(LocalDate.of(2026, 10, 1));
            var result = assemble(day, List.of(interval), executive, LocalDate.of(2020, 1, 1), true);
            assertScheduled(result, day, active ? 480 : 0);
            assertOvertime(result, active ? 150 : 0);
        }
    }

    @Test
    void twoSeparatePeriodsDoNotFillTheGapBetweenThem() {
        var periods = List.of(interval("2026-09-01", "2026-09-10"),
                interval("2026-09-20", "2026-10-01"));
        for (String date : List.of("2026-09-09", "2026-09-10", "2026-09-17", "2026-09-20")) {
            LocalDate day = LocalDate.parse(date);
            boolean active = day.getDayOfMonth() < 10 || day.getDayOfMonth() >= 20;
            var result = assemble(day, periods, false, LocalDate.of(2020, 1, 1), true);
            assertScheduled(result, day, active ? 480 : 0);
            assertOvertime(result, active ? 150 : 0);
        }
    }

    @Test
    void scheduledWorkUsesBusinessDayStartRatherThanOvernightPunchEvidenceWindow() {
        LocalDate day = LocalDate.of(2026, 9, 17);
        Instant start = day.atStartOfDay(ZONE).toInstant();
        var startsLater = new PunchExemptionRoleIntervalRow(EMPLOYEE, start.plusSeconds(1), null);
        assertScheduled(assemble(day, List.of(startsLater), false,
                LocalDate.of(2020, 1, 1), false), day, 0);
        var expiresExactly = new PunchExemptionRoleIntervalRow(EMPLOYEE, start.minusSeconds(86400), start);
        assertScheduled(assemble(day, List.of(expiresExactly), false,
                LocalDate.of(2020, 1, 1), false), day, 0);
        var expiresAfter = new PunchExemptionRoleIntervalRow(EMPLOYEE, start.minusSeconds(86400), start.plusSeconds(1));
        assertScheduled(assemble(day, List.of(expiresAfter), false,
                LocalDate.of(2020, 1, 1), false), day, 480);
    }

    @Test
    void januaryOpenEndedListPreservesSeptemberHoursAndDoesNotCreatePunches() {
        LocalDate day = LocalDate.of(2026, 9, 17);
        var result = assemble(day, List.of(interval("2026-01-01", null)), false,
                LocalDate.of(2020, 1, 1), true);
        assertScheduled(result, day, 480);
        assertOvertime(result, 150);
    }

    @Test
    void liuSeptember14EmploymentAndExemptionNeverCreatePreHireFacts() {
        LocalDate hire = LocalDate.of(2026, 9, 14);
        var periods = List.of(interval("2026-09-14", "2026-10-01"));
        var before = assemble(hire.minusDays(1), periods, false, hire, false);
        assertThat(before.calculatedFacts()).noneSatisfy(value ->
                assertThat(value.facts().dailyFact().businessDate()).isBefore(hire));
        assertThat(before.oaDocumentFacts()).isEmpty();
        var hired = assemble(hire, periods, false, hire, false);
        assertThat(hired.calculatedFacts()).allSatisfy(value ->
                assertThat(value.facts().dailyFact().businessDate()).isAfterOrEqualTo(hire));
        var daily = hired.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.confirmedScheduledWorkMinutes()).isEqualTo(480);
        assertThat(daily.missingPunchCount()).isZero();
    }

    private static void assertScheduled(PublishCommand result, LocalDate day, long minutes) {
        assertThat(result.calculatedFacts().stream()
                .filter(value -> day.equals(value.facts().dailyFact().businessDate())).toList())
                .singleElement().satisfies(value -> {
            var fact = value.facts().dailyFact();
            assertThat(fact.businessDate()).isEqualTo(day);
            assertThat(fact.confirmedScheduledWorkMinutes()).isEqualTo(minutes);
            assertThat(fact.firstPunchAt()).isNull();
            assertThat(fact.lastPunchAt()).isNull();
            if (minutes > 0) assertThat(fact.missingPunchCount()).isZero();
            else assertThat(fact.missingPunchCount()).isPositive();
        });
    }

    private static void assertOvertime(PublishCommand result, long minutes) {
        LocalDate day = result.oaDocumentFacts().getFirst().intervalStart().atZone(ZONE).toLocalDate();
        assertThat(result.calculatedFacts().stream()
                .filter(value -> day.equals(value.facts().dailyFact().businessDate())).toList())
                .singleElement().satisfies(value ->
                assertThat(value.facts().dailyFact().paidOvertimeMinutes()).isEqualTo(minutes));
        assertThat(result.oaDocumentFacts()).singleElement().satisfies(value -> {
            assertThat(value.recognizedMinutes()).isEqualTo(minutes);
            assertThat(value.sourceStatus()).isEqualTo("APPROVED");
        });
        var exceptionTypes = result.calculatedFacts().stream()
                .filter(value -> day.equals(value.facts().dailyFact().businessDate()))
                .flatMap(value -> value.facts().exceptionFacts().stream())
                .map(value -> value.exceptionType()).toList();
        if (minutes > 0) {
            assertThat(exceptionTypes).doesNotContain("OVERTIME_FORM_BEYOND_LAST_PUNCH");
        } else {
            assertThat(exceptionTypes).contains("OVERTIME_FORM_BEYOND_LAST_PUNCH");
        }
    }

    private static PunchExemptionRoleIntervalRow interval(String from, String to) {
        return new PunchExemptionRoleIntervalRow(EMPLOYEE,
                LocalDate.parse(from).atStartOfDay(ZONE).toInstant(),
                to == null ? null : LocalDate.parse(to).atStartOfDay(ZONE).toInstant());
    }

    private static PublishCommand assemble(LocalDate day, List<PunchExemptionRoleIntervalRow> intervals,
            boolean executive, LocalDate hired, boolean overtime) {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        when(mapper.findAttendanceSourceVersions(COMPANY, CUTOFF)).thenReturn(List.of(
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), CUTOFF),
                new SourceInputVersionRow("oa", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), CUTOFF)));
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(EMPLOYEE, "version", NUMBER, "刘梓轩", "assignment",
                        "organization", "org-version", "现场技术服务组", hired, null, hired, null, hired, null)));
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(List.of(new CalendarDayRow(EMPLOYEE, day, "WEEKDAY", 1, 1)));
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(List.of(segment(day, "am", 8, 30, 12, 0), segment(day, "pm", 13, 30, 18, 0)));
        if (executive) when(mapper.findPunchExemptionRoleIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(intervals);
        else when(mapper.findStandingPunchExemptionIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(intervals);
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(List.of(new AttendancePolicyRow(
                        EMPLOYEE, day,
                        1, 1, true, 15,
                        1, true, 15, 1, false,
                        1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS",
                        1, true, 48)));
        if (overtime) {
            Instant from = day.atTime(18, 30).atZone(ZONE).toInstant();
            Instant to = day.atTime(21, 0).atZone(ZONE).toInstant();
            when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                    .thenReturn(List.of(new OaDocumentRow("OVERTIME:1", "OVERTIME", OvertimeType.PAID, null,
                            NUMBER, from, to, "Asia/Shanghai", from.minusSeconds(3600), true, null, null, "oa", EMPLOYEE)));
            when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                    .thenReturn(List.of(new OaReportFactRow("report-1", "OVERTIME:1", "OVERTIME", null,
                            EMPLOYEE, NUMBER, "assignment", from, to, "APPROVED", "v1", OvertimeType.PAID)));
        }
        return new FullCalculationEngineOrchestrator(mapper).assemble(
                COMPANY, YearMonth.from(day), PeriodState.OPEN, "admin", CUTOFF);
    }

    private static ShiftSegmentRow segment(LocalDate day, String key, int h, int m, int eh, int em) {
        Instant from = day.atTime(h, m).atZone(ZONE).toInstant();
        Instant to = day.atTime(eh, em).atZone(ZONE).toInstant();
        return new ShiftSegmentRow(EMPLOYEE, day, key, from, to,
                from.minusSeconds(3600), from.plusSeconds(3600), to.minusSeconds(3600), to.plusSeconds(3600));
    }
}
