package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OvernightOvertimeFoldTest {

    @Test
    void weekdayOvernightUsesStoredStartDayMinutes() {
        var document = new OvernightOvertimeFold.OvertimeSpan(
                OvernightReturnFixtures.shanghai(
                        LocalDate.of(2026, 8, 11).atTime(21, 0)),
                OvernightReturnFixtures.shanghai(
                        LocalDate.of(2026, 8, 12).atTime(2, 0)),
                true);
        List<OvernightOvertimeFold.OvertimeSpan> docs = List.of(document);
        assertThat(OvernightOvertimeFold.displayMinutes(
                        LocalDate.of(2026, 8, 11),
                        300,
                        docs,
                        OvernightOvertimeFold.DEFAULT_SHIFT_START))
                .isEqualTo(300);
        assertThat(OvernightOvertimeFold.displayMinutes(
                        LocalDate.of(2026, 8, 12),
                        0,
                        docs,
                        OvernightOvertimeFold.DEFAULT_SHIFT_START))
                .isZero();
    }

    @Test
    void nextDayEveningFormIsKeptOnNextDay() {
        var overnight = new OvernightOvertimeFold.OvertimeSpan(
                OvernightReturnFixtures.shanghai(
                        LocalDate.of(2026, 8, 11).atTime(21, 0)),
                OvernightReturnFixtures.shanghai(
                        LocalDate.of(2026, 8, 12).atTime(2, 0)),
                true);
        var evening = new OvernightOvertimeFold.OvertimeSpan(
                OvernightReturnFixtures.shanghai(
                        LocalDate.of(2026, 8, 12).atTime(18, 0)),
                OvernightReturnFixtures.shanghai(
                        LocalDate.of(2026, 8, 12).atTime(21, 0)),
                true);
        List<OvernightOvertimeFold.OvertimeSpan> docs = List.of(overnight, evening);
        assertThat(OvernightOvertimeFold.displayMinutes(
                        LocalDate.of(2026, 8, 12),
                        180,
                        docs,
                        OvernightOvertimeFold.DEFAULT_SHIFT_START))
                .isEqualTo(180);
    }

    @Test
    void continuationOnlyOnNextMorning() {
        assertThat(OvernightOvertimeFold.continuationOnlyOnDate(
                        OvernightReturnFixtures.shanghai(
                                LocalDate.of(2026, 8, 11).atTime(18, 0)),
                        OvernightReturnFixtures.shanghai(
                                LocalDate.of(2026, 8, 12).atTime(0, 30)),
                        LocalDate.of(2026, 8, 12),
                        OvernightOvertimeFold.DEFAULT_SHIFT_START))
                .isTrue();
        assertThat(OvernightOvertimeFold.continuationOnlyOnDate(
                        OvernightReturnFixtures.shanghai(
                                LocalDate.of(2026, 8, 11).atTime(18, 0)),
                        OvernightReturnFixtures.shanghai(
                                LocalDate.of(2026, 8, 12).atTime(0, 30)),
                        LocalDate.of(2026, 8, 11),
                        OvernightOvertimeFold.DEFAULT_SHIFT_START))
                .isFalse();
    }
}
