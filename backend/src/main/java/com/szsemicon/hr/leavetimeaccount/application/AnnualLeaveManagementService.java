package com.szsemicon.hr.leavetimeaccount.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.AdjustBalanceCommand;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.LeaveAccountView;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.LedgerEntryView;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.OpeningBalanceCommand;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.LedgerEntryRow;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.TimeAccountRow;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnnualLeaveManagementService {

    private static final int MAX_ENTRIES_PAGE = 100;
    private static final BigDecimal HOURS_PER_DAY = BigDecimal.valueOf(8);

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principalProvider;
    private final AnnualLeaveManagementRepository repository;
    private final Clock clock;

    public AnnualLeaveManagementService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AnnualLeaveManagementRepository repository,
            Clock clock) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LeaveAccountView getAccount(String employeeId, int year, int page, int size) {
        capabilities.require(CapabilityCodes.ANNUAL_LEAVE_READ);
        String principalId = principalProvider.currentPrincipalId();
        Instant at = clock.instant();
        if (!repository.canAccessEmployee(principalId, CapabilityCodes.ANNUAL_LEAVE_READ, employeeId, at)) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        int safeSize = Math.min(size, MAX_ENTRIES_PAGE);
        var account = repository.findTimeAccount(employeeId, year);
        if (account.isEmpty()) {
            return new LeaveAccountView(
                    null, employeeId, year,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    0, List.of(), 0L);
        }
        TimeAccountRow row = account.get();
        long total = repository.countLedgerEntries(row.accountId());
        List<LedgerEntryRow> entries = repository.listLedgerEntries(row.accountId(), safeSize, page * safeSize);
        return toView(row, entries, total);
    }

    @Transactional
    public LeaveAccountView setOpeningBalance(OpeningBalanceCommand command) {
        capabilities.require(CapabilityCodes.ANNUAL_LEAVE_ADJUST);
        String principalId = principalProvider.currentPrincipalId();
        Instant at = clock.instant();
        if (!repository.canAccessEmployee(principalId, CapabilityCodes.ANNUAL_LEAVE_ADJUST, command.employeeId(), at)) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        String accountId = ensureAccount(command.employeeId(), command.year(), principalId, at);
        TimeAccountRow current = repository.findTimeAccount(command.employeeId(), command.year())
                .orElseThrow(() -> new IllegalStateException("account not found after creation"));

        LocalDate businessDate = at.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate expiresOn = AnnualLeaveManagementModels.yearEnd(command.year());

        // Remove any previous OPENING entry for this year by inserting a reversal if needed
        BigDecimal existingOpening = sumExistingOpening(current.accountId());
        BigDecimal delta = command.balanceHours().subtract(existingOpening);

        if (delta.compareTo(BigDecimal.ZERO) == 0) {
            return getAccount(command.employeeId(), command.year(), 0, 20);
        }

        int seq = repository.nextSequenceNo(accountId);
        String entryType = delta.compareTo(BigDecimal.ZERO) > 0 ? "OPENING" : "MANUAL_DEDUCTION";
        var entry = new LedgerEntryRow(
                UUID.randomUUID().toString(),
                accountId,
                seq,
                entryType,
                delta,
                "HR_OPENING_IMPORT",
                command.requestId(),
                businessDate,
                businessDate,
                expiresOn,
                current.policyVersionId(),
                command.requestId(),
                principalId,
                at);
        repository.insertLedgerEntry(entry);

        BigDecimal newBalance = current.balanceHours().add(delta).max(BigDecimal.ZERO);
        if (!repository.updateBalance(accountId, newBalance, current.rowVersion())) {
            throw new OptimisticLockingFailureException("concurrent leave balance update");
        }
        return getAccount(command.employeeId(), command.year(), 0, 20);
    }

    @Transactional
    public LeaveAccountView adjustBalance(AdjustBalanceCommand command) {
        capabilities.require(CapabilityCodes.ANNUAL_LEAVE_ADJUST);
        String principalId = principalProvider.currentPrincipalId();
        Instant at = clock.instant();
        if (!repository.canAccessEmployee(principalId, CapabilityCodes.ANNUAL_LEAVE_ADJUST, command.employeeId(), at)) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        String accountId = ensureAccount(command.employeeId(), command.year(), principalId, at);
        TimeAccountRow current = repository.findTimeAccount(command.employeeId(), command.year())
                .orElseThrow(() -> new IllegalStateException("account not found after creation"));

        LocalDate businessDate = at.atZone(ZoneOffset.UTC).toLocalDate();
        boolean isIncrease = command.adjustmentHours().compareTo(BigDecimal.ZERO) >= 0;
        String entryType = isIncrease ? "MANUAL_INCREASE" : "MANUAL_DEDUCTION";

        int seq = repository.nextSequenceNo(accountId);
        var entry = new LedgerEntryRow(
                UUID.randomUUID().toString(),
                accountId,
                seq,
                entryType,
                command.adjustmentHours(),
                "HR_MANUAL_ADJUSTMENT",
                command.requestId(),
                businessDate,
                businessDate,
                null,
                current.policyVersionId(),
                command.requestId(),
                principalId,
                at);
        repository.insertLedgerEntry(entry);

        BigDecimal newBalance = current.balanceHours().add(command.adjustmentHours()).max(BigDecimal.ZERO);
        if (!repository.updateBalance(accountId, newBalance, current.rowVersion())) {
            throw new OptimisticLockingFailureException("concurrent leave balance update");
        }
        return getAccount(command.employeeId(), command.year(), 0, 20);
    }

    private String ensureAccount(String employeeId, int year, String principalId, Instant at) {
        String accountId = AnnualLeaveManagementModels.accountId(employeeId, year);
        String employmentPeriodId = repository.findCurrentEmploymentPeriodId(employeeId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        String companyId = repository.findEmployeeCompanyId(employeeId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        String policyVersionId = repository.findPublishedPolicyVersionId(companyId)
                .orElse("ANNUAL_LEAVE_DEFAULT_V1");
        repository.createTimeAccountIfAbsent(
                accountId, employeeId, employmentPeriodId, companyId, year, policyVersionId, at);
        return accountId;
    }

    private BigDecimal sumExistingOpening(String accountId) {
        return repository.listLedgerEntries(accountId, MAX_ENTRIES_PAGE, 0).stream()
                .filter(e -> "OPENING".equals(e.entryType()) || "HR_OPENING_IMPORT".equals(e.sourceType()))
                .map(LedgerEntryRow::amountHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static LeaveAccountView toView(
            TimeAccountRow account,
            List<LedgerEntryRow> entries,
            long total) {
        BigDecimal equiv = account.balanceHours()
                .divide(HOURS_PER_DAY, 2, RoundingMode.HALF_UP);
        List<LedgerEntryView> entryViews = entries.stream()
                .map(e -> new LedgerEntryView(
                        e.entryId(),
                        e.entryType(),
                        AnnualLeaveManagementModels.entryTypeLabel(e.entryType()),
                        e.amountHours(),
                        e.sourceType(),
                        e.businessDate(),
                        e.effectiveFrom(),
                        e.expiresOn(),
                        e.occurredAt()))
                .toList();
        return new LeaveAccountView(
                account.accountId(),
                account.employeeId(),
                account.year(),
                account.balanceHours(),
                equiv,
                account.rowVersion(),
                entryViews,
                total);
    }
}
