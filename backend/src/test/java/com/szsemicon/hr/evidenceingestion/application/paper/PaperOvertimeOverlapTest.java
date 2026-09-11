package com.szsemicon.hr.evidenceingestion.application.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaperOvertimeOverlapTest {

    @Test
    void overnightOccupiesTwoCalendarDays() {
        Instant start = Instant.parse("2026-08-18T14:00:00Z");
        Instant end = Instant.parse("2026-08-18T22:00:00Z");
        assertThat(PaperOvertimeOverlap.calendarDays(start, end))
                .containsExactly(
                        LocalDate.of(2026, 8, 18),
                        LocalDate.of(2026, 8, 19));
    }

    @Test
    void sameNameDifferentDaysDoNotOverlap() {
        assertThat(PaperOvertimeOverlap.datesOverlap(
                Set.of(LocalDate.of(2026, 8, 18)),
                Set.of(LocalDate.of(2026, 8, 20)))).isFalse();
    }

    @Test
    void sameDayOverlaps() {
        assertThat(PaperOvertimeOverlap.datesOverlap(
                Set.of(LocalDate.of(2026, 8, 18)),
                Set.of(LocalDate.of(2026, 8, 18)))).isTrue();
    }
}
