package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationInputSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.GraceConsumptionSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.IntervalEvidence;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.RuleHit;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ScheduledWorkSegment;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.SegmentKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.attendance.calculation.domain.DeterministicAttendanceCalculator;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionContext;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutingOvertimeWarningDayWindowTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate FIRST_DAY = LocalDate.parse("2026-09-29");
    private final DeterministicAttendanceCalculator calculator =
            new DeterministicAttendanceCalculator();

    @Test
    void tangTwoDayOutingHasSeparateWarningsAndNoInferredPaidOvertime() {
        var outing = outing("2026-09-29T08:30", "2026-09-30T20:30");
        var first = calculate(FIRST_DAY, List.of(outing));
        var second = calculate(FIRST_DAY.plusDays(1), List.of(outing));

        assertWarning(first, 360);
        assertWarning(second, 150);
        assertNormalAttendanceOnly(first);
        assertNormalAttendanceOnly(second);
        assertProjectedWarning(first, FIRST_DAY, 360);
        assertProjectedWarning(second, FIRST_DAY.plusDays(1), 150);
    }

    @Test
    void threeDayOutingDoesNotRepeatFollowingDaysInEarlierWarnings() {
        var outing = outing("2026-09-28T08:30", "2026-09-30T20:30");
        assertWarning(calculate(FIRST_DAY.minusDays(1), List.of(outing)), 360);
        assertWarning(calculate(FIRST_DAY, List.of(outing)), 360);
        assertWarning(calculate(FIRST_DAY.plusDays(1), List.of(outing)), 150);
    }

    @Test
    void midnightEndBelongsOnlyToThePreviousDay() {
        var outing = outing("2026-09-29T08:30", "2026-09-30T00:00");
        assertWarning(calculate(FIRST_DAY, List.of(outing)), 360);
        assertThat(warnings(calculate(FIRST_DAY.plusDays(1), List.of(outing))))
                .isEmpty();
    }

    @Test
    void outingStartingAtNextMidnightDoesNotCreatePreviousDayWarning() {
        var outing = outing("2026-09-30T00:00", "2026-09-30T20:30");
        assertThat(warnings(calculate(FIRST_DAY, List.of(outing)))).isEmpty();
        assertWarning(calculate(FIRST_DAY.plusDays(1), List.of(outing)), 150);
    }

    @Test
    void singleDayUndeclaredOverrunKeepsItsMinutesAndReviewState() {
        var result = calculate(FIRST_DAY,
                List.of(outing("2026-09-29T08:30", "2026-09-29T20:30")));
        assertWarning(result, 150);
        assertNormalAttendanceOnly(result);
        assertProjectedWarning(result, FIRST_DAY, 150);
    }

    @Test
    void outingStartingAfterOffTimeUsesItsActualStart() {
        assertWarning(calculate(FIRST_DAY,
                List.of(outing("2026-09-29T19:00", "2026-09-29T20:30"))), 90);
    }

    @Test
    void declaredOvertimeCoveringClippedDaySuppressesOnlyThatDayWarning() {
        var outing = outing("2026-09-29T08:30", "2026-09-30T20:30");
        var declared = overtime("2026-09-29T18:00", "2026-09-30T00:00");
        assertThat(warnings(calculate(FIRST_DAY, List.of(outing, declared))))
                .isEmpty();
        assertWarning(calculate(FIRST_DAY.plusDays(1), List.of(outing, declared)),
                150);
    }

    @Test
    void singleDayDeclaredOvertimeStillSuppressesTheWarning() {
        assertThat(warnings(calculate(FIRST_DAY, List.of(
                outing("2026-09-29T08:30", "2026-09-29T20:30"),
                overtime("2026-09-29T18:00", "2026-09-29T20:30")))))
                .isEmpty();
    }

    @Test
    void partialOvertimeDeclarationKeepsExistingFullWindowReviewRule() {
        assertWarning(calculate(FIRST_DAY, List.of(
                outing("2026-09-29T08:30", "2026-09-29T20:30"),
                overtime("2026-09-29T18:00", "2026-09-29T19:00"))), 150);
    }

    @Test
    void endingAtOffTimeHasNoOverrun() {
        assertThat(warnings(calculate(FIRST_DAY,
                List.of(outing("2026-09-29T08:30", "2026-09-29T18:00")))))
                .isEmpty();
    }

    @Test
    void calendarBoundaryUsesTheSnapshotBusinessZone() {
        ZoneId zone = ZoneId.of("UTC");
        var outing = evidence(EvidenceKind.OUTING,
                at("2026-09-29T08:30", zone),
                at("2026-09-30T20:30", zone));
        assertWarning(calculate(FIRST_DAY, zone, shifts(FIRST_DAY, zone),
                List.of(outing)), 360);
    }

    @Test
    void overnightShiftRetainsOverrunAfterItsNextMorningOffTime() {
        var shift = segment(FIRST_DAY, "night", "2026-09-29T22:00",
                "2026-09-30T06:00", ZONE);
        var outing = outing("2026-09-29T22:00", "2026-09-30T08:00");
        assertWarning(calculate(FIRST_DAY, ZONE, List.of(shift),
                List.of(outing)), 120);
    }

    private void assertNormalAttendanceOnly(DailyAttendanceResult result) {
        assertThat(result.metrics().confirmedScheduledWorkMinutes()).isEqualTo(480);
        assertThat(result.metrics().actualWorkMinutes()).isEqualTo(480);
        assertThat(result.metrics().recognizedOvertimeMinutes()).isZero();
        assertThat(result.metrics().totalOvertimeMinutes()).isZero();
        assertThat(result.metrics().absenceMinutes()).isZero();
    }

    private void assertWarning(DailyAttendanceResult result, long minutes) {
        assertThat(warnings(result)).singleElement().satisfies(hit -> {
            assertThat(hit.rawMinutes()).isEqualTo(minutes);
            assertThat(hit.includedMinutes()).isZero();
        });
    }

    private List<RuleHit> warnings(DailyAttendanceResult result) {
        return result.ruleHits().stream()
                .filter(hit -> "OUTING_OVERTIME_UNDECLARED".equals(hit.ruleCode()))
                .toList();
    }

    private void assertProjectedWarning(
            DailyAttendanceResult result, LocalDate date, long minutes) {
        var facts = new AttendanceReportFactProjector().project(result,
                new ProjectionContext("fact:" + date, "company", "employee",
                        "E001", "测试员工", "org", "org-version", "测试部门",
                        date, DayType.WEEKDAY, "标准班", null, null));
        assertThat(facts.exceptionFacts())
                .filteredOn(fact -> "OUTING_OVERTIME_UNDECLARED"
                        .equals(fact.exceptionType()))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.minutes()).isEqualTo(minutes);
                    assertThat(fact.severity().name()).isEqualTo("WARNING");
                    assertThat(fact.state().name()).isEqualTo("PENDING_REVIEW");
                });
        assertThat(facts.dailyFact().totalOvertimeMinutes()).isZero();
        assertThat(facts.dailyFact().missingPunchCount()).isZero();
    }

    private DailyAttendanceResult calculate(
            LocalDate date, List<IntervalEvidence> evidence) {
        return calculate(date, ZONE, shifts(date, ZONE), evidence);
    }

    private DailyAttendanceResult calculate(LocalDate date, ZoneId zone,
            List<ScheduledWorkSegment> shifts, List<IntervalEvidence> evidence) {
        var snapshot = new CalculationInputSnapshot(
                "company", "employee", "employment", date, zone,
                Instant.parse("2026-10-06T14:27:44.445796Z"),
                shifts, List.of(), evidence, List.of(),
                new GraceConsumptionSnapshot("employee", YearMonth.from(date),
                        0, "grace"),
                false, SyntheticAttendanceFixtures.defaultPolicy(),
                "config", "config-digest", "evidence", "evidence-digest",
                "adjustment-digest", "period", 1, "open-token", "algorithm",
                "request", "correlation");
        return calculator.calculate("outing-day-window", snapshot);
    }

    private List<ScheduledWorkSegment> shifts(LocalDate date, ZoneId zone) {
        return List.of(
                segment(date, "am", date + "T08:30", date + "T12:00", zone),
                segment(date, "pm", date + "T13:30", date + "T18:00", zone));
    }

    private ScheduledWorkSegment segment(LocalDate date, String id,
            String start, String end, ZoneId zone) {
        var interval = new TimeInterval(at(start, zone), at(end, zone));
        return new ScheduledWorkSegment(id, date, interval, interval, interval,
                SegmentKind.SCHEDULED_WORK);
    }

    private IntervalEvidence outing(String start, String end) {
        return evidence(EvidenceKind.OUTING, at(start, ZONE), at(end, ZONE));
    }

    private IntervalEvidence overtime(String start, String end) {
        return evidence(EvidenceKind.OVERTIME, at(start, ZONE), at(end, ZONE));
    }

    private IntervalEvidence evidence(EvidenceKind kind, Instant start, Instant end) {
        return new IntervalEvidence(kind.name(), kind, new TimeInterval(start, end),
                "document:" + kind, Instant.parse("2026-09-28T00:00:00Z"), true,
                kind == EvidenceKind.OVERTIME ? OvertimeType.PAID : null);
    }

    private Instant at(String local, ZoneId zone) {
        return LocalDateTime.parse(local).atZone(zone).toInstant();
    }
}
