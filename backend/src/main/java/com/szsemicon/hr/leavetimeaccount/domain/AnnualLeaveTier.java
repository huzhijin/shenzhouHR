package com.szsemicon.hr.leavetimeaccount.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record AnnualLeaveTier(
        AnnualLeaveTierCode code,
        int minimumMonthsInclusive,
        Integer maximumMonthsExclusive,
        int days,
        BigDecimal hours) {

    public AnnualLeaveTier {
        Objects.requireNonNull(code, "年假档位编码不能为空");
        Objects.requireNonNull(hours, "年假档位小时数不能为空");
        if (code == AnnualLeaveTierCode.NONE) {
            throw new IllegalArgumentException("发布档位不能使用 NONE");
        }
        if (minimumMonthsInclusive < 0) {
            throw new IllegalArgumentException("档位最小累计工龄月数不得为负数");
        }
        if (maximumMonthsExclusive != null
                && maximumMonthsExclusive <= minimumMonthsInclusive) {
            throw new IllegalArgumentException("档位结束月数必须大于开始月数");
        }
        if (days <= 0 || hours.signum() <= 0) {
            throw new IllegalArgumentException("档位天数和小时数必须为正数");
        }
        try {
            hours = hours.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("年假档位小时最多保留两位小数", exception);
        }
    }

    public boolean contains(int completedMonths) {
        return completedMonths >= minimumMonthsInclusive
                && (maximumMonthsExclusive == null
                        || completedMonths < maximumMonthsExclusive);
    }
}
