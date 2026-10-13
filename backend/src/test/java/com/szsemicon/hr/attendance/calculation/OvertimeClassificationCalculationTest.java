package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.IntervalEvidence;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchDirection;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchEvent;
import com.szsemicon.hr.attendance.calculation.domain.DeterministicAttendanceCalculator;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OvertimeClassificationCalculationTest {

    private final DeterministicAttendanceCalculator calculator =
            new DeterministicAttendanceCalculator();

    @Test
    void paid_and_compensatory_overtime_preserve_their_types() {
        var paid = overtime(
                "paid", "2026-07-15T10:00:00Z",
                "2026-07-15T12:00:00Z", OvertimeType.PAID);
        var compensatory = overtime(
                "compensatory", "2026-07-15T12:30:00Z",
                "2026-07-15T13:30:00Z", OvertimeType.COMPENSATORY);

        var result = calculator.calculate(
                "classified-paid-compensatory-v1",
                SyntheticAttendanceFixtures.snapshot(
                        List.of(),
                        List.of(
                                punch("paid-in", paid.interval().start(),
                                        PunchDirection.ENTRY),
                                punch("paid-out", paid.interval().end(),
                                        PunchDirection.EXIT),
                                punch("comp-in", compensatory.interval().start(),
                                        PunchDirection.ENTRY),
                                punch("comp-out", compensatory.interval().end(),
                                        PunchDirection.EXIT)),
                        List.of(paid, compensatory),
                        List.of(),
                        SyntheticAttendanceFixtures.defaultPolicy(),
                        SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF));

        assertThat(result.metrics().paidOvertimeMinutes()).isEqualTo(120);
        assertThat(result.metrics().compensatoryOvertimeMinutes())
                .isEqualTo(60);
        assertThat(result.metrics().voluntaryOvertimeMinutes()).isZero();
        assertThat(result.metrics().totalOvertimeMinutes()).isEqualTo(180);
        assertThat(result.metrics().recognizedOvertimeMinutes()).isEqualTo(180);
    }

    @Test
    void total_overtime_is_the_sum_of_all_three_types() {
        var paid = overtime(
                "paid", "2026-07-15T10:00:00Z",
                "2026-07-15T12:00:00Z", OvertimeType.PAID);
        var compensatory = overtime(
                "compensatory", "2026-07-15T12:30:00Z",
                "2026-07-15T13:30:00Z", OvertimeType.COMPENSATORY);
        var voluntary = overtime(
                "voluntary", "2026-07-15T14:00:00Z",
                "2026-07-15T14:30:00Z", OvertimeType.VOLUNTARY);

        var result = calculator.calculate(
                "classified-total-v1",
                SyntheticAttendanceFixtures.snapshot(
                        List.of(),
                        List.of(
                                punch("paid-in", paid.interval().start(),
                                        PunchDirection.ENTRY),
                                punch("paid-out", paid.interval().end(),
                                        PunchDirection.EXIT),
                                punch("comp-in", compensatory.interval().start(),
                                        PunchDirection.ENTRY),
                                punch("comp-out", compensatory.interval().end(),
                                        PunchDirection.EXIT),
                                punch("voluntary-in", voluntary.interval().start(),
                                        PunchDirection.ENTRY),
                                punch("voluntary-out", voluntary.interval().end(),
                                        PunchDirection.EXIT)),
                        List.of(paid, compensatory, voluntary),
                        List.of(),
                        SyntheticAttendanceFixtures.defaultPolicy(),
                        SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF));

        assertThat(result.metrics().paidOvertimeMinutes()).isEqualTo(120);
        assertThat(result.metrics().compensatoryOvertimeMinutes())
                .isEqualTo(60);
        assertThat(result.metrics().voluntaryOvertimeMinutes()).isEqualTo(30);
        assertThat(result.metrics().totalOvertimeMinutes())
                .isEqualTo(120 + 60 + 30);
    }

    @Test
    void unclassified_overtime_never_enters_recognized_or_typed_metrics() {
        var interval = SyntheticAttendanceFixtures.interval(
                "2026-07-15T10:00:00Z",
                "2026-07-15T12:00:00Z");
        var unclassified = new IntervalEvidence(
                "overtime-unclassified",
                EvidenceKind.OVERTIME,
                interval,
                "oa-unclassified",
                interval.end(),
                true);

        var result = calculator.calculate(
                "unclassified-isolated-v1",
                SyntheticAttendanceFixtures.snapshot(
                        List.of(),
                        List.of(
                                punch("unclassified-in", interval.start(),
                                        PunchDirection.ENTRY),
                                punch("unclassified-out", interval.end(),
                                        PunchDirection.EXIT)),
                        List.of(unclassified),
                        List.of(),
                        SyntheticAttendanceFixtures.defaultPolicy(),
                        SyntheticAttendanceFixtures.KNOWLEDGE_CUTOFF));

        assertThat(result.metrics().recognizedOvertimeMinutes()).isZero();
        assertThat(result.metrics().paidOvertimeMinutes()).isZero();
        assertThat(result.metrics().compensatoryOvertimeMinutes()).isZero();
        assertThat(result.metrics().voluntaryOvertimeMinutes()).isZero();
        assertThat(result.metrics().totalOvertimeMinutes()).isZero();
    }

    private IntervalEvidence overtime(
            String id, String start, String end, OvertimeType type) {
        var interval = SyntheticAttendanceFixtures.interval(start, end);
        return new IntervalEvidence(
                "overtime-" + id,
                EvidenceKind.OVERTIME,
                interval,
                "oa-" + id,
                interval.end(),
                true,
                type);
    }

    private PunchEvent punch(
            String id, Instant instant, PunchDirection direction) {
        return SyntheticAttendanceFixtures.punch(
                id, instant.toString(), direction);
    }
}
