package com.szsemicon.hr.leavetimeaccount.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record AnnualLeaveEntitlement(
        boolean qualified,
        int currentEmploymentCompletedMonths,
        int priorServiceMonths,
        int cumulativeServiceMonths,
        LocalDate qualificationDate,
        AnnualLeaveTierCode tierCode,
        int days,
        BigDecimal hours,
        String policyVersionId) {

    public AnnualLeaveEntitlement {
        Objects.requireNonNull(qualificationDate, "资格日期不能为空");
        Objects.requireNonNull(tierCode, "年假档位编码不能为空");
        Objects.requireNonNull(hours, "年假小时数不能为空");
        if (currentEmploymentCompletedMonths < 0
                || priorServiceMonths < 0
                || cumulativeServiceMonths < 0) {
            throw new IllegalArgumentException("工龄月数不得为负数");
        }
        if (cumulativeServiceMonths
                != Math.addExact(currentEmploymentCompletedMonths, priorServiceMonths)) {
            throw new IllegalArgumentException("累计工龄月数必须等于本次任职与入职前工龄之和");
        }
        if (days < 0 || hours.signum() < 0) {
            throw new IllegalArgumentException("年假额度不得为负数");
        }
        if (!qualified && (days != 0 || hours.signum() != 0)) {
            throw new IllegalArgumentException("未取得资格时年假额度必须为零");
        }
        AnnualLeavePolicy.requireText(policyVersionId, "年假策略版本不能为空");
    }
}
