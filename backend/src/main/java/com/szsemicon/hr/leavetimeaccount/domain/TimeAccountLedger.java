package com.szsemicon.hr.leavetimeaccount.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TimeAccountLedger {

    private static final BigDecimal ZERO_HOURS = new BigDecimal("0.00");

    private TimeAccountLedger() {
    }

    public static LedgerReplayResult replay(
            String accountId,
            TimeAccountType accountType,
            List<TimeAccountLedgerEntry> sourceEntries) {
        LedgerEntryProvenance.requireText(accountId, "重放账户 ID 不能为空");
        Objects.requireNonNull(accountType, "时间账户类型不能为空");
        Objects.requireNonNull(sourceEntries, "重放流水不能为空");

        assertUniqueIdentities(sourceEntries);
        List<TimeAccountLedgerEntry> entries = new ArrayList<>(sourceEntries);
        entries.sort(Comparator.comparingLong(TimeAccountLedgerEntry::sequence));

        Map<LedgerEntryType, BigDecimal> totals = new EnumMap<>(LedgerEntryType.class);
        for (LedgerEntryType type : LedgerEntryType.values()) {
            totals.put(type, ZERO_HOURS);
        }
        Map<String, TimeAccountLedgerEntry> processedEntries = new HashMap<>();
        Set<String> reversedTargets = new HashSet<>();
        BigDecimal balance = ZERO_HOURS;
        String employmentPeriodId = null;

        for (TimeAccountLedgerEntry entry : entries) {
            if (!accountId.equals(entry.accountId())) {
                throw new IllegalArgumentException("流水账户与重放账户不一致");
            }
            if (employmentPeriodId == null) {
                employmentPeriodId = entry.employmentPeriodId();
            } else if (!employmentPeriodId.equals(entry.employmentPeriodId())) {
                throw new IllegalArgumentException("同一时间账户不得混用任职周期");
            }
            if (entry.type() == LedgerEntryType.REVERSAL) {
                validateReversal(entry, processedEntries, reversedTargets);
            }
            balance = balance.add(entry.amountHours());
            if (accountType.balanceControlled() && balance.signum() < 0) {
                throw new IllegalArgumentException("余额受控账户不得出现负余额");
            }
            totals.compute(
                    entry.type(),
                    (ignored, current) -> current.add(entry.amountHours()));
            processedEntries.put(entry.entryId(), entry);
        }

        long lastSequence = entries.isEmpty() ? 0 : entries.getLast().sequence();
        return new LedgerReplayResult(
                balance,
                totals,
                entries.size(),
                lastSequence,
                digest(entries),
                entries);
    }

    private static void assertUniqueIdentities(List<TimeAccountLedgerEntry> entries) {
        Set<String> entryIds = new HashSet<>();
        Set<Long> sequences = new HashSet<>();
        for (TimeAccountLedgerEntry entry : entries) {
            Objects.requireNonNull(entry, "重放流水不得包含 null");
            if (!entryIds.add(entry.entryId())) {
                throw new IllegalArgumentException("流水 ID 不得重复");
            }
            if (!sequences.add(entry.sequence())) {
                throw new IllegalArgumentException("流水 sequence 不得重复");
            }
        }
    }

    private static void validateReversal(
            TimeAccountLedgerEntry reversal,
            Map<String, TimeAccountLedgerEntry> processedEntries,
            Set<String> reversedTargets) {
        TimeAccountLedgerEntry target = processedEntries.get(reversal.reversalOfEntryId());
        if (target == null) {
            throw new IllegalArgumentException("冲正目标必须存在且先于冲正流水");
        }
        if (target.type() == LedgerEntryType.REVERSAL) {
            throw new IllegalArgumentException("冲正流水不能再次冲正");
        }
        if (!target.accountId().equals(reversal.accountId())
                || !target.employmentPeriodId().equals(reversal.employmentPeriodId())) {
            throw new IllegalArgumentException("冲正目标必须属于同一账户和任职周期");
        }
        if (!reversedTargets.add(target.entryId())) {
            throw new IllegalArgumentException("同一流水不得重复冲正");
        }
        if (reversal.amountHours().compareTo(target.amountHours().negate()) != 0) {
            throw new IllegalArgumentException("冲正金额必须与目标流水精确反向");
        }
    }

    private static String digest(List<TimeAccountLedgerEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (TimeAccountLedgerEntry entry : entries) {
                update(digest, entry.entryId());
                update(digest, entry.accountId());
                update(digest, entry.employmentPeriodId());
                update(digest, Long.toString(entry.sequence()));
                update(digest, entry.type().name());
                update(digest, entry.amountHours().toPlainString());
                update(digest, entry.provenance().sourceType());
                update(digest, entry.provenance().sourceId());
                update(digest, entry.provenance().businessDate().toString());
                update(digest, entry.provenance().effectiveFrom().toString());
                update(digest, nullable(entry.provenance().expiresOn()));
                update(digest, nullable(entry.provenance().policyVersionId()));
                update(digest, nullable(entry.provenance().periodVersionId()));
                update(digest, nullable(entry.provenance().closeSnapshotId()));
                update(digest, entry.provenance().requestId());
                update(digest, nullable(entry.reversalOfEntryId()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static String nullable(Object value) {
        return value == null ? "<null>" : value.toString();
    }
}
