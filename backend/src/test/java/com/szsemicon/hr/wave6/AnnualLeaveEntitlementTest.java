package com.szsemicon.hr.wave6;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.leavetimeaccount.domain.AnnualLeaveCalculator;
import com.szsemicon.hr.leavetimeaccount.domain.AnnualLeavePolicy;
import com.szsemicon.hr.leavetimeaccount.domain.AnnualLeaveTierCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AnnualLeaveEntitlementTest {

    private static final AnnualLeavePolicy DEFAULT_POLICY =
            AnnualLeavePolicy.defaults("annual-policy-v1");

    @Test
    void anniversary_eve_is_not_qualified_even_when_prior_service_is_high() {
        var result = AnnualLeaveCalculator.assess(
                LocalDate.of(2025, 7, 22),
                LocalDate.of(2026, 7, 21),
                180,
                DEFAULT_POLICY);

        assertThat(result.qualified()).isFalse();
        assertThat(result.currentEmploymentCompletedMonths()).isEqualTo(11);
        assertThat(result.cumulativeServiceMonths()).isEqualTo(191);
        assertThat(result.tierCode()).isEqualTo(AnnualLeaveTierCode.TEN_DAYS);
        assertThat(result.days()).isZero();
        assertThat(result.hours()).isEqualByComparingTo("0.00");
        assertThat(result.qualificationDate()).isEqualTo("2026-07-22");
    }

    @Test
    void first_anniversary_qualifies_and_uses_the_first_tier() {
        var result = AnnualLeaveCalculator.assess(
                LocalDate.of(2025, 7, 22),
                LocalDate.of(2026, 7, 22),
                0,
                DEFAULT_POLICY);

        assertThat(result.qualified()).isTrue();
        assertThat(result.currentEmploymentCompletedMonths()).isEqualTo(12);
        assertThat(result.cumulativeServiceMonths()).isEqualTo(12);
        assertThat(result.tierCode()).isEqualTo(AnnualLeaveTierCode.FIVE_DAYS);
        assertThat(result.days()).isEqualTo(5);
        assertThat(result.hours()).isEqualByComparingTo("40.00");
    }

    @Test
    void ten_year_eve_stays_in_the_five_day_tier() {
        var result = AnnualLeaveCalculator.assess(
                LocalDate.of(2016, 7, 21),
                LocalDate.of(2026, 7, 20),
                0,
                DEFAULT_POLICY);

        assertThat(result.currentEmploymentCompletedMonths()).isEqualTo(119);
        assertThat(result.tierCode()).isEqualTo(AnnualLeaveTierCode.FIVE_DAYS);
        assertThat(result.hours()).isEqualByComparingTo("40.00");
    }

    @Test
    void ten_year_day_enters_the_ten_day_tier() {
        var result = AnnualLeaveCalculator.assess(
                LocalDate.of(2016, 7, 21),
                LocalDate.of(2026, 7, 21),
                0,
                DEFAULT_POLICY);

        assertThat(result.currentEmploymentCompletedMonths()).isEqualTo(120);
        assertThat(result.tierCode()).isEqualTo(AnnualLeaveTierCode.TEN_DAYS);
        assertThat(result.days()).isEqualTo(10);
        assertThat(result.hours()).isEqualByComparingTo("80.00");
    }

    @Test
    void twenty_year_day_enters_the_fifteen_day_tier() {
        var result = AnnualLeaveCalculator.assess(
                LocalDate.of(2006, 7, 21),
                LocalDate.of(2026, 7, 21),
                0,
                DEFAULT_POLICY);

        assertThat(result.currentEmploymentCompletedMonths()).isEqualTo(240);
        assertThat(result.tierCode()).isEqualTo(AnnualLeaveTierCode.FIFTEEN_DAYS);
        assertThat(result.days()).isEqualTo(15);
        assertThat(result.hours()).isEqualByComparingTo("120.00");
    }

    @Test
    void published_prior_service_contributes_to_tier_only_after_qualification() {
        var result = AnnualLeaveCalculator.assess(
                LocalDate.of(2024, 7, 21),
                LocalDate.of(2026, 7, 21),
                144,
                DEFAULT_POLICY);

        assertThat(result.qualified()).isTrue();
        assertThat(result.currentEmploymentCompletedMonths()).isEqualTo(24);
        assertThat(result.cumulativeServiceMonths()).isEqualTo(168);
        assertThat(result.tierCode()).isEqualTo(AnnualLeaveTierCode.TEN_DAYS);
        assertThat(result.hours()).isEqualByComparingTo("80.00");
    }

    @Test
    void configured_twenty_four_month_qualification_blocks_the_first_anniversary() {
        var policy = DEFAULT_POLICY.withQualificationMonths(24);

        var firstAnniversary = AnnualLeaveCalculator.assess(
                LocalDate.of(2025, 7, 22),
                LocalDate.of(2026, 7, 22),
                144,
                policy);
        var secondAnniversary = AnnualLeaveCalculator.assess(
                LocalDate.of(2025, 7, 22),
                LocalDate.of(2027, 7, 22),
                144,
                policy);

        assertThat(firstAnniversary.qualified()).isFalse();
        assertThat(firstAnniversary.hours()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(secondAnniversary.qualified()).isTrue();
        assertThat(secondAnniversary.hours()).isEqualByComparingTo("80.00");
    }

    @Test
    void future_start_date_and_negative_prior_service_are_rejected() {
        assertThatThrownBy(() -> AnnualLeaveCalculator.assess(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 7, 21),
                0,
                DEFAULT_POLICY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("评估日期不得早于最新入职日期");

        assertThatThrownBy(() -> AnnualLeaveCalculator.assess(
                LocalDate.of(2025, 7, 21),
                LocalDate.of(2026, 7, 21),
                -1,
                DEFAULT_POLICY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("入职前累计工龄月数不得为负数");
    }
}
