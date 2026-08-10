package com.szsemicon.hr.leavetimeaccount.infrastructure.persistence;

import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AnnualLeaveMapper {

    Optional<String> findCurrentEmploymentPeriodId(
            @Param("employeeId") String employeeId);

    Optional<String> findEmployeeCompanyId(
            @Param("employeeId") String employeeId);

    Optional<String> findPublishedPolicyVersionId(
            @Param("companyId") String companyId);

    Optional<AnnualLeaveManagementRepository.TimeAccountRow> findTimeAccount(
            @Param("employeeId") String employeeId,
            @Param("year") int year);

    void createTimeAccountIfAbsent(
            @Param("accountId") String accountId,
            @Param("employeeId") String employeeId,
            @Param("employmentPeriodId") String employmentPeriodId,
            @Param("companyId") String companyId,
            @Param("year") int year,
            @Param("policyVersionId") String policyVersionId,
            @Param("now") Instant now);

    int updateBalance(
            @Param("accountId") String accountId,
            @Param("newBalance") BigDecimal newBalance,
            @Param("expectedRowVersion") long expectedRowVersion);

    int nextSequenceNo(@Param("accountId") String accountId);

    void insertLedgerEntry(AnnualLeaveManagementRepository.LedgerEntryRow entry);

    List<AnnualLeaveManagementRepository.LedgerEntryRow> listLedgerEntries(
            @Param("accountId") String accountId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countLedgerEntries(@Param("accountId") String accountId);

    boolean canAccessEmployee(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("employeeId") String employeeId,
            @Param("at") Instant at);
}
