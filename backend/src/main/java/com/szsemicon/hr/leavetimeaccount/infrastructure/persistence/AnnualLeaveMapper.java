package com.szsemicon.hr.leavetimeaccount.infrastructure.persistence;

import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AnnualLeaveMapper {

    List<AnnualLeaveManagementRepository.CurrentEmploymentRow>
            findCurrentEmployments(
                    @Param("employeeId") String employeeId,
                    @Param("at") Instant at);

    Optional<String> findPublishedPolicyVersionId(
            @Param("companyId") String companyId);

    Optional<AnnualLeaveManagementRepository.TimeAccountRow> findTimeAccount(
            @Param("employeeId") String employeeId,
            @Param("employmentPeriodId") String employmentPeriodId,
            @Param("year") int year,
            @Param("accountType") String accountType);

    Optional<AnnualLeaveManagementRepository.TimeAccountRow> lockTimeAccount(
            @Param("employeeId") String employeeId,
            @Param("employmentPeriodId") String employmentPeriodId,
            @Param("year") int year,
            @Param("accountType") String accountType);

    void createTimeAccountIfAbsent(
            @Param("accountId") String accountId,
            @Param("employeeId") String employeeId,
            @Param("employmentPeriodId") String employmentPeriodId,
            @Param("companyId") String companyId,
            @Param("year") int year,
            @Param("accountType") String accountType,
            @Param("policyVersionId") String policyVersionId,
            @Param("now") Instant now);

    int updateBalance(
            @Param("accountId") String accountId,
            @Param("newBalance") BigDecimal newBalance,
            @Param("expectedRowVersion") long expectedRowVersion);

    int nextSequenceNo(@Param("accountId") String accountId);

    BigDecimal sumLedgerAmount(@Param("accountId") String accountId);

    BigDecimal sumActiveReservations(@Param("accountId") String accountId);

    AnnualLeaveManagementRepository.OpeningImportSummary summarizeOpeningImport(
            @Param("accountId") String accountId);

    void insertLedgerEntry(AnnualLeaveManagementRepository.LedgerEntryRow entry);

    List<AnnualLeaveManagementRepository.LedgerEntryRow> listLedgerEntries(
            @Param("accountId") String accountId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countLedgerEntries(@Param("accountId") String accountId);

    void claimBalanceIdempotency(
            AnnualLeaveManagementRepository.BalanceIdempotencyRow row);

    Optional<AnnualLeaveManagementRepository.BalanceIdempotencyRow>
            lockBalanceIdempotency(
                    @Param("principalId") String principalId,
                    @Param("idempotencyKey") String idempotencyKey);

    int completeBalanceIdempotency(
            @Param("principalId") String principalId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("claimToken") String claimToken,
            @Param("resultingLedgerEntryId") String resultingLedgerEntryId,
            @Param("resultingBalanceHours") BigDecimal resultingBalanceHours,
            @Param("resultingRowVersion") long resultingRowVersion,
            @Param("completedAt") Instant completedAt);

    boolean canAccessEmployee(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("employeeId") String employeeId,
            @Param("at") Instant at);

    Optional<String> findPrincipalEmployeeId(
            @Param("principalId") String principalId);
}
