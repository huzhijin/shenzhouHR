package com.szsemicon.hr.leavetimeaccount.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

public final class AnnualLeaveCalculator {

    private static final BigDecimal ZERO_HOURS = new BigDecimal("0.00");

    private AnnualLeaveCalculator() {
    }

    public static AnnualLeaveEntitlement assess(
            LocalDate latestEmploymentStart,
            LocalDate asOfDate,
            int priorServiceMonths,
            AnnualLeavePolicy policy) {
        Objects.requireNonNull(policy, "年假策略不能为空");
        if (priorServiceMonths < 0) {
            throw new IllegalArgumentException("入职前累计工龄月数不得为负数");
        }

        int currentMonths = AnnualLeaveCalendar.completedMonths(
                latestEmploymentStart, asOfDate, policy.leapDayRule());
        int cumulativeMonths = Math.addExact(currentMonths, priorServiceMonths);
        LocalDate qualificationDate = AnnualLeaveCalendar.addMonths(
                latestEmploymentStart, policy.qualificationMonths(), policy.leapDayRule());
        boolean qualified = !policy.qualificationRequired()
                || currentMonths >= policy.qualificationMonths();
        AnnualLeaveTier tier = policy.tiers().stream()
                .filter(candidate -> candidate.contains(cumulativeMonths))
                .findFirst()
                .orElse(null);

        AnnualLeaveTierCode tierCode =
                tier == null ? AnnualLeaveTierCode.NONE : tier.code();
        int days = qualified && tier != null ? tier.days() : 0;
        BigDecimal hours = qualified && tier != null
                ? tier.hours()
                : ZERO_HOURS;

        return new AnnualLeaveEntitlement(
                qualified,
                currentMonths,
                priorServiceMonths,
                cumulativeMonths,
                qualificationDate,
                tierCode,
                days,
                hours.setScale(2, RoundingMode.UNNECESSARY),
                policy.policyVersionId());
    }
}
