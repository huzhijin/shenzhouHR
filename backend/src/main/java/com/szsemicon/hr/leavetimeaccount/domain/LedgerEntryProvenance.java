package com.szsemicon.hr.leavetimeaccount.domain;

import java.time.LocalDate;
import java.util.Objects;

public record LedgerEntryProvenance(
        String sourceType,
        String sourceId,
        LocalDate businessDate,
        LocalDate effectiveFrom,
        LocalDate expiresOn,
        String policyVersionId,
        String periodVersionId,
        String closeSnapshotId,
        String requestId) {

    public LedgerEntryProvenance {
        requireText(sourceType, "流水来源类型不能为空");
        requireText(sourceId, "流水来源 ID 不能为空");
        Objects.requireNonNull(businessDate, "流水业务日期不能为空");
        Objects.requireNonNull(effectiveFrom, "流水生效日期不能为空");
        requireText(requestId, "流水请求 ID 不能为空");
        if (expiresOn != null && expiresOn.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("流水到期日不得早于生效日");
        }
        requireOptionalText(policyVersionId, "策略版本不能是空白字符串");
        requireOptionalText(periodVersionId, "期间版本不能是空白字符串");
        requireOptionalText(closeSnapshotId, "月结快照 ID 不能是空白字符串");
    }

    static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireOptionalText(String value, String message) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
