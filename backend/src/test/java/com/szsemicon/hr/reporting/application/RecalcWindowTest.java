package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class RecalcWindowTest {

    @Test
    void lastThreeDaysAreRelativeToTodayNotSelectedMonth() {
        RecalcWindow.DateSpan span = RecalcWindow.LAST_3_DAYS.resolve(
                LocalDate.of(2026, 8, 26),
                YearMonth.of(2026, 7));

        assertThat(span.startInclusive()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(span.endExclusive()).isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(RecalcWindow.LAST_3_DAYS.months(span))
                .containsExactly(YearMonth.of(2026, 8));
    }

    @Test
    void lastThreeDaysOnTheFirstCrossesIntoThePreviousMonth() {
        RecalcWindow.DateSpan span = RecalcWindow.LAST_3_DAYS.resolve(
                LocalDate.of(2026, 8, 1),
                YearMonth.of(2026, 8));

        assertThat(span.startInclusive()).isEqualTo(LocalDate.of(2026, 7, 30));
        assertThat(span.endExclusive()).isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(RecalcWindow.LAST_3_DAYS.months(span))
                .containsExactly(YearMonth.of(2026, 7), YearMonth.of(2026, 8));
        assertThat(span.writeStart(YearMonth.of(2026, 8)))
                .isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(span.writeEndExclusive(YearMonth.of(2026, 7)))
                .isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void monthWindowFollowsTheSelectedPeriod() {
        RecalcWindow.DateSpan span = RecalcWindow.MONTH.resolve(
                LocalDate.of(2026, 8, 26),
                YearMonth.of(2026, 7));

        assertThat(span.startInclusive()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(span.endExclusive()).isEqualTo(LocalDate.of(2026, 8, 1));
    }
}
