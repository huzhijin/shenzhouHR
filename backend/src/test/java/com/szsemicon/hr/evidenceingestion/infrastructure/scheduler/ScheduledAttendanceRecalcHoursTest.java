package com.szsemicon.hr.evidenceingestion.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ScheduledAttendanceRecalcHoursTest {

    @Test
    void onlyMidnightAndNoonShanghaiStartARebuild() {
        assertThat(ScheduledAttendanceRecalcHours.isRecalcHour(
                Instant.parse("2026-08-16T16:00:00Z"))).isTrue();
        assertThat(ScheduledAttendanceRecalcHours.isRecalcHour(
                Instant.parse("2026-08-17T04:00:00Z"))).isTrue();
        assertThat(ScheduledAttendanceRecalcHours.isRecalcHour(
                Instant.parse("2026-08-17T00:00:00Z"))).isFalse();
        assertThat(ScheduledAttendanceRecalcHours.isRecalcHour(
                Instant.parse("2026-08-17T10:00:00Z"))).isFalse();
        assertThat(ScheduledAttendanceRecalcHours.isRecalcHour(null)).isFalse();
    }
}
