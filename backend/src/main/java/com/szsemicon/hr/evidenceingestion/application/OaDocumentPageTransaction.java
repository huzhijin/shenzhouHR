package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.CommittedPage;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.SourceJobStart;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceResolutionPolicy;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.DocumentType;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OaDocumentRecord;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.SourceStatus;
import com.szsemicon.hr.evidenceingestion.port.OaOrgMemberDirectoryPort;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits one page of OA attendance documents into the evidence chain.
 *
 * <p>For each {@link OaDocumentRecord} the transaction:
 * <ol>
 *   <li>Checks for an existing raw fact with the same source identity — safe
 *       replay.</li>
 *   <li>Resolves the employee by {@code employeeNumber} (the OA
 *       {@code org_member.code} value).</li>
 *   <li>Inserts {@code raw_attendance_fact} → {@code normalized_attendance_record}
 *       → {@code employee_match_decision} following the same chain as Deli punch
 *       ingestion.</li>
 *   <li>Inserts {@code oa_attendance_document} using the new normalized-record
 *       ID as the FK.</li>
 * </ol>
 *
 * <p>Records that fail employee or classification resolution are quarantined
 * with raw + normalized rows ({@code validation_status = 'QUARANTINED'}), so
 * the sync can continue without losing their source payload identity. Rows
 * lacking a stable source version or interval cannot satisfy the evidence
 * schema; they are counted as quarantined and the atomically committed page
 * cursor advances past them instead of looping forever.
 */
@Service
public class OaDocumentPageTransaction {

    private static final Logger log =
            LoggerFactory.getLogger(OaDocumentPageTransaction.class);

    private static final String SCHEMA_VERSION = "OA_DOCUMENT_V1";
    private static final String FACT_KIND = "OA_DOCUMENT";
    private static final String RECORD_KIND = "OA_INTERVAL";
    private static final String OA_CONTEXT_ACTIVATION = "ACTIVATED";
    private static final String UNRESOLVED_OVERTIME_TREATMENT = "UNKNOWN";

    private final AttendanceSourceSyncRepository syncRepository;
    private final AttendanceEvidenceRepository evidenceRepository;
    private final EmployeeEmploymentResolverPort employeeResolver;
    private final OaOrgMemberDirectoryPort memberDirectory;
    private final Clock clock;

    @Autowired
    public OaDocumentPageTransaction(
            AttendanceSourceSyncRepository syncRepository,
            AttendanceEvidenceRepository evidenceRepository,
            EmployeeEmploymentResolverPort employeeResolver,
            ObjectProvider<OaOrgMemberDirectoryPort> memberDirectory,
            Clock clock) {
        this.syncRepository = syncRepository;
        this.evidenceRepository = evidenceRepository;
        this.employeeResolver = employeeResolver;
        this.memberDirectory = memberDirectory == null
                ? null
                : memberDirectory.getIfAvailable();
        this.clock = clock;
    }

