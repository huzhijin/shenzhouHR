package com.szsemicon.hr.leavetimeaccount.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.AccountCandidate;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.ExpiryResult;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.ItemStatus;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunItemRecord;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunRecord;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunStatus;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.TriggerType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TimeOffYearEndServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2027-01-02T01:00:00Z"), ZoneOffset.UTC);
    private static final TimeOffYearEndSettings SETTINGS =
            new TimeOffYearEndSettings(
                    Duration.ofMinutes(5),
                    ZoneId.of("Asia/Shanghai"),
                    "test-node");

    @Test
    void repeatedRunUsesStableEventAndRecordsIdempotentSkip() {
        FakeRepository repository = new FakeRepository(List.of(
                new AccountCandidate("account-1", "SZ0001")));
        RecordingGateway gateway = new RecordingGateway(repository);
        TimeOffYearEndService service = service(repository, gateway);

        var first = service.runYear(2026, TriggerType.MANUAL);
        var second = service.runYear(2026, TriggerType.MANUAL);

        assertThat(first.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(first.successCount()).isEqualTo(1);
        assertThat(second.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(second.skippedCount()).isEqualTo(1);
        assertThat(gateway.calls).hasSize(2);
        assertThat(gateway.calls.get(1).eventId())
                .isEqualTo(gateway.calls.getFirst().eventId());
        assertThat(gateway.calls.get(1).digest())
                .isEqualTo(gateway.calls.getFirst().digest());
        assertThat(repository.items)
                .extracting(RunItemRecord::status)
                .containsExactly(ItemStatus.SUCCESS, ItemStatus.SKIPPED);
    }

    @Test
    void employeeFailureIsRecordedAndDoesNotStopFollowingEmployee() {
        FakeRepository repository = new FakeRepository(List.of(
                new AccountCandidate("account-1", "SZ0001"),
                new AccountCandidate("account-2", "SZ0002")));
        RecordingGateway gateway = new RecordingGateway(repository);
        gateway.failureEmployee = "SZ0001";
        TimeOffYearEndService service = service(repository, gateway);

        assertThatThrownBy(() -> service.runYear(2026, TriggerType.SCHEDULED))
                .isInstanceOf(TimeOffYearEndBatchException.class)
                .satisfies(exception -> {
                    TimeOffYearEndBatchException failure =
                            (TimeOffYearEndBatchException) exception;
                    assertThat(failure.summary().failureCount()).isEqualTo(1);
                    assertThat(failure.summary().successCount()).isEqualTo(1);
                });

        assertThat(gateway.calls)
                .extracting(ProcedureCall::employeeNumber)
                .containsExactly("SZ0001", "SZ0002");
        assertThat(repository.items)
                .extracting(RunItemRecord::status)
                .containsExactly(ItemStatus.FAILED, ItemStatus.SUCCESS);
        assertThat(repository.completedRuns.getFirst().status())
                .isEqualTo(RunStatus.COMPLETED_WITH_FAILURES);
    }

    @Test
    void clusterLeaseAllowsOnlyOneNodeToProcessTheYear() throws Exception {
        FakeRepository repository = new FakeRepository(List.of(
                new AccountCandidate("account-1", "SZ0001")));
        CountDownLatch enteredProcedure = new CountDownLatch(1);
        CountDownLatch releaseProcedure = new CountDownLatch(1);
        RecordingGateway firstGateway = new RecordingGateway(repository);
        firstGateway.entered = enteredProcedure;
        firstGateway.release = releaseProcedure;
        RecordingGateway secondGateway = new RecordingGateway(repository);
        TimeOffYearEndService first = service(repository, firstGateway);
        TimeOffYearEndService second = service(repository, secondGateway);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            var firstFuture = executor.submit(
                    () -> first.runYear(2026, TriggerType.SCHEDULED));
            assertThat(enteredProcedure.await(5, TimeUnit.SECONDS)).isTrue();

            var skipped = second.runYear(2026, TriggerType.SCHEDULED);
            assertThat(skipped.status()).isEqualTo(RunStatus.SKIPPED_LOCKED);
            assertThat(secondGateway.calls).isEmpty();

            releaseProcedure.countDown();
            assertThat(firstFuture.get(5, TimeUnit.SECONDS).successCount())
                    .isEqualTo(1);
        } finally {
            releaseProcedure.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void currentOrFutureYearFailsBeforeTakingClusterLock() {
        FakeRepository repository = new FakeRepository(List.of());
        TimeOffYearEndService service = service(
                repository, new RecordingGateway(repository));

        assertThatThrownBy(
                () -> service.runYear(2027, TriggerType.MANUAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precede the current year");
        assertThat(repository.activeLock.get()).isNull();
    }

    @Test
    void leaseLostAfterProcedureIsRecordedAndStopsRemainingEmployees() {
        FakeRepository repository = new FakeRepository(List.of(
                new AccountCandidate("account-1", "SZ0001"),
                new AccountCandidate("account-2", "SZ0002")));
        repository.loseLockOnRenewCall = 2;
        RecordingGateway gateway = new RecordingGateway(repository);
        TimeOffYearEndService service = service(repository, gateway);

        assertThatThrownBy(() -> service.runYear(2026, TriggerType.SCHEDULED))
                .isInstanceOf(TimeOffYearEndBatchException.class)
                .satisfies(exception -> {
                    TimeOffYearEndBatchException failure =
                            (TimeOffYearEndBatchException) exception;
                    assertThat(failure.summary().failureCount()).isEqualTo(1);
                    assertThat(failure.summary().skippedCount()).isEqualTo(1);
                });

        assertThat(gateway.calls)
                .extracting(ProcedureCall::employeeNumber)
                .containsExactly("SZ0001");
        assertThat(repository.items)
                .extracting(RunItemRecord::status)
                .containsExactly(ItemStatus.FAILED, ItemStatus.SKIPPED);
        assertThat(repository.items.getFirst().resultCode())
                .isEqualTo("SZSC_YEAR_END_LOCK_LOST");
    }

    private static TimeOffYearEndService service(
            FakeRepository repository,
            TimeOffYearEndProcedureGateway gateway) {
        return new TimeOffYearEndService(repository, gateway, CLOCK, SETTINGS);
    }

    private static final class FakeRepository
            implements TimeOffYearEndRepository {

        private final List<AccountCandidate> candidates;
        private final AtomicReference<String> activeLock = new AtomicReference<>();
        private final Map<String, String> eventDigests = new ConcurrentHashMap<>();
        private final List<RunRecord> startedRuns = new CopyOnWriteArrayList<>();
        private final List<RunRecord> completedRuns = new CopyOnWriteArrayList<>();
        private final List<RunItemRecord> items = new CopyOnWriteArrayList<>();
        private final AtomicInteger renewalCalls = new AtomicInteger();
        private int loseLockOnRenewCall = -1;

        private FakeRepository(List<AccountCandidate> candidates) {
            this.candidates = List.copyOf(candidates);
        }

        @Override
        public boolean tryAcquireLock(
                int accountYear,
                String lockToken,
                String lockOwner,
                Duration lease) {
            return activeLock.compareAndSet(null, lockToken);
        }

        @Override
        public boolean renewLock(
                int accountYear,
                String lockToken,
                Duration lease) {
            if (renewalCalls.incrementAndGet() == loseLockOnRenewCall) {
                activeLock.compareAndSet(lockToken, null);
                return false;
            }
            return lockToken.equals(activeLock.get());
        }

        @Override
        public void releaseLock(int accountYear, String lockToken) {
            activeLock.compareAndSet(lockToken, null);
        }

        @Override
        public List<AccountCandidate> findCandidates(int accountYear) {
            return candidates;
        }

        @Override
        public Optional<String> findExpiryEventDigest(
                String sourceRequestId, String sourceEventId) {
            return Optional.ofNullable(
                    eventDigests.get(sourceRequestId + "|" + sourceEventId));
        }

        @Override
        public void insertRun(RunRecord run) {
            startedRuns.add(run);
        }

        @Override
        public void insertRunItem(RunItemRecord item) {
            items.add(item);
        }

        @Override
        public void completeRun(RunRecord run) {
            completedRuns.add(run);
        }

        private void rememberEvent(
                String sourceRequestId, String sourceEventId, String digest) {
            eventDigests.put(sourceRequestId + "|" + sourceEventId, digest);
        }
    }

    private static final class RecordingGateway
            implements TimeOffYearEndProcedureGateway {

        private final FakeRepository repository;
        private final List<ProcedureCall> calls = new ArrayList<>();
        private String failureEmployee;
        private CountDownLatch entered;
        private CountDownLatch release;

        private RecordingGateway(FakeRepository repository) {
            this.repository = repository;
        }

        @Override
        public synchronized ExpiryResult expire(
                String employeeNumber,
                int accountYear,
                String eventId,
                String payloadDigest) {
            calls.add(new ProcedureCall(
                    employeeNumber, accountYear, eventId, payloadDigest));
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrupted", exception);
                }
            }
            if (employeeNumber.equals(failureEmployee)) {
                throw new IllegalStateException("SZSC_BALANCE_LEDGER_MISMATCH");
            }
            String sourceRequestId = "TIME_OFF_ACCOUNT:"
                    + sha256(employeeNumber + "|" + accountYear);
            repository.rememberEvent(sourceRequestId, eventId, payloadDigest);
            return new ExpiryResult(
                    sourceRequestId,
                    "EXPIRED",
                    "TIME_OFF",
                    accountYear,
                    new BigDecimal("8.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO);
        }
    }

    private record ProcedureCall(
            String employeeNumber,
            int accountYear,
            String eventId,
            String digest) {
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
