package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.IntervalEvidence;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchDirection;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchEvent;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ScheduledWorkSegment;
import com.szsemicon.hr.attendance.calculation.domain.DeterministicAttendanceCalculator;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionContext;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MissingPunchCountCalculationTest {

    private static final String START = "2026-07-15T01:00:00Z";
    private static final String MIDPOINT = "2026-07-15T05:00:00Z";
    private static final String END = "2026-07-15T09:00:00Z";

    private final DeterministicAttendanceCalculator calculator =
            new DeterministicAttendanceCalculator();

    @Test
    void fullDayWithoutPunchCountsBothEntryAndExitAsMissing() {
        DailyFact fact = calculate(List.of(), List.of(), false);

        assertThat(fact.missingPunchCount()).isEqualTo(2);
        assertThat(fact.actualAttendanceDays()).isZero();
    }

    @Test
    void entryOnlyLeavesOneExitPunchMissing() {
        DailyFact fact = calculate(
                List.of(punch("entry", START, PunchDirection.ENTRY)),
                List.of(),
                false);

        assertThat(fact.missingPunchCount()).isEqualTo(1);
    }

    @Test
    void exitOnlyLeavesOneEntryPunchMissing() {
        DailyFact fact = calculate(
                List.of(punch("exit", END, PunchDirection.EXIT)),
                List.of(),
                false);

        assertThat(fact.missingPunchCount()).isEqualTo(1);
    }

    @Test
    void pairedEntryAndExitHaveNoMissingPunches() {
        DailyFact fact = calculate(
                List.of(
                        punch("entry", START, PunchDirection.ENTRY),
                        punch("exit", END, PunchDirection.EXIT)),
                List.of(),
                false);

        assertThat(fact.missingPunchCount()).isZero();
        assertThat(fact.actualAttendanceDays()).isEqualTo(1);
    }

    @Test
    void approvedFullDayExemptionRemovesBothExpectedPunchSides() {
        DailyFact fact = calculate(
                List.of(),
                List.of(exemption("full-exemption", START, END)),
                false);

        assertThat(fact.missingPunchCount()).isZero();
        assertThat(fact.actualAttendanceDays()).isEqualTo(1);
    }

    @Test
    void punchExemptRoleNeverAccumulatesMissingPunches() {
        DailyFact fact = calculate(List.of(), List.of(), true);

        assertThat(fact.missingPunchCount()).isZero();
        assertThat(fact.actualAttendanceDays()).isEqualTo(1);
    }

    @Test
    void morningExemptionLeavesOnlyEveningExitExpected() {
        DailyFact fact = calculate(
                List.of(),
                List.of(exemption("morning-exemption", START, MIDPOINT)),
                false);

        assertThat(fact.missingPunchCount()).isEqualTo(1);
    }

    private DailyFact calculate(
            List<PunchEvent> punches,
            List<IntervalEvidence> evidence,
            boolean punchExempt) {
        ScheduledWorkSegment segment = SyntheticAttendanceFixtures.segment(
                "standard-day", START, END);
        var snapshot = SyntheticAttendanceFixtures.snapshot(
                List.of(segment),
                punches,
                evidence,
                List.of(),
                punchExempt,
                SyntheticAttendanceFixtures.defaultPolicy(),
                SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF);
        var result = calculator.calculate("missing-punch-v1", snapshot);
        return new AttendanceReportFactProjector()
                .project(result, new ProjectionContext(
                        "missing-punch-fact",
                        "synthetic-company",
                        "synthetic-employee-001",
                        "E001",
                        "测试员工",
                        "synthetic-organization",
                        "synthetic-organization-version",
                        "测试部门",
                        SyntheticAttendanceFixtures.BUSINESS_DATE,
                        DayType.WEEKDAY,
                        "标准班",
                        punches.stream()
                                .map(PunchEvent::instant)
                                .min(Instant::compareTo)
                                .orElse(null),
                        punches.stream()
                                .map(PunchEvent::instant)
                                .max(Instant::compareTo)
                                .orElse(null)))
                .dailyFact();
    }

    private PunchEvent punch(
            String id, String instant, PunchDirection direction) {
        return SyntheticAttendanceFixtures.punch(id, instant, direction);
    }

    private IntervalEvidence exemption(
            String id, String start, String end) {
        return new IntervalEvidence(
                id,
                EvidenceKind.EXEMPT_PUNCH,
                SyntheticAttendanceFixtures.interval(start, end),
                "oa-" + id,
                Instant.parse("2026-07-14T00:00:00Z"),
                true);
    }
}
