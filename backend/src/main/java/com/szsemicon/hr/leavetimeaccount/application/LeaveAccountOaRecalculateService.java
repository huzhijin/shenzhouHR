package com.szsemicon.hr.leavetimeaccount.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.CurrentEmploymentRow;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.LedgerEntryRow;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.TimeAccountRow;
import com.szsemicon.hr.leavetimeaccount.application.LeaveAccountDataPort.EmployeeEmploymentRow;
import com.szsemicon.hr.leavetimeaccount.application.LeaveAccountDataPort.OaHourRow;
import com.szsemicon.hr.leavetimeaccount.application.LeaveAccountDataPort.OaSyncEntryRow;
import com.szsemicon.hr.leavetimeaccount.application.LeaveAccountDataPort.OaSyncEntryRowWithAccount;
import com.szsemicon.hr.leavetimeaccount.domain.LedgerEntryType;
import java.util.ArrayList;
import java.util.Collections;
import com.szsemicon.hr.reporting.application.AttendanceReportQueryService;
import com.szsemicon.hr.reporting.application.RecalcWindow;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LeaveAccountOaRecalculateService {

    private static final Logger log =
            LoggerFactory.getLogger(LeaveAccountOaRecalculateService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String OA_SYNC = "OA_SYNC";

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principals;
    private final LeaveAccountDataPort dataPort;
    private final AnnualLeaveManagementRepository accounts;
    private final AttendanceReportQueryService reports;
    private final Clock clock;
    /**
     * Inclusive lower bound for the OA-sync window. Only OA documents whose
     * interval_start falls on or after this date are included, and only
     * OA_SYNC ledger entries with business_date on or after this date are
     * reversed and re-written.
     *
     * Set once after a batch script has already written the history up to
     * (but not including) this date into the account ledger as OPENING /
     * ADJUSTMENT entries. For example, the 2026-09-18 batch script covered
     * 2026-08-01 to 2026-09-30, so the next incremental sync should start
     * from 2026-09-19 (the first date the batch did not reach at runtime).
     *
     * Leave blank to sync the full year (default before the batch existed).
     */
    private final LocalDate oaSyncEffectiveFrom;
    private final TransactionTemplate employeeTx;

    public LeaveAccountOaRecalculateService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principals,
            LeaveAccountDataPort dataPort,
            AnnualLeaveManagementRepository accounts,
            AttendanceReportQueryService reports,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${shenzhouhr.leave-account.oa-sync-effective-from:}") String effectiveFromStr) {
        this.capabilities = capabilities;
        this.principals = principals;
        this.dataPort = dataPort;
        this.accounts = accounts;
        this.reports = reports;
        this.clock = clock;
        this.oaSyncEffectiveFrom = (effectiveFromStr == null || effectiveFromStr.isBlank())
                ? null : LocalDate.parse(effectiveFromStr);
        log.info("OA leave sync effective from {}", this.oaSyncEffectiveFrom);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(30);
        this.employeeTx = tx;
    }

    public RecalcResult recalculate(String companyId, Integer year) {
        capabilities.require(CapabilityCodes.ANNUAL_LEAVE_ADJUST);
        RecalcResult result = recalculateInternal(
                companyId, year, principals.currentPrincipalId());
        YearMonth month = YearMonth.from(clock.instant().atZone(BUSINESS_ZONE));
        reports.recalculate(month, companyId, RecalcWindow.MONTH);
        return result;
    }

    public RecalcResult recalculateAsSystem(String companyId, Integer year) {
        return recalculateInternal(companyId, year, "SYSTEM");
    }

    private RecalcResult recalculateInternal(
            String companyId,
            Integer year,
            String principalId) {
        if (companyId == null || companyId.isBlank()) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "公司必填");
        }
        Instant at = clock.instant();
        int accountYear = year == null
                ? at.atZone(BUSINESS_ZONE).getYear()
                : year;
        if (accountYear < 2000 || accountYear > 2100) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "年份无效");
        }
        LocalDate yearStart = LocalDate.of(accountYear, 1, 1);
        LocalDate yearEnd = LocalDate.of(accountYear + 1, 1, 1);
        // If a batch script already covered history up to a certain date and
        // wrote it into the ledger as OPENING/ADJUSTMENT entries, only sync
        // OA documents from oaSyncEffectiveFrom onward to avoid overwriting
        // those entries. Has no effect when the cutoff is in a different year.
        if (oaSyncEffectiveFrom != null
                && oaSyncEffectiveFrom.getYear() == accountYear
                && oaSyncEffectiveFrom.isAfter(yearStart)) {
            yearStart = oaSyncEffectiveFrom;
        }
        Instant yearStartAt = yearStart.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant yearEndAt = yearEnd.atStartOfDay(BUSINESS_ZONE).toInstant();
        List<EmployeeEmploymentRow> employments =
                dataPort.listCompanyEmployments(companyId, at);
        Map<String, EmployeeEmploymentRow> byEmployee = new HashMap<>();
        for (EmployeeEmploymentRow row : employments) {
            byEmployee.put(row.employeeId(), row);
        }
        Map<String, BigDecimal> annualUsed = new HashMap<>();
        Map<String, BigDecimal> timeOffUsed = new HashMap<>();
        Map<String, BigDecimal> overtimeCredit = new HashMap<>();
        for (OaHourRow row : dataPort.listOaHours(companyId, yearStartAt, yearEndAt, at)) {
            BigDecimal hours = minutesToHours(row.recognizedMinutes());
            switch (row.kind()) {
                case "ANNUAL" -> annualUsed.merge(row.employeeId(), hours, BigDecimal::add);
                case "TIME_OFF" -> timeOffUsed.merge(row.employeeId(), hours, BigDecimal::add);
                case "COMPENSATORY_OT" -> overtimeCredit.merge(
                        row.employeeId(), hours, BigDecimal::add);
                default -> {
                }
            }
        }
        Set<String> employeeIds = new LinkedHashSet<>();
        employeeIds.addAll(byEmployee.keySet());
        employeeIds.addAll(annualUsed.keySet());
        employeeIds.addAll(timeOffUsed.keySet());
        employeeIds.addAll(overtimeCredit.keySet());
        // Pre-fetch all unreversed OA sync entries for this company in one query
        // instead of one query per employee account (N+1 elimination).
        Map<String, List<OaSyncEntryRow>> prefetchedOaSyncEntries = new HashMap<>();
        for (OaSyncEntryRowWithAccount row : dataPort.listUnreversedOaSyncEntriesForCompany(
                companyId, yearStart, yearEnd)) {
            prefetchedOaSyncEntries
                    .computeIfAbsent(row.accountId(), k -> new ArrayList<>())
                    .add(new OaSyncEntryRow(row.entryId(), row.entryType(), row.amountHours()));
        }
        int annualAccounts = 0;
        int timeOffAccounts = 0;
        for (String employeeId : employeeIds) {
            EmployeeEmploymentRow resolved = byEmployee.get(employeeId);
            if (resolved == null) {
                List<CurrentEmploymentRow> current =
                        accounts.findCurrentEmployments(employeeId, at);
                if (current.size() != 1
                        || !companyId.equals(current.getFirst().companyId())) {
                    continue;
                }
                resolved = new EmployeeEmploymentRow(
                        employeeId,
                        current.getFirst().employmentPeriodId(),
                        companyId);
            }
            final EmployeeEmploymentRow employment = resolved;
            final BigDecimal annualHours =
                    annualUsed.getOrDefault(employeeId, BigDecimal.ZERO);
            final BigDecimal timeOffHours =
                    timeOffUsed.getOrDefault(employeeId, BigDecimal.ZERO);
            final BigDecimal otCredit =
                    overtimeCredit.getOrDefault(employeeId, BigDecimal.ZERO);
            final LocalDate txYearStart = yearStart;
            final LocalDate txYearEnd = yearEnd;
            try {
                employeeTx.execute(status -> {
                    syncAccount(
                            employment,
                            "ANNUAL_LEAVE",
                            accountYear,
                            annualHours,
                            BigDecimal.ZERO,
                            txYearStart,
                            txYearEnd,
                            prefetchedOaSyncEntries,
                            principalId,
                            at);
                    syncAccount(
                            employment,
                            "TIME_OFF",
                            accountYear,
                            timeOffHours,
                            otCredit,
                            txYearStart,
                            txYearEnd,
                            prefetchedOaSyncEntries,
                            principalId,
                            at);
                    return null;
                });
            } catch (RuntimeException employeeError) {
                log.error(
                        "Leave OA sync failed employee={} company={} year={}",
                        employeeId,
                        companyId,
                        accountYear,
                        employeeError);
                continue;
            }
            annualAccounts++;
            timeOffAccounts++;
        }
        return new RecalcResult(
                companyId, accountYear, annualAccounts, timeOffAccounts);
    }

    private void syncAccount(
            EmployeeEmploymentRow employment,
            String accountType,
            int year,
            BigDecimal usedHours,
            BigDecimal overtimeCreditHours,
            LocalDate yearStart,
            LocalDate yearEnd,
            Map<String, List<OaSyncEntryRow>> prefetchedEntries,
            String principalId,
            Instant at) {
        TimeAccountRow account = ensureAccount(employment, accountType, year, at);
        BigDecimal delta = BigDecimal.ZERO;
        String requestId = "oa-sync:" + account.accountId() + ":" + year
                + ":" + at.toEpochMilli();
        LocalDate businessDate = yearEnd.minusDays(1);
        // Use pre-fetched entries if available; fall back to per-account query.
        List<OaSyncEntryRow> entries = prefetchedEntries != null
                ? prefetchedEntries.getOrDefault(account.accountId(), Collections.emptyList())
                : dataPort.listUnreversedOaSyncEntries(account.accountId(), yearStart, yearEnd);
        // Use a local counter seeded from the DB max once, instead of one
        // SELECT MAX(sequence_no) round-trip per reversal row.
        // Read the current max sequence once and increment locally; avoids
        // one DB round-trip per ledger entry while staying correct whether
        // or not there are any reversal rows to write.
        int localSeq = accounts.nextSequenceNo(account.accountId()) - 1;
        for (OaSyncEntryRow entry : entries) {
            BigDecimal reverseAmount = entry.amountHours().negate();
            localSeq++;
            dataPort.insertReversal(
                    UUID.randomUUID().toString(),
                    account.accountId(),
                    localSeq,
                    reverseAmount,
                    OA_SYNC,
                    entry.entryId(),
                    businessDate,
                    account.policyVersionId(),
                    "oa-sync-rev:" + entry.entryId(),
                    entry.entryId(),
                    principalId,
                    at);
            delta = delta.add(reverseAmount);
        }
        if (usedHours.signum() > 0) {
            localSeq++;
            delta = delta.add(append(
                    account,
                    LedgerEntryType.USE.name(),
                    usedHours.negate(),
                    requestId + ":use",
                    businessDate,
                    localSeq,
                    principalId,
                    at));
        }
        if (overtimeCreditHours.signum() > 0) {
            localSeq++;
            delta = delta.add(append(
                    account,
                    LedgerEntryType.OVERTIME_CREDIT.name(),
                    overtimeCreditHours,
                    requestId + ":ot",
                    businessDate,
                    localSeq,
                    principalId,
                    at));
        }
        if (delta.signum() == 0) {
            return;
        }
        BigDecimal resulting = account.balanceHours().add(delta);
        if (!accounts.updateBalance(
                account.accountId(), resulting, account.rowVersion())) {
            throw new OptimisticLockingFailureException(
                    "concurrent leave balance update");
        }
    }

    private BigDecimal append(
            TimeAccountRow account,
            String entryType,
            BigDecimal amount,
            String requestId,
            LocalDate businessDate,
            int sequence,
            String principalId,
            Instant at) {
        accounts.insertLedgerEntry(new LedgerEntryRow(
                UUID.randomUUID().toString(),
                account.accountId(),
                sequence,
                entryType,
                amount,
                OA_SYNC,
                requestId,
                businessDate,
                businessDate,
                null,
                account.policyVersionId(),
                requestId,
                principalId,
                at));
        return amount;
    }

    private TimeAccountRow ensureAccount(
            EmployeeEmploymentRow employment,
            String accountType,
            int year,
            Instant at) {
        var locked = accounts.lockTimeAccount(
                employment.employeeId(),
                employment.employmentPeriodId(),
                year,
                accountType);
        if (locked.isPresent()) {
            return locked.get();
        }
        String accountId = AnnualLeaveManagementModels.accountId(
                accountType,
                employment.employeeId(),
                employment.employmentPeriodId(),
                year);
        String policyVersionId = "TIME_OFF".equals(accountType)
                ? AnnualLeaveManagementModels.defaultPolicyVersionId(accountType)
                : accounts.findPublishedPolicyVersionId(employment.companyId())
                        .orElse(AnnualLeaveManagementModels.defaultPolicyVersionId(
                                accountType));
        accounts.createTimeAccountIfAbsent(
                accountId,
                employment.employeeId(),
                employment.employmentPeriodId(),
                employment.companyId(),
                year,
                accountType,
                policyVersionId,
                at);
        return accounts.lockTimeAccount(
                        employment.employeeId(),
                        employment.employmentPeriodId(),
                        year,
                        accountType)
                .orElseThrow(() -> new IllegalStateException(
                        "leave account unavailable after creation"));
    }

    private static BigDecimal minutesToHours(long minutes) {
        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    public record RecalcResult(
            String companyId,
            int year,
            int annualAccounts,
            int timeOffAccounts) {
    }
}
