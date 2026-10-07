package com.szsemicon.hr.leavetimeaccount.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.CurrentEmploymentRow;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.LedgerEntryRow;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.TimeAccountRow;
import com.szsemicon.hr.leavetimeaccount.domain.LedgerEntryType;
import com.szsemicon.hr.leavetimeaccount.infrastructure.persistence.LeaveAccountOaRecalculateMapper;
import com.szsemicon.hr.leavetimeaccount.infrastructure.persistence.LeaveAccountOaRecalculateMapper.EmployeeEmploymentRow;
import com.szsemicon.hr.leavetimeaccount.infrastructure.persistence.LeaveAccountOaRecalculateMapper.OaHourRow;
import com.szsemicon.hr.leavetimeaccount.infrastructure.persistence.LeaveAccountOaRecalculateMapper.OaSyncEntryRow;
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
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaveAccountOaRecalculateService {

    private static final Logger log =
            LoggerFactory.getLogger(LeaveAccountOaRecalculateService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String OA_SYNC = "OA_SYNC";

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principals;
    private final LeaveAccountOaRecalculateMapper mapper;
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

    public LeaveAccountOaRecalculateService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principals,
            LeaveAccountOaRecalculateMapper mapper,
            AnnualLeaveManagementRepository accounts,
            AttendanceReportQueryService reports,
            Clock clock,
            @Value("${shenzhouhr.leave-account.oa-sync-effective-from:}") String effectiveFromStr) {
        this.capabilities = capabilities;
        this.principals = principals;
        this.mapper = mapper;
        this.accounts = accounts;
        this.reports = reports;
        this.clock = clock;
        this.oaSyncEffectiveFrom = (effectiveFromStr == null || effectiveFromStr.isBlank())
                ? null : LocalDate.parse(effectiveFromStr);
        log.info("OA leave sync effective from {}", this.oaSyncEffectiveFrom);
    }

    @Transactional
    public RecalcResult recalculate(String companyId, Integer year) {
        capabilities.require(CapabilityCodes.ANNUAL_LEAVE_ADJUST);
        return recalculateInternal(
                companyId, year, principals.currentPrincipalId(), true);
    }

    @Transactional
    public RecalcResult recalculateAsSystem(String companyId, Integer year) {
        return recalculateInternal(companyId, year, "SYSTEM", false);
    }

    private RecalcResult recalculateInternal(
            String companyId,
            Integer year,
            String principalId,
            boolean refreshReports) {
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
                mapper.listCompanyEmployments(companyId, at);
        Map<String, EmployeeEmploymentRow> byEmployee = new HashMap<>();
        for (EmployeeEmploymentRow row : employments) {
            byEmployee.put(row.employeeId(), row);
        }
        Map<String, BigDecimal> annualUsed = new HashMap<>();
        Map<String, BigDecimal> timeOffUsed = new HashMap<>();
        Map<String, BigDecimal> overtimeCredit = new HashMap<>();
        for (OaHourRow row : mapper.listOaHours(companyId, yearStartAt, yearEndAt)) {
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
        int annualAccounts = 0;
        int timeOffAccounts = 0;
        for (String employeeId : employeeIds) {
            EmployeeEmploymentRow employment = byEmployee.get(employeeId);
            if (employment == null) {
                List<CurrentEmploymentRow> current =
                        accounts.findCurrentEmployments(employeeId, at);
                if (current.size() != 1
                        || !companyId.equals(current.getFirst().companyId())) {
                    continue;
                }
                employment = new EmployeeEmploymentRow(
                        employeeId,
                        current.getFirst().employmentPeriodId(),
                        companyId);
            }
            syncAccount(
                    employment,
                    "ANNUAL_LEAVE",
                    accountYear,
                    annualUsed.getOrDefault(employeeId, BigDecimal.ZERO),
                    BigDecimal.ZERO,
                    yearStart,
                    yearEnd,
                    principalId,
                    at);
            annualAccounts++;
            syncAccount(
                    employment,
                    "TIME_OFF",
                    accountYear,
                    timeOffUsed.getOrDefault(employeeId, BigDecimal.ZERO),
                    overtimeCredit.getOrDefault(employeeId, BigDecimal.ZERO),
                    yearStart,
                    yearEnd,
                    principalId,
                    at);
            timeOffAccounts++;
        }
        if (refreshReports) {
            YearMonth month = YearMonth.from(at.atZone(BUSINESS_ZONE));
            reports.recalculate(month, companyId, RecalcWindow.MONTH);
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
            String principalId,
            Instant at) {
        TimeAccountRow account = ensureAccount(employment, accountType, year, at);
        BigDecimal delta = BigDecimal.ZERO;
        String requestId = "oa-sync:" + account.accountId() + ":" + year
                + ":" + at.toEpochMilli();
        LocalDate businessDate = yearEnd.minusDays(1);
        for (OaSyncEntryRow entry : mapper.listUnreversedOaSyncEntries(
                account.accountId(), yearStart, yearEnd)) {
            BigDecimal reverseAmount = entry.amountHours().negate();
            int sequence = accounts.nextSequenceNo(account.accountId());
            mapper.insertReversal(
                    UUID.randomUUID().toString(),
                    account.accountId(),
                    sequence,
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
            delta = delta.add(append(
                    account,
                    LedgerEntryType.USE.name(),
                    usedHours.negate(),
                    requestId + ":use",
                    businessDate,
                    principalId,
                    at));
        }
        if (overtimeCreditHours.signum() > 0) {
            delta = delta.add(append(
                    account,
                    LedgerEntryType.OVERTIME_CREDIT.name(),
                    overtimeCreditHours,
                    requestId + ":ot",
                    businessDate,
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
            String principalId,
            Instant at) {
        int sequence = accounts.nextSequenceNo(account.accountId());
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
