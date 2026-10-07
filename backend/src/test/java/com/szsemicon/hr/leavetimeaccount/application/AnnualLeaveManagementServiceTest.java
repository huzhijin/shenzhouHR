package com.szsemicon.hr.leavetimeaccount.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.AdjustBalanceCommand;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.LeaveAccountView;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementModels.OpeningBalanceCommand;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.BalanceIdempotencyRow;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.CurrentEmploymentRow;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.LedgerEntryRow;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.OpeningImportSummary;
import com.szsemicon.hr.leavetimeaccount.application.AnnualLeaveManagementRepository.TimeAccountRow;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnnualLeaveManagementServiceTest {

    private static final String EMPLOYEE =
            "10000000-0000-0000-0000-000000000001";
    private static final String PERIOD =
            "20000000-0000-0000-0000-000000000001";
    private static final String COMPANY =
            "30000000-0000-0000-0000-000000000001";
    private static final String PRINCIPAL =
            "40000000-0000-0000-0000-000000000001";
    private static final String ACCOUNT =
            "50000000-0000-0000-0000-000000000001";
    private static final Instant NOW = Instant.parse("2026-08-16T08:00:00Z");

    private final FakeRepository repository = new FakeRepository();
    private final AnnualLeaveManagementService service = service(repository);

    @Test
    void openingCorrectionsUseCanonicalTypesAndKeepLedgerReconciled() {
        service.setOpeningBalance(new OpeningBalanceCommand(
                EMPLOYEE,
                new BigDecimal("10.00"),
                2026,
                LocalDate.of(2026, 8, 1),
                "initial opening import",
                "idem-opening-00000001"));
        service.setOpeningBalance(new OpeningBalanceCommand(
                EMPLOYEE,
                new BigDecimal("8.00"),
                2026,
                LocalDate.of(2026, 8, 1),
                "correct opening import",
                "idem-opening-00000002"));

        assertThat(repository.account.balanceHours())
                .isEqualByComparingTo("8.00");
        assertThat(repository.ledger)
                .extracting(LedgerEntryRow::entryType)
                .containsExactly("OPENING", "ADJUSTMENT")
                .doesNotContain("MANUAL_INCREASE", "MANUAL_DEDUCTION");
        assertThat(repository.ledger)
                .extracting(LedgerEntryRow::amountHours)
                .containsExactly(
                        new BigDecimal("10.00"),
                        new BigDecimal("-2.00"));
        assertThat(repository.sumLedgerAmount(ACCOUNT))
                .isEqualByComparingTo(repository.account.balanceHours());
        assertThat(repository.events)
                .containsSubsequence("lock-account", "next-sequence");
    }

    @Test
    void exactAdjustmentReplayDoesNotAppendAndChangedPayloadConflicts() {
        AdjustBalanceCommand command = new AdjustBalanceCommand(
                EMPLOYEE,
                new BigDecimal("4.00"),
                2026,
                "manual entitlement correction",
                "idem-adjustment-00001");

        service.adjustBalance(command);
        service.adjustBalance(command);

        assertThat(repository.ledger).hasSize(1);
        assertThat(repository.account.balanceHours())
                .isEqualByComparingTo("4.00");
        assertThatThrownBy(() -> service.adjustBalance(
                new AdjustBalanceCommand(
                        EMPLOYEE,
                        new BigDecimal("4.50"),
                        2026,
                        "manual entitlement correction",
                        "idem-adjustment-00001")))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo(
                                "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"));
        assertThat(repository.ledger).hasSize(1);
    }

    @Test
    void samePrincipalKeyCannotBeReusedForAnotherAccount() {
        String idempotencyKey = "idem-cross-account-0001";
        repository.idempotency.put(
                repository.key(PRINCIPAL, idempotencyKey),
                new BalanceIdempotencyRow(
                        "70000000-0000-0000-0000-000000000001",
                        PRINCIPAL,
                        idempotencyKey,
                        "ADJUST",
                        "0".repeat(64),
                        "80000000-0000-0000-0000-000000000001",
                        "50000000-0000-0000-0000-000000000099",
                        "COMPLETED",
                        null,
                        BigDecimal.ZERO,
                        0L,
                        NOW,
                        NOW));

        assertThatThrownBy(() -> service.adjustBalance(
                new AdjustBalanceCommand(
                        EMPLOYEE,
                        new BigDecimal("1.00"),
                        2026,
                        "must reject cross-account key reuse",
                        idempotencyKey)))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo(
                                "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"));

        assertThat(repository.ledger).isEmpty();
        assertThat(repository.account.balanceHours()).isEqualByComparingTo("0.00");
    }

    @Test
    void openingMayBeNegativeWhenNothingIsReserved() {
        service.setOpeningBalance(new OpeningBalanceCommand(
                EMPLOYEE,
                new BigDecimal("-8.00"),
                2026,
                LocalDate.of(2026, 8, 1),
                "cutover overused annual leave",
                "idem-opening-negative-01"));

        assertThat(repository.account.balanceHours())
                .isEqualByComparingTo("-8.00");
        assertThat(repository.ledger)
                .extracting(LedgerEntryRow::entryType)
                .containsExactly("OPENING");
        assertThat(repository.ledger.getFirst().amountHours())
                .isEqualByComparingTo("-8.00");
        assertThat(repository.sumLedgerAmount(ACCOUNT))
                .isEqualByComparingTo(repository.account.balanceHours());
    }

    @Test
    void downwardAdjustmentCannotConsumeOaReservedHours() {
        repository.seedAccount("10.00", "10.00");
        repository.reservedHours = new BigDecimal("4.00");

        assertThatThrownBy(() -> service.adjustBalance(
                new AdjustBalanceCommand(
                        EMPLOYEE,
                        new BigDecimal("-6.50"),
                        2026,
                        "manual downward correction",
                        "idem-adjustment-00002")))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo(
                                "ANNUAL_LEAVE_AVAILABLE_BALANCE_INSUFFICIENT"));

        assertThat(repository.account.balanceHours())
                .isEqualByComparingTo("10.00");
        assertThat(repository.ledger).hasSize(1);
    }

    @Test
    void balanceMayLandExactlyOnReservationButNotHalfHourBelowIt() {
        repository.seedAccount("10.00", "10.00");
        repository.reservedHours = new BigDecimal("4.00");

        service.adjustBalance(new AdjustBalanceCommand(
                EMPLOYEE,
                new BigDecimal("-6.00"),
                2026,
                "reduce to available boundary",
                "idem-adjustment-00004"));

        assertThat(repository.account.balanceHours())
                .isEqualByComparingTo("4.00");
        assertThatThrownBy(() -> service.adjustBalance(
                new AdjustBalanceCommand(
                        EMPLOYEE,
                        new BigDecimal("-0.50"),
                        2026,
                        "half hour below boundary",
                        "idem-adjustment-00005")))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo(
                                "ANNUAL_LEAVE_AVAILABLE_BALANCE_INSUFFICIENT"));
        assertThat(repository.account.balanceHours())
                .isEqualByComparingTo("4.00");
        assertThat(repository.ledger).hasSize(2);
    }

    @Test
    void ledgerMismatchFailsClosedBeforeIdempotencyOrBalanceWrites() {
        repository.seedAccount("10.00", "9.00");

        assertThatThrownBy(() -> service.adjustBalance(
                new AdjustBalanceCommand(
                        EMPLOYEE,
                        new BigDecimal("1.00"),
                        2026,
                        "must fail closed",
                        "idem-adjustment-00003")))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo(
                                "ANNUAL_LEAVE_LEDGER_MISMATCH"));

        assertThat(repository.idempotency).isEmpty();
        assertThat(repository.account.balanceHours())
                .isEqualByComparingTo("10.00");
    }

    @Test
    void readAndWriteBindTheAccountToTheOnlyCurrentEmploymentPeriod() {
        repository.seedAccount("8.00", "8.00");

        service.getAccount(EMPLOYEE, 2026, 0, 20);

        assertThat(repository.lastLookupEmploymentPeriodId).isEqualTo(PERIOD);
        assertThat(AnnualLeaveManagementModels.accountId(
                EMPLOYEE, PERIOD, 2026))
                .isNotEqualTo(AnnualLeaveManagementModels.accountId(
                        EMPLOYEE,
                        "20000000-0000-0000-0000-000000000002",
                        2026));
        assertThat(AnnualLeaveManagementModels.accountId(
                "TIME_OFF", EMPLOYEE, PERIOD, 2026))
                .isNotEqualTo(AnnualLeaveManagementModels.accountId(
                        EMPLOYEE, PERIOD, 2026));
    }

    @Test
    void timeOffOpeningCreatesASeparateAccount() {
        OpeningBalanceCommand command = new OpeningBalanceCommand(
                EMPLOYEE,
                new BigDecimal("7.00"),
                2026,
                LocalDate.of(2026, 8, 1),
                "期初调休额度录入",
                "idem-timeoff-opening-0001");

        LeaveAccountView view = service.setOpeningBalance(command, "TIME_OFF");

        assertThat(view.balanceHours()).isEqualByComparingTo("7.00");
        assertThat(view.equivalentDays()).isEqualByComparingTo("0.88");
        assertThat(view.accountId()).isEqualTo(
                AnnualLeaveManagementModels.accountId(
                        "TIME_OFF", EMPLOYEE, PERIOD, 2026));
    }

    @Test
    void missingOrAmbiguousCurrentEmploymentFailsClosed() {
        repository.currentEmployments = List.of();
        assertThatThrownBy(() -> service.getAccount(EMPLOYEE, 2026, 0, 20))
                .isInstanceOf(ResourceNotAvailableAccessDeniedException.class);

        repository.currentEmployments = List.of(
                repository.employment,
                new CurrentEmploymentRow(
                        EMPLOYEE,
                        "20000000-0000-0000-0000-000000000002",
                        COMPANY));
        assertThatThrownBy(() -> service.getAccount(EMPLOYEE, 2026, 0, 20))
                .isInstanceOf(ResourceNotAvailableAccessDeniedException.class);
    }

    @Test
    void zeroOpeningIsStillDurablyIdempotentWithoutIllegalZeroLedger() {
        OpeningBalanceCommand command = new OpeningBalanceCommand(
                EMPLOYEE,
                BigDecimal.ZERO,
                2026,
                LocalDate.of(2026, 8, 1),
                "confirmed zero opening",
                "idem-opening-00000003");

        service.setOpeningBalance(command);
        service.setOpeningBalance(command);

        assertThat(repository.ledger).isEmpty();
        assertThat(repository.idempotency.values())
                .singleElement()
                .extracting(BalanceIdempotencyRow::recordStatus)
                .isEqualTo("COMPLETED");
    }

    private static AnnualLeaveManagementService service(
            AnnualLeaveManagementRepository repository) {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        CurrentPrincipalProvider principalProvider =
                mock(CurrentPrincipalProvider.class);
        when(principalProvider.currentPrincipalId()).thenReturn(PRINCIPAL);
        return new AnnualLeaveManagementService(
                capabilities,
                principalProvider,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class FakeRepository
            implements AnnualLeaveManagementRepository {

        private final CurrentEmploymentRow employment =
                new CurrentEmploymentRow(EMPLOYEE, PERIOD, COMPANY);
        private List<CurrentEmploymentRow> currentEmployments =
                List.of(employment);
        private TimeAccountRow account;
        private final List<LedgerEntryRow> ledger = new ArrayList<>();
        private final Map<String, BalanceIdempotencyRow> idempotency =
                new HashMap<>();
        private final List<String> events = new ArrayList<>();
        private BigDecimal reservedHours = new BigDecimal("0.00");
        private String lastLookupEmploymentPeriodId;
        private boolean accountLocked;

        @Override
        public List<CurrentEmploymentRow> findCurrentEmployments(
                String employeeId,
                Instant at) {
            return currentEmployments;
        }

        @Override
        public Optional<String> findPublishedPolicyVersionId(String companyId) {
            return Optional.of("ANNUAL_LEAVE_DEFAULT_V1");
        }

        @Override
        public Optional<TimeAccountRow> findTimeAccount(
                String employeeId,
                String employmentPeriodId,
                int year,
                String accountType) {
            accountLocked = false;
            lastLookupEmploymentPeriodId = employmentPeriodId;
            if (account == null
                    || !account.employeeId().equals(employeeId)
                    || !account.employmentPeriodId().equals(employmentPeriodId)
                    || account.year() != year) {
                return Optional.empty();
            }
            return Optional.of(account);
        }

        @Override
        public Optional<TimeAccountRow> lockTimeAccount(
                String employeeId,
                String employmentPeriodId,
                int year,
                String accountType) {
            events.add("lock-account");
            accountLocked = true;
            return account != null
                    && account.employeeId().equals(employeeId)
                    && account.employmentPeriodId().equals(employmentPeriodId)
                    && account.year() == year
                    ? Optional.of(account)
                    : Optional.empty();
        }

        @Override
        public void createTimeAccountIfAbsent(
                String accountId,
                String employeeId,
                String employmentPeriodId,
                String companyId,
                int year,
                String accountType,
                String policyVersionId,
                Instant now) {
            if (account == null) {
                account = new TimeAccountRow(
                        accountId,
                        employeeId,
                        employmentPeriodId,
                        companyId,
                        year,
                        new BigDecimal("0.00"),
                        policyVersionId,
                        0);
            }
        }

        @Override
        public boolean updateBalance(
                String accountId,
                BigDecimal newBalance,
                long expectedRowVersion) {
            if (!accountLocked || account.rowVersion() != expectedRowVersion) {
                return false;
            }
            account = new TimeAccountRow(
                    account.accountId(),
                    account.employeeId(),
                    account.employmentPeriodId(),
                    account.companyId(),
                    account.year(),
                    newBalance,
                    account.policyVersionId(),
                    account.rowVersion() + 1);
            return true;
        }

        @Override
        public int nextSequenceNo(String accountId) {
            events.add("next-sequence");
            if (!accountLocked) {
                throw new AssertionError("sequence allocated without account lock");
            }
            return ledger.size() + 1;
        }

        @Override
        public BigDecimal sumLedgerAmount(String accountId) {
            return ledger.stream()
                    .map(LedgerEntryRow::amountHours)
                    .reduce(new BigDecimal("0.00"), BigDecimal::add);
        }

        @Override
        public BigDecimal sumActiveReservations(String accountId) {
            return reservedHours;
        }

        @Override
        public OpeningImportSummary summarizeOpeningImport(String accountId) {
            List<LedgerEntryRow> entries = ledger.stream()
                    .filter(entry -> "HR_OPENING_IMPORT".equals(
                            entry.sourceType()))
                    .toList();
            return new OpeningImportSummary(
                    entries.size(),
                    entries.stream()
                            .map(LedgerEntryRow::amountHours)
                            .reduce(new BigDecimal("0.00"), BigDecimal::add));
        }

        @Override
        public void insertLedgerEntry(LedgerEntryRow entry) {
            if (!accountLocked) {
                throw new AssertionError("ledger appended without account lock");
            }
            ledger.add(entry);
        }

        @Override
        public List<LedgerEntryRow> listLedgerEntries(
                String accountId,
                int limit,
                int offset) {
            List<LedgerEntryRow> sorted = ledger.stream()
                    .sorted(Comparator.comparingInt(
                            LedgerEntryRow::sequenceNo).reversed())
                    .toList();
            if (offset >= sorted.size()) {
                return List.of();
            }
            return sorted.subList(
                    offset, Math.min(sorted.size(), offset + limit));
        }

        @Override
        public long countLedgerEntries(String accountId) {
            return ledger.size();
        }

        @Override
        public void claimBalanceIdempotency(BalanceIdempotencyRow row) {
            idempotency.putIfAbsent(
                    key(row.principalId(), row.idempotencyKey()), row);
        }

        @Override
        public Optional<BalanceIdempotencyRow> lockBalanceIdempotency(
                String principalId,
                String idempotencyKey) {
            return Optional.ofNullable(idempotency.get(
                    key(principalId, idempotencyKey)));
        }

        @Override
        public boolean completeBalanceIdempotency(
                String principalId,
                String idempotencyKey,
                String claimToken,
                String resultingLedgerEntryId,
                BigDecimal resultingBalanceHours,
                long resultingRowVersion,
                Instant completedAt) {
            String key = key(principalId, idempotencyKey);
            BalanceIdempotencyRow current = idempotency.get(key);
            if (current == null
                    || !current.claimToken().equals(claimToken)
                    || !"PROCESSING".equals(current.recordStatus())) {
                return false;
            }
            idempotency.put(key, new BalanceIdempotencyRow(
                    current.recordId(),
                    current.principalId(),
                    current.idempotencyKey(),
                    current.operation(),
                    current.requestDigest(),
                    current.claimToken(),
                    current.accountId(),
                    "COMPLETED",
                    resultingLedgerEntryId,
                    resultingBalanceHours,
                    resultingRowVersion,
                    current.createdAt(),
                    completedAt));
            return true;
        }

        @Override
        public boolean canAccessEmployee(
                String principalId,
                String capability,
                String employeeId,
                Instant at) {
            return true;
        }

        @Override
        public Optional<String> findPrincipalEmployeeId(String principalId) {
            return Optional.of(EMPLOYEE);
        }

        private void seedAccount(String balance, String ledgerAmount) {
            account = new TimeAccountRow(
                    ACCOUNT,
                    EMPLOYEE,
                    PERIOD,
                    COMPANY,
                    2026,
                    new BigDecimal(balance),
                    "ANNUAL_LEAVE_DEFAULT_V1",
                    0);
            ledger.clear();
            BigDecimal amount = new BigDecimal(ledgerAmount);
            if (amount.signum() != 0) {
                ledger.add(new LedgerEntryRow(
                        "60000000-0000-0000-0000-000000000001",
                        ACCOUNT,
                        1,
                        "OPENING",
                        amount,
                        "HR_OPENING_IMPORT",
                        "seed-opening-000001",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 12, 31),
                        "ANNUAL_LEAVE_DEFAULT_V1",
                        "seed-opening-000001",
                        PRINCIPAL,
                        NOW));
            }
        }

        private static String key(String accountId, String idempotencyKey) {
            return accountId + ':' + idempotencyKey;
        }
    }
}
