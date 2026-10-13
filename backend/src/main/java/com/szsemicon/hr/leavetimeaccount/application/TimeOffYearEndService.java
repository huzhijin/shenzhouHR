package com.szsemicon.hr.leavetimeaccount.application;

import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.AccountCandidate;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.ExpiryResult;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.ItemStatus;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunItemRecord;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunRecord;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunStatus;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunSummary;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.TriggerType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class TimeOffYearEndService {

    private static final Logger log =
            LoggerFactory.getLogger(TimeOffYearEndService.class);
    private static final Pattern SZSC_ERROR =
            Pattern.compile("SZSC_[A-Z0-9_]+");
    private static final int MIN_ACCOUNT_YEAR = 2000;

    private final TimeOffYearEndRepository repository;
    private final TimeOffYearEndProcedureGateway procedureGateway;
    private final Clock clock;
    private final TimeOffYearEndSettings settings;

    public TimeOffYearEndService(
            TimeOffYearEndRepository repository,
            TimeOffYearEndProcedureGateway procedureGateway,
            Clock clock,
            TimeOffYearEndSettings settings) {
        this.repository = repository;
        this.procedureGateway = procedureGateway;
        this.clock = clock;
        this.settings = settings;
    }

    public RunSummary runPreviousYear(TriggerType triggerType) {
        int previousYear = LocalDate.now(clock.withZone(settings.businessZone()))
                .getYear() - 1;
        return runYear(previousYear, triggerType);
    }

    public RunSummary runYear(int accountYear, TriggerType triggerType) {
        validateYear(accountYear);
        if (triggerType == null) {
            throw new IllegalArgumentException("year-end trigger type is required");
        }

        String runId = UUID.randomUUID().toString();
        String lockToken = UUID.randomUUID().toString();
        Instant startedAt = clock.instant();
        boolean acquired = repository.tryAcquireLock(
                accountYear,
                lockToken,
                settings.lockOwner(),
                settings.lockLease());

        if (!acquired) {
            Instant completedAt = clock.instant();
            RunRecord skippedRun = runRecord(
                    runId, accountYear, triggerType, RunStatus.SKIPPED_LOCKED,
                    0, 0, 0, 0, "LOCK_HELD_BY_ANOTHER_NODE",
                    startedAt, completedAt);
            repository.insertRun(skippedRun);
            return summary(skippedRun);
        }

        RuntimeException primaryFailure = null;
        try {
            return executeAcquiredRun(
                    runId, accountYear, triggerType, lockToken, startedAt);
        } catch (RuntimeException exception) {
            primaryFailure = exception;
            throw exception;
        } finally {
            try {
                repository.releaseLock(accountYear, lockToken);
            } catch (RuntimeException releaseFailure) {
                if (primaryFailure == null) {
                    throw releaseFailure;
                }
                primaryFailure.addSuppressed(releaseFailure);
                log.error(
                        "TIME_OFF year-end lock release failed after batch failure "
                                + "for year {}",
                        accountYear,
                        releaseFailure);
            }
        }
    }

    private RunSummary executeAcquiredRun(
            String runId,
            int accountYear,
            TriggerType triggerType,
            String lockToken,
            Instant startedAt) {
        RunProgress progress = new RunProgress();
        repository.insertRun(runRecord(
                runId, accountYear, triggerType, RunStatus.RUNNING,
                0, 0, 0, 0, null, startedAt, null));

        try {
            List<AccountCandidate> candidates =
                    repository.findCandidates(accountYear);
            progress.totalCount = candidates.size();

            for (int index = 0; index < candidates.size(); index++) {
                AccountCandidate candidate = candidates.get(index);
                progress.currentEmployeeNumber = candidate.employeeNumber();
                Instant attemptAt = clock.instant();
                if (!repository.renewLock(
                        accountYear, lockToken, settings.lockLease())) {
                    String lockError = "SZSC_YEAR_END_LOCK_LOST";
                    progress.failureCount += recordFailure(
                            runId, accountYear, candidate, lockError,
                            "cluster lease was lost before expiry", attemptAt);
                    progress.failures.add(
                            candidate.employeeNumber() + ":" + lockError);
                    recordRemainingAsSkipped(
                            runId,
                            accountYear,
                            candidates,
                            index + 1,
                            lockError,
                            progress);
                    progress.lockLost = true;
                    break;
                }

                ItemOutcome outcome = processCandidate(
                        runId,
                        accountYear,
                        candidate,
                        attemptAt,
                        lockToken);
                switch (outcome.status()) {
                    case SUCCESS -> progress.successCount++;
                    case SKIPPED -> progress.skippedCount++;
                    case FAILED -> {
                        progress.failureCount++;
                        progress.failures.add(candidate.employeeNumber()
                                + ":" + outcome.resultCode());
                    }
                }
                if (outcome.lockLost()) {
                    recordRemainingAsSkipped(
                            runId,
                            accountYear,
                            candidates,
                            index + 1,
                            outcome.resultCode(),
                            progress);
                    progress.lockLost = true;
                    break;
                }
                progress.currentEmployeeNumber = null;
            }

            if (!progress.lockLost
                    && !repository.renewLock(
                            accountYear, lockToken, settings.lockLease())) {
                String lockError = "SZSC_YEAR_END_LOCK_LOST";
                progress.failures.add("BATCH:" + lockError);
                RunRecord failedRun = completedRun(
                        runId,
                        accountYear,
                        triggerType,
                        RunStatus.FAILED,
                        progress,
                        startedAt);
                repository.completeRun(failedRun);
                throw new TimeOffYearEndBatchException(summary(failedRun));
            }

            RunStatus finalStatus = progress.failureCount == 0
                    ? RunStatus.SUCCEEDED
                    : RunStatus.COMPLETED_WITH_FAILURES;
            RunRecord completedRun = completedRun(
                    runId,
                    accountYear,
                    triggerType,
                    finalStatus,
                    progress,
                    startedAt);
            repository.completeRun(completedRun);
            RunSummary completedSummary = summary(completedRun);
            if (progress.failureCount > 0) {
                throw new TimeOffYearEndBatchException(completedSummary);
            }
            return completedSummary;
        } catch (TimeOffYearEndBatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            completeUnexpectedFailure(
                    runId,
                    accountYear,
                    triggerType,
                    startedAt,
                    progress,
                    exception);
            throw exception;
        }
    }

    private ItemOutcome processCandidate(
            String runId,
            int accountYear,
            AccountCandidate candidate,
            Instant attemptedAt,
            String lockToken) {
        String sourceRequestId = sourceRequestId(
                candidate.employeeNumber(), accountYear);
        String eventId = eventId(candidate.employeeNumber(), accountYear);
        String payloadDigest = payloadDigest(
                candidate.employeeNumber(), accountYear, eventId);

        ExpiryResult result;
        boolean replay;
        try {
            Optional<String> previousDigest = repository.findExpiryEventDigest(
                    sourceRequestId, eventId);
            if (previousDigest.isPresent()
                    && !previousDigest.orElseThrow().equals(payloadDigest)) {
                throw new IllegalStateException("SZSC_IDEMPOTENCY_CONFLICT");
            }

            result = procedureGateway.expire(
                    candidate.employeeNumber(),
                    accountYear,
                    eventId,
                    payloadDigest);
            requireExpectedResult(result, sourceRequestId, accountYear);
            replay = previousDigest.isPresent();
        } catch (RuntimeException exception) {
            String resultCode = stableErrorCode(exception);
            String resultDetail = boundedDetail(exception, resultCode);
            log.error(
                    "TIME_OFF year-end expiry failed for employee {} and year {} ({})",
                    candidate.employeeNumber(), accountYear, resultCode, exception);
            repository.insertRunItem(new RunItemRecord(
                    UUID.randomUUID().toString(),
                    runId,
                    candidate.timeAccountId(),
                    candidate.employeeNumber(),
                    eventId,
                    payloadDigest,
                    ItemStatus.FAILED,
                    null,
                    resultCode,
                    resultDetail,
                    attemptedAt,
                    clock.instant()));
            return new ItemOutcome(ItemStatus.FAILED, resultCode, false);
        }

        if (!repository.renewLock(
                accountYear, lockToken, settings.lockLease())) {
            String resultCode = "SZSC_YEAR_END_LOCK_LOST";
            log.error(
                    "TIME_OFF year-end lease was lost after expiry for employee {} "
                            + "and year {}",
                    candidate.employeeNumber(),
                    accountYear);
            repository.insertRunItem(new RunItemRecord(
                    UUID.randomUUID().toString(),
                    runId,
                    candidate.timeAccountId(),
                    candidate.employeeNumber(),
                    eventId,
                    payloadDigest,
                    ItemStatus.FAILED,
                    result.affectedHours(),
                    resultCode,
                    "expiry committed but lease ownership was lost before audit",
                    attemptedAt,
                    clock.instant()));
            return new ItemOutcome(ItemStatus.FAILED, resultCode, true);
        }

        ItemStatus status = replay ? ItemStatus.SKIPPED : ItemStatus.SUCCESS;
        String resultCode = replay ? "IDEMPOTENT_REPLAY" : "EXPIRED";
        repository.insertRunItem(new RunItemRecord(
                UUID.randomUUID().toString(),
                runId,
                candidate.timeAccountId(),
                candidate.employeeNumber(),
                eventId,
                payloadDigest,
                status,
                result.affectedHours(),
                resultCode,
                null,
                attemptedAt,
                clock.instant()));
        return new ItemOutcome(status, resultCode, false);
    }

    private void recordRemainingAsSkipped(
            String runId,
            int accountYear,
            List<AccountCandidate> candidates,
            int fromIndex,
            String resultCode,
            RunProgress progress) {
        for (int skippedIndex = fromIndex;
                skippedIndex < candidates.size(); skippedIndex++) {
            recordSkippedForLostLock(
                    runId,
                    accountYear,
                    candidates.get(skippedIndex),
                    resultCode);
            progress.skippedCount++;
        }
    }

    private int recordFailure(
            String runId,
            int accountYear,
            AccountCandidate candidate,
            String resultCode,
            String resultDetail,
            Instant attemptedAt) {
        String eventId = eventId(candidate.employeeNumber(), accountYear);
        repository.insertRunItem(new RunItemRecord(
                UUID.randomUUID().toString(),
                runId,
                candidate.timeAccountId(),
                candidate.employeeNumber(),
                eventId,
                payloadDigest(candidate.employeeNumber(), accountYear, eventId),
                ItemStatus.FAILED,
                null,
                resultCode,
                resultDetail,
                attemptedAt,
                clock.instant()));
        return 1;
    }

    private void recordSkippedForLostLock(
            String runId,
            int accountYear,
            AccountCandidate candidate,
            String resultCode) {
        Instant at = clock.instant();
        String eventId = eventId(candidate.employeeNumber(), accountYear);
        repository.insertRunItem(new RunItemRecord(
                UUID.randomUUID().toString(),
                runId,
                candidate.timeAccountId(),
                candidate.employeeNumber(),
                eventId,
                payloadDigest(candidate.employeeNumber(), accountYear, eventId),
                ItemStatus.SKIPPED,
                BigDecimal.ZERO,
                resultCode,
                "not attempted after cluster lease loss",
                at,
                at));
    }

    private RunRecord completedRun(
            String runId,
            int accountYear,
            TriggerType triggerType,
            RunStatus status,
            RunProgress progress,
            Instant startedAt) {
        return runRecord(
                runId,
                accountYear,
                triggerType,
                status,
                progress.totalCount,
                progress.successCount,
                progress.failureCount,
                progress.skippedCount,
                failureSummary(progress.failures),
                startedAt,
                clock.instant());
    }

    private void completeUnexpectedFailure(
            String runId,
            int accountYear,
            TriggerType triggerType,
            Instant startedAt,
            RunProgress progress,
            RuntimeException failure) {
        String resultCode = stableErrorCode(failure);
        String subject = progress.currentEmployeeNumber == null
                ? "BATCH"
                : progress.currentEmployeeNumber;
        progress.failures.add(subject + ":" + resultCode);
        int accounted = progress.successCount
                + progress.failureCount
                + progress.skippedCount;
        if (progress.currentEmployeeNumber != null
                && accounted < progress.totalCount) {
            progress.failureCount++;
        }
        RunRecord failedRun = completedRun(
                runId,
                accountYear,
                triggerType,
                RunStatus.FAILED,
                progress,
                startedAt);
        try {
            repository.completeRun(failedRun);
        } catch (RuntimeException completionFailure) {
            failure.addSuppressed(completionFailure);
        }
    }

    private void requireExpectedResult(
            ExpiryResult result,
            String sourceRequestId,
            int accountYear) {
        if (!sourceRequestId.equals(result.sourceRequestId())
                || !"EXPIRED".equals(result.operationStatus())
                || !"TIME_OFF".equals(result.accountType())
                || accountYear != result.accountYear()) {
            throw new IllegalStateException("SZSC_EXPIRY_RESULT_CONTRACT_INVALID");
        }
    }

    private void validateYear(int accountYear) {
        int currentYear = LocalDate.now(clock.withZone(settings.businessZone()))
                .getYear();
        if (accountYear < MIN_ACCOUNT_YEAR || accountYear >= currentYear) {
            throw new IllegalArgumentException(
                    "TIME_OFF year-end account year must precede the current year");
        }
    }

    private RunRecord runRecord(
            String runId,
            int accountYear,
            TriggerType triggerType,
            RunStatus status,
            int totalCount,
            int successCount,
            int failureCount,
            int skippedCount,
            String failureSummary,
            Instant startedAt,
            Instant completedAt) {
        return new RunRecord(
                runId,
                accountYear,
                triggerType,
                status,
                settings.lockOwner(),
                totalCount,
                successCount,
                failureCount,
                skippedCount,
                failureSummary,
                startedAt,
                completedAt);
    }

    private static RunSummary summary(RunRecord run) {
        return new RunSummary(
                run.runId(),
                run.accountYear(),
                run.status(),
                run.totalCount(),
                run.successCount(),
                run.failureCount(),
                run.skippedCount());
    }

    private static String sourceRequestId(String employeeNumber, int year) {
        return "TIME_OFF_ACCOUNT:" + sha256(employeeNumber + "|" + year);
    }

    private static String eventId(String employeeNumber, int year) {
        return "HR_TIME_OFF_YEAR_END_V1:" + year + ":"
                + sha256(employeeNumber + "|" + year).substring(0, 32);
    }

    private static String payloadDigest(
            String employeeNumber, int year, String eventId) {
        return sha256("EXPIRY|" + employeeNumber + "|" + year + "|" + eventId);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String stableErrorCode(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            Matcher matcher = SZSC_ERROR.matcher(
                    current.getMessage() == null ? "" : current.getMessage());
            if (matcher.find()) {
                return matcher.group();
            }
            current = current.getCause();
        }
        return "SZSC_YEAR_END_UNEXPECTED_FAILURE";
    }

    private static String boundedDetail(
            RuntimeException exception, String resultCode) {
        String detail = exception.getClass().getSimpleName() + ":" + resultCode;
        return detail.length() <= 500 ? detail : detail.substring(0, 500);
    }

    private static String failureSummary(List<String> failures) {
        if (failures.isEmpty()) {
            return null;
        }
        String summary = String.join(",", failures);
        return summary.length() <= 500 ? summary : summary.substring(0, 500);
    }

    private record ItemOutcome(
            ItemStatus status,
            String resultCode,
            boolean lockLost) {
    }

    private static final class RunProgress {

        private int totalCount;
        private int successCount;
        private int failureCount;
        private int skippedCount;
        private boolean lockLost;
        private String currentEmployeeNumber;
        private final List<String> failures = new ArrayList<>();
    }
}
