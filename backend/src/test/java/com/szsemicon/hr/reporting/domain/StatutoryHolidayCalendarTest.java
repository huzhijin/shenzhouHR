package com.szsemicon.hr.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.domain.StatutoryHolidayCalendar.OvertimeClass;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StatutoryHolidayCalendarTest {

    @Test
    void twentyTwentySixListIsTheThirteenStatutoryDays() {
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 9, 25))).isTrue();
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 9, 26))).isFalse();
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 9, 27))).isFalse();
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 10, 1))).isTrue();
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 10, 2))).isTrue();
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 10, 3))).isTrue();
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 10, 4))).isFalse();
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 10, 7))).isFalse();
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 4, 4))).isFalse();
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 4, 5))).isTrue();
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 5, 1))).isTrue();
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 5, 2))).isTrue();
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 2, 15))).isFalse();
        assertThat(StatutoryHolidayCalendar.isStatutory(LocalDate.of(2026, 2, 16))).isTrue();
    }

    @Test
    void extendedPublicHolidayIsWeekendOvertimeAndStatutoryDayStaysHoliday() {
        assertThat(StatutoryHolidayCalendar.classify(
                LocalDate.of(2026, 9, 25), "PUBLIC_HOLIDAY"))
                .isEqualTo(OvertimeClass.HOLIDAY);
        assertThat(StatutoryHolidayCalendar.classify(
                LocalDate.of(2026, 9, 26), "PUBLIC_HOLIDAY"))
                .isEqualTo(OvertimeClass.WEEKEND);
        assertThat(StatutoryHolidayCalendar.classify(
                LocalDate.of(2026, 10, 7), "PUBLIC_HOLIDAY"))
                .isEqualTo(OvertimeClass.WEEKEND);
    }

    @Test
    void makeupWorkdayStaysWeekdayOvertime() {
        assertThat(StatutoryHolidayCalendar.classify(
                LocalDate.of(2026, 9, 20), "ADJUSTED_WORKDAY"))
                .isEqualTo(OvertimeClass.WEEKDAY);
    }

    @Test
    void yearWithoutAListKeepsEveryPublicHolidayAsHolidayOvertime() {
        assertThat(StatutoryHolidayCalendar.yearConfigured(2025)).isFalse();
        assertThat(StatutoryHolidayCalendar.classify(
                LocalDate.of(2025, 10, 7), "PUBLIC_HOLIDAY"))
                .isEqualTo(OvertimeClass.HOLIDAY);
    }
}
