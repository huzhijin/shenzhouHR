package com.szsemicon.hr.leavetimeaccount.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AnnualLeaveManagementRepository {

    /**
     * Resolve the employee's single current, effective employment period.
     */
    List<CurrentEmploymentRow> findCurrentEmployments(
            String employeeId,
            Instant at);

    /**
     * Find published annual leave policy version ID for a company.
     */
    Optional<String> findPublishedPolicyVersionId(String companyId);

    /**
     * Find an existing annual leave time account for the employee in the given year.
     */
    Optional<TimeAccountRow> findTimeAccount(
            String employeeId,
            String employmentPeriodId,
            int year);

    /** Lock the account row before inspecting or appending its ledger. */
    Optional<TimeAccountRow> lockTimeAccount(
            String employeeId,
            String employmentPeriodId,
            int year);

    /**
     * Create a new annual leave time account if none exists (idempotent via INSERT IGNORE).
     */
    void createTimeAccountIfAbsent(
            String accountId,
            String employeeId,
            String employmentPeriodId,
            String companyId,
            int year,
            String policyVersionId,
            Instant now);

    /**
     * Atomically update the time account balance using optimistic locking.
     * Returns true if updated, false if row_version mismatch.
     */
    boolean updateBalance(String accountId, BigDecimal newBalance, long expectedRowVersion);

    /**
     * Return the next ledger sequence number while the caller holds the account lock.
     */
    int nextSequenceNo(String accountId);

    /** Sum the immutable ledger for fail-closed balance reconciliation. */
    BigDecimal sumLedgerAmount(String accountId);

    /** Sum OA reservations that still reduce the administratively available balance. */
    BigDecimal sumActiveReservations(String accountId);

    /** Summarize all entries that establish/correct the imported opening amount. */
    OpeningImportSummary summarizeOpeningImport(String accountId);

    /**
     * Insert a canonical ledger entry (OPENING, ADJUSTMENT, etc.).
     */
    void insertLedgerEntry(LedgerEntryRow entry);

    /**
     * List all ledger entries for an account, newest first.
     */
    List<LedgerEntryRow> listLedgerEntries(String accountId, int limit, int offset);

    /**
     * Count ledger entries for an account.
     */
    long countLedgerEntries(String accountId);

    /** Claim an idempotency key without overwriting an existing claim. */
    void claimBalanceIdempotency(BalanceIdempotencyRow row);

    /** Lock and return the durable idempotency record. */
    Optional<BalanceIdempotencyRow> lockBalanceIdempotency(
            String principalId,
            String idempotencyKey);

    /** Complete the caller-owned claim with the committed account result. */
    boolean completeBalanceIdempotency(
            String principalId,
            String idempotencyKey,
            String claimToken,
            String resultingLedgerEntryId,
            BigDecimal resultingBalanceHours,
            long resultingRowVersion,
            Instant completedAt);

    /**
     * Check whether the principal can access this employee's leave data.
     */
    boolean canAccessEmployee(
            String principalId, String capability, String employeeId, Instant at);

    record CurrentEmploymentRow(
            String employeeId,
            String employmentPeriodId,
            String companyId) {
    }

    record TimeAccountRow(
            String accountId,
            String employeeId,
            String employmentPeriodId,
            String companyId,
            int year,
            BigDecimal balanceHours,
            String policyVersionId,
            long rowVersion) {
    }

    record OpeningImportSummary(
            long entryCount,
            BigDecimal totalHours) {
    }

    record BalanceIdempotencyRow(
            String recordId,
            String principalId,
            String idempotencyKey,
            String operation,
            String requestDigest,
            String claimToken,
            String accountId,
            String recordStatus,
            String resultingLedgerEntryId,
            BigDecimal resultingBalanceHours,
            Long resultingRowVersion,
            Instant createdAt,
            Instant completedAt) {
    }

    record LedgerEntryRow(
            String entryId,
            String accountId,
            int sequenceNo,
            String entryType,
            BigDecimal amountHours,
            String sourceType,
            String sourceId,
            LocalDate businessDate,
            LocalDate effectiveFrom,
            LocalDate expiresOn,
            String policyVersionId,
            String requestId,
            String actorId,
            Instant occurredAt) {
    }
}
