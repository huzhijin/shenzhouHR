package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceDecisionStatus;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.IntervalEvidence;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchDirection;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchEvent;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultCategory;
import com.szsemicon.hr.attendance.calculation.domain.DeterministicAttendanceCalculator;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionContext;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExemptionOutingCalculationTest {

    private static final String START = "2026-07-15T01:00:00Z";
    private static final String MIDPOINT = "2026-07-15T05:00:00Z";
    private static final String END = "2026-07-15T09:00:00Z";

    private final DeterministicAttendanceCalculator calculator =
            new DeterministicAttendanceCalculator();

    @Test
    void approvedExemptionGrantsAttendanceWithoutPunch() {
        DailyFact fact = calculate(
                List.of(),
                List.of(evidence(
                        "exempt-full",
                        EvidenceKind.EXEMPT_PUNCH,
                        START,
                        END)),
                SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF);

        assertThat(fact.scheduledAttendanceDays()).isEqualTo(1);
        assertThat(fact.actualAttendanceDays()).isEqualTo(1);
        assertThat(fact.confirmedScheduledWorkMinutes()).isEqualTo(480);
        assertThat(fact.missingPunchCount()).isZero();
    }

    @Test
    void partialExemptionAndRemainingValidPunchCompleteTheDay() {
        DailyFact fact = calculate(
                List.of(punch("afternoon-out", END, PunchDirection.EXIT)),
                List.of(evidence(
                        "exempt-morning",
                        EvidenceKind.EXEMPT_PUNCH,
                        START,
                        MIDPOINT)),
                SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF);

        assertThat(fact.actualAttendanceDays()).isEqualTo(1);
        assertThat(fact.confirmedScheduledWorkMinutes()).isEqualTo(480);
        assertThat(fact.missingPunchCount()).isZero();
    }

    @Test
    void approvedOutingAndOneValidDailyPunchGrantFullAttendance() {
        DailyFact fact = calculate(
                List.of(punch(
                        "outing-punch",
                        "2026-07-15T08:00:00Z",
                        PunchDirection.AUTO)),
                List.of(evidence(
                        "outing-half-day",
                        EvidenceKind.OUTING,
                        "2026-07-15T02:00:00Z",
                        "2026-07-15T06:00:00Z")),
                SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF);

        assertThat(fact.scheduledAttendanceDays()).isEqualTo(1);
        assertThat(fact.actualAttendanceDays()).isEqualTo(1);
        assertThat(fact.confirmedScheduledWorkMinutes()).isEqualTo(480);
        assertThat(fact.absenceMinutes()).isZero();
        assertThat(fact.missingPunchCount()).isZero();
    }

    @Test
    void approvedOutingWithoutPunchIsAbsentAndCountsBothMissingSides() {
        DailyFact fact = calculate(
                List.of(),
                List.of(evidence(
                        "outing-no-punch",
                        EvidenceKind.OUTING,
                        "2026-07-15T02:00:00Z",
                        "2026-07-15T06:00:00Z")),
                SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF);

        assertThat(fact.scheduledAttendanceDays()).isEqualTo(1);
        assertThat(fact.actualAttendanceDays()).isZero();
        assertThat(fact.absenceMinutes()).isEqualTo(480);
        assertThat(fact.missingPunchCount()).isEqualTo(2);
    }

    @Test
    void exemptionTakesPrecedenceOverOutingPunchRequirement() {
        var result = calculateResult(
                List.of(),
                List.of(
                        evidence(
                                "outing-no-punch",
                                EvidenceKind.OUTING,
                                START,
                                END),
                        evidence(
                                "exempt-full",
                                EvidenceKind.EXEMPT_PUNCH,
                                START,
                                END)),
                SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF);
        DailyFact fact = project(result, List.of());

        assertThat(fact.actualAttendanceDays()).isEqualTo(1);
        assertThat(fact.missingPunchCount()).isZero();
        assertThat(result.items())
                .extracting(item -> item.category())
                .containsExactly(ResultCategory.EXEMPT_WORK);
        assertThat(result.evidenceDecisions())
                .filteredOn(decision -> decision.evidenceId()
                        .equals("outing-no-punch"))
                .singleElement()
                .satisfies(decision -> {
                    assertThat(decision.status())
                            .isEqualTo(EvidenceDecisionStatus.REJECTED);
                    assertThat(decision.reasonCode())
                            .isEqualTo("EXEMPTION_PRECEDENCE");
                });
    }

    @Test
    void tripEvidenceNeverGrantsAttendance() {
        DailyFact fact = calculate(
                List.of(),
                List.of(evidence(
                        "legacy-trip",
                        EvidenceKind.TRIP,
                        START,
                        END)),
                Instant.parse("2026-07-24T00:00:00Z"));

        assertThat(fact.actualAttendanceDays()).isZero();
        assertThat(fact.absenceMinutes()).isEqualTo(480);
        assertThat(fact.missingPunchCount()).isEqualTo(2);
    }

    private DailyFact calculate(
            List<PunchEvent> punches,
            List<IntervalEvidence> evidence,
            Instant knowledgeCutoff) {
        return project(
                calculateResult(punches, evidence, knowledgeCutoff),
                punches);
    }

    private DailyAttendanceResult calculateResult(
                    List<PunchEvent> punches,
                    List<IntervalEvidence> evidence,
                    Instant knowledgeCutoff) {
        var segment = SyntheticAttendanceFixtures.segment(
                "standard-day", START, END);
        var snapshot = SyntheticAttendanceFixtures.snapshot(
                List.of(segment),
                punches,
                evidence,
                List.of(),
                SyntheticAttendanceFixtures.defaultPolicy(),
                knowledgeCutoff);
        return calculator.calculate("exemption-outing-v1", snapshot);
    }

    private DailyFact project(
            DailyAttendanceResult result,
            List<PunchEvent> punches) {
        return new AttendanceReportFactProjector()
                .project(result, new ProjectionContext(
                        "group-7-fact",
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

    private static IntervalEvidence evidence(
            String id,
            EvidenceKind kind,
            String start,
            String end) {
        return new IntervalEvidence(
                id,
                kind,
                SyntheticAttendanceFixtures.interval(start, end),
                "oa-" + id,
                Instant.parse("2026-07-14T00:00:00Z"),
                true);
    }

    private static PunchEvent punch(
            String id,
            String instant,
            PunchDirection direction) {
        return SyntheticAttendanceFixtures.punch(id, instant, direction);
    }
}
