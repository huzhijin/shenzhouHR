package com.szsemicon.hr.leavetimeaccount.domain;

import java.time.LocalDate;
import java.util.Objects;

public record AnnualLeaveCycle(
        String employmentPeriodId,
        LocalDate validFrom,
        LocalDate validUntilExclusive,
        LocalDate expiresOn,
        String policyVersionId) {

    public AnnualLeaveCycle {
        AnnualLeavePolicy.requireText(employmentPeriodId, "任职周期 ID 不能为空");
        Objects.requireNonNull(validFrom, "年假周期开始日期不能为空");
        Objects.requireNonNull(validUntilExclusive, "年假周期结束日期不能为空");
        Objects.requireNonNull(expiresOn, "年假到期日期不能为空");
        AnnualLeavePolicy.requireText(policyVersionId, "年假策略版本不能为空");
        if (!validUntilExclusive.isAfter(validFrom)) {
            throw new IllegalArgumentException("年假周期必须具有正长度");
        }
        if (!expiresOn.equals(validUntilExclusive.minusDays(1))) {
            throw new IllegalArgumentException("年假到期日必须为下一周年日前一日");
        }
    }
}
