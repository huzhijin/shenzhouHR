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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;
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

    private static final String SCHEMA_VERSION = "OA_DOCUMENT_V1";
    private static final String FACT_KIND = "INTERVAL_EVIDENCE";
    private static final String OA_CONTEXT_ACTIVATION = "ACTIVATED";
    private static final String UNRESOLVED_OVERTIME_TREATMENT = "UNKNOWN";

    private final AttendanceSourceSyncRepository syncRepository;
    private final AttendanceEvidenceRepository evidenceRepository;
    private final EmployeeEmploymentResolverPort employeeResolver;
    private final Clock clock;

    public OaDocumentPageTransaction(
            AttendanceSourceSyncRepository syncRepository,
            AttendanceEvidenceRepository evidenceRepository,
            EmployeeEmploymentResolverPort employeeResolver,
            Clock clock) {
        this.syncRepository = syncRepository;
        this.evidenceRepository = evidenceRepository;
        this.employeeResolver = employeeResolver;
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
                || record.end() == null) {
            return RecordOutcome.QUARANTINED;
        }

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
            if (!rawDigest.equals(existing.canonicalPayloadDigest())) {
                throw new AttendanceSourceSyncFailure("OA_SOURCE_IDENTITY_COLLISION");
            }
            return overtimeTypeUnclassified || leaveTypeUnclassified
                    ? RecordOutcome.QUARANTINED
                    : RecordOutcome.ACCEPTED;
        }

        String runtimeContractRevisionId =
                resolveRequiredOvertimeRuntimeContract(
                        job.sourceId(), record, overtimeTypeUnclassified);

        // Employee resolution — OA uses org_member.code which equals employee_number
        String employeeNumber = record.employeeNumber();
        EvidenceResolutionPolicy.MatchDecision decision =
                EvidenceResolutionPolicy.resolve(
                        employeeResolver,
                        job.sourceId(),
                        job.companyId(),
                        employeeNumber,
                        null,
                        null,
                        record.externalPersonRef(),
                        null,
                        record.start());

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
                        employeeNumber != null ? employeeNumber : "",
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
                record.start(),
                record.start(),
                record.end(),
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
                FACT_KIND,
                null,           // OA docs have no punch direction
                null,           // no point instant
                record.start(),
                record.end(),
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
                receivedAt));

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

    // ------------------------------------------------------------------

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

    private String resolveRequiredOvertimeRuntimeContract(
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
        String revisionId =
                evidenceRepository
                        .findLatestPublishedOaRuntimeContractRevisionId(
                                sourceId);
        if (revisionId == null || revisionId.isBlank()) {
            throw new AttendanceSourceSyncFailure(
                    "OA_RUNTIME_CONTRACT_NOT_PUBLISHED");
        }
        return revisionId;
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
