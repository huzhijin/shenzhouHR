package com.szsemicon.hr.leavetimeaccount.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

public final class AnnualLeaveAnniversary {

    private AnnualLeaveAnniversary() {
    }

    public static boolean isAnniversary(
            LocalDate latestEmploymentStart,
            LocalDate candidateDate,
            LeapDayAnniversaryRule leapDayRule) {
        return AnnualLeaveCalendar.anniversaryNumberOn(
                        latestEmploymentStart, candidateDate, leapDayRule)
                > 0;
    }

    public static Optional<AnnualLeaveGrantPlan> planForDate(
            String employmentPeriodId,
            LocalDate latestEmploymentStart,
            LocalDate businessDate,
            int priorServiceMonths,
            BigDecimal previousCycleBalanceHours,
            AnnualLeavePolicy policy) {
        AnnualLeavePolicy.requireText(employmentPeriodId, "任职周期 ID 不能为空");
        Objects.requireNonNull(policy, "年假策略不能为空");
        BigDecimal previousBalance = normalizeNonNegative(previousCycleBalanceHours);
        int anniversaryNumber = AnnualLeaveCalendar.anniversaryNumberOn(
                latestEmploymentStart, businessDate, policy.leapDayRule());
        if (anniversaryNumber == 0) {
            return Optional.empty();
        }

        AnnualLeaveEntitlement entitlement = AnnualLeaveCalculator.assess(
                latestEmploymentStart, businessDate, priorServiceMonths, policy);
        if (!entitlement.qualified() || entitlement.hours().signum() == 0) {
            return Optional.empty();
        }

        LocalDate nextAnniversary = AnnualLeaveCalendar.anniversary(
                latestEmploymentStart, anniversaryNumber + 1, policy.leapDayRule());
        AnnualLeaveCycle cycle = new AnnualLeaveCycle(
                employmentPeriodId,
                businessDate,
                nextAnniversary,
                nextAnniversary.minusDays(1),
                policy.policyVersionId());
        var events = new ArrayList<AnnualLeaveGrantEvent>(2);
        if (previousBalance.signum() > 0) {
            events.add(new AnnualLeaveGrantEvent(
                    AnnualLeaveEventType.EXPIRE_PREVIOUS,
                    previousBalance.negate()));
        }
        events.add(new AnnualLeaveGrantEvent(
                AnnualLeaveEventType.GRANT_NEW,
                entitlement.hours()));
        return Optional.of(new AnnualLeaveGrantPlan(cycle, entitlement, events));
    }

    private static BigDecimal normalizeNonNegative(BigDecimal value) {
        Objects.requireNonNull(value, "上周期余额不能为空");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("上周期余额不得为负数");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("上周期余额最多保留两位小数", exception);
        }
    }
}
