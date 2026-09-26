package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class OaIntervalGridTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void minutesBelow30SnapToTheHour() {
        assertThat(OaIntervalGrid.snap(shanghai("2026-08-17T09:17:40")))
                .isEqualTo(shanghai("2026-08-17T09:00:00"));
    }

    @Test
    void minutesFrom30SnapToHalfPast() {
        assertThat(OaIntervalGrid.snap(shanghai("2026-08-16T18:45:01")))
                .isEqualTo(shanghai("2026-08-16T18:30:00"));
    }

    @Test
    void exactHourAndHalfHourAreUnchanged() {
        assertThat(OaIntervalGrid.snap(shanghai("2026-08-17T13:00:00")))
                .isEqualTo(shanghai("2026-08-17T13:00:00"));
        assertThat(OaIntervalGrid.snap(shanghai("2026-08-17T17:30:00")))
                .isEqualTo(shanghai("2026-08-17T17:30:00"));
    }

    @Test
    void overnightIntervalKeepsTheNextMorning() {
        TimeInterval snapped = OaIntervalGrid.snapInterval(
                shanghai("2026-08-15T23:50:00"),
                shanghai("2026-08-16T07:20:00"));
        assertThat(snapped.start()).isEqualTo(shanghai("2026-08-15T23:30:00"));
        assertThat(snapped.end()).isEqualTo(shanghai("2026-08-16T07:00:00"));
    }

    @Test
    void degenerateIntervalAfterSnapIsNullAndPersistEndKeepsAHalfOpenPoint() {
        Instant start = shanghai("2026-08-17T09:00:00");
        Instant end = shanghai("2026-08-17T09:17:00");
        assertThat(OaIntervalGrid.snapInterval(start, end)).isNull();
        Instant snappedStart = OaIntervalGrid.snap(start);
        Instant snappedEnd = OaIntervalGrid.snap(end);
        assertThat(snappedStart).isEqualTo(snappedEnd);
        assertThat(OaIntervalGrid.persistEnd(snappedStart, snappedEnd))
                .isEqualTo(snappedStart.plusNanos(1));
    }

    private static Instant shanghai(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SHANGHAI).toInstant();
    }
}
