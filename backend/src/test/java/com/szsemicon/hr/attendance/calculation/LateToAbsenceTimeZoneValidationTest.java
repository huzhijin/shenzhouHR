package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationInputSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationPolicy;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.GraceConsumptionSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchDirection;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultCategory;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ScheduledWorkSegment;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.SegmentKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.attendance.calculation.domain.DeterministicAttendanceCalculator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Validates the fixed severe-lateness boundary in local business time. */
class LateToAbsenceTimeZoneValidationTest {

    private static final DeterministicAttendanceCalculator CALCULATOR =
            new DeterministicAttendanceCalculator();

    @ParameterizedTest(name = "{0}: 29 minutes stays late, 30 minutes becomes absence")
    @MethodSource("businessZones")
    void fixedThirtyMinuteThresholdSurvivesTimeZoneAndDstBoundaries(
            ZoneId zone, LocalDate businessDate) {
        var lateAtTwentyNine = calculate(zone, businessDate, 29);
        assertThat(lateAtTwentyNine.items())
                .extracting(value -> value.category())
                .contains(ResultCategory.SCHEDULED_WORK, ResultCategory.LATE)
                .doesNotContain(ResultCategory.ABSENCE);
        assertThat(lateAtTwentyNine.metrics().confirmedScheduledWorkMinutes())
                .isEqualTo(240);
        assertThat(lateAtTwentyNine.metrics().absenceMinutes()).isZero();

        var lateAtThirty = calculate(zone, businessDate, 30);
        assertThat(lateAtThirty.items()).singleElement().satisfies(item -> {
            assertThat(item.category()).isEqualTo(ResultCategory.ABSENCE);
            assertThat(item.reasonCode())
                    .isEqualTo("LATE_CONVERTED_TO_ABSENCE");
        });
        assertThat(lateAtThirty.metrics().confirmedScheduledWorkMinutes())
                .isZero();
        assertThat(lateAtThirty.metrics().absenceMinutes()).isEqualTo(240);
        assertThat(lateAtThirty.ruleHits())
                .filteredOn(hit -> "LATE_CONVERTED_TO_ABSENCE"
                        .equals(hit.ruleCode()))
                .singleElement()
                .satisfies(hit -> {
                    // 15 minutes of configured grace is applied before the
                    // immutable 30-minute business threshold.
                    assertThat(hit.rawMinutes()).isEqualTo(45);
                    assertThat(hit.includedMinutes()).isZero();
                });
    }

    private static Stream<Arguments> businessZones() {
        return Stream.of(
                Arguments.of(ZoneId.of("Asia/Shanghai"),
                        LocalDate.of(2026, 8, 17)),
                // This is the US daylight-saving transition date. The shift
                // begins after the clock jump, which validates local-time
                // arithmetic without relying on a fixed UTC offset.
                Arguments.of(ZoneId.of("America/New_York"),
                        LocalDate.of(2026, 3, 8)));
    }

    private static com.szsemicon.hr.attendance.calculation.domain
            .AttendanceCalculationModels.DailyAttendanceResult calculate(
                    ZoneId zone, LocalDate businessDate, int afterGraceMinutes) {
        Instant start = ZonedDateTime.of(
                businessDate, LocalTime.of(9, 0), zone).toInstant();
        Instant end = ZonedDateTime.of(
                businessDate, LocalTime.of(13, 0), zone).toInstant();
        TimeInterval interval = new TimeInterval(start, end);
        ScheduledWorkSegment segment = new ScheduledWorkSegment(
                "segment-" + zone.getId(),
                businessDate,
                interval,
                interval,
                interval,
                SegmentKind.SCHEDULED_WORK);
        CalculationPolicy policy = new CalculationPolicy(
                15,
                1,
                end.plusSeconds(86_400),
                false,
                null,
                48 * 60,
                List.of());
        return CALCULATOR.calculate(
                "timezone-boundary-" + zone.getId() + '-' + afterGraceMinutes,
                new CalculationInputSnapshot(
                        "company-001",
                        "employee-001",
                        "employment-001",
                        businessDate,
                        zone,
                        end.plusSeconds(86_400),
                        List.of(segment),
                        List.of(
                                new com.szsemicon.hr.attendance
                                        .calculation.domain
                                        .AttendanceCalculationModels.PunchEvent(
                                                "entry-" + afterGraceMinutes,
                                                start.plusSeconds(
                                                        (15L + afterGraceMinutes)
                                                                * 60),
                                                PunchDirection.ENTRY,
                                                "validation"),
                                new com.szsemicon.hr.attendance
                                        .calculation.domain
                                        .AttendanceCalculationModels.PunchEvent(
                                                "exit-" + afterGraceMinutes,
                                                end,
                                                PunchDirection.EXIT,
                                                "validation")),
                        List.of(),
                        List.of(),
                        new GraceConsumptionSnapshot(
                                "employee-001",
                                YearMonth.from(businessDate),
                                0,
                                "grace-snapshot"),
                        false,
                        policy,
                        "configuration-snapshot",
                        "configuration-digest",
                        "evidence-snapshot",
                        "evidence-digest",
                        "adjustment-digest",
                        "period-001",
                        1,
                        "period-token",
                        "validation-v1",
                        "request-001",
                        "correlation-001"));
    }
}
