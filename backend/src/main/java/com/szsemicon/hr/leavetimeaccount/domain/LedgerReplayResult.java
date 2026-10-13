package com.szsemicon.hr.leavetimeaccount.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record LedgerReplayResult(
        BigDecimal balanceHours,
        Map<LedgerEntryType, BigDecimal> totals,
        int entryCount,
        long lastSequence,
        String replayDigest,
        List<TimeAccountLedgerEntry> entries) {

    public LedgerReplayResult {
        Objects.requireNonNull(balanceHours, "重放余额不能为空");
        totals = Map.copyOf(Objects.requireNonNull(totals, "流水分类合计不能为空"));
        LedgerEntryProvenance.requireText(replayDigest, "重放摘要不能为空");
        entries = List.copyOf(Objects.requireNonNull(entries, "重放流水不能为空"));
        if (entryCount != entries.size()) {
            throw new IllegalArgumentException("重放流水数量不一致");
        }
    }
}
