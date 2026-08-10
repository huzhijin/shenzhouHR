package com.szsemicon.hr.leavetimeaccount.infrastructure.persistence;

import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
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
    public Optional<String> findCurrentEmploymentPeriodId(String employeeId) {
        return mapper.findCurrentEmploymentPeriodId(employeeId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findEmployeeCompanyId(String employeeId) {
        return mapper.findEmployeeCompanyId(employeeId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findPublishedPolicyVersionId(String companyId) {
        return mapper.findPublishedPolicyVersionId(companyId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TimeAccountRow> findTimeAccount(String employeeId, int year) {
        return mapper.findTimeAccount(employeeId, year);
    }

    @Override
    @Transactional
    public void createTimeAccountIfAbsent(
            String accountId,
            String employeeId,
            String employmentPeriodId,
            String companyId,
            int year,
            String policyVersionId,
            Instant now) {
        mapper.createTimeAccountIfAbsent(
                accountId, employeeId, employmentPeriodId,
                companyId, year, policyVersionId, now);
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
    @Transactional(readOnly = true)
    public boolean canAccessEmployee(
            String principalId, String capability, String employeeId, Instant at) {
        return mapper.canAccessEmployee(principalId, capability, employeeId, at);
    }
}
