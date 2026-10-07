package com.szsemicon.hr.leavetimeaccount.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record TimeAccountLedgerEntry(
        String entryId,
        String accountId,
        String employmentPeriodId,
        long sequence,
        LedgerEntryType type,
        BigDecimal amountHours,
        LedgerEntryProvenance provenance,
        String reversalOfEntryId) {

    public TimeAccountLedgerEntry {
        LedgerEntryProvenance.requireText(entryId, "流水 ID 不能为空");
        LedgerEntryProvenance.requireText(accountId, "账户 ID 不能为空");
        LedgerEntryProvenance.requireText(employmentPeriodId, "任职周期 ID 不能为空");
        Objects.requireNonNull(type, "流水类型不能为空");
        Objects.requireNonNull(amountHours, "流水小时不能为空");
        Objects.requireNonNull(provenance, "流水来源信息不能为空");
        if (sequence <= 0) {
            throw new IllegalArgumentException("流水 sequence 必须大于零");
        }
        amountHours = normalizeHours(amountHours);
        validateDirection(type, amountHours);
        if (type == LedgerEntryType.REVERSAL) {
            LedgerEntryProvenance.requireText(reversalOfEntryId, "冲正目标流水 ID 不能为空");
        } else if (reversalOfEntryId != null) {
            throw new IllegalArgumentException("非冲正流水不得引用冲正目标");
        }
    }

    public static TimeAccountLedgerEntry create(
            String entryId,
            String accountId,
            String employmentPeriodId,
            long sequence,
            LedgerEntryType type,
            BigDecimal amountHours,
            LedgerEntryProvenance provenance,
            String reversalOfEntryId) {
        return new TimeAccountLedgerEntry(
                entryId,
                accountId,
                employmentPeriodId,
                sequence,
                type,
                amountHours,
                provenance,
                reversalOfEntryId);
    }

    private static BigDecimal normalizeHours(BigDecimal value) {
        if (value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("流水小时最多保留两位小数");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("流水小时最多保留两位小数", exception);
        }
    }

    private static void validateDirection(LedgerEntryType type, BigDecimal amount) {
        switch (type) {
            case OPENING, GRANT, OVERTIME_CREDIT, RETURN -> {
                if (amount.signum() <= 0) {
                    throw new IllegalArgumentException(type + " 流水金额必须为正数");
                }
            }
            case USE, EXPIRY -> {
                if (amount.signum() >= 0) {
                    throw new IllegalArgumentException(type + " 流水金额必须为负数");
                }
            }
            case ADJUSTMENT, REVERSAL -> {
                if (amount.signum() == 0) {
                    throw new IllegalArgumentException(type + " 流水金额不得为零");
                }
            }
        }
    }
}
