package com.szsemicon.hr.leavetimeaccount.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Port for leave account data operations.
 * Isolates application layer from infrastructure persistence details.
 */
public interface LeaveAccountDataPort {

    /**
     * List all active employments for a company at a given point in time.
     */
    List<EmployeeEmploymentRow> listCompanyEmployments(String companyId, Instant at);

    /**
     * List OA hours by kind for a company within a year window.
     */
    List<OaHourRow> listOaHours(
            String companyId,
            Instant yearStartAt,
            Instant yearEndAt,
            Instant asOf);

    /**
     * List unreversed OA sync entries for a company within a date range.
     */
    List<OaSyncEntryRowWithAccount> listUnreversedOaSyncEntriesForCompany(
            String companyId,
            LocalDate yearStart,
            LocalDate yearEnd);

    /**
     * List unreversed OA sync entries for a specific account within a date range.
     */
    List<OaSyncEntryRow> listUnreversedOaSyncEntries(
            String accountId,
            LocalDate yearStart,
            LocalDate yearEnd);

    /**
     * Insert a reversal entry for OA sync reconciliation.
     */
    void insertReversal(
            String id,
            String accountId,
            int sequence,
            BigDecimal amount,
            String source,
            String reversesEntryId,
            LocalDate businessDate,
            String policyVersionId,
            String requestId,
            String reversedEntryId,
            String principalId,
            Instant at);

    record EmployeeEmploymentRow(
            String employeeId,
            String employmentPeriodId,
            String companyId) {
    }

    record OaHourRow(
            String employeeId,
            String kind,
            long recognizedMinutes) {
    }

    record OaSyncEntryRow(
            String entryId,
            String entryType,
            BigDecimal amountHours) {
    }

    record OaSyncEntryRowWithAccount(
            String accountId,
            String entryId,
            String entryType,
            BigDecimal amountHours) {
    }
}