    OaDocumentPageTransaction(
            AttendanceSourceSyncRepository syncRepository,
            AttendanceEvidenceRepository evidenceRepository,
            EmployeeEmploymentResolverPort employeeResolver,
            OaOrgMemberDirectoryPort memberDirectory,
            Clock clock) {
        this.syncRepository = syncRepository;
        this.evidenceRepository = evidenceRepository;
        this.employeeResolver = employeeResolver;
        this.memberDirectory = memberDirectory;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PageCommitResult commitPage(
            SourceJobStart job,
            String principalId,
            String correlationId,
            String executionCapability,
            int pageNumber,
            OaAttendanceDocumentSourcePort.OaPage page) {

        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(page, "page");
        requireReference(principalId, 36, "OA_SYNC_ACTOR_INVALID");
        requireReference(correlationId, 64, "OA_SYNC_REQUEST_INVALID");
        if (!CapabilityCodes.ATTENDANCE_SOURCE_RUN.equals(executionCapability)
                && !CapabilityCodes.ATTENDANCE_SOURCE_RETRY.equals(executionCapability)) {
            throw new AttendanceSourceSyncFailure("OA_SYNC_CAPABILITY_INVALID");
        }
        if (pageNumber < 1) {
            throw new AttendanceSourceSyncFailure("OA_PAGE_NUMBER_INVALID");
        }
        validatePage(page);

        Instant committedAt = clock.instant();
        var pageState = "SYSTEM".equals(principalId)
                ? syncRepository.lockSystemOaPageForCommit(
                        job.jobId(), job.sourceId())
                : syncRepository.lockOaPageForCommit(
                        job.jobId(),
                        job.sourceId(),
                        principalId,
                        executionCapability,
                        committedAt);
        if (pageState == null) {
            throw new AttendanceSourceSyncFailure(
                    "ATTENDANCE_SOURCE_SCOPE_REVOKED");
        }
        if (!"RUNNING".equals(pageState.jobStatus())
                || pageState.committedPages() + 1 != pageNumber
                || !sameCursor(
                        pageState.committedCursor(), page.inputCursor())) {
            throw new AttendanceSourceSyncFailure("OA_WATERMARK_STALE");
        }

        int accepted = 0;
        int quarantined = 0;

        for (OaDocumentRecord record : page.records()) {
            RecordOutcome outcome = ingestRecord(
                    job, principalId, correlationId, record, committedAt);
            if (outcome == RecordOutcome.ACCEPTED) {
                accepted++;
            } else {
                quarantined++;
            }
        }

        // OA pages deliberately allow an all-quarantined outcome: one malformed
        // source row must not pin the durable cursor and poison every later run.
        // The complete page, its quarantine counts, and the watermark still
        // commit atomically under the locked job/watermark version.
        syncRepository.insertCommittedPage(new CommittedPage(
                UUID.randomUUID().toString(),
                job.jobId(),
                pageNumber,
                page.inputCursor(),
                page.nextCursor(),
                page.records().size(),
                accepted,
                quarantined,
                page.pageDigest(),
                correlationId,
                committedAt));
        syncRepository.advanceWatermark(
                job.sourceId(),
                pageState.watermarkVersion(),
                page.nextCursor(),
                page.pageDigest(),
                committedAt);
        syncRepository.incrementJobCounters(job.jobId(), accepted, quarantined);
        return new PageCommitResult(accepted, quarantined);
    }

    /**
     * Re-ingest already-seen OA records without touching the durable cursor.
     * Quarantined rows rematch; accepted overtime rows backfill context.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PageCommitResult rematchRecords(
            SourceJobStart job,
            String principalId,
            String requestId,
            String executionCapability,
            List<OaDocumentRecord> records) {
        Objects.requireNonNull(job, "job");
        requireReference(principalId, 36, "OA_SYNC_ACTOR_INVALID");
        requireReference(requestId, 64, "OA_SYNC_REQUEST_INVALID");
        if (!CapabilityCodes.ATTENDANCE_SOURCE_RUN.equals(executionCapability)
                && !CapabilityCodes.ATTENDANCE_SOURCE_RETRY.equals(
                        executionCapability)) {
            throw new AttendanceSourceSyncFailure("OA_SYNC_CAPABILITY_INVALID");
        }
        Instant receivedAt = clock.instant();
        int accepted = 0;
        int quarantined = 0;
        List<OaDocumentRecord> incoming =
                records == null ? List.<OaDocumentRecord>of() : records;
        for (OaDocumentRecord record : incoming) {
            RecordOutcome outcome = ingestRecord(
                    job, principalId, requestId, record, receivedAt);
            if (outcome == RecordOutcome.ACCEPTED) {
                accepted++;
            } else {
                quarantined++;
            }
        }
        syncRepository.incrementJobCounters(job.jobId(), accepted, quarantined);
        return new PageCommitResult(accepted, quarantined);
    }

    // ------------------------------------------------------------------

    private RecordOutcome ingestRecord(
            SourceJobStart job,
            String principalId,
            String requestId,
            OaDocumentRecord record,
            Instant receivedAt) {

        if (record.sourceBusinessKey() == null
                || record.sourceVersion() == null
                || record.sourceVersion().isBlank()
                || record.start() == null
                || record.end() == null
                || record.end().isBefore(record.start())) {
            return RecordOutcome.QUARANTINED;
        }
        boolean interval = record.end().isAfter(record.start());

        boolean overtimeTypeUnclassified =
                record.documentType() == DocumentType.OVERTIME
                        && !record.hasValidOvertimeClassification();
        boolean leaveTypeUnclassified =
                record.documentType() == DocumentType.LEAVE
                        && !record.hasValidLeaveClassification();
        String rawDigest = rawDigest(job, record);

        // Idempotency: same source identity → safe replay
        var existing = evidenceRepository.findRawBySourceIdentity(
                job.sourceId(),
                record.sourceBusinessKey(),
                record.sourceVersion());
        if (existing != null) {
            String existingDocId = evidenceRepository.findOaAttendanceDocumentId(
                    job.sourceId(),
                    record.sourceBusinessKey(),
                    record.sourceVersion());
            boolean sameDigest = rawDigest.equals(existing.canonicalPayloadDigest());
            if (!sameDigest && existingDocId != null) {
                // Same OA identity already has an effective document. A later
                // ingest formula (leave catalog, empno) can change the digest
                // without OA bumping source_version; failing the whole job
                // leaves later streams (August overtime) unscanned. Keep the
                // first write and continue the page.
                log.warn(
                        "OA source identity digest changed; keeping existing document sourceId={} key={} version={}",
                        job.sourceId(),
                        record.sourceBusinessKey(),
                        record.sourceVersion());
                ensureOvertimeContext(job, record, receivedAt);
                return RecordOutcome.ACCEPTED;
            }
            if (overtimeTypeUnclassified || leaveTypeUnclassified) {
                return RecordOutcome.QUARANTINED;
            }
            if (existingDocId != null) {
                ensureOvertimeContext(job, record, receivedAt);
                return RecordOutcome.ACCEPTED;
            }
            return rematchQuarantinedEmployee(
                    job, record, existing, receivedAt, interval);
        }

        String runtimeContractRevisionId =
                findPublishedOvertimeRuntimeContract(
                        job.sourceId(), record, overtimeTypeUnclassified);

        EvidenceResolutionPolicy.MatchDecision decision =
                resolveEmployee(job, record);

        boolean employeeUnresolved =
                decision.status() != EvidenceResolutionPolicy.MatchStatus.MATCHED;
        boolean quarantined = employeeUnresolved
                || overtimeTypeUnclassified
                || leaveTypeUnclassified;

        String rawId = UUID.randomUUID().toString();
        String normalizedId = UUID.randomUUID().toString();
        String matchId = UUID.randomUUID().toString();
        String oaDocId = UUID.randomUUID().toString();

        String normalizedDigest = AttendanceEvidenceDigests.sha256(
                "OA_NORMALIZED_V1",
                rawDigest,
                record.start().toString(),
                record.end().toString(),
                record.documentType().name(),
                overtimeTypeName(record),
                leaveTypeName(record));

        String resolverDigest = employeeUnresolved
                ? AttendanceEvidenceDigests.sha256(
                        "OA_UNRESOLVED_MATCH_V1",
                        job.companyId(),
                        record.employeeNumber() != null ? record.employeeNumber() : "",
                        record.start().toString(),
                        decision.status().name(),
                        decision.reason())
                : decision.resolverSnapshotDigest();

        // 1. raw_attendance_fact
        evidenceRepository.insertRawFact(new EvidenceRows.RawFactRow(
                rawId,
                job.sourceId(),
                job.companyId(),
                FACT_KIND,
                record.sourceBusinessKey(),
                record.sourceVersion(),
                null,
                record.start().toString(),
                record.sourceTimeZone(),
                interval ? null : record.start(),
                interval ? record.start() : null,
                interval ? record.end() : null,
                rawDigest,
                null,
                requestId,
                receivedAt,
                principalId));

        // 2. normalized_attendance_record
        evidenceRepository.insertNormalizedRecord(new EvidenceRows.NormalizedRecordRow(
                normalizedId,
                rawId,
                1,
                SCHEMA_VERSION,
                RECORD_KIND,
                null,           // OA docs have no punch direction
                interval ? null : record.start(),
                interval ? record.start() : null,
                interval ? record.end() : null,
                quarantined ? "QUARANTINED" : "VALID",
                overtimeTypeUnclassified
                        ? "OVERTIME_TYPE_UNCLASSIFIED"
                        : leaveTypeUnclassified
                                ? "LEAVE_TYPE_UNCLASSIFIED"
                        : quarantined ? issueCode(decision.status()) : null,
                normalizedDigest,
                null,
                receivedAt));

        // 3. employee_match_decision
        evidenceRepository.insertMatchDecision(new EvidenceRows.MatchDecisionRow(
                matchId,
                normalizedId,
                decision.status().name(),
                decision.reason(),
                decision.employeeId(),
                decision.employmentPeriodId(),
                null,
                resolverDigest,
                receivedAt));

        if (quarantined) {
            return RecordOutcome.QUARANTINED;
        }

        // 4. oa_attendance_document (only when employee resolved and the
        // overtime classification, when applicable, is recognized)
        Instant approvedAt = record.approvedAt();
        Instant revokedAt = record.revokedAt();
        long knowledgeRank = receivedAt.toEpochMilli();

        evidenceRepository.insertOaAttendanceDocument(new EvidenceRows.OaDocumentRow(
                oaDocId,
                job.sourceId(),
                record.sourceBusinessKey(),
                record.sourceVersion(),
                record.documentType().name(),
                record.overtimeType(),
                record.leaveType(),
                record.sourceStatus().name(),
                normalizedId,
                knowledgeRank,
                null,
                record.firstSubmittedAt(),
                approvedAt,
                record.modifiedAt(),
                revokedAt,
                receivedAt,
                record.leaveSerial(),
                record.originalLeaveSerial()));

        if (runtimeContractRevisionId != null) {
            evidenceRepository.insertOaAttendanceDocumentContext(
                    overtimeContext(
                            job,
                            record,
                            oaDocId,
                            runtimeContractRevisionId,
                            receivedAt));
        }

        return RecordOutcome.ACCEPTED;
    }

    private RecordOutcome rematchQuarantinedEmployee(
            SourceJobStart job,
            OaDocumentRecord record,
            EvidenceRows.RawFactRow existing,
            Instant receivedAt,
            boolean interval) {
        EvidenceResolutionPolicy.MatchDecision decision = resolveEmployee(job, record);
        if (decision.status() != EvidenceResolutionPolicy.MatchStatus.MATCHED) {
            return RecordOutcome.QUARANTINED;
        }
        var current = evidenceRepository.findReplayStateBySourceIdentity(
                job.sourceId(),
                record.sourceBusinessKey(),
                record.sourceVersion());
        int nextRevision = current == null || current.normalizationRevision() == null
                ? 1
                : current.normalizationRevision() + 1;
        String normalizedId = UUID.randomUUID().toString();
        String matchId = UUID.randomUUID().toString();
        String oaDocId = UUID.randomUUID().toString();
        String rawDigest = existing.canonicalPayloadDigest();
        String normalizedDigest = AttendanceEvidenceDigests.sha256(
                "OA_NORMALIZED_V1",
                rawDigest,
                record.start().toString(),
                record.end().toString(),
                record.documentType().name(),
                overtimeTypeName(record),
                leaveTypeName(record),
                "REMATCH",
                String.valueOf(nextRevision));
        evidenceRepository.insertNormalizedRecord(new EvidenceRows.NormalizedRecordRow(
                normalizedId,
                existing.rawAttendanceFactId(),
                nextRevision,
                SCHEMA_VERSION,
                RECORD_KIND,
                null,
                interval ? null : record.start(),
                interval ? record.start() : null,
                interval ? record.end() : null,
                "VALID",
                null,
                normalizedDigest,
                current == null ? null : current.normalizedAttendanceRecordId(),
                receivedAt));
        evidenceRepository.insertMatchDecision(new EvidenceRows.MatchDecisionRow(
                matchId,
                normalizedId,
                decision.status().name(),
                decision.reason(),
                decision.employeeId(),
                decision.employmentPeriodId(),
                null,
                decision.resolverSnapshotDigest(),
                receivedAt));
        evidenceRepository.insertOaAttendanceDocument(new EvidenceRows.OaDocumentRow(
                oaDocId,
                job.sourceId(),
                record.sourceBusinessKey(),
                record.sourceVersion(),
                record.documentType().name(),
                record.overtimeType(),
                record.leaveType(),
                record.sourceStatus().name(),
                normalizedId,
                receivedAt.toEpochMilli(),
                null,
                record.firstSubmittedAt(),
                record.approvedAt(),
                record.modifiedAt(),
                record.revokedAt(),
                receivedAt,
                record.leaveSerial(),
                record.originalLeaveSerial()));
        String runtimeContractRevisionId =
                findPublishedOvertimeRuntimeContract(job.sourceId(), record, false);
        if (runtimeContractRevisionId != null) {
            evidenceRepository.insertOaAttendanceDocumentContext(
                    overtimeContext(
                            job,
                            record,
                            oaDocId,
                            runtimeContractRevisionId,
                            receivedAt));
        }
        return RecordOutcome.ACCEPTED;
    }

    private EvidenceResolutionPolicy.MatchDecision resolveEmployee(
            SourceJobStart job, OaDocumentRecord record) {
        String employeeNumber =
                com.szsemicon.hr.evidenceingestion.domain.oa.OaEmployeeNumberCatalog
                        .relocateOvertimeEmployeeNumber(
                                record.employeeNumber(),
                                record.start(),
                                record.end());
        return EvidenceResolutionPolicy.resolve(
                employeeResolver,
                job.sourceId(),
                job.companyId(),
                employeeNumber,
                null,
                null,
                record.externalPersonRef(),
                null,
                memberDisplayName(record.externalPersonRef()),
                record.start());
    }

    private String memberDisplayName(String memberId) {
        if (memberDirectory == null || memberId == null || memberId.isBlank()) {
            return null;
        }
        try {
            List<OaOrgMemberDirectoryPort.OrgMemberRecord> matches =
                    memberDirectory.findById(new BigInteger(memberId.trim()));
            if (matches.size() != 1) {
                return null;
            }
            String name = matches.getFirst().name();
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String rawDigest(SourceJobStart job, OaDocumentRecord record) {
        return AttendanceEvidenceDigests.sha256(
                "OA_RAW_FACT_V1",
                job.sourceId(),
                job.companyId(),
                record.sourceBusinessKey(),
                record.sourceVersion(),
                record.externalPersonRef() != null ? record.externalPersonRef() : "",
                record.employeeNumber() != null ? record.employeeNumber() : "",
                record.start() != null ? record.start().toString() : "",
                record.end() != null ? record.end().toString() : "",
                record.sourceTimeZone() != null ? record.sourceTimeZone() : "",
                record.documentType().name(),
                record.sourceStatus().name(),
                overtimeTypeName(record),
                leaveTypeName(record));
    }

    private static String overtimeTypeName(OaDocumentRecord record) {
        return record.overtimeType() != null
                ? record.overtimeType().name()
                : "";
    }

    private static String leaveTypeName(OaDocumentRecord record) {
        return record.leaveType() != null
                ? record.leaveType().name()
                : "";
    }

    private String findPublishedOvertimeRuntimeContract(
            String sourceId,
            OaDocumentRecord record,
            boolean overtimeTypeUnclassified) {
        boolean approvedClassifiedOvertime =
                record.documentType() == DocumentType.OVERTIME
                        && record.sourceStatus() == SourceStatus.APPROVED
                        && !overtimeTypeUnclassified;
        if (!approvedClassifiedOvertime) {
            return null;
        }
        return evidenceRepository
                .findLatestPublishedOaRuntimeContractRevisionId(sourceId);
    }

    /**
     * Seeyon overtime form IDs are signed and often negative. New pages write
     * context in the first insert; replay fills context that was skipped when
     * no published runtime contract existed yet.
     */
    private void ensureOvertimeContext(
            SourceJobStart job,
            OaDocumentRecord record,
            Instant createdAt) {
        if (record.documentType() != DocumentType.OVERTIME
                || record.sourceStatus() != SourceStatus.APPROVED
                || !record.hasValidOvertimeClassification()) {
            return;
        }
        String runtimeContractRevisionId =
                evidenceRepository.findLatestPublishedOaRuntimeContractRevisionId(
                        job.sourceId());
        if (runtimeContractRevisionId == null) {
            return;
        }
        String documentId = evidenceRepository.findOaAttendanceDocumentId(
                job.sourceId(),
                record.sourceBusinessKey(),
                record.sourceVersion());
        if (documentId == null
                || evidenceRepository.hasOaAttendanceDocumentContext(documentId)) {
            return;
        }
        evidenceRepository.insertOaAttendanceDocumentContext(
                overtimeContext(
                        job,
                        record,
                        documentId,
                        runtimeContractRevisionId,
                        createdAt));
    }

    private static EvidenceRows.OaDocumentContextRow overtimeContext(
            SourceJobStart job,
            OaDocumentRecord record,
            String oaDocumentId,
            String runtimeContractRevisionId,
            Instant createdAt) {
        String authorizedContextJson = "{\"overtimeType\":\""
                + record.overtimeType().name()
                + "\",\"sourceStatus\":\""
                + record.sourceStatus().name()
                + "\"}";
        String contextDigest = AttendanceEvidenceDigests.sha256(
                "OA_OVERTIME_DOCUMENT_CONTEXT_V1",
                job.sourceId(),
                record.sourceBusinessKey(),
                record.sourceVersion(),
                runtimeContractRevisionId,
                OA_CONTEXT_ACTIVATION,
                record.sourceStatus().name(),
                UNRESOLVED_OVERTIME_TREATMENT,
                record.overtimeType().name(),
                "0",
                "0",
                "0",
                authorizedContextJson);
        return new EvidenceRows.OaDocumentContextRow(
                UUID.randomUUID().toString(),
                oaDocumentId,
                runtimeContractRevisionId,
                null,
                OA_CONTEXT_ACTIVATION,
                record.sourceStatus().name(),
                UNRESOLVED_OVERTIME_TREATMENT,
                record.overtimeType(),
                0,
                0,
                0,
                authorizedContextJson,
                contextDigest,
                createdAt);
    }

    private static String issueCode(EvidenceResolutionPolicy.MatchStatus status) {
        return switch (status) {
            case MATCHED -> null;
            case UNMATCHED -> "EMPLOYEE_UNMATCHED";
            case AMBIGUOUS -> "EMPLOYEE_AMBIGUOUS";
        };
    }

    private static void validatePage(
            OaAttendanceDocumentSourcePort.OaPage page) {
        if (page.records() == null
                || !isOptionalCursor(page.inputCursor())
                || !isRequiredCursor(page.nextCursor())
                || !isDigest(page.pageDigest())) {
            throw new AttendanceSourceSyncFailure("OA_PAGE_CONTRACT_INVALID");
        }
        if (Objects.equals(page.inputCursor(), page.nextCursor())) {
            throw new AttendanceSourceSyncFailure("OA_CURSOR_LOOP");
        }
    }

    private static boolean isOptionalCursor(String value) {
        return value == null || isRequiredCursor(value);
    }

    private static boolean isRequiredCursor(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 512
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void requireReference(
            String value, int maximumLength, String safeCode) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new AttendanceSourceSyncFailure(safeCode);
        }
    }

    private static boolean sameCursor(String committed, String input) {
        return Objects.equals(committed, input)
                || (committed == null && "0".equals(input));
    }

    private enum RecordOutcome {
        ACCEPTED,
        QUARANTINED
    }

    public record PageCommitResult(
            int acceptedCount,
            int quarantinedCount) {
    }
}
