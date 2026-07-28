package com.szsemicon.hr.wave6;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.leavetimeaccount.domain.LedgerEntryProvenance;
import com.szsemicon.hr.leavetimeaccount.domain.LedgerEntryType;
import com.szsemicon.hr.leavetimeaccount.domain.TimeAccountLedger;
import com.szsemicon.hr.leavetimeaccount.domain.TimeAccountLedgerEntry;
import com.szsemicon.hr.leavetimeaccount.domain.TimeAccountReversal;
import com.szsemicon.hr.leavetimeaccount.domain.TimeAccountType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimeAccountLedgerTest {

    @Test
    void balance_equals_the_exact_replay_of_every_signed_entry_category() {
        var opening = entry("entry-opening", 1, LedgerEntryType.OPENING, "20.00");
        var grant = entry("entry-grant", 2, LedgerEntryType.GRANT, "40.00");
        var returned = entry("entry-return", 3, LedgerEntryType.RETURN, "4.00");
        var adjustment = entry("entry-adjustment", 4, LedgerEntryType.ADJUSTMENT, "2.00");
        var use = entry("entry-use", 5, LedgerEntryType.USE, "-8.00");
        var expiry = entry("entry-expiry", 6, LedgerEntryType.EXPIRY, "-6.00");
        var reversal = TimeAccountReversal.reverse(
                adjustment,
                "entry-reversal",
                7,
                "MANUAL_CORRECTION",
                "correction-synthetic-1",
                LocalDate.of(2026, 7, 28),
                "request-synthetic-reversal");

        var result = TimeAccountLedger.replay(
                ACCOUNT_ID,
                TimeAccountType.ANNUAL_LEAVE,
                List.of(opening, grant, returned, adjustment, use, expiry, reversal));

        assertThat(result.balanceHours()).isEqualByComparingTo("50.00");
        assertThat(result.totals().get(LedgerEntryType.OPENING)).isEqualByComparingTo("20.00");
        assertThat(result.totals().get(LedgerEntryType.GRANT)).isEqualByComparingTo("40.00");
        assertThat(result.totals().get(LedgerEntryType.REVERSAL)).isEqualByComparingTo("-2.00");
        assertThat(result.entryCount()).isEqualTo(7);
        assertThat(result.lastSequence()).isEqualTo(7);
        assertThat(result.replayDigest()).matches("[a-f0-9]{64}");
    }

    @Test
    void expiry_date_does_not_implicitly_change_balance_without_an_expiry_entry() {
        var grant = TimeAccountLedgerEntry.create(
                "entry-expiring-grant",
                ACCOUNT_ID,
                EMPLOYMENT_ID,
                1,
                LedgerEntryType.GRANT,
                new BigDecimal("40.00"),
                provenance("ANNUAL_GRANT", "grant-synthetic-1", "2026-07-22", "2027-07-21"),
                null);

        var result = TimeAccountLedger.replay(
                ACCOUNT_ID, TimeAccountType.ANNUAL_LEAVE, List.of(grant));

        assertThat(result.balanceHours()).isEqualByComparingTo("40.00");
    }

    @Test
    void reversal_of_a_grant_is_an_exact_negative_append_and_retains_the_grant() {
        var grant = entry("entry-grant", 1, LedgerEntryType.GRANT, "40.00");
        var reversal = TimeAccountReversal.reverse(
                grant,
                "entry-grant-reversal",
                2,
                "ANNUAL_CORRECTION",
                "correction-synthetic-grant",
                LocalDate.of(2026, 7, 28),
                "request-synthetic-grant-reversal");

        var result = TimeAccountLedger.replay(
                ACCOUNT_ID, TimeAccountType.ANNUAL_LEAVE, List.of(grant, reversal));

        assertThat(reversal.amountHours()).isEqualByComparingTo("-40.00");
        assertThat(reversal.reversalOfEntryId()).isEqualTo(grant.entryId());
        assertThat(result.balanceHours()).isEqualByComparingTo("0.00");
        assertThat(result.entries()).containsExactly(grant, reversal);
    }

    @Test
    void reversal_of_a_use_restores_balance_without_deleting_the_use() {
        var opening = entry("entry-opening", 1, LedgerEntryType.OPENING, "8.00");
        var use = entry("entry-use", 2, LedgerEntryType.USE, "-8.00");
        var reversal = TimeAccountReversal.reverse(
                use,
                "entry-use-reversal",
                3,
                "LEAVE_RECONCILIATION",
                "reconciliation-synthetic-1",
                LocalDate.of(2026, 7, 28),
                "request-synthetic-use-reversal");

        var result = TimeAccountLedger.replay(
                ACCOUNT_ID, TimeAccountType.ANNUAL_LEAVE, List.of(opening, use, reversal));

        assertThat(reversal.amountHours()).isEqualByComparingTo("8.00");
        assertThat(result.balanceHours()).isEqualByComparingTo("8.00");
        assertThat(result.entries()).containsExactly(opening, use, reversal);
    }

    @Test
    void duplicate_missing_cross_account_and_non_exact_reversals_are_rejected() {
        var opening = entry("entry-opening", 1, LedgerEntryType.OPENING, "40.00");
        var reversal = TimeAccountReversal.reverse(
                opening,
                "entry-reversal-one",
                2,
                "OPENING_IMPORT_REVERSE",
                "reverse-synthetic-1",
                LocalDate.of(2026, 7, 28),
                "request-synthetic-reversal-one");
        var duplicateReversal = TimeAccountReversal.reverse(
                opening,
                "entry-reversal-two",
                3,
                "OPENING_IMPORT_REVERSE",
                "reverse-synthetic-2",
                LocalDate.of(2026, 7, 28),
                "request-synthetic-reversal-two");

        assertThatThrownBy(() -> TimeAccountLedger.replay(
                ACCOUNT_ID,
                TimeAccountType.ANNUAL_LEAVE,
                List.of(opening, reversal, duplicateReversal)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("同一流水不得重复冲正");

        var missingTarget = reversalEntry(
                "entry-missing-target-reversal", ACCOUNT_ID, 2, "-40.00", "missing-entry");
        assertThatThrownBy(() -> TimeAccountLedger.replay(
                ACCOUNT_ID, TimeAccountType.ANNUAL_LEAVE, List.of(opening, missingTarget)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("冲正目标必须存在且先于冲正流水");

        var otherAccountTarget = TimeAccountLedgerEntry.create(
                "entry-other-account",
                "account-synthetic-other",
                EMPLOYMENT_ID,
                1,
                LedgerEntryType.OPENING,
                new BigDecimal("40.00"),
                provenance("OPENING_IMPORT", "opening-synthetic-other", "2026-07-21", null),
                null);
        assertThatThrownBy(() -> TimeAccountLedger.replay(
                ACCOUNT_ID,
                TimeAccountType.ANNUAL_LEAVE,
                List.of(otherAccountTarget)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("流水账户与重放账户不一致");

        var malformed = reversalEntry(
                "entry-malformed-reversal", ACCOUNT_ID, 2, "-39.99", opening.entryId());
        assertThatThrownBy(() -> TimeAccountLedger.replay(
                ACCOUNT_ID, TimeAccountType.ANNUAL_LEAVE, List.of(opening, malformed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("冲正金额必须与目标流水精确反向");
    }

    @Test
    void reversing_a_reversal_is_rejected() {
        var opening = entry("entry-opening", 1, LedgerEntryType.OPENING, "40.00");
        var reversal = TimeAccountReversal.reverse(
                opening,
                "entry-reversal",
                2,
                "OPENING_IMPORT_REVERSE",
                "reverse-synthetic-1",
                LocalDate.of(2026, 7, 28),
                "request-synthetic-reversal");

        assertThatThrownBy(() -> TimeAccountReversal.reverse(
                reversal,
                "entry-reversal-of-reversal",
                3,
                "MANUAL_CORRECTION",
                "reverse-synthetic-2",
                LocalDate.of(2026, 7, 28),
                "request-synthetic-reversal-of-reversal"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("冲正流水不能再次冲正");
    }

    @Test
    void entry_direction_precision_and_required_provenance_are_validated() {
        assertThatThrownBy(() -> entry("entry-positive-use", 1, LedgerEntryType.USE, "1.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USE 流水金额必须为负数");

        assertThatThrownBy(() -> entry("entry-negative-grant", 1, LedgerEntryType.GRANT, "-1.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("GRANT 流水金额必须为正数");

        assertThatThrownBy(() -> entry("entry-too-precise", 1, LedgerEntryType.ADJUSTMENT, "1.001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("流水小时最多保留两位小数");

        assertThatThrownBy(() -> TimeAccountLedgerEntry.create(
                "entry-missing-source",
                ACCOUNT_ID,
                EMPLOYMENT_ID,
                1,
                LedgerEntryType.OPENING,
                new BigDecimal("1.00"),
                new LedgerEntryProvenance(
                        "",
                        "opening-synthetic-1",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 7, 21),
                        null,
                        null,
                        null,
                        null,
                        "request-synthetic-opening"),
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("流水来源类型不能为空");
    }

    @Test
    void balance_controlled_accounts_reject_a_negative_prefix() {
        var opening = entry("entry-opening", 1, LedgerEntryType.OPENING, "4.00");
        var use = entry("entry-use", 2, LedgerEntryType.USE, "-8.00");

        assertThatThrownBy(() -> TimeAccountLedger.replay(
                ACCOUNT_ID, TimeAccountType.ANNUAL_LEAVE, List.of(opening, use)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("余额受控账户不得出现负余额");
    }

    @Test
    void replay_is_deterministic_by_sequence_not_input_or_business_date_order() {
        var opening = entry("entry-opening", 1, LedgerEntryType.OPENING, "20.00");
        var use = TimeAccountLedgerEntry.create(
                "entry-use",
                ACCOUNT_ID,
                EMPLOYMENT_ID,
                2,
                LedgerEntryType.USE,
                new BigDecimal("-4.00"),
                provenance("LEAVE_REQUEST", "leave-synthetic-1", "2026-07-20", null),
                null);
        var adjustment = entry("entry-adjustment", 3, LedgerEntryType.ADJUSTMENT, "1.25");

        var chronological = TimeAccountLedger.replay(
                ACCOUNT_ID,
                TimeAccountType.ANNUAL_LEAVE,
                List.of(opening, use, adjustment));
        var shuffledEntries = new ArrayList<>(List.of(adjustment, opening, use));
        var shuffled = TimeAccountLedger.replay(
                ACCOUNT_ID, TimeAccountType.ANNUAL_LEAVE, shuffledEntries);

        assertThat(shuffled.balanceHours()).isEqualByComparingTo("17.25");
        assertThat(shuffled.entries()).containsExactly(opening, use, adjustment);
        assertThat(shuffled.replayDigest()).isEqualTo(chronological.replayDigest());
    }

    @Test
    void duplicate_entry_ids_and_sequences_are_rejected() {
        var opening = entry("entry-opening", 1, LedgerEntryType.OPENING, "20.00");
        var duplicateId = entry("entry-opening", 2, LedgerEntryType.ADJUSTMENT, "1.00");
        var duplicateSequence = entry("entry-adjustment", 1, LedgerEntryType.ADJUSTMENT, "1.00");

        assertThatThrownBy(() -> TimeAccountLedger.replay(
                ACCOUNT_ID,
                TimeAccountType.ANNUAL_LEAVE,
                List.of(opening, duplicateId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("流水 ID 不得重复");

        assertThatThrownBy(() -> TimeAccountLedger.replay(
                ACCOUNT_ID,
                TimeAccountType.ANNUAL_LEAVE,
                List.of(opening, duplicateSequence)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("流水 sequence 不得重复");
    }

    private static final String ACCOUNT_ID = "account-synthetic-annual";
    private static final String EMPLOYMENT_ID = "employment-synthetic-current";

    private TimeAccountLedgerEntry entry(
            String entryId, long sequence, LedgerEntryType type, String amount) {
        return TimeAccountLedgerEntry.create(
                entryId,
                ACCOUNT_ID,
                EMPLOYMENT_ID,
                sequence,
                type,
                new BigDecimal(amount),
                provenance(type.name(), "source-" + entryId, "2026-07-21", null),
                null);
    }

    private TimeAccountLedgerEntry reversalEntry(
            String entryId,
            String accountId,
            long sequence,
            String amount,
            String reversalOfEntryId) {
        return TimeAccountLedgerEntry.create(
                entryId,
                accountId,
                EMPLOYMENT_ID,
                sequence,
                LedgerEntryType.REVERSAL,
                new BigDecimal(amount),
                provenance(
                        "MANUAL_CORRECTION",
                        "source-" + entryId,
                        "2026-07-28",
                        null),
                reversalOfEntryId);
    }

    private LedgerEntryProvenance provenance(
            String sourceType, String sourceId, String businessDate, String expiresOn) {
        LocalDate date = LocalDate.parse(businessDate);
        return new LedgerEntryProvenance(
                sourceType,
                sourceId,
                date,
                date,
                expiresOn == null ? null : LocalDate.parse(expiresOn),
                "policy-synthetic-v1",
                null,
                null,
                "request-" + sourceId);
    }
}
