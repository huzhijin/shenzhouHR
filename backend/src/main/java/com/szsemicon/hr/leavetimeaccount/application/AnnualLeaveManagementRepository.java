package com.szsemicon.hr.leavetimeaccount.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AnnualLeaveManagementRepository {

    /**
     * Find the current employment period ID for an employee (latest active assignment).
     */
    Optional<String> findCurrentEmploymentPeriodId(String employeeId);

    /**
     * Find the company ID for the given employee.
     */
    Optional<String> findEmployeeCompanyId(String employeeId);

    /**
     * Find published annual leave policy version ID for a company.
     */
    Optional<String> findPublishedPolicyVersionId(String companyId);

    /**
     * Find an existing annual leave time account for the employee in the given year.
     */
    Optional<TimeAccountRow> findTimeAccount(String employeeId, int year);

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
     * Return the next ledger sequence number for this account.
     */
    int nextSequenceNo(String accountId);

    /**
     * Insert a ledger entry (OPENING, MANUAL_ADJUSTMENT, etc.).
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

    /**
     * Check whether the principal can access this employee's leave data.
     */
    boolean canAccessEmployee(
            String principalId, String capability, String employeeId, Instant at);

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
