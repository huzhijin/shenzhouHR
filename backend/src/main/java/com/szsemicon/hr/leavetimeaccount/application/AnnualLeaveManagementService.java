package com.szsemicon.hr.leavetimeaccount.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.AdjustBalanceCommand;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.LeaveAccountView;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.LedgerEntryView;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.OpeningBalanceCommand;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.BalanceIdempotencyRow;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.CurrentEmploymentRow;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.LedgerEntryRow;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.OpeningImportSummary;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.TimeAccountRow;
import com.szsemicon.hr.leavetimeaccount.domain.LedgerEntryType;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnnualLeaveManagementService {

    private static final int MAX_ENTRIES_PAGE = 100;
    private static final BigDecimal HOURS_PER_DAY = BigDecimal.valueOf(8);
    private static final String OPENING_OPERATION = "SET_OPENING";
    private static final String ADJUST_OPERATION = "ADJUST";
    private static final String IDEMPOTENCY_COMPLETED = "COMPLETED";
    private static final String IDEMPOTENCY_PROCESSING = "PROCESSING";

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
    public LeaveAccountView getAccount(
            String employeeId,
            int year,
            int page,
            int size) {
        capabilities.require(CapabilityCodes.ANNUAL_LEAVE_READ);
        String principalId = principalProvider.currentPrincipalId();
        Instant at = clock.instant();
        requireEmployeeAccess(
                principalId,
                CapabilityCodes.ANNUAL_LEAVE_READ,
                employeeId,
                at);
        CurrentEmploymentRow employment = resolveCurrentEmployment(employeeId, at);
        return loadAccountView(employeeId, employment.employmentPeriodId(), year, page, size);
    }

    @Transactional
    public LeaveAccountView setOpeningBalance(OpeningBalanceCommand command) {
        Instant at = clock.instant();
        String principalId = requireAdjustmentAccess(command.employeeId(), at);
        CurrentEmploymentRow employment = resolveCurrentEmployment(command.employeeId(), at);
        TimeAccountRow current = ensureAndLockAccount(
                command.employeeId(), employment, command.year(), at);
        assertLedgerInvariant(current);
        assertExistingAvailability(current);

        String requestDigest = requestDigest(
                "ANNUAL_LEAVE_OPENING_V1",
                command.employeeId(),
                employment.employmentPeriodId(),
                Integer.toString(command.year()),
                command.balanceHours().toPlainString(),
                command.openingDate().toString(),
                command.reason());
        IdempotencyClaim claim = claimIdempotency(
                current,
                principalId,
                command.requestId(),
                OPENING_OPERATION,
                requestDigest,
                at);
        if (claim.replay()) {
            return loadAccountView(
                    command.employeeId(),
                    employment.employmentPeriodId(),
                    command.year(),
                    0,
                    20);
        }

        OpeningImportSummary opening =
                repository.summarizeOpeningImport(current.accountId());
        BigDecimal delta = command.balanceHours().subtract(opening.totalHours());
        if (delta.signum() == 0) {
            completeIdempotency(claim, null, current);
            return loadAccountView(
                    command.employeeId(),
                    employment.employmentPeriodId(),
                    command.year(),
                    0,
                    20);
        }

        BigDecimal resultingBalance = current.balanceHours().add(delta);
        assertResultingAvailability(current.accountId(), resultingBalance);
        String entryType = opening.entryCount() == 0
                ? LedgerEntryType.OPENING.name()
                : LedgerEntryType.ADJUSTMENT.name();
        LedgerEntryRow entry = appendLedgerAndUpdateBalance(
                current,
                entryType,
                delta,
                "HR_OPENING_IMPORT",
                command.requestId(),
                command.openingDate(),
                AnnualLeaveManagementModels.yearEnd(command.year()),
                principalId,
                at,
                resultingBalance);
        completeIdempotency(
                claim,
                entry.entryId(),
                withResult(current, resultingBalance));
        return loadAccountView(
                command.employeeId(),
                employment.employmentPeriodId(),
                command.year(),
                0,
                20);
    }

    @Transactional
    public LeaveAccountView adjustBalance(AdjustBalanceCommand command) {
        Instant at = clock.instant();
        String principalId = requireAdjustmentAccess(command.employeeId(), at);
        CurrentEmploymentRow employment = resolveCurrentEmployment(command.employeeId(), at);
        TimeAccountRow current = ensureAndLockAccount(
                command.employeeId(), employment, command.year(), at);
        assertLedgerInvariant(current);
        assertExistingAvailability(current);

        String requestDigest = requestDigest(
                "ANNUAL_LEAVE_ADJUSTMENT_V1",
                command.employeeId(),
                employment.employmentPeriodId(),
                Integer.toString(command.year()),
                command.adjustmentHours().toPlainString(),
                command.reason());
        IdempotencyClaim claim = claimIdempotency(
                current,
                principalId,
                command.requestId(),
                ADJUST_OPERATION,
                requestDigest,
                at);
        if (claim.replay()) {
            return loadAccountView(
                    command.employeeId(),
                    employment.employmentPeriodId(),
                    command.year(),
                    0,
                    20);
        }

        BigDecimal adjustment = command.adjustmentHours();
        if (adjustment.signum() == 0) {
            completeIdempotency(claim, null, current);
            return loadAccountView(
                    command.employeeId(),
                    employment.employmentPeriodId(),
                    command.year(),
                    0,
                    20);
        }

        BigDecimal resultingBalance = current.balanceHours().add(adjustment);
        assertResultingAvailability(current.accountId(), resultingBalance);
        LocalDate businessDate = at.atZone(ZoneOffset.UTC).toLocalDate();
        LedgerEntryRow entry = appendLedgerAndUpdateBalance(
                current,
                LedgerEntryType.ADJUSTMENT.name(),
                adjustment,
                "HR_MANUAL_ADJUSTMENT",
                command.requestId(),
                businessDate,
                null,
                principalId,
                at,
                resultingBalance);
        completeIdempotency(
                claim,
                entry.entryId(),
                withResult(current, resultingBalance));
        return loadAccountView(
                command.employeeId(),
                employment.employmentPeriodId(),
                command.year(),
                0,
                20);
    }

    private String requireAdjustmentAccess(String employeeId, Instant at) {
        capabilities.require(CapabilityCodes.ANNUAL_LEAVE_ADJUST);
        String principalId = principalProvider.currentPrincipalId();
        requireEmployeeAccess(
                principalId,
                CapabilityCodes.ANNUAL_LEAVE_ADJUST,
                employeeId,
                at);
        return principalId;
    }

    private void requireEmployeeAccess(
            String principalId,
            String capability,
            String employeeId,
            Instant at) {
        if (!repository.canAccessEmployee(
                principalId, capability, employeeId, at)) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
    }

    private CurrentEmploymentRow resolveCurrentEmployment(
            String employeeId,
            Instant at) {
        List<CurrentEmploymentRow> employments =
                repository.findCurrentEmployments(employeeId, at);
        if (employments.size() != 1) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return employments.getFirst();
    }

    private TimeAccountRow ensureAndLockAccount(
            String employeeId,
            CurrentEmploymentRow employment,
            int year,
            Instant at) {
        Optional<TimeAccountRow> locked = repository.lockTimeAccount(
                employeeId, employment.employmentPeriodId(), year);
        if (locked.isEmpty()) {
            createAccount(employeeId, employment, year, at);
            locked = repository.lockTimeAccount(
                    employeeId, employment.employmentPeriodId(), year);
        }
        return locked
                .orElseThrow(() -> new IllegalStateException(
                        "annual leave account unavailable after creation"));
    }

    private void createAccount(
            String employeeId,
            CurrentEmploymentRow employment,
            int year,
            Instant at) {
        String accountId = AnnualLeaveManagementModels.accountId(
                employeeId, employment.employmentPeriodId(), year);
        String policyVersionId = repository
                .findPublishedPolicyVersionId(employment.companyId())
                .orElse("ANNUAL_LEAVE_DEFAULT_V1");
        repository.createTimeAccountIfAbsent(
                accountId,
                employeeId,
                employment.employmentPeriodId(),
                employment.companyId(),
                year,
                policyVersionId,
                at);
    }

    private void assertLedgerInvariant(TimeAccountRow account) {
        BigDecimal ledgerBalance = repository.sumLedgerAmount(account.accountId());
        if (account.balanceHours().compareTo(ledgerBalance) != 0) {
            throw conflict(
                    "ANNUAL_LEAVE_LEDGER_MISMATCH",
                    "年假账户余额与不可变台账不一致，已拒绝写入");
        }
    }

    private void assertExistingAvailability(TimeAccountRow account) {
        assertResultingAvailability(account.accountId(), account.balanceHours());
    }

    private void assertResultingAvailability(
            String accountId,
            BigDecimal resultingBalance) {
        BigDecimal reservedHours = repository.sumActiveReservations(accountId);
        if (resultingBalance.compareTo(reservedHours) < 0) {
            throw conflict(
                    "ANNUAL_LEAVE_AVAILABLE_BALANCE_INSUFFICIENT",
                    "调整后余额不得低于 OA 已预占小时");
        }
    }

    private IdempotencyClaim claimIdempotency(
            TimeAccountRow account,
            String principalId,
            String idempotencyKey,
            String operation,
            String requestDigest,
            Instant at) {
        String claimToken = UUID.randomUUID().toString();
        repository.claimBalanceIdempotency(new BalanceIdempotencyRow(
                UUID.randomUUID().toString(),
                principalId,
                idempotencyKey,
                operation,
                requestDigest,
                claimToken,
                account.accountId(),
                IDEMPOTENCY_PROCESSING,
                null,
                null,
                null,
                at,
                null));
        BalanceIdempotencyRow existing = repository.lockBalanceIdempotency(
                        principalId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "annual leave idempotency claim missing"));
        if (!operation.equals(existing.operation())
                || !requestDigest.equals(existing.requestDigest())
                || !account.accountId().equals(existing.accountId())) {
            throw conflict(
                    "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
                    "Idempotency-Key 已用于不同的年假余额请求");
        }
        if (IDEMPOTENCY_COMPLETED.equals(existing.recordStatus())) {
            return new IdempotencyClaim(existing, true);
        }
        if (!IDEMPOTENCY_PROCESSING.equals(existing.recordStatus())
                || !claimToken.equals(existing.claimToken())) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    "相同 Idempotency-Key 的请求仍在处理中",
                    true);
        }
        return new IdempotencyClaim(existing, false);
    }

    private LedgerEntryRow appendLedgerAndUpdateBalance(
            TimeAccountRow current,
            String entryType,
            BigDecimal amount,
            String sourceType,
            String requestId,
            LocalDate businessDate,
            LocalDate expiresOn,
            String principalId,
            Instant at,
            BigDecimal resultingBalance) {
        int sequence = repository.nextSequenceNo(current.accountId());
        LedgerEntryRow entry = new LedgerEntryRow(
                UUID.randomUUID().toString(),
                current.accountId(),
                sequence,
                entryType,
                amount,
                sourceType,
                requestId,
                businessDate,
                businessDate,
                expiresOn,
                current.policyVersionId(),
                requestId,
                principalId,
                at);
        repository.insertLedgerEntry(entry);
        if (!repository.updateBalance(
                current.accountId(), resultingBalance, current.rowVersion())) {
            throw new OptimisticLockingFailureException(
                    "concurrent leave balance update");
        }
        return entry;
    }

    private void completeIdempotency(
            IdempotencyClaim claim,
            String ledgerEntryId,
            TimeAccountRow resultingAccount) {
        if (!repository.completeBalanceIdempotency(
                claim.row().principalId(),
                claim.row().idempotencyKey(),
                claim.row().claimToken(),
                ledgerEntryId,
                resultingAccount.balanceHours(),
                resultingAccount.rowVersion(),
                clock.instant())) {
            throw new OptimisticLockingFailureException(
                    "annual leave idempotency completion conflict");
        }
    }

    private static TimeAccountRow withResult(
            TimeAccountRow current,
            BigDecimal resultingBalance) {
        return new TimeAccountRow(
                current.accountId(),
                current.employeeId(),
                current.employmentPeriodId(),
                current.companyId(),
                current.year(),
                resultingBalance,
                current.policyVersionId(),
                current.rowVersion() + 1);
    }

    private LeaveAccountView loadAccountView(
            String employeeId,
            String employmentPeriodId,
            int year,
            int page,
            int size) {
        int safeSize = Math.min(size, MAX_ENTRIES_PAGE);
        var account = repository.findTimeAccount(employeeId, employmentPeriodId, year);
        if (account.isEmpty()) {
            return new LeaveAccountView(
                    null,
                    employeeId,
                    year,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    List.of(),
                    0L);
        }
        TimeAccountRow row = account.get();
        long total = repository.countLedgerEntries(row.accountId());
        List<LedgerEntryRow> entries = repository.listLedgerEntries(
                row.accountId(), safeSize, page * safeSize);
        return toView(row, entries, total);
    }

    private static LeaveAccountView toView(
            TimeAccountRow account,
            List<LedgerEntryRow> entries,
            long total) {
        BigDecimal equivalentDays = account.balanceHours()
                .divide(HOURS_PER_DAY, 2, RoundingMode.HALF_UP);
        List<LedgerEntryView> entryViews = entries.stream()
                .map(entry -> new LedgerEntryView(
                        entry.entryId(),
                        entry.entryType(),
                        AnnualLeaveManagementModels.entryTypeLabel(
                                entry.entryType()),
                        entry.amountHours(),
                        entry.sourceType(),
                        entry.businessDate(),
                        entry.effectiveFrom(),
                        entry.expiresOn(),
                        entry.occurredAt()))
                .toList();
        return new LeaveAccountView(
                account.accountId(),
                account.employeeId(),
                account.year(),
                account.balanceHours(),
                equivalentDays,
                account.rowVersion(),
                entryViews,
                total);
    }

    private static String requestDigest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length)
                        .getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 must be available", exception);
        }
    }

    private static ApiProblemException conflict(String code, String message) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, message);
    }

    private record IdempotencyClaim(
            BalanceIdempotencyRow row,
            boolean replay) {
    }
}
