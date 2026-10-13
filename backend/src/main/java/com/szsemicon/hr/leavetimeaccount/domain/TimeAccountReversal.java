package com.szsemicon.hr.leavetimeaccount.domain;

import java.time.LocalDate;
import java.util.Objects;

public final class TimeAccountReversal {

    private TimeAccountReversal() {
    }

    public static TimeAccountLedgerEntry reverse(
            TimeAccountLedgerEntry target,
            String reversalEntryId,
            long reversalSequence,
            String sourceType,
            String sourceId,
            LocalDate businessDate,
            String requestId) {
        Objects.requireNonNull(target, "冲正目标流水不能为空");
        if (target.type() == LedgerEntryType.REVERSAL) {
            throw new IllegalArgumentException("冲正流水不能再次冲正");
        }
        if (reversalSequence <= target.sequence()) {
            throw new IllegalArgumentException("冲正 sequence 必须晚于目标流水");
        }
        LedgerEntryProvenance provenance = new LedgerEntryProvenance(
                sourceType,
                sourceId,
                businessDate,
                businessDate,
                null,
                target.provenance().policyVersionId(),
                target.provenance().periodVersionId(),
                target.provenance().closeSnapshotId(),
                requestId);
        return TimeAccountLedgerEntry.create(
                reversalEntryId,
                target.accountId(),
                target.employmentPeriodId(),
                reversalSequence,
                LedgerEntryType.REVERSAL,
                target.amountHours().negate(),
                provenance,
                target.entryId());
    }
}
