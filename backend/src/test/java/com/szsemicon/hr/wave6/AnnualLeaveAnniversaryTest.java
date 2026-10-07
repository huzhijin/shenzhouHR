package com.szsemicon.hr.wave6;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.leavetimeaccount.domain.AnnualLeaveAnniversary;
import com.szsemicon.hr.leavetimeaccount.domain.AnnualLeaveEventType;
import com.szsemicon.hr.leavetimeaccount.domain.AnnualLeavePolicy;
import com.szsemicon.hr.leavetimeaccount.domain.LeapDayAnniversaryRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AnnualLeaveAnniversaryTest {

    private static final AnnualLeavePolicy DEFAULT_POLICY =
            AnnualLeavePolicy.defaults("annual-policy-v1");

    @Test
    void anniversary_eve_has_no_new_grant_plan() {
        var plan = AnnualLeaveAnniversary.planForDate(
                "employment-synthetic-a",
                LocalDate.of(2025, 7, 22),
                LocalDate.of(2026, 7, 21),
                0,
                new BigDecimal("8.00"),
                DEFAULT_POLICY);

        assertThat(plan).isEmpty();
    }

    @Test
    void anniversary_plan_expires_the_old_cycle_before_granting_the_new_cycle() {
        var plan = AnnualLeaveAnniversary.planForDate(
                        "employment-synthetic-a",
                        LocalDate.of(2025, 7, 22),
                        LocalDate.of(2026, 7, 22),
                        0,
                        new BigDecimal("8.00"),
                        DEFAULT_POLICY)
                .orElseThrow();

        assertThat(plan.cycle().employmentPeriodId()).isEqualTo("employment-synthetic-a");
        assertThat(plan.cycle().validFrom()).isEqualTo("2026-07-22");
        assertThat(plan.cycle().validUntilExclusive()).isEqualTo("2027-07-22");
        assertThat(plan.cycle().expiresOn()).isEqualTo("2027-07-21");
        assertThat(plan.events())
                .extracting(event -> event.type())
                .containsExactly(
                        AnnualLeaveEventType.EXPIRE_PREVIOUS,
                        AnnualLeaveEventType.GRANT_NEW);
        assertThat(plan.events())
                .extracting(event -> event.amountHours())
                .containsExactly(new BigDecimal("-8.00"), new BigDecimal("40.00"));
    }

    @Test
    void reaching_a_higher_tier_mid_cycle_does_not_create_a_top_up_plan() {
        var plan = AnnualLeaveAnniversary.planForDate(
                "employment-synthetic-b",
                LocalDate.of(2016, 7, 21),
                LocalDate.of(2026, 8, 1),
                0,
                BigDecimal.ZERO,
                DEFAULT_POLICY);

        assertThat(plan).isEmpty();
    }

    @Test
    void rehire_uses_the_new_employment_period_and_new_anniversary_anchor() {
        var dayBefore = AnnualLeaveAnniversary.planForDate(
                "employment-synthetic-rehire",
                LocalDate.of(2026, 7, 21),
                LocalDate.of(2027, 7, 20),
                144,
                BigDecimal.ZERO,
                DEFAULT_POLICY);
        var anniversary = AnnualLeaveAnniversary.planForDate(
                        "employment-synthetic-rehire",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2027, 7, 21),
                        144,
                        BigDecimal.ZERO,
                        DEFAULT_POLICY)
                .orElseThrow();

        assertThat(dayBefore).isEmpty();
        assertThat(anniversary.cycle().employmentPeriodId())
                .isEqualTo("employment-synthetic-rehire");
        assertThat(anniversary.entitlement().hours()).isEqualByComparingTo("80.00");
        assertThat(anniversary.cycle().validFrom()).isEqualTo("2027-07-21");
    }

    @Test
    void february_twenty_ninth_defaults_to_february_twenty_eighth() {
        LocalDate startDate = LocalDate.of(2024, 2, 29);

        assertThat(AnnualLeaveAnniversary.isAnniversary(
                        startDate, LocalDate.of(2025, 2, 27), LeapDayAnniversaryRule.FEBRUARY_28))
                .isFalse();
        assertThat(AnnualLeaveAnniversary.isAnniversary(
                        startDate, LocalDate.of(2025, 2, 28), LeapDayAnniversaryRule.FEBRUARY_28))
                .isTrue();

        var plan = AnnualLeaveAnniversary.planForDate(
                        "employment-synthetic-leap",
                        startDate,
                        LocalDate.of(2025, 2, 28),
                        0,
                        BigDecimal.ZERO,
                        DEFAULT_POLICY)
                .orElseThrow();
        assertThat(plan.entitlement().hours()).isEqualByComparingTo("40.00");
    }

    @Test
    void february_twenty_ninth_can_use_march_first_in_non_leap_years() {
        var policy = DEFAULT_POLICY.withLeapDayRule(LeapDayAnniversaryRule.MARCH_1);
        LocalDate startDate = LocalDate.of(2024, 2, 29);

        assertThat(AnnualLeaveAnniversary.isAnniversary(
                        startDate, LocalDate.of(2025, 2, 28), LeapDayAnniversaryRule.MARCH_1))
                .isFalse();
        assertThat(AnnualLeaveAnniversary.isAnniversary(
                        startDate, LocalDate.of(2025, 3, 1), LeapDayAnniversaryRule.MARCH_1))
                .isTrue();
        assertThat(AnnualLeaveAnniversary.planForDate(
                        "employment-synthetic-leap",
                        startDate,
                        LocalDate.of(2025, 3, 1),
                        0,
                        BigDecimal.ZERO,
                        policy))
                .isPresent();
    }

    @Test
    void february_twenty_ninth_remains_the_anniversary_in_a_leap_year() {
        LocalDate startDate = LocalDate.of(2020, 2, 29);

        assertThat(AnnualLeaveAnniversary.isAnniversary(
                        startDate, LocalDate.of(2024, 2, 29), LeapDayAnniversaryRule.FEBRUARY_28))
                .isTrue();
        assertThat(AnnualLeaveAnniversary.isAnniversary(
                        startDate, LocalDate.of(2024, 2, 29), LeapDayAnniversaryRule.MARCH_1))
                .isTrue();
    }
}
