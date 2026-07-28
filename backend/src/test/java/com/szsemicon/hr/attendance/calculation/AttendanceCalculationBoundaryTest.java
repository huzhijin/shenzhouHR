package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.AdjustmentFact;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationPolicy;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceDecisionStatus;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.IntervalEvidence;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.MealDeductionRule;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchDirection;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultCategory;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.attendance.calculation.domain.DeterministicAttendanceCalculator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceCalculationBoundaryTest {

    private final DeterministicAttendanceCalculator calculator =
            new DeterministicAttendanceCalculator();

    @Test
    void same_priority_evidence_conflicts_and_adjustment_has_priority() {
        var segment = SyntheticAttendanceFixtures.segment(
                "synthetic-segment",
                "2026-07-15T01:00:00Z",
                "2026-07-15T05:00:00Z");
        var leave = evidence(
                "synthetic-leave",
                EvidenceKind.LEAVE,
                segment.interval(),
                Instant.parse("2026-07-14T00:00:00Z"));
        var outing = evidence(
                "synthetic-outing",
                EvidenceKind.OUTING,
                segment.interval(),
                Instant.parse("2026-07-14T00:00:00Z"));
        var conflicted = calculator.calculate(
                "synthetic-conflict-v1",
                SyntheticAttendanceFixtures.snapshot(
                        List.of(segment),
                        List.of(),
                        List.of(leave, outing),
                        List.of(),
                        SyntheticAttendanceFixtures.defaultPolicy(),
                        SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF));

        assertThat(conflicted.items())
                .extracting(item -> item.category())
                .containsExactly(ResultCategory.EVIDENCE_CONFLICT);
        assertThat(conflicted.evidenceDecisions())
                .extracting(value -> value.status())
                .containsOnly(EvidenceDecisionStatus.CONFLICT);
        assertThat(conflicted.exceptionFingerprints()).hasSize(1);

        var adjustment = new AdjustmentFact(
                "synthetic-adjustment",
                segment.interval(),
                ResultCategory.SCHEDULED_WORK,
                "synthetic adjudication",
                "synthetic-actor",
                "synthetic-scope-decision",
                "synthetic-approval",
                "synthetic-adjustment-request",
                "synthetic-adjustment-correlation",
                "synthetic-open-token-v1",
                0,
                null);
        var adjusted = calculator.calculate(
                "synthetic-adjusted-v2",
                SyntheticAttendanceFixtures.snapshot(
                        List.of(segment),
                        List.of(),
                        List.of(leave, outing),
                        List.of(adjustment),
                        SyntheticAttendanceFixtures.defaultPolicy(),
                        SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF));

        assertThat(adjusted.items())
                .extracting(item -> item.category())
                .containsExactly(ResultCategory.SCHEDULED_WORK);
        assertThat(adjusted.evidenceDecisions())
                .filteredOn(value ->
                        value.evidenceId().equals("synthetic-adjustment"))
                .extracting(value -> value.status())
                .containsExactly(EvidenceDecisionStatus.SELECTED);
    }

    @Test
    void late_grace_boundaries_preserve_raw_and_chargeable_minutes() {
        var atFifteen = lateResult(15);
        assertThat(atFifteen.ruleHits())
                .filteredOn(value -> value.ruleCode()
                        .equals("MONTHLY_LATE_GRACE_CONSUMED"))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.rawMinutes()).isEqualTo(15);
                    assertThat(value.includedMinutes()).isZero();
                });

        var atSixteen = lateResult(16);
        assertThat(atSixteen.ruleHits())
                .filteredOn(value -> value.ruleCode()
                        .equals("LATE_CHARGEABLE"))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.rawMinutes()).isEqualTo(16);
                    assertThat(value.includedMinutes()).isEqualTo(16);
                });
    }

    @Test
    void overdue_missing_punch_affects_only_its_segment() {
        var morning = SyntheticAttendanceFixtures.segment(
                "synthetic-morning",
                "2026-07-15T01:00:00Z",
                "2026-07-15T05:00:00Z");
        var afternoon = SyntheticAttendanceFixtures.segment(
                "synthetic-afternoon",
                "2026-07-15T06:00:00Z",
                "2026-07-15T10:00:00Z");
        var result = calculator.calculate(
                "synthetic-missing-v1",
                SyntheticAttendanceFixtures.snapshot(
                        List.of(morning, afternoon),
                        List.of(
                                SyntheticAttendanceFixtures.punch(
                                        "synthetic-morning-in",
                                        "2026-07-15T01:00:00Z",
                                        PunchDirection.ENTRY),
                                SyntheticAttendanceFixtures.punch(
                                        "synthetic-afternoon-in",
                                        "2026-07-15T06:00:00Z",
                                        PunchDirection.ENTRY),
                                SyntheticAttendanceFixtures.punch(
                                        "synthetic-afternoon-out",
                                        "2026-07-15T10:00:00Z",
                                        PunchDirection.EXIT)),
                        List.of(),
                        List.of(),
                        SyntheticAttendanceFixtures.defaultPolicy(),
                        Instant.parse("2026-07-24T00:00:00Z")));

        assertThat(result.metrics().absenceMinutes()).isEqualTo(240);
        assertThat(result.metrics().confirmedScheduledWorkMinutes())
                .isEqualTo(240);
        assertThat(result.items())
                .filteredOn(value ->
                        value.category() == ResultCategory.ABSENCE)
                .extracting(value -> value.segmentId())
                .containsExactly("synthetic-morning");
    }

    @Test
    void timely_pending_submission_prevents_overdue_absence() {
        var segment = SyntheticAttendanceFixtures.segment(
                "synthetic-segment",
                "2026-07-15T01:00:00Z",
                "2026-07-15T05:00:00Z");
        CalculationPolicy base =
                SyntheticAttendanceFixtures.defaultPolicy();
        var policy = new CalculationPolicy(
                base.lateGraceMaxMinutes(),
                base.monthlyLateGraceUses(),
                base.correctionDeadline(),
                true,
                null,
                base.overtimeSubmissionDeadlineMinutes(),
                List.of());
        var result = calculator.calculate(
                "synthetic-pending-v1",
                SyntheticAttendanceFixtures.snapshot(
                        List.of(segment),
                        List.of(SyntheticAttendanceFixtures.punch(
                                "synthetic-in",
                                "2026-07-15T01:00:00Z",
                                PunchDirection.ENTRY)),
                        List.of(),
                        List.of(),
                        policy,
                        Instant.parse("2026-07-25T00:00:00Z")));

        assertThat(result.metrics().absenceMinutes()).isZero();
        assertThat(result.items())
                .extracting(item -> item.category())
                .containsExactly(ResultCategory.MISSING_PUNCH_PENDING);
    }

    @Test
    void cross_midnight_boundary_punch_is_consumed_only_once() {
        var crossMidnight = SyntheticAttendanceFixtures.segment(
                "synthetic-cross-midnight",
                "2026-07-15T12:00:00Z",
                "2026-07-15T18:00:00Z");
        var following = SyntheticAttendanceFixtures.segment(
                "synthetic-following",
                "2026-07-15T18:00:00Z",
                "2026-07-15T22:00:00Z");
        var result = calculator.calculate(
                "synthetic-cross-midnight-v1",
                SyntheticAttendanceFixtures.snapshot(
                        List.of(crossMidnight, following),
                        List.of(
                                SyntheticAttendanceFixtures.punch(
                                        "synthetic-night-in",
                                        "2026-07-15T12:00:00Z",
                                        PunchDirection.ENTRY),
                                SyntheticAttendanceFixtures.punch(
                                        "synthetic-boundary-auto",
                                        "2026-07-15T18:00:00Z",
                                        PunchDirection.AUTO),
                                SyntheticAttendanceFixtures.punch(
                                        "synthetic-following-out",
                                        "2026-07-15T22:00:00Z",
                                        PunchDirection.EXIT)),
                        List.of(),
                        List.of(),
                        SyntheticAttendanceFixtures.defaultPolicy(),
                        SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF));

        assertThat(result.consumedPunchEventIds())
                .contains("synthetic-boundary-auto");
        assertThat(result.items())
                .filteredOn(value -> value.segmentId()
                        .equals("synthetic-following"))
                .extracting(value -> value.category())
                .contains(ResultCategory.MISSING_PUNCH_PENDING);
    }

    @Test
    void overtime_deadline_and_meal_deduction_boundaries_are_minutes_exact() {
        var timely = overtimeResult(47 * 60 + 59);
        assertThat(timely.metrics().extendedPresenceMinutes()).isEqualTo(120);
        assertThat(timely.metrics().recognizedOvertimeMinutes()).isEqualTo(90);

        var late = overtimeResult(48 * 60 + 1);
        assertThat(late.metrics().extendedPresenceMinutes()).isEqualTo(120);
        assertThat(late.metrics().recognizedOvertimeMinutes()).isZero();
    }

    @Test
    void adjustment_requires_reason_approval_scope_and_period_metadata() {
        var interval = SyntheticAttendanceFixtures.interval(
                "2026-07-15T01:00:00Z",
                "2026-07-15T05:00:00Z");
        assertThatThrownBy(() -> new AdjustmentFact(
                "synthetic-adjustment",
                interval,
                ResultCategory.SCHEDULED_WORK,
                " ",
                "synthetic-actor",
                "synthetic-scope",
                "synthetic-approval",
                "synthetic-request",
                "synthetic-correlation",
                "synthetic-token",
                0,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void calculation_inputs_and_prior_results_are_not_mutated() {
        var input = SyntheticAttendanceFixtures.mixedDay(false);
        var first = calculator.calculate("synthetic-v1", input);
        var second = calculator.calculate("synthetic-v2", input);

        assertThat(input.intervalEvidence()).hasSize(1);
        assertThat(first.items()).isEqualTo(second.items());
        assertThat(first.resultDigest()).isEqualTo(second.resultDigest());
        assertThatThrownBy(() -> input.intervalEvidence().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private DailyAttendanceResult lateResult(int lateMinutes) {
        var segment = SyntheticAttendanceFixtures.segment(
                "synthetic-late-segment",
                "2026-07-15T01:00:00Z",
                "2026-07-15T05:00:00Z");
        return calculator.calculate(
                "synthetic-late-" + lateMinutes,
                SyntheticAttendanceFixtures.snapshot(
                        List.of(segment),
                        List.of(
                                SyntheticAttendanceFixtures.punch(
                                        "synthetic-late-in-" + lateMinutes,
                                        Instant.parse("2026-07-15T01:00:00Z")
                                                .plusSeconds(lateMinutes * 60L)
                                                .toString(),
                                        PunchDirection.ENTRY),
                                SyntheticAttendanceFixtures.punch(
                                        "synthetic-late-out-" + lateMinutes,
                                        "2026-07-15T05:00:00Z",
                                        PunchDirection.EXIT)),
                        List.of(),
                        List.of(),
                        SyntheticAttendanceFixtures.defaultPolicy(),
                        SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF));
    }

    private DailyAttendanceResult overtimeResult(int submissionLagMinutes) {
        var interval = SyntheticAttendanceFixtures.interval(
                "2026-07-15T10:00:00Z",
                "2026-07-15T12:00:00Z");
        var authorization = evidence(
                "synthetic-overtime",
                EvidenceKind.OVERTIME,
                interval,
                interval.end().plusSeconds(submissionLagMinutes * 60L));
        var mealRule = new MealDeductionRule(
                "synthetic-meal-rule",
                SyntheticAttendanceFixtures.interval(
                        "2026-07-15T10:30:00Z",
                        "2026-07-15T11:00:00Z"),
                30,
                true);
        var policy = new CalculationPolicy(
                15,
                1,
                Instant.parse("2026-07-23T15:59:59Z"),
                false,
                null,
                48 * 60,
                List.of(mealRule));
        return calculator.calculate(
                "synthetic-overtime-" + submissionLagMinutes,
                SyntheticAttendanceFixtures.snapshot(
                        List.of(),
                        List.of(
                                SyntheticAttendanceFixtures.punch(
                                        "synthetic-overtime-in",
                                        interval.start().toString(),
                                        PunchDirection.ENTRY),
                                SyntheticAttendanceFixtures.punch(
                                        "synthetic-overtime-out",
                                        interval.end().toString(),
                                        PunchDirection.EXIT)),
                        List.of(authorization),
                        List.of(),
                        policy,
                        SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF));
    }

    private IntervalEvidence evidence(
            String id,
            EvidenceKind kind,
            TimeInterval interval,
            Instant firstSubmittedAt) {
        return new IntervalEvidence(
                id,
                kind,
                interval,
                "synthetic-source-" + id,
                firstSubmittedAt,
                true);
    }
}
