package com.szsemicon.hr.leavetimeaccount.infrastructure.persistence;

import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class MyBatisAnnualLeaveRepository implements AnnualLeaveManagementRepository {

    private final AnnualLeaveMapper mapper;

    MyBatisAnnualLeaveRepository(AnnualLeaveMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CurrentEmploymentRow> findCurrentEmployments(
            String employeeId,
            Instant at) {
        return mapper.findCurrentEmployments(employeeId, at);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findPublishedPolicyVersionId(String companyId) {
        return mapper.findPublishedPolicyVersionId(companyId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TimeAccountRow> findTimeAccount(
            String employeeId,
            String employmentPeriodId,
            int year,
            String accountType) {
        return mapper.findTimeAccount(
                employeeId, employmentPeriodId, year, accountType);
    }

    @Override
    @Transactional
    public Optional<TimeAccountRow> lockTimeAccount(
            String employeeId,
            String employmentPeriodId,
            int year,
            String accountType) {
        return mapper.lockTimeAccount(
                employeeId, employmentPeriodId, year, accountType);
    }

    @Override
    @Transactional
    public void createTimeAccountIfAbsent(
            String accountId,
            String employeeId,
            String employmentPeriodId,
            String companyId,
            int year,
            String accountType,
            String policyVersionId,
            Instant now) {
        mapper.createTimeAccountIfAbsent(
                accountId, employeeId, employmentPeriodId,
                companyId, year, accountType, policyVersionId, now);
    }

    @Override
    @Transactional
    public boolean updateBalance(
            String accountId, BigDecimal newBalance, long expectedRowVersion) {
        return mapper.updateBalance(accountId, newBalance, expectedRowVersion) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public int nextSequenceNo(String accountId) {
        return mapper.nextSequenceNo(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal sumLedgerAmount(String accountId) {
        return mapper.sumLedgerAmount(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal sumActiveReservations(String accountId) {
        return mapper.sumActiveReservations(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public OpeningImportSummary summarizeOpeningImport(String accountId) {
        return mapper.summarizeOpeningImport(accountId);
    }

    @Override
    @Transactional
    public void insertLedgerEntry(LedgerEntryRow entry) {
        mapper.insertLedgerEntry(entry);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntryRow> listLedgerEntries(
            String accountId, int limit, int offset) {
        return mapper.listLedgerEntries(accountId, limit, offset);
    }

    @Override
    @Transactional(readOnly = true)
    public long countLedgerEntries(String accountId) {
        return mapper.countLedgerEntries(accountId);
    }

    @Override
    @Transactional
    public void claimBalanceIdempotency(BalanceIdempotencyRow row) {
        mapper.claimBalanceIdempotency(row);
    }

    @Override
    @Transactional
    public Optional<BalanceIdempotencyRow> lockBalanceIdempotency(
            String principalId,
            String idempotencyKey) {
        return mapper.lockBalanceIdempotency(principalId, idempotencyKey);
    }

    @Override
    @Transactional
    public boolean completeBalanceIdempotency(
            String principalId,
            String idempotencyKey,
            String claimToken,
            String resultingLedgerEntryId,
            BigDecimal resultingBalanceHours,
            long resultingRowVersion,
            Instant completedAt) {
        return mapper.completeBalanceIdempotency(
                principalId,
                idempotencyKey,
                claimToken,
                resultingLedgerEntryId,
                resultingBalanceHours,
                resultingRowVersion,
                completedAt) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canAccessEmployee(
            String principalId, String capability, String employeeId, Instant at) {
        return mapper.canAccessEmployee(principalId, capability, employeeId, at);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findPrincipalEmployeeId(String principalId) {
        return mapper.findPrincipalEmployeeId(principalId);
    }
}
