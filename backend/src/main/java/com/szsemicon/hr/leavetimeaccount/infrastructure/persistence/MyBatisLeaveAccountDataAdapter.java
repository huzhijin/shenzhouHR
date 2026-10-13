package com.szsemicon.hr.leavetimeaccount.infrastructure.persistence;

import com.szsemicon.hr.leavetimeaccount.application.LeaveAccountDataPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * MyBatis adapter implementing leave account data port.
 * Delegates to existing LeaveAccountOaRecalculateMapper infrastructure.
 */
@Component
public class MyBatisLeaveAccountDataAdapter implements LeaveAccountDataPort {

    private final LeaveAccountOaRecalculateMapper mapper;

    public MyBatisLeaveAccountDataAdapter(LeaveAccountOaRecalculateMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<EmployeeEmploymentRow> listCompanyEmployments(String companyId, Instant at) {
        return mapper.listCompanyEmployments(companyId, at).stream()
                .map(row -> new EmployeeEmploymentRow(
                        row.employeeId(),
                        row.employmentPeriodId(),
                        row.companyId()))
                .toList();
    }

    @Override
    public List<OaHourRow> listOaHours(
            String companyId,
            Instant yearStartAt,
            Instant yearEndAt,
            Instant asOf) {
        return mapper.listOaHours(companyId, yearStartAt, yearEndAt, asOf).stream()
                .map(row -> new OaHourRow(
                        row.employeeId(),
                        row.kind(),
                        row.recognizedMinutes()))
                .toList();
    }

    @Override
    public List<OaSyncEntryRowWithAccount> listUnreversedOaSyncEntriesForCompany(
            String companyId,
            LocalDate yearStart,
            LocalDate yearEnd) {
        return mapper.listUnreversedOaSyncEntriesForCompany(companyId, yearStart, yearEnd).stream()
                .map(row -> new OaSyncEntryRowWithAccount(
                        row.accountId(),
                        row.entryId(),
                        row.entryType(),
                        row.amountHours()))
                .toList();
    }

    @Override
    public List<OaSyncEntryRow> listUnreversedOaSyncEntries(
            String accountId,
            LocalDate yearStart,
            LocalDate yearEnd) {
        return mapper.listUnreversedOaSyncEntries(accountId, yearStart, yearEnd).stream()
                .map(row -> new OaSyncEntryRow(
                        row.entryId(),
                        row.entryType(),
                        row.amountHours()))
                .toList();
    }

    @Override
    public void insertReversal(
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
            Instant at) {
        mapper.insertReversal(
                id,
                accountId,
                sequence,
                amount,
                source,
                reversesEntryId,
                businessDate,
                policyVersionId,
                requestId,
                reversedEntryId,
                principalId,
                at);
    }
}
