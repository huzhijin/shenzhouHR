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
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AttendanceEvidenceRepository evidenceRepository;

    public OaDocumentSyncApplicationService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceSourceSyncRepository repository,
            OaDocumentPageTransaction pageTransaction,
            AuditService auditService,
            List<OaAttendanceDocumentSourcePort> oaSources,
            Clock clock) {
        this(capabilities, principalProvider, repository, pageTransaction,
                auditService, oaSources, clock, null);
    }

    @Autowired
    public OaDocumentSyncApplicationService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceSourceSyncRepository repository,
            OaDocumentPageTransaction pageTransaction,
            AuditService auditService,
            List<OaAttendanceDocumentSourcePort> oaSources,
            Clock clock,
            AttendanceEvidenceRepository evidenceRepository) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.pageTransaction = pageTransaction;
        this.auditService = auditService;
        this.oaSources = List.copyOf(oaSources);
        this.clock = clock;
        this.evidenceRepository = evidenceRepository;
    }

    // ------------------------------------------------------------------
    // Scheduled entry point (SYSTEM principal, no auth check)
    // ------------------------------------------------------------------

    public boolean runScheduled() {
        boolean successful = true;
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
                successful = false;
                continue;
            }
            if (result.state()
                    == AttendanceSourceSyncRepository.StartState.ALREADY_RUNNING) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .info("Scheduled OA sync: source {} already running, skipping",
                                sourceId);
                successful = false;
                continue;
            }
            if (result.state()
                    != AttendanceSourceSyncRepository.StartState.CREATED) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("Scheduled OA sync: source {} unavailable ({}), skipping",
                                sourceId, result.state());
                successful = false;
                continue;
            }
            try {
                var status = execute(Objects.requireNonNull(result.job()),
                        "SYSTEM",
                        correlationId,
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN);
                successful &= completed(status);
            } catch (RuntimeException exception) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .error("Scheduled OA sync: execution failed for source {}",
                                sourceId, exception);
                successful = false;
            }
        }
        return successful && !sourceIds.isEmpty();
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
     * Re-read every supported OA family and previously known identities in
     * an occurrence window. Updates evidence knowledge without advancing the scan cursor.
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

    /** Internal report calculation prerequisite, after report-scope authorization.
     * OA sources are shared across companies and matched by employee identity,
     * just as in the calculation source repository. No source payload is exposed.
     */
    public Instant refreshMonthForReport(
            String companyId, YearMonth period, Instant requestedAt) {
        Objects.requireNonNull(period, "period");
        return refreshRangeForReport(
                companyId, period.atDay(1), period.plusMonths(1).atDay(1), requestedAt);
    }

    /**
     * Re-read all OA families and every historically overlapping business key
     * in the calculation's evidence range. The caller supplies the complete
     * dependency range, including neighbouring days needed by overnight shifts.
     */
    public Instant refreshRangeForReport(
            String companyId, LocalDate fromInclusive, LocalDate toExclusive,
            Instant requestedAt) {
        return refreshRangeForReport(companyId, fromInclusive, toExclusive, requestedAt, List.of());
    }

    public Instant refreshRangeForReport(
            String companyId, LocalDate fromInclusive, LocalDate toExclusive,
            Instant requestedAt, List<String> reportEmployeeIds) {
        requireReference(companyId, 36);
        Objects.requireNonNull(reportEmployeeIds, "reportEmployeeIds");
        reportEmployeeIds.forEach(id -> requireReference(id, 36));
        reportEmployeeIds = reportEmployeeIds.stream().distinct().sorted().toList();
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("OA report refresh range must be nonempty");
        }
        List<String> sourceIds = repository.findAllActiveOaSourceIds();
        if (sourceIds.isEmpty() || oaSources.isEmpty()) {
            throw reportRefreshFailure("OA_REPORT_SOURCE_REFRESH_FAILED",
                    "OA 数据源不可用，本次未更新报表，请恢复同步后重试");
        }
        Instant evidenceAsOf = requestedAt;
        for (String sourceId : sourceIds) {
            String jobId = UUID.randomUUID().toString();
            String correlationId = "REPORT:" + jobId;
            var result = repository.createScheduledOaJob(
                    sourceId, jobId, correlationId, clock.instant());
            if (result.state() == AttendanceSourceSyncRepository.StartState.ALREADY_RUNNING) {
                throw new ApiProblemException(HttpStatus.CONFLICT,
                        "OA_REPORT_SOURCE_REFRESH_BUSY",
                        "OA 数据正在同步，本次未更新报表，请同步完成后重试", true);
            }
            if (result.state() != AttendanceSourceSyncRepository.StartState.CREATED) {
                throw reportRefreshFailure("OA_REPORT_SOURCE_REFRESH_FAILED",
                        "OA 数据源不可用，本次未更新报表，请恢复同步后重试");
            }
            var status = rematchExecute(Objects.requireNonNull(result.job()),
                    "SYSTEM", correlationId, fromInclusive, toExclusive.minusDays(1), companyId, reportEmployeeIds);
            if (!completed(status)) {
                String code = switch (Objects.toString(status.safeErrorCode(), "")) {
                    case "OA_REPORT_SOURCE_QUARANTINED" -> "OA_REPORT_SOURCE_QUARANTINED";
                    case "OA_REPORT_SOURCE_DOCUMENT_MISSING" -> "OA_REPORT_SOURCE_DOCUMENT_MISSING";
                    default -> "OA_REPORT_SOURCE_REFRESH_FAILED";
                };
                throw reportRefreshFailure(code,
                        "OA 最新单据读取或核验失败，本次未更新报表，请检查同步任务后重试");
            }
            if (status.completedAt() != null && status.completedAt().isAfter(evidenceAsOf)) {
                evidenceAsOf = status.completedAt();
            }
        }
        Instant completedAt = clock.instant();
        return completedAt.isAfter(evidenceAsOf) ? completedAt : evidenceAsOf;
    }

    private static boolean completed(AttendanceSourceSyncModels.JobStatus status) {
        return status != null && ("SUCCEEDED".equals(status.state())
                || "PARTIALLY_QUARANTINED".equals(status.state()));
    }

    private static ApiProblemException reportRefreshFailure(String code, String message) {
        return new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, code, message, true);
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
        return rematchExecute(job, principalId, correlationId, fromDate, toDate, null);
    }

    private AttendanceSourceSyncModels.JobStatus rematchExecute(
            AttendanceSourceSyncModels.SourceJobStart job, String principalId,
            String correlationId, LocalDate fromDate, LocalDate toDate, String reportCompanyId) {
        return rematchExecute(job, principalId, correlationId, fromDate, toDate, reportCompanyId, List.of());
    }

    private AttendanceSourceSyncModels.JobStatus rematchExecute(
            AttendanceSourceSyncModels.SourceJobStart job, String principalId,
            String correlationId, LocalDate fromDate, LocalDate toDate, String reportCompanyId,
            List<String> reportEmployeeIds) {
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
            var window = readCurrentWindow(source, job.sourceId(), fromInclusive, toExclusive,
                    reportCompanyId, reportEmployeeIds);
            var records = window.records();
            pageTransaction.preflightUnsafeChanges(job.sourceId(), records);
            var outcome = window.isolatedCount() == 0
                    ? pageTransaction.rematchRecords(job, principalId, correlationId,
                            CapabilityCodes.ATTENDANCE_SOURCE_RUN, records)
                    : pageTransaction.rematchRecords(job, principalId, correlationId,
                            CapabilityCodes.ATTENDANCE_SOURCE_RUN, records, window.isolatedCount());
            Instant finishedAt = clock.instant();
            if (outcome.evidenceAsOf() != null && outcome.evidenceAsOf().isAfter(finishedAt)) {
                finishedAt = outcome.evidenceAsOf();
            }
            int quarantinedCount = outcome.quarantinedCount();
            repository.markFinished(
                    jobId,
                    quarantinedCount == 0
                            ? "SUCCEEDED" : "PARTIALLY_QUARANTINED",
                    finishedAt);
            auditService.record(principalId, "OA_SOURCE_REMATCH_COMPLETE",
                    "ATTENDANCE_SYNC_JOB", jobId,
                    "SUCCESS",
                    quarantinedCount == 0
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

    private record CurrentWindow(List<OaAttendanceDocumentSourcePort.OaDocumentRecord> records, int isolatedCount) { }

    private CurrentWindow readCurrentWindow(
            OaAttendanceDocumentSourcePort source, String sourceId,
            Instant fromInclusive, Instant toExclusive, String reportCompanyId, List<String> reportEmployeeIds) {
        var current = new LinkedHashMap<String, OaAttendanceDocumentSourcePort.OaDocumentRecord>();
        var overlapping = source.fetchOverlapping(sourceId, fromInclusive, toExclusive);
        if (overlapping == null) {
            throw new AttendanceSourceSyncFailure("OA_PAGE_CONTRACT_INVALID");
        }
        for (var record : overlapping) {
            mergeObservation(current, record);
        }
        var initialWindow = new LinkedHashMap<>(current);
        if (evidenceRepository != null) {
            List<String> knownKeys = evidenceRepository.findOaBusinessKeysOverlapping(
                    sourceId, fromInclusive, toExclusive);
            if (!knownKeys.isEmpty()) {
                var reread = source.fetchByBusinessKeys(sourceId, knownKeys);
                if (reread == null) {
                    throw new AttendanceSourceSyncFailure("OA_PAGE_CONTRACT_INVALID");
                }
                var currentKnown = new LinkedHashMap<String, OaAttendanceDocumentSourcePort.OaDocumentRecord>();
                for (var record : reread) {
                    mergeObservation(currentKnown, record);
                }
                if (!currentKnown.keySet().containsAll(knownKeys)) {
                    List<String> missing = knownKeys.stream().filter(key -> !currentKnown.containsKey(key)).toList();
                    // Conflicting current source reads are not absence. An old
                    // foreign attribution cannot discard a newly returned form.
                    if (missing.stream().anyMatch(current::containsKey)) {
                        throw new AttendanceSourceSyncFailure("OA_PAGE_CONTRACT_INVALID");
                    }
                    Set<String> provenUnrelated = proveMissingKeysOutsideReport(
                            sourceId, reportCompanyId, reportEmployeeIds, missing);
                    if (!provenUnrelated.containsAll(missing)) {
                        throw new AttendanceSourceSyncFailure("OA_REPORT_SOURCE_DOCUMENT_MISSING");
                    }
                    // Absence is not cancellation: preserve every old record
                    // and publish no synthetic observation for these keys.
                    org.slf4j.LoggerFactory.getLogger(getClass()).info(
                            "report-source missing keys proven noncontributing company={} selectedEmployees={} source={} count={}",
                            reportCompanyId, reportEmployeeIds.size(), sourceId, provenUnrelated.size());
                }
                current.putAll(currentKnown);
            }
        }
        int isolated = isolateUnparseablePendingLeaves(source, sourceId, reportCompanyId,
                reportEmployeeIds, current, initialWindow);
        return new CurrentWindow(List.copyOf(current.values()), isolated);
    }

    private int isolateUnparseablePendingLeaves(OaAttendanceDocumentSourcePort source, String sourceId,
            String reportCompanyId, List<String> reportEmployeeIds,
            java.util.Map<String, OaAttendanceDocumentSourcePort.OaDocumentRecord> current,
            java.util.Map<String, OaAttendanceDocumentSourcePort.OaDocumentRecord> initialWindow) {
        if (reportCompanyId == null || evidenceRepository == null) return 0;
        var candidates = current.values().stream()
                .filter(UnparseablePendingLeaveReportScopeProof::isCandidate).toList();
        if (candidates.isEmpty()) return 0;
        int isolated = 0;
        for (var candidate : candidates) {
            var earlier = initialWindow.get(candidate.sourceBusinessKey());
            if (earlier != null && !earlier.equals(candidate)) {
                throw new AttendanceSourceSyncFailure("OA_PAGE_CONTRACT_INVALID");
            }
        }
        try {
            var keys = candidates.stream()
                    .map(OaAttendanceDocumentSourcePort.OaDocumentRecord::sourceBusinessKey).toList();
            var history = reportEmployeeIds.isEmpty()
                    ? evidenceRepository.findUnparseableLeaveScopeRows(sourceId, reportCompanyId, keys)
                    : evidenceRepository.findUnparseableLeaveEmployeeScopeRows(
                            sourceId, reportCompanyId, reportEmployeeIds, keys);
            var numbers = reportEmployeeIds.isEmpty()
                    ? evidenceRepository.findCompanyEmployeeNumbers(reportCompanyId)
                    : evidenceRepository.findReportEmployeeNumbers(reportCompanyId, reportEmployeeIds);
            for (var candidate : candidates) {
                if (UnparseablePendingLeaveReportScopeProof.cannotActivateAndNoTargetHistory(
                        reportCompanyId, reportEmployeeIds, candidate, history, numbers, current.values())
                        && source.hasNoLeaveRevocationsForSerial(sourceId, candidate.leaveSerial())) {
                    current.remove(candidate.sourceBusinessKey());
                    isolated++;
                    org.slf4j.LoggerFactory.getLogger(getClass()).info(
                            "oa-report-draft-isolation company={} source={} keyDigest={} versionDigest={} shapeDigest={} reason=CANNOT_ACTIVATE_AND_NO_TARGET_HISTORY",
                            reportCompanyId, sourceId, AttendanceEvidenceDigests.sha256(candidate.sourceBusinessKey()),
                            AttendanceEvidenceDigests.sha256(candidate.sourceVersion()),
                            AttendanceEvidenceDigests.sha256(candidate.toString()));
                }
            }
        } catch (RuntimeException unavailableProof) {
            // Never treat unavailable history or a failed source read as absence.
            throw new AttendanceSourceSyncFailure("OA_REPORT_SOURCE_QUARANTINED");
        }
        return isolated;
    }

    private Set<String> proveMissingKeysOutsideReport(
            String sourceId, String reportCompanyId, List<String> reportEmployeeIds, List<String> missing) {
        if (reportCompanyId == null) return Set.of();
        try {
            if (!reportEmployeeIds.isEmpty()) {
                var history = evidenceRepository.findMissingOaKeyEmployeeScopeRows(
                        sourceId, reportCompanyId, reportEmployeeIds, missing);
                var numbers = evidenceRepository.findReportEmployeeNumbers(reportCompanyId, reportEmployeeIds);
                Set<String> proven = new HashSet<>(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(
                        reportCompanyId, reportEmployeeIds, missing, history, numbers));
                var remaining = missing.stream().filter(key -> !proven.contains(key))
                        .filter(key -> key != null && (key.startsWith("OVERTIME:")
                                || key.startsWith("PUNCH_CORRECTION:"))).toList();
                if (!remaining.isEmpty()) {
                    Instant proofAsOf = clock.instant();
                    proven.addAll(MissingOaReportScopeProof.provenNoncontributingEmployeeKeys(
                            reportCompanyId, reportEmployeeIds, remaining, history, numbers,
                            evidenceRepository.findMissingOaCurrentEligibilityRows(
                                    sourceId, reportCompanyId, reportEmployeeIds, remaining, proofAsOf), proofAsOf));
                }
                var missingLeaves = missing.stream().filter(key -> !proven.contains(key))
                        .filter(key -> key != null && key.startsWith("LEAVE:")).toList();
                if (!missingLeaves.isEmpty()) {
                    Instant proofAsOf = clock.instant();
                    // This is a global never-consumed proof, not a selected-employee
                    // complement: even a zero-minute fact in any old company pin blocks it.
                    proven.addAll(MissingOaReportScopeProof.provenNeverEffectiveDraftLeaveKeys(
                            reportCompanyId, missingLeaves,
                            evidenceRepository.findMissingOaKeyScopeRows(sourceId, reportCompanyId, missingLeaves),
                            evidenceRepository.findMissingOaCompanyCurrentEligibilityRows(
                                    sourceId, reportCompanyId, missingLeaves, proofAsOf), proofAsOf));
                }
                return Set.copyOf(proven);
            }
            var history = evidenceRepository.findMissingOaKeyScopeRows(sourceId, reportCompanyId, missing);
            var numbers = evidenceRepository.findCompanyEmployeeNumbers(reportCompanyId);
            Set<String> proven = new HashSet<>(MissingOaReportScopeProof.provenUnrelatedKeys(
                    reportCompanyId, missing, history, numbers));
            var remaining = missing.stream().filter(key -> !proven.contains(key))
                    .filter(key -> key != null && (key.startsWith("OVERTIME:")
                            || key.startsWith("PUNCH_CORRECTION:") || key.startsWith("LEAVE:"))).toList();
            if (!remaining.isEmpty()) {
                Instant proofAsOf = clock.instant();
                var eligibility = evidenceRepository.findMissingOaCompanyCurrentEligibilityRows(
                        sourceId, reportCompanyId, remaining, proofAsOf);
                proven.addAll(MissingOaReportScopeProof.provenNoncontributingCompanyKeys(
                        reportCompanyId, remaining, history, numbers, eligibility, proofAsOf));
                proven.addAll(MissingOaReportScopeProof.provenNeverEffectiveDraftLeaveKeys(
                        reportCompanyId, remaining, history, eligibility, proofAsOf));
            }
            return Set.copyOf(proven);
        } catch (RuntimeException unproven) {
            // A broken/unavailable proof remains a missing-source failure. Do
            // not log database exception text or any historical source payload.
            throw new AttendanceSourceSyncFailure("OA_REPORT_SOURCE_DOCUMENT_MISSING");
        }
    }

    private static void mergeObservation(
            java.util.Map<String, OaAttendanceDocumentSourcePort.OaDocumentRecord> records,
            OaAttendanceDocumentSourcePort.OaDocumentRecord record) {
        if (record == null || record.sourceBusinessKey() == null) {
            throw new AttendanceSourceSyncFailure("OA_PAGE_CONTRACT_INVALID");
        }
        var prior = records.putIfAbsent(record.sourceBusinessKey(), record);
        if (prior != null && !prior.equals(record)) {
            // Multiple workflow summaries for one business key are not a safe
            // current-state contract. Never choose a status by arbitrary SQL order.
            throw new AttendanceSourceSyncFailure("OA_PAGE_CONTRACT_INVALID");
        }
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
