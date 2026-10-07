package com.szsemicon.hr.evidenceingestion.domain.oa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class OaSourceDateTimesTest {

    @Test
    void readsNaiveOaDateTimeAsAsiaShanghaiWallClock() throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getObject("start_dt", LocalDateTime.class))
                .thenReturn(LocalDateTime.parse("2026-08-07T08:30:00"));
        when(result.getObject("end_dt", LocalDateTime.class))
                .thenReturn(LocalDateTime.parse("2026-08-07T18:00:00"));

        assertThat(OaSourceDateTimes.wallClock(result, "start_dt"))
                .isEqualTo(Instant.parse("2026-08-07T00:30:00Z"));
        assertThat(OaSourceDateTimes.wallClock(result, "end_dt"))
                .isEqualTo(Instant.parse("2026-08-07T10:00:00Z"));
    }

    @Test
    void fallsBackToTimestampWallClockWhenDriverOnlyExposesTimestamp()
            throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getObject("start_dt", LocalDateTime.class)).thenReturn(null);
        when(result.getTimestamp("start_dt"))
                .thenReturn(Timestamp.valueOf("2026-08-07 08:30:00"));

        assertThat(OaSourceDateTimes.wallClock(result, "start_dt"))
                .isEqualTo(LocalDateTime.parse("2026-08-07T08:30:00")
                        .atZone(OaSourceDateTimes.SOURCE_ZONE)
                        .toInstant());
    }
}
