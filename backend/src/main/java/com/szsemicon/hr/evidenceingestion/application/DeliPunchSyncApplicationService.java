package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.evidenceingestion.port.AttendanceConfigurationResolverPort;
import com.szsemicon.hr.evidenceingestion.port.AttendancePeriodProtectionPort;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.validation.IdempotencyKeyPolicy;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DeliPunchSyncApplicationService {

    private static final int MAX_PAGES_PER_RUN = 10_000;
    private static final int MAX_FETCH_ATTEMPTS = 3;

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principalProvider;
    private final AttendanceSourceSyncRepository repository;
    private final DeliPunchPageTransaction pageTransaction;
    private final AuditService auditService;
    private final List<DeliPunchSourcePort> deliSources;
    private final List<AttendanceConfigurationResolverPort> configurationResolvers;
    private final List<AttendancePeriodProtectionPort> periodProtections;
    private final Clock clock;

    public DeliPunchSyncApplicationService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceSourceSyncRepository repository,
            DeliPunchPageTransaction pageTransaction,
            AuditService auditService,
            List<DeliPunchSourcePort> deliSources,
            List<AttendanceConfigurationResolverPort> configurationResolvers,
            List<AttendancePeriodProtectionPort> periodProtections,
            Clock clock) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.pageTransaction = pageTransaction;
        this.auditService = auditService;
        this.deliSources = List.copyOf(deliSources);
        this.configurationResolvers = List.copyOf(configurationResolvers);
        this.periodProtections = List.copyOf(periodProtections);
        this.clock = clock;
    }

    public AttendanceSourceSyncModels.ScheduledSyncResult runScheduled() {
        List<String> sourceIds = repository.findAllActiveDeliSourceIds();
        if (sourceIds.isEmpty()) {
            return new AttendanceSourceSyncModels.ScheduledSyncResult(
                    0, false, "DELI_ACTIVE_SOURCE_NOT_FOUND");
        }
        long recordCount = 0;
        Set<String> failures = new LinkedHashSet<>();
        for (String sourceId : sourceIds) {
            String jobId = UUID.randomUUID().toString();
            String correlationId = "SCHEDULED:" + jobId;
            Instant at = clock.instant();
            AttendanceSourceSyncRepository.StartResult result;
            try {
                result = repository.createScheduledDeliJob(
                        sourceId, jobId, correlationId, at);
            } catch (RuntimeException exception) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .error("Scheduled sync: failed to create job for source {}",
                                sourceId, exception);
                failures.add("DELI_SYNC_JOB_CREATION_FAILED");
                continue;
            }
            if (result.state()
                    == AttendanceSourceSyncRepository.StartState.ALREADY_RUNNING) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .info("Scheduled sync: source {} already has a running job, skipping",
                                sourceId);
                failures.add("DELI_SYNC_ALREADY_RUNNING");
                continue;
            }
            if (result.state()
                    != AttendanceSourceSyncRepository.StartState.CREATED) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("Scheduled sync: source {} unavailable ({}), skipping",
                                sourceId, result.state());
                failures.add("DELI_SOURCE_RESOURCE_UNAVAILABLE");
                continue;
            }
            try {
                AttendanceSourceSyncModels.JobStatus status = execute(
                        Objects.requireNonNull(result.job()),
                        "SYSTEM",
                        correlationId,
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN);
                recordCount = Math.addExact(
                        recordCount,
                        Math.addExact(
                                status.acceptedCount(),
                                status.quarantinedCount()));
                if (!completedSuccessfully(status.state())) {
                    failures.add(status.safeErrorCode() == null
                            ? "DELI_SCHEDULED_SYNC_FAILED"
                            : status.safeErrorCode());
                }
            } catch (RuntimeException exception) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .error("Scheduled sync: execution failed for source {}",
                                sourceId, exception);
                failures.add("DELI_SCHEDULED_SYNC_FAILED");
            }
        }
        return new AttendanceSourceSyncModels.ScheduledSyncResult(
                recordCount,
                failures.isEmpty(),
                failures.isEmpty() ? null : String.join("; ", failures));
    }

    public AttendanceSourceSyncModels.JobStatus run(
            String sourceId, String correlationId) {
        requireReference(sourceId, 36);
        requireReference(correlationId, 64);
        capabilities.require(CapabilityCodes.ATTENDANCE_SOURCE_RUN);
        String principalId = principalProvider.currentPrincipalId();
        Instant requestedAt = clock.instant();
        String jobId = UUID.randomUUID().toString();
        AttendanceSourceSyncRepository.StartResult result;
        try {
            result = repository.createAuthorizedDeliJob(
                    sourceId,
                    principalId,
                    CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                    jobId,
                    correlationId,
                    requestedAt);
        } catch (RuntimeException exception) {
            auditService.recordFailure(
                    principalId,
                    "DELI_SOURCE_SYNC_REQUEST",
                    "ATTENDANCE_SOURCE",
                    sourceId,
                    "FAILURE",
                    "DELI_SYNC_JOB_CREATION_FAILED");
            throw exception;
        }
        if (result.state()
                == AttendanceSourceSyncRepository.StartState.RESOURCE_UNAVAILABLE) {
            auditService.recordFailure(
                    principalId,
                    "DELI_SOURCE_SYNC_REQUEST",
                    "ATTENDANCE_SOURCE",
                    sourceId,
                    "DENIED",
                    "RESOURCE_UNAVAILABLE");
            throw new ResourceNotAvailableAccessDeniedException();
        }
        if (result.state()
                == AttendanceSourceSyncRepository.StartState.ALREADY_RUNNING) {
            auditService.recordFailure(
                    principalId,
                    "DELI_SOURCE_SYNC_REQUEST",
                    "ATTENDANCE_SOURCE",
                    sourceId,
                    "FAILURE",
                    "ATTENDANCE_SOURCE_SYNC_ALREADY_RUNNING");
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ATTENDANCE_SOURCE_SYNC_ALREADY_RUNNING",
                    "该考勤数据源已有同步任务运行中",
                    true);
        }
        var job = Objects.requireNonNull(result.job(), "created sync job");
        jobId = job.jobId();
        if (result.recoveredStaleJobs() > 0) {
            auditService.record(
                    principalId,
                    "DELI_SOURCE_SYNC_RECOVERY",
                    "ATTENDANCE_SOURCE",
                    sourceId,
                    "SUCCESS",
                    "DELI_SYNC_STALE_JOB_RECOVERED");
        }
        auditService.record(
                principalId,
                "DELI_SOURCE_SYNC_REQUEST",
                "ATTENDANCE_SYNC_JOB",
                jobId,
                "SUCCESS",
                "SYNC_JOB_CREATED");
        return execute(
                job,
                principalId,
                correlationId,
                CapabilityCodes.ATTENDANCE_SOURCE_RUN);
    }

    public AttendanceSourceSyncModels.JobStatus retry(
            String originalJobId,
            long expectedRowVersion,
            String idempotencyKey,
            String reason,
            String correlationId) {
        requireReference(originalJobId, 36);
        requireReference(correlationId, 64);
        requireReference(reason, 500);
        if (reason.length() < 2
                || expectedRowVersion < 0
                || !IdempotencyKeyPolicy.isValid(idempotencyKey)) {
            throw new IllegalArgumentException(
                    "invalid source sync retry request");
        }
        capabilities.require(CapabilityCodes.ATTENDANCE_SOURCE_RETRY);
        String principalId = principalProvider.currentPrincipalId();
        Instant at = clock.instant();
        String requestDigest = AttendanceEvidenceDigests.sha256(
                "DELI_SOURCE_RETRY_V1",
                originalJobId,
                Long.toString(expectedRowVersion),
                reason);
        AttendanceSourceSyncRepository.RetryResult result;
        try {
            result = repository.createAuthorizedDeliRetryJob(
                    originalJobId,
                    principalId,
                    CapabilityCodes.ATTENDANCE_SOURCE_RETRY,
                    expectedRowVersion,
                    idempotencyKey,
                    requestDigest,
                    UUID.randomUUID().toString(),
                    correlationId,
                    at);
        } catch (RuntimeException exception) {
            auditService.recordFailure(
                    principalId,
                    "DELI_SOURCE_SYNC_RETRY_REQUEST",
                    "ATTENDANCE_SYNC_JOB",
                    originalJobId,
                    "FAILURE",
                    "DELI_SYNC_RETRY_CREATION_FAILED");
            throw exception;
        }
        if (result.state()
                == AttendanceSourceSyncRepository.RetryState.RESOURCE_UNAVAILABLE) {
            auditService.recordFailure(
                    principalId,
                    "DELI_SOURCE_SYNC_RETRY_REQUEST",
                    "ATTENDANCE_SYNC_JOB",
                    originalJobId,
                    "DENIED",
                    "RESOURCE_UNAVAILABLE");
            throw new ResourceNotAvailableAccessDeniedException();
        }
        if (result.state()
                == AttendanceSourceSyncRepository.RetryState.REPLAYED) {
            auditService.record(
                    principalId,
                    "DELI_SOURCE_SYNC_RETRY_REQUEST",
                    "ATTENDANCE_SYNC_JOB",
                    result.replayJobId(),
                    "SUCCESS",
                    "IDEMPOTENCY_REPLAY");
            return readCreated(result.replayJobId(), principalId);
        }
        if (result.state()
                != AttendanceSourceSyncRepository.RetryState.CREATED) {
            String code = retryConflictCode(result.state());
            auditService.recordFailure(
                    principalId,
                    "DELI_SOURCE_SYNC_RETRY_REQUEST",
                    "ATTENDANCE_SYNC_JOB",
                    originalJobId,
                    "FAILURE",
                    code);
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    code,
                    "同步任务当前状态不允许重试",
                    true);
        }
        var job = Objects.requireNonNull(
                result.job(), "created retry sync job");
        if (result.recoveredStaleJobs() > 0) {
            auditService.record(
                    principalId,
                    "DELI_SOURCE_SYNC_RECOVERY",
                    "ATTENDANCE_SOURCE",
                    job.sourceId(),
                    "SUCCESS",
                    "DELI_SYNC_STALE_JOB_RECOVERED");
        }
        auditService.record(
                principalId,
                "DELI_SOURCE_SYNC_RETRY_REQUEST",
                "ATTENDANCE_SYNC_JOB",
                job.jobId(),
                "SUCCESS",
                "SYNC_RETRY_JOB_CREATED");
        return execute(
                job,
                principalId,
                correlationId,
                CapabilityCodes.ATTENDANCE_SOURCE_RETRY);
    }

    private AttendanceSourceSyncModels.JobStatus execute(
            AttendanceSourceSyncModels.SourceJobStart job,
            String principalId,
            String correlationId,
            String executionCapability) {
        String jobId = job.jobId();

        DeliPunchSourcePort source =
                exactlyOneProductionSource(job.secretReferenceName());
        if (source == null) {
            return failAndRead(
                    jobId,
                    principalId,
                    "DELI_INTEGRATION_DISABLED");
        }
        AttendanceConfigurationResolverPort configurationResolver =
                exactlyOne(configurationResolvers);
        if (configurationResolver == null) {
            return failAndRead(
                    jobId,
                    principalId,
                    "ATTENDANCE_CONFIGURATION_RESOLVER_UNAVAILABLE");
        }
        AttendancePeriodProtectionPort periodProtection =
                exactlyOne(periodProtections);
        if (periodProtection == null) {
            return failAndRead(
                    jobId,
                    principalId,
                    "ATTENDANCE_PERIOD_PROTECTION_UNAVAILABLE");
        }

        int quarantined = 0;
        try {
            // A complete employee directory is required before any check-in
            // page is requested. Continuing after a directory failure would
            // turn a protocol problem into silently quarantined evidence.
            Map<String, String> employeeDirectory = fetchEmployeeDirectory(
                    source, job.sourceId());
            var fetchSettings = fetchSettings(job, employeeDirectory);
            repository.markRunning(jobId, clock.instant());
            String cursor = job.committedCursor();
            Set<String> seenCursors = new HashSet<>();
            seenCursors.add(cursor == null ? "0" : cursor);
            int pageNumber = 0;
            while (true) {
                if (pageNumber >= MAX_PAGES_PER_RUN) {
                    throw new AttendanceSourceSyncFailure(
                            "DELI_PAGE_LIMIT_REACHED");
                }
                DeliPunchSourcePort.DeliPage page = fetchWithRetry(
                        source, job, cursor, fetchSettings);
                if (isTerminalEmptyPage(page, cursor)) {
                    break;
                }
                requireForwardCursor(page, seenCursors);
                pageNumber++;
                var outcome = pageTransaction.commitPage(
                        job,
                        principalId,
                        correlationId,
                        executionCapability,
                        pageNumber,
                        page,
                        configurationResolver,
                        periodProtection);
                quarantined = Math.addExact(
                        quarantined, outcome.quarantinedCount());
                cursor = page.nextCursor();
                applyRateLimit(job.rateLimitPerMinute());
            }
            repository.markFinished(
                    jobId,
                    quarantined == 0
                            ? "SUCCEEDED"
                            : "PARTIALLY_QUARANTINED",
                    clock.instant());
        } catch (AttendanceSourceSyncFailure failure) {
            return failAndRead(
                    jobId, principalId, failure.safeCode());
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .error("Deli sync job {} failed with unexpected exception",
                            jobId, exception);
            return failAndRead(
                    jobId, principalId, "DELI_SYNC_FAILED");
        }
        auditService.record(
                principalId,
                "DELI_SOURCE_SYNC_COMPLETE",
                "ATTENDANCE_SYNC_JOB",
                jobId,
                "SUCCESS",
                quarantined == 0
                        ? "DELI_SYNC_SUCCEEDED"
                        : "DELI_SYNC_PARTIALLY_QUARANTINED");
        return readCreated(jobId, principalId);
    }

    public AttendanceSourceSyncModels.JobStatus get(String jobId) {
        requireReference(jobId, 36);
        capabilities.require(CapabilityCodes.ATTENDANCE_SOURCE_READ);
        String principalId = principalProvider.currentPrincipalId();
        return repository.findAuthorizedJob(
                        jobId,
                        principalId,
                        CapabilityCodes.ATTENDANCE_SOURCE_READ,
                        clock.instant())
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
    }

    private DeliPunchSourcePort exactlyOneProductionSource(
            String secretReferenceName) {
        List<DeliPunchSourcePort> production = deliSources.stream()
                .filter(DeliPunchSourcePort::productionIntegration)
                .filter(source -> Objects.equals(
                        secretReferenceName,
                        source.credentialReferenceName()))
                .toList();
        return exactlyOne(production);
    }

    private static <T> T exactlyOne(List<T> values) {
        return values.size() == 1 ? values.getFirst() : null;
    }

    private static Map<String, String> fetchEmployeeDirectory(
            DeliPunchSourcePort source, String sourceId) {
        try {
            Map<String, String> directory =
                    source.fetchEmployeeDirectory(sourceId);
            if (directory == null) {
                throw new AttendanceSourceSyncFailure(
                        "DELI_EMPLOYEE_DIRECTORY_CONTRACT_INVALID");
            }
            return directory;
        } catch (DeliPunchSourcePort.FetchException exception) {
            throw new AttendanceSourceSyncFailure(exception.safeCode());
        }
    }

    private static DeliPunchSourcePort.FetchSettings fetchSettings(
            AttendanceSourceSyncModels.SourceJobStart job,
            Map<String, String> employeeDirectory) {
        if (job.rateLimitPerMinute() < 60
                || job.rateLimitPerMinute() > 10_000
                || job.backoffSeconds() < 0
                || job.backoffSeconds() > 5) {
            throw new AttendanceSourceSyncFailure(
                    "DELI_RUNTIME_CONFIGURATION_UNSUPPORTED");
        }
        try {
            return new DeliPunchSourcePort.FetchSettings(
                    job.pageSize(),
                    ZoneId.of(job.sourceTimeZone()),
                    employeeDirectory);
        } catch (RuntimeException exception) {
            throw new AttendanceSourceSyncFailure(
                    "DELI_RUNTIME_CONFIGURATION_INVALID");
        }
    }

    private static DeliPunchSourcePort.DeliPage fetchWithRetry(
            DeliPunchSourcePort source,
            AttendanceSourceSyncModels.SourceJobStart job,
            String cursor,
            DeliPunchSourcePort.FetchSettings settings) {
        for (int attempt = 1; attempt <= MAX_FETCH_ATTEMPTS; attempt++) {
            try {
                return source.fetchPage(job.sourceId(), cursor, settings);
            } catch (DeliPunchSourcePort.FetchException exception) {
                if (!exception.retryable()) {
                    throw new AttendanceSourceSyncFailure(
                            exception.safeCode());
                }
                if (attempt == MAX_FETCH_ATTEMPTS) {
                    throw new AttendanceSourceSyncFailure(
                            "DELI_SOURCE_FETCH_RETRY_EXHAUSTED");
                }
                delayMillis(Math.multiplyExact(
                        job.backoffSeconds(), 1_000L));
            } catch (RuntimeException exception) {
                throw new AttendanceSourceSyncFailure(
                        "DELI_SOURCE_FETCH_UNCLASSIFIED");
            }
        }
        throw new AttendanceSourceSyncFailure(
                "DELI_SOURCE_FETCH_FAILED");
    }

    private static boolean completedSuccessfully(String state) {
        return "SUCCEEDED".equals(state)
                || "PARTIALLY_QUARANTINED".equals(state);
    }

    private static void applyRateLimit(int rateLimitPerMinute) {
        long delayMillis = Math.max(
                1L, (long) Math.ceil(60_000.0 / rateLimitPerMinute));
        delayMillis(delayMillis);
    }

    private static void delayMillis(long delayMillis) {
        if (delayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AttendanceSourceSyncFailure(
                    "DELI_SYNC_INTERRUPTED");
        }
    }

    private AttendanceSourceSyncModels.JobStatus failAndRead(
            String jobId, String principalId, String safeErrorCode) {
        repository.markFailed(jobId, safeErrorCode, clock.instant());
        auditService.recordFailure(
                principalId,
                "DELI_SOURCE_SYNC_COMPLETE",
                "ATTENDANCE_SYNC_JOB",
                jobId,
                "FAILURE",
                safeErrorCode);
        return readCreated(jobId, principalId);
    }

    private AttendanceSourceSyncModels.JobStatus readCreated(
            String jobId, String principalId) {
        return repository.findCreatedJob(jobId, principalId)
                .orElseThrow(() -> new IllegalStateException(
                        "created sync job is unavailable"));
    }

    private static boolean isTerminalEmptyPage(
            DeliPunchSourcePort.DeliPage page, String requestedCursor) {
        if (page == null || page.records() == null) {
            throw new AttendanceSourceSyncFailure(
                    "DELI_PAGE_CONTRACT_INVALID");
        }
        if (!sameRequestedCursor(requestedCursor, page.inputCursor())) {
            throw new AttendanceSourceSyncFailure("DELI_WATERMARK_STALE");
        }
        if (!page.records().isEmpty()) {
            return false;
        }
        return page.nextCursor() == null
                || Objects.equals(page.inputCursor(), page.nextCursor());
    }

    private static boolean sameRequestedCursor(
            String requested, String input) {
        return Objects.equals(requested, input)
                || (requested == null && "0".equals(input));
    }

    private static void requireForwardCursor(
            DeliPunchSourcePort.DeliPage page,
            Set<String> seenCursors) {
        String nextCursor = page.nextCursor();
        if (nextCursor == null
                || nextCursor.isBlank()
                || Objects.equals(page.inputCursor(), nextCursor)
                || !seenCursors.add(nextCursor)) {
            throw new AttendanceSourceSyncFailure(
                    "DELI_CURSOR_CYCLE_DETECTED");
        }
    }

    private static String retryConflictCode(
            AttendanceSourceSyncRepository.RetryState state) {
        return switch (state) {
            case VERSION_CONFLICT -> "VERSION_CONFLICT";
            case NOT_RETRYABLE -> "ATTENDANCE_SOURCE_JOB_NOT_RETRYABLE";
            case ALREADY_RUNNING ->
                    "ATTENDANCE_SOURCE_SYNC_ALREADY_RUNNING";
            case IDEMPOTENCY_CONFLICT -> "IDEMPOTENCY_KEY_REUSED";
            case CREATED, REPLAYED, RESOURCE_UNAVAILABLE ->
                    throw new IllegalArgumentException(
                            "retry state is not a conflict");
        };
    }

    private static void requireReference(String value, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid sync reference");
        }
    }
}
