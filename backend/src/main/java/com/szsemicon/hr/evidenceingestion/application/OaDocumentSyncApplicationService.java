package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Application service for OA attendance document synchronisation.
 *
 * <p>Mirrors the structure of {@link DeliPunchSyncApplicationService} but
 * drives the {@link OaAttendanceDocumentSourcePort} instead of the Deli
 * punch source. It reuses the same {@link AttendanceSourceSyncRepository}
 * job tracking tables so that the existing UI and sync-job read endpoints
 * can display OA jobs without a separate projection.</p>
 *
 * <p>The service supports two entry points:
 * <ul>
 *   <li>{@link #runScheduled()} — called by
 *       {@code OaAutoSyncJob}, runs as SYSTEM principal, no capability
 *       check, cycles through every active {@code OA_ATTENDANCE} source.</li>
 *   <li>{@link #run(String, String)} — manually triggered via the
 *       {@code POST /api/v1/attendance-source-jobs} endpoint with a
 *       {@code sourceId} belonging to an {@code OA_ATTENDANCE} source.</li>
 * </ul>
 */
@Service
public class OaDocumentSyncApplicationService {

    private static final int MAX_PAGES_PER_RUN = 10_000;

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principalProvider;
    private final AttendanceSourceSyncRepository repository;
    private final OaDocumentPageTransaction pageTransaction;
    private final AuditService auditService;
    private final List<OaAttendanceDocumentSourcePort> oaSources;
    private final Clock clock;

    public OaDocumentSyncApplicationService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceSourceSyncRepository repository,
            OaDocumentPageTransaction pageTransaction,
            AuditService auditService,
            List<OaAttendanceDocumentSourcePort> oaSources,
            Clock clock) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.pageTransaction = pageTransaction;
        this.auditService = auditService;
        this.oaSources = List.copyOf(oaSources);
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Scheduled entry point (SYSTEM principal, no auth check)
    // ------------------------------------------------------------------

    public void runScheduled() {
        List<String> sourceIds = repository.findAllActiveOaSourceIds();
        for (String sourceId : sourceIds) {
            String jobId = UUID.randomUUID().toString();
            String correlationId = "SCHEDULED:" + jobId;
            Instant at = clock.instant();
            AttendanceSourceSyncRepository.StartResult result;
            try {
                result = repository.createScheduledOaJob(
                        sourceId, jobId, correlationId, at);
            } catch (RuntimeException exception) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .error("Scheduled OA sync: failed to create job for source {}",
                                sourceId, exception);
                continue;
            }
            if (result.state()
                    == AttendanceSourceSyncRepository.StartState.ALREADY_RUNNING) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .info("Scheduled OA sync: source {} already running, skipping",
                                sourceId);
                continue;
            }
            if (result.state()
                    != AttendanceSourceSyncRepository.StartState.CREATED) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("Scheduled OA sync: source {} unavailable ({}), skipping",
                                sourceId, result.state());
                continue;
            }
            try {
                execute(Objects.requireNonNull(result.job()),
                        "SYSTEM",
                        correlationId,
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN);
            } catch (RuntimeException exception) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .error("Scheduled OA sync: execution failed for source {}",
                                sourceId, exception);
            }
        }
    }

    // ------------------------------------------------------------------
    // Manual trigger entry point
    // ------------------------------------------------------------------

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
            result = repository.createManualOaJob(
                    sourceId, jobId, correlationId, principalId, requestedAt);
        } catch (RuntimeException exception) {
            auditService.recordFailure(
                    principalId, "OA_SOURCE_SYNC_REQUEST",
                    "ATTENDANCE_SOURCE", sourceId,
                    "FAILURE", "OA_SYNC_JOB_CREATION_FAILED");
            throw exception;
        }

        if (result.state()
                == AttendanceSourceSyncRepository.StartState.RESOURCE_UNAVAILABLE) {
            auditService.recordFailure(
                    principalId, "OA_SOURCE_SYNC_REQUEST",
                    "ATTENDANCE_SOURCE", sourceId,
                    "DENIED", "RESOURCE_UNAVAILABLE");
            throw new ResourceNotAvailableAccessDeniedException();
        }
        if (result.state()
                == AttendanceSourceSyncRepository.StartState.ALREADY_RUNNING) {
            auditService.recordFailure(
                    principalId, "OA_SOURCE_SYNC_REQUEST",
                    "ATTENDANCE_SOURCE", sourceId,
                    "FAILURE", "ATTENDANCE_SOURCE_SYNC_ALREADY_RUNNING");
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ATTENDANCE_SOURCE_SYNC_ALREADY_RUNNING",
                    "该考勤数据源已有同步任务运行中",
                    true);
        }

        var job = Objects.requireNonNull(result.job(), "created sync job");
        jobId = job.jobId();

        if (result.recoveredStaleJobs() > 0) {
            auditService.record(principalId, "OA_SOURCE_SYNC_RECOVERY",
                    "ATTENDANCE_SOURCE", sourceId,
                    "SUCCESS", "OA_SYNC_STALE_JOB_RECOVERED");
        }
        auditService.record(principalId, "OA_SOURCE_SYNC_REQUEST",
                "ATTENDANCE_SYNC_JOB", jobId,
                "SUCCESS", "SYNC_JOB_CREATED");

        return execute(job, principalId, correlationId,
                CapabilityCodes.ATTENDANCE_SOURCE_RUN);
    }

    /**
     * Re-read OA leave/overtime/outing in an occurrence window and rematch.
     * Does not advance the durable watermark.
     */
    public AttendanceSourceSyncModels.JobStatus rematchWindow(
            String sourceId,
            LocalDate fromDate,
            LocalDate toDate,
            String correlationId) {
        requireReference(sourceId, 36);
        requireReference(correlationId, 64);
        if (fromDate == null || toDate == null || toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("invalid OA rematch window");
        }
        if (fromDate.plusDays(62).isBefore(toDate)) {
            throw new IllegalArgumentException("OA rematch window too long");
        }
        capabilities.require(CapabilityCodes.ATTENDANCE_SOURCE_RUN);
        String principalId = principalProvider.currentPrincipalId();
        Instant requestedAt = clock.instant();
        String jobId = UUID.randomUUID().toString();
        AttendanceSourceSyncRepository.StartResult result;
        try {
            result = repository.createManualOaJob(
                    sourceId, jobId, correlationId, principalId, requestedAt);
        } catch (RuntimeException exception) {
            auditService.recordFailure(
                    principalId, "OA_SOURCE_REMATCH_REQUEST",
                    "ATTENDANCE_SOURCE", sourceId,
                    "FAILURE", "OA_SYNC_JOB_CREATION_FAILED");
            throw exception;
        }
        if (result.state()
                == AttendanceSourceSyncRepository.StartState.RESOURCE_UNAVAILABLE) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        if (result.state()
                == AttendanceSourceSyncRepository.StartState.ALREADY_RUNNING) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ATTENDANCE_SOURCE_SYNC_ALREADY_RUNNING",
                    "该考勤数据源已有同步任务运行中",
                    true);
        }
        var job = Objects.requireNonNull(result.job(), "created sync job");
        jobId = job.jobId();
        auditService.record(principalId, "OA_SOURCE_REMATCH_REQUEST",
                "ATTENDANCE_SYNC_JOB", jobId,
                "SUCCESS", "SYNC_JOB_CREATED");
        return rematchExecute(job, principalId, correlationId, fromDate, toDate);
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

    private AttendanceSourceSyncModels.JobStatus rematchExecute(
            AttendanceSourceSyncModels.SourceJobStart job,
            String principalId,
            String correlationId,
            LocalDate fromDate,
            LocalDate toDate) {
        String jobId = job.jobId();
        if (oaSources.isEmpty()) {
            return failAndRead(jobId, principalId, "OA_INTEGRATION_DISABLED");
        }
        OaAttendanceDocumentSourcePort source = oaSources.getFirst();
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant fromInclusive = fromDate.atStartOfDay(zone).toInstant();
        Instant toExclusive = toDate.plusDays(1).atStartOfDay(zone).toInstant();
        try {
            repository.markRunning(jobId, clock.instant());
            List<OaAttendanceDocumentSourcePort.OaDocumentRecord> records =
                    source.fetchOverlapping(
                            job.sourceId(), fromInclusive, toExclusive);
            var outcome = pageTransaction.rematchRecords(
                    job,
                    principalId,
                    correlationId,
                    CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                    records);
            repository.markFinished(
                    jobId,
                    outcome.quarantinedCount() == 0
                            ? "SUCCEEDED" : "PARTIALLY_QUARANTINED",
                    clock.instant());
            auditService.record(principalId, "OA_SOURCE_REMATCH_COMPLETE",
                    "ATTENDANCE_SYNC_JOB", jobId,
                    "SUCCESS",
                    outcome.quarantinedCount() == 0
                            ? "OA_REMATCH_SUCCEEDED"
                            : "OA_REMATCH_PARTIALLY_QUARANTINED");
        } catch (AttendanceSourceSyncFailure failure) {
            return failAndRead(jobId, principalId, failure.safeCode());
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .error("OA rematch job {} failed", jobId, exception);
            return failAndRead(jobId, principalId, "OA_SYNC_FAILED");
        }
        return readCreated(jobId, principalId);
    }

    // ------------------------------------------------------------------
    // Core sync execution loop
    // ------------------------------------------------------------------

    private AttendanceSourceSyncModels.JobStatus execute(
            AttendanceSourceSyncModels.SourceJobStart job,
            String principalId,
            String correlationId,
            String executionCapability) {

        String jobId = job.jobId();

        // Find the single production OA source — unlike Deli there is only one.
        if (oaSources.isEmpty()) {
            return failAndRead(jobId, principalId, "OA_INTEGRATION_DISABLED");
        }
        OaAttendanceDocumentSourcePort source = oaSources.getFirst();

        int quarantined = 0;
        try {
            repository.markRunning(jobId, clock.instant());
            String cursor = job.committedCursor();
            Set<String> seenCursors = new HashSet<>();
            seenCursors.add(cursor == null ? "" : cursor);
            int pageNumber = 0;

            while (true) {
                if (pageNumber >= MAX_PAGES_PER_RUN) {
                    throw new AttendanceSourceSyncFailure("OA_PAGE_LIMIT_REACHED");
                }
                OaAttendanceDocumentSourcePort.OaPage page =
                        source.fetchPage(job.sourceId(), cursor);

                if (page == null || page.records() == null) {
                    throw new AttendanceSourceSyncFailure("OA_PAGE_CONTRACT_INVALID");
                }

                // Empty page with same or null nextCursor means we are caught up.
                if (page.records().isEmpty()
                        && (page.nextCursor() == null
                                || page.nextCursor().equals(page.inputCursor()))) {
                    break;
                }

                // Cycle guard
                if (page.nextCursor() != null
                        && !seenCursors.add(page.nextCursor())) {
                    throw new AttendanceSourceSyncFailure("OA_CURSOR_CYCLE_DETECTED");
                }

                pageNumber++;
                var outcome = pageTransaction.commitPage(
                        job,
                        principalId,
                        correlationId,
                        executionCapability,
                        pageNumber,
                        page);
                quarantined = Math.addExact(quarantined, outcome.quarantinedCount());
                cursor = page.nextCursor();
                if (cursor == null) {
                    break;
                }
            }

            repository.markFinished(
                    jobId,
                    quarantined == 0 ? "SUCCEEDED" : "PARTIALLY_QUARANTINED",
                    clock.instant());

        } catch (AttendanceSourceSyncFailure failure) {
            return failAndRead(jobId, principalId, failure.safeCode());
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .error("OA sync job {} failed with unexpected exception",
                            jobId, exception);
            return failAndRead(jobId, principalId, "OA_SYNC_FAILED");
        }

        auditService.record(principalId, "OA_SOURCE_SYNC_COMPLETE",
                "ATTENDANCE_SYNC_JOB", jobId,
                "SUCCESS",
                quarantined == 0
                        ? "OA_SYNC_SUCCEEDED"
                        : "OA_SYNC_PARTIALLY_QUARANTINED");
        return readCreated(jobId, principalId);
    }

    private AttendanceSourceSyncModels.JobStatus failAndRead(
            String jobId, String principalId, String safeErrorCode) {
        repository.markFailed(jobId, safeErrorCode, clock.instant());
        auditService.recordFailure(principalId, "OA_SOURCE_SYNC_COMPLETE",
                "ATTENDANCE_SYNC_JOB", jobId, "FAILURE", safeErrorCode);
        return readCreated(jobId, principalId);
    }

    private AttendanceSourceSyncModels.JobStatus readCreated(
            String jobId, String principalId) {
        return repository.findCreatedJob(jobId, principalId)
                .orElseThrow(() -> new IllegalStateException(
                        "created OA sync job is unavailable"));
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
