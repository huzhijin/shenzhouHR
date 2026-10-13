package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PunchClockFormatTest {

    @Test
    void overnightLeavingGetsNextDayPrefix() {
        assertThat(PunchClockFormat.format(
                        OvernightReturnFixtures.AUG_11,
                        OvernightReturnFixtures.jinAug12OvernightOff()))
                .isEqualTo("次日 00:14");
    }

    @Test
    void sameCalendarEveningStaysBareClock() {
        assertThat(PunchClockFormat.format(
                        OvernightReturnFixtures.AUG_10,
                        OvernightReturnFixtures.jinAug10Off()))
                .isEqualTo("19:01");
    }
}
