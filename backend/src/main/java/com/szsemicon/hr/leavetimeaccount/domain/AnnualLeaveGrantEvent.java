package com.szsemicon.hr.leavetimeaccount.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record AnnualLeaveGrantEvent(
        AnnualLeaveEventType type,
        BigDecimal amountHours) {

    public AnnualLeaveGrantEvent {
        Objects.requireNonNull(type, "周年事件类型不能为空");
        Objects.requireNonNull(amountHours, "周年事件小时数不能为空");
        try {
            amountHours = amountHours.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("周年事件小时最多保留两位小数", exception);
        }
        if (type == AnnualLeaveEventType.EXPIRE_PREVIOUS && amountHours.signum() >= 0) {
            throw new IllegalArgumentException("旧周期失效金额必须为负数");
        }
        if (type == AnnualLeaveEventType.GRANT_NEW && amountHours.signum() <= 0) {
            throw new IllegalArgumentException("新周期发放金额必须为正数");
        }
    }
}
