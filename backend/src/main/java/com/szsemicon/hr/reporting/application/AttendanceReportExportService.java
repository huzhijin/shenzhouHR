package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportExportEncoder.EncodedExport;
import com.szsemicon.hr.reporting.application.AttendanceReportExportEncoder.ExportContext;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.DeliveryMode;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportArtifact;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportJob;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportStatus;
import com.szsemicon.hr.reporting.domain.AttendanceReportCalculator;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportDataSet;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceReportExportService {

    static final long SYNCHRONOUS_ROW_LIMIT = 50_000;

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principalProvider;
    private final AttendanceReportSourceRepository sourceRepository;
    private final AttendanceReportExportStore exportStore;
    private final AttendanceReportExportEncoder encoder;
    private final AttendanceReportCalculator calculator;
    private final AttendanceReportExportTransactions transactions;
    private final AuditService auditService;
    private final Clock clock;
    private final Duration retention;
    private final RealtimeAttendanceReportSnapshotService realtimeSnapshots;

    @Autowired
    public AttendanceReportExportService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceReportSourceRepository sourceRepository,
            AttendanceReportExportStore exportStore,
            AttendanceReportExportEncoder encoder,
            AttendanceReportExportTransactions transactions,
            AuditService auditService,
            Clock clock,
            @Value("${shenzhouhr.reporting.export-retention:PT24H}")
                    Duration retention,
            @Autowired(required = false)
                    RealtimeAttendanceReportSnapshotService realtimeSnapshots) {
        this(
                capabilities,
                principalProvider,
                sourceRepository,
                exportStore,
                encoder,
                new AttendanceReportCalculator(),
                transactions,
                auditService,
                clock,
                retention,
                realtimeSnapshots);
    }

    AttendanceReportExportService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceReportSourceRepository sourceRepository,
            AttendanceReportExportStore exportStore,
            AttendanceReportExportEncoder encoder,
            AttendanceReportCalculator calculator,
            AttendanceReportExportTransactions transactions,
            AuditService auditService,
            Clock clock,
            Duration retention) {
        this(
                capabilities,
                principalProvider,
                sourceRepository,
                exportStore,
                encoder,
                calculator,
                transactions,
                auditService,
                clock,
                retention,
                null);
    }

    AttendanceReportExportService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceReportSourceRepository sourceRepository,
            AttendanceReportExportStore exportStore,
            AttendanceReportExportEncoder encoder,
            AttendanceReportCalculator calculator,
            AttendanceReportExportTransactions transactions,
            AuditService auditService,
            Clock clock,
            Duration retention,
            RealtimeAttendanceReportSnapshotService realtimeSnapshots) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.sourceRepository = sourceRepository;
        this.exportStore = exportStore;
        this.encoder = encoder;
        this.calculator = calculator;
        this.transactions = transactions;
        this.auditService = auditService;
        this.clock = clock;
        this.realtimeSnapshots = realtimeSnapshots;
        if (retention == null
                || retention.isZero()
                || retention.isNegative()
                || retention.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException(
                    "report export retention must be between zero and seven days");
        }
        this.retention = retention;
    }

    public ExportView create(
            ReportType reportType,
            ReportFilter filter,
            RequestedExportBinding requestedBinding,
            String purpose) {
        if (reportType == null
                || filter == null
                || requestedBinding == null) {
            throw new IllegalArgumentException(
                    "report type, filter, and export binding are required");
        }
        if (filter.status() != null
                && reportType != ReportType.EXCEPTIONS) {
            throw new IllegalArgumentException(
                    "status is only supported by the exception report");
        }
        String normalizedPurpose = ExportJob.normalizePurpose(purpose);
        return transactions.readCommitted(() -> createAuthorized(
                reportType,
                filter,
                requestedBinding,
                normalizedPurpose));
    }

    private ExportView createAuthorized(
            ReportType reportType,
            ReportFilter filter,
            RequestedExportBinding requestedBinding,
            String normalizedPurpose) {
        capabilities.require(
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE);
        requireReportOrQueryRead();
        String principalId = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        var snapshot = loadSnapshot(principalId, filter, now)
                .orElseThrow(AttendanceReportExportService::notReady);
        var dataSet = calculator.calculate(reportType, snapshot);
        String queryFingerprint = AttendanceReportQueryService.fingerprint(
                reportType,
                snapshot.filter(),
                snapshot.projectionVersion(),
                snapshot.scope().authorizationDigest(),
                dataSet.calculationFormulaVersion());
        List<ReportField> selectedFields = requireCurrentBinding(
                filter,
                requestedBinding,
                snapshot,
                dataSet,
                queryFingerprint);
        ReportDataSet exportDataSet = withExportFields(
                dataSet, selectedFields);
        if (!capabilityCoversVisibleExport(
                principalId,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                reportType,
                snapshot,
                exportDataSet,
                now)) {
            throw unavailable();
        }
        String visibleContentDigest =
                AttendanceReportVisibilityDigest.calculate(
                        reportType, snapshot, exportDataSet);
        DeliveryMode deliveryMode =
                dataSet.rows().size() <= SYNCHRONOUS_ROW_LIMIT
                        ? DeliveryMode.SYNC
                        : DeliveryMode.ASYNC;
        String exportId = UUID.randomUUID().toString();
        EncodedExport encoded = null;
        if (deliveryMode == DeliveryMode.SYNC) {
            try {
                encoded = encoder.encode(
                        exportDataSet,
                        new ExportContext(
                                exportId,
                                principalId,
                                normalizedPurpose,
                                snapshot.filter(),
                                snapshot.scope(),
                                snapshot.projectionVersion(),
                                dataSet.calculationFormulaVersion(),
                                snapshot.sourceVersions(),
                                snapshot.dataAsOf(),
                                snapshot.periodState(),
                                queryFingerprint,
                                visibleContentDigest,
                                now,
                                now,
                                selectedFields,
                                monthMatrixFor(reportType, snapshot)));
            } catch (RuntimeException exception) {
                throw new ApiProblemException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "ATTENDANCE_REPORT_EXPORT_BUILD_FAILED",
                        "报表导出生成失败，请稍后重试",
                        true);
            }
        }
        ExportJob job = new ExportJob(
                exportId,
                principalId,
                reportType,
                snapshot.filter(),
                normalizedPurpose,
                snapshot.projectionVersion(),
                snapshot.scope().authorizationDigest(),
                queryFingerprint,
                visibleContentDigest,
                dataSet.calculationFormulaVersion(),
                selectedFields,
                dataSet.rows().size(),
                deliveryMode,
                encoded == null
                        ? ExportStatus.QUEUED
                        : ExportStatus.READY,
                encoded == null ? null : encoded.contentType(),
                encoded == null ? null : encoded.fileExtension(),
                encoded == null ? null : sha256(encoded.content()),
                encoded == null ? 0 : encoded.content().length,
                null,
                now.plus(retention),
                now,
                encoded == null ? null : now);
        exportStore.insert(
                job, encoded == null ? null : encoded.content());
        auditService.record(
                principalId,
                "ATTENDANCE_REPORT_EXPORT_CREATED",
                "ATTENDANCE_REPORT_EXPORT",
                exportId,
                "SUCCESS",
                deliveryMode.name(),
                null,
                queryFingerprint);
        return ExportView.from(job, now);
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.READ_COMMITTED)
    public ExportView status(String exportId) {
        capabilities.require(
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE);
        requireReportOrQueryRead();
        String principalId = principalProvider.currentPrincipalId();
        ExportJob job = exportStore
                .findOwnedJob(exportId, principalId)
                .orElseThrow(AttendanceReportExportService::unavailable);
        Instant now = clock.instant();
        requireCurrentAuthorization(
                job,
                principalId,
                now,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                "ATTENDANCE_REPORT_EXPORT_STATUS_DENIED");
        return ExportView.from(job, now);
    }

    public DownloadedExport download(String exportId) {
        return transactions.serialized(
                () -> downloadAuthorized(exportId));
    }

    private DownloadedExport downloadAuthorized(String exportId) {
        capabilities.require(
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_DOWNLOAD);
        requireReportOrQueryRead();
        String principalId = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        ExportJob job = exportStore
                .findOwnedJob(exportId, principalId)
                .orElseThrow(AttendanceReportExportService::unavailable);
        if (!job.expiresAt().isAfter(now)) {
            throw new ApiProblemException(
                    HttpStatus.GONE,
                    "ATTENDANCE_REPORT_EXPORT_EXPIRED",
                    "报表导出已过期，请重新创建");
        }
        if (job.status() != ExportStatus.READY
                || job.contentSha256() == null) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ATTENDANCE_REPORT_EXPORT_NOT_READY",
                    "报表导出尚未就绪",
                    true);
        }
        requireCurrentAuthorization(
                job,
                principalId,
                now,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_DOWNLOAD,
                "ATTENDANCE_REPORT_EXPORT_DOWNLOAD_DENIED");
        if (!job.expiresAt().isAfter(clock.instant())) {
            throw new ApiProblemException(
                    HttpStatus.GONE,
                    "ATTENDANCE_REPORT_EXPORT_EXPIRED",
                    "报表导出已过期，请重新创建");
        }
        ExportArtifact artifact = exportStore
                .findOwnedReady(
                        exportId,
                        principalId,
                        job.queryFingerprint(),
                        job.visibleContentDigest(),
                        now)
                .orElseThrow(AttendanceReportExportService::unavailable);
        if (artifact.job().status() != ExportStatus.READY
                || !constantTimeEquals(
                        job.queryFingerprint(),
                        artifact.job().queryFingerprint())
                || !constantTimeEquals(
                        job.visibleContentDigest(),
                        artifact.job().visibleContentDigest())
                || !constantTimeEquals(
                        job.contentSha256(),
                        artifact.job().contentSha256())
                || artifact.content() == null) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ATTENDANCE_REPORT_EXPORT_CORRUPTED",
                    "报表导出校验失败，请重新创建");
        }
        byte[] content = artifact.content();
        if (!constantTimeEquals(job.contentSha256(), sha256(content))
                || job.contentLength() != content.length) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ATTENDANCE_REPORT_EXPORT_CORRUPTED",
                    "报表导出校验失败，请重新创建");
        }
        auditService.record(
                principalId,
                "ATTENDANCE_REPORT_EXPORT_DOWNLOADED",
                "ATTENDANCE_REPORT_EXPORT",
                exportId,
                "SUCCESS",
                null,
                job.queryFingerprint(),
                job.contentSha256());
        return new DownloadedExport(
                job.reportType().name().toLowerCase(
                                java.util.Locale.ROOT)
                        + "-"
                        + job.filter().period()
                        + "."
                        + job.fileExtension(),
                job.contentType(),
                content);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean processNextQueued() {
        Instant now = clock.instant();
        var claimed = exportStore.claimNextQueued(now);
        if (claimed.isEmpty()) {
            return false;
        }
        ExportJob job = claimed.orElseThrow();
        Set<String> activeCapabilities = capabilities.activeCapabilities(
                job.principalId(), now);
        if (!activeCapabilities.contains(
                        CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE)
                || (!activeCapabilities.contains(
                        CapabilityCodes.ATTENDANCE_REPORT_READ)
                        && !activeCapabilities.contains(
                                CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ))) {
            failBuild(job, "AUTHORIZATION_OR_SOURCE_CHANGED");
            return true;
        }
        var snapshot = loadSnapshot(
                job.principalId(),
                job.filter(),
                job.projectionVersion(),
                now,
                snapshotReadCapability(activeCapabilities));
        if (snapshot.isEmpty()) {
            failBuild(job, "AUTHORIZATION_OR_SOURCE_CHANGED");
            return true;
        }
        var authorizedSnapshot = snapshot.orElseThrow();
        EncodedExport encoded;
        try {
            var dataSet = calculator.calculate(
                    job.reportType(), authorizedSnapshot);
            if (!authorizedSnapshot.filter().equals(job.filter())
                    || !job.projectionVersion().equals(
                            authorizedSnapshot.projectionVersion())
                    || !constantTimeEquals(
                            job.authorizationDigest(),
                            authorizedSnapshot
                                    .scope()
                                    .authorizationDigest())
                    || !exportFieldsAreCurrentlyAllowed(
                            job.exportFields(),
                            dataSet.exportAllowlist())) {
                failBuild(job, "AUTHORIZATION_OR_SOURCE_CHANGED");
                return true;
            }
            ReportDataSet exportDataSet = withExportFields(
                    dataSet, job.exportFields());
            if (!capabilityCoversVisibleExport(
                    job.principalId(),
                    CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                    job.reportType(),
                    authorizedSnapshot,
                    exportDataSet,
                    now)) {
                failBuild(job, "AUTHORIZATION_OR_SOURCE_CHANGED");
                return true;
            }
            String currentFingerprint =
                    AttendanceReportQueryService.fingerprint(
                            job.reportType(),
                            authorizedSnapshot.filter(),
                            authorizedSnapshot.projectionVersion(),
                            authorizedSnapshot
                                    .scope()
                                    .authorizationDigest(),
                            dataSet.calculationFormulaVersion());
            String currentVisibleContentDigest =
                    AttendanceReportVisibilityDigest.calculate(
                            job.reportType(),
                            authorizedSnapshot,
                            exportDataSet);
            if (!constantTimeEquals(
                            job.queryFingerprint(), currentFingerprint)
                    || !constantTimeEquals(
                            job.visibleContentDigest(),
                            currentVisibleContentDigest)
                    || job.rowCount() != dataSet.rows().size()) {
                failBuild(job, "AUTHORIZATION_OR_SOURCE_CHANGED");
                return true;
            }
            encoded = encoder.encode(
                    exportDataSet,
                    new ExportContext(
                            job.exportId(),
                            job.principalId(),
                            job.purpose(),
                            job.filter(),
                            authorizedSnapshot.scope(),
                            job.projectionVersion(),
                            job.formulaVersion(),
                            authorizedSnapshot.sourceVersions(),
                            authorizedSnapshot.dataAsOf(),
                            authorizedSnapshot.periodState(),
                            job.queryFingerprint(),
                            job.visibleContentDigest(),
                            job.createdAt(),
                            clock.instant(),
                            job.exportFields(),
                            monthMatrixFor(job.reportType(), authorizedSnapshot)));
        } catch (RuntimeException exception) {
            failBuild(job, "EXPORT_BUILD_FAILED");
            return true;
        }
        exportStore.markReady(
                job.exportId(),
                encoded.content(),
                encoded.contentType(),
                encoded.fileExtension(),
                sha256(encoded.content()),
                job.visibleContentDigest(),
                clock.instant());
        auditService.record(
                job.principalId(),
                "ATTENDANCE_REPORT_EXPORT_BUILT",
                "ATTENDANCE_REPORT_EXPORT",
                job.exportId(),
                "SUCCESS",
                null);
        return true;
    }

    @Transactional
    public int purgeExpiredExports() {
        return exportStore.purgeExpired(clock.instant(), 100);
    }

    private void failBuild(ExportJob job, String failureCode) {
        exportStore.markFailed(
                job.exportId(), failureCode, clock.instant());
        auditService.record(
                job.principalId(),
                "ATTENDANCE_REPORT_EXPORT_BUILD_FAILED",
                "ATTENDANCE_REPORT_EXPORT",
                job.exportId(),
                "FAILURE",
                failureCode);
    }

    private void requireCurrentAuthorization(
            ExportJob job,
            String principalId,
            Instant now,
            String requiredCapabilityCode,
            String deniedAuditAction) {
        var currentSnapshotResult = loadSnapshot(
                principalId,
                job.filter(),
                job.projectionVersion(),
                now);
        if (currentSnapshotResult.isEmpty()) {
            auditService.recordFailure(
                    principalId,
                    deniedAuditAction,
                    "ATTENDANCE_REPORT_EXPORT",
                    job.exportId(),
                    "DENIED",
                    "AUTHORIZATION_OR_SOURCE_CHANGED");
            throw unavailable();
        }
        var currentSnapshot = currentSnapshotResult.orElseThrow();
        var currentDataSet =
                calculator.calculate(job.reportType(), currentSnapshot);
        if (!currentSnapshot.filter().equals(job.filter())
                || !job.projectionVersion().equals(
                        currentSnapshot.projectionVersion())
                || !constantTimeEquals(
                        job.authorizationDigest(),
                        currentSnapshot.scope().authorizationDigest())
                || !exportFieldsAreCurrentlyAllowed(
                        job.exportFields(),
                        currentDataSet.exportAllowlist())) {
            denyCurrentAuthorization(job, principalId, deniedAuditAction);
        }
        ReportDataSet currentExportDataSet = withExportFields(
                currentDataSet, job.exportFields());
        if (!capabilityCoversVisibleExport(
                principalId,
                requiredCapabilityCode,
                job.reportType(),
                currentSnapshot,
                currentExportDataSet,
                now)) {
            denyCurrentAuthorization(
                    job, principalId, deniedAuditAction);
        }
        String currentFingerprint =
                AttendanceReportQueryService.fingerprint(
                        job.reportType(),
                        currentSnapshot.filter(),
                        currentSnapshot.projectionVersion(),
                        currentSnapshot.scope().authorizationDigest(),
                        currentDataSet.calculationFormulaVersion());
        String currentVisibleContentDigest =
                AttendanceReportVisibilityDigest.calculate(
                        job.reportType(),
                        currentSnapshot,
                        currentExportDataSet);
        if (!constantTimeEquals(
                        job.queryFingerprint(), currentFingerprint)
                || !constantTimeEquals(
                        job.visibleContentDigest(),
                        currentVisibleContentDigest)
                || job.rowCount() != currentDataSet.rows().size()) {
            denyCurrentAuthorization(job, principalId, deniedAuditAction);
        }
    }

    private boolean capabilityCoversVisibleExport(
            String principalId,
            String capabilityCode,
            ReportType reportType,
            ReportSourceSnapshot readSnapshot,
            ReportDataSet readExportDataSet,
            Instant authorizationTime) {
        if (realtimeSnapshots != null) {
            String readCapability = snapshotReadCapability(
                    capabilities.activeCapabilities(
                            principalId, authorizationTime));
            return realtimeSnapshots.loadAuthorizedSnapshot(
                            principalId,
                            readCapability,
                            readSnapshot.filter(),
                            readSnapshot.projectionVersion(),
                            authorizationTime)
                    .map(candidate -> {
                        ReportDataSet candidateDataSet = calculator.calculate(
                                reportType, candidate);
                        if (!exportFieldsAreCurrentlyAllowed(
                                readExportDataSet.exportAllowlist(),
                                candidateDataSet.exportAllowlist())) {
                            return false;
                        }
                        return AttendanceReportVisibilityDigest
                                .calculateScopeIndependent(
                                        reportType,
                                        readSnapshot,
                                        readExportDataSet)
                                .equals(AttendanceReportVisibilityDigest
                                        .calculateScopeIndependent(
                                                reportType,
                                                candidate,
                                                withExportFields(
                                                        candidateDataSet,
                                                        readExportDataSet
                                                                .exportAllowlist())));
                    })
                    .orElse(false);
        }
        return sourceRepository.loadAuthorizedSnapshotIntersection(
                        principalId,
                        capabilityCode,
                        readSnapshot,
                        readExportDataSet.rows().isEmpty(),
                        authorizationTime)
                .map(candidate -> {
                    ReportDataSet candidateDataSet = calculator.calculate(
                            reportType, candidate);
                    if (!exportFieldsAreCurrentlyAllowed(
                            readExportDataSet.exportAllowlist(),
                            candidateDataSet.exportAllowlist())) {
                        return false;
                    }
                    ReportDataSet candidateExportDataSet = withExportFields(
                            candidateDataSet,
                            readExportDataSet.exportAllowlist());
                    String expected = AttendanceReportVisibilityDigest
                            .calculateScopeIndependent(
                                    reportType,
                                    readSnapshot,
                                    readExportDataSet);
                    String actual = AttendanceReportVisibilityDigest
                            .calculateScopeIndependent(
                                    reportType,
                                    candidate,
                                    candidateExportDataSet);
                    return constantTimeEquals(expected, actual);
                })
                .orElse(false);
    }

    private static AttendanceMonthMatrixPage monthMatrixFor(
            ReportType reportType, ReportSourceSnapshot snapshot) {
        if (reportType != ReportType.ATTENDANCE_DETAIL || snapshot == null) {
            return null;
        }
        try {
            var matrix = AttendanceMonthMatrixAssembler.assemble(snapshot);
            return new AttendanceMonthMatrixPage(
                    snapshot.projectionVersion(),
                    snapshot.scope().authorizationDigest(),
                    AttendanceMonthMatrixAssembler.FORMULA_VERSION,
                    snapshot.periodState(),
                    snapshot.dataAsOf(),
                    snapshot.sourceVersions(),
                    snapshot.scope(),
                    snapshot.filter(),
                    List.of(),
                    matrix.dates(),
                    matrix.rows(),
                    0,
                    Math.max(1, matrix.rows().size()),
                    matrix.rows().size(),
                    matrix.rows().isEmpty() ? 0 : 1,
                    false);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private List<ReportField> requireCurrentBinding(
            ReportFilter requestedFilter,
            RequestedExportBinding requestedBinding,
            ReportSourceSnapshot snapshot,
            ReportDataSet dataSet,
            String currentQueryFingerprint) {
        if (!snapshot.filter().equals(requestedFilter)
                || !snapshot.projectionVersion().equals(
                        requestedBinding.projectionVersion())
                || !snapshot.scope().reference().equals(
                        requestedBinding.scopeReference())
                || !snapshot.scope().reference().equals(
                        requestedBinding.filterScopeReference())
                || !constantTimeEquals(
                        currentQueryFingerprint,
                        requestedBinding.queryFingerprint())) {
            throw bindingStale();
        }
        var fieldsByKey = new HashMap<String, ReportField>();
        for (ReportField field : dataSet.exportAllowlist()) {
            fieldsByKey.put(field.key(), field);
        }
        var selectedFields = new ArrayList<ReportField>();
        for (String key : requestedBinding.selectedFieldKeys()) {
            ReportField field = fieldsByKey.get(key);
            if (field == null) {
                throw bindingStale();
            }
            selectedFields.add(field);
        }
        return List.copyOf(selectedFields);
    }

    private static boolean exportFieldsAreCurrentlyAllowed(
            List<ReportField> selectedFields,
            List<ReportField> currentAllowlist) {
        return selectedFields != null
                && !selectedFields.isEmpty()
                && new HashSet<>(selectedFields).size()
                        == selectedFields.size()
                && currentAllowlist.containsAll(selectedFields);
    }

    private static ReportDataSet withExportFields(
            ReportDataSet dataSet, List<ReportField> selectedFields) {
        return new ReportDataSet(
                dataSet.type(),
                dataSet.title(),
                dataSet.columns(),
                selectedFields,
                dataSet.rows(),
                dataSet.calculationFormulaVersion());
    }

    private void denyCurrentAuthorization(
            ExportJob job,
            String principalId,
            String deniedAuditAction) {
        auditService.recordFailure(
                principalId,
                deniedAuditAction,
                "ATTENDANCE_REPORT_EXPORT",
                job.exportId(),
                "DENIED",
                "AUTHORIZATION_OR_SOURCE_CHANGED");
        throw unavailable();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "required report export digest is unavailable",
                    exception);
        }
    }

    private static boolean constantTimeEquals(
            String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private void requireReportOrQueryRead() {
        java.util.Set<String> active = capabilities.currentCapabilities();
        if (!active.contains(CapabilityCodes.ATTENDANCE_REPORT_READ)
                && !active.contains(
                        CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "required capability is not granted");
        }
    }

    private String snapshotReadCapability() {
        return snapshotReadCapability(capabilities.currentCapabilities());
    }

    private static String snapshotReadCapability(java.util.Set<String> active) {
        if (active.contains(CapabilityCodes.ATTENDANCE_REPORT_READ)) {
            return CapabilityCodes.ATTENDANCE_REPORT_READ;
        }
        return CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ;
    }

    private java.util.Optional<ReportSourceSnapshot> loadSnapshot(
            String principalId,
            ReportFilter filter,
            Instant authorizationTime) {
        return loadSnapshot(
                principalId,
                filter,
                null,
                authorizationTime,
                snapshotReadCapability());
    }

    private java.util.Optional<ReportSourceSnapshot> loadSnapshot(
            String principalId,
            ReportFilter filter,
            String expectedSnapshotVersion,
            Instant authorizationTime) {
        return loadSnapshot(
                principalId,
                filter,
                expectedSnapshotVersion,
                authorizationTime,
                snapshotReadCapability());
    }

    private java.util.Optional<ReportSourceSnapshot> loadSnapshot(
            String principalId,
            ReportFilter filter,
            String expectedSnapshotVersion,
            Instant authorizationTime,
            String capability) {
        if (realtimeSnapshots != null) {
            return realtimeSnapshots.loadAuthorizedSnapshot(
                    principalId,
                    capability,
                    filter,
                    expectedSnapshotVersion,
                    authorizationTime);
        }
        return sourceRepository.loadAuthorizedSnapshot(
                principalId,
                capability,
                filter,
                authorizationTime);
    }

    private static ApiProblemException notReady() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "ATTENDANCE_REPORT_SNAPSHOT_NOT_READY",
                "当前期间尚不能安全导出实时报表",
                true);
    }

    private static ApiProblemException bindingStale() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "ATTENDANCE_REPORT_EXPORT_BINDING_STALE",
                "报表已更新或导出条件不再匹配，请刷新报表后重试",
                true);
    }

    private static ApiProblemException unavailable() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_AVAILABLE",
                "请求的资源不可用");
    }

    public record ExportView(
            String exportId,
            ReportType reportType,
            YearMonth period,
            String companyId,
            String deliveryMode,
            String status,
            String purpose,
            long rowCount,
            Instant expiresAt,
            Instant completedAt) {

        static ExportView from(ExportJob job, Instant now) {
            return new ExportView(
                    job.exportId(),
                    job.reportType(),
                    job.filter().period(),
                    job.filter().companyId(),
                    job.deliveryMode().name(),
                    job.expiresAt().isAfter(now)
                            ? job.status().name()
                            : "EXPIRED",
                    job.purpose(),
                    job.rowCount(),
                    job.expiresAt(),
                    job.completedAt());
        }
    }

    public record DownloadedExport(
            String fileName, String contentType, byte[] content) {

        public DownloadedExport {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public record RequestedExportBinding(
            String projectionVersion,
            String queryFingerprint,
            String scopeReference,
            String filterScopeReference,
            List<String> selectedFieldKeys) {

        public RequestedExportBinding {
            requireSafeText(projectionVersion, "projectionVersion", 128);
            requireDigest(queryFingerprint, "queryFingerprint");
            requireSafeText(scopeReference, "scopeReference", 128);
            requireSafeText(
                    filterScopeReference, "filterScopeReference", 128);
            selectedFieldKeys = List.copyOf(Objects.requireNonNull(
                    selectedFieldKeys, "selectedFieldKeys"));
            if (selectedFieldKeys.isEmpty()
                    || selectedFieldKeys.size() > 64
                    || new HashSet<>(selectedFieldKeys).size()
                            != selectedFieldKeys.size()) {
                throw new IllegalArgumentException(
                        "selected export fields are invalid");
            }
            for (String field : selectedFieldKeys) {
                requireSafeText(field, "selectedField", 64);
            }
        }

        private static void requireSafeText(
                String value, String field, int maximumLength) {
            if (value == null
                    || value.isBlank()
                    || value.length() > maximumLength
                    || !value.equals(value.strip())
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        field + " is invalid");
            }
        }

        private static void requireDigest(String value, String field) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        field + " must be a lowercase SHA-256 digest");
            }
        }
    }
}
