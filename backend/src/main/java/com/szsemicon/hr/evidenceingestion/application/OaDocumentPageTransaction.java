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
        int changed = 0;
        var evidenceAsOf = new java.util.concurrent.atomic.AtomicReference<>(clock.instant());

        for (OaDocumentRecord record : page.records()) {
            RecordOutcome outcome = ingestRecord(
                    job, principalId, correlationId, record, committedAt, evidenceAsOf);
            if (outcome.changed) { changed++; }
            if (outcome.accepted) {
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
                evidenceAsOf.get());
        syncRepository.incrementJobCounters(job.jobId(), accepted, quarantined);
        return new PageCommitResult(accepted, quarantined, changed, evidenceAsOf.get());
    }

    /**
     * Refresh all lifecycle observations without advancing the durable scan cursor.
     * Changed content appends immutable revisions; the knowledge watermark advances
     * only when new evidence or an activation context was actually written.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PageCommitResult rematchRecords(
            SourceJobStart job,
            String principalId,
            String requestId,
            String executionCapability,
            List<OaDocumentRecord> records) {
        return rematchRecords(job, principalId, requestId, executionCapability, records, 0);
    }

    /** Counts report-only exclusions in the same transaction as the refreshed evidence. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PageCommitResult rematchRecords(
            SourceJobStart job, String principalId, String requestId,
            String executionCapability, List<OaDocumentRecord> records, int isolatedCount) {
        if (isolatedCount < 0) throw new IllegalArgumentException("isolatedCount must be nonnegative");
        Objects.requireNonNull(job, "job");
        requireReference(principalId, 36, "OA_SYNC_ACTOR_INVALID");
        requireReference(requestId, 64, "OA_SYNC_REQUEST_INVALID");
        if (!CapabilityCodes.ATTENDANCE_SOURCE_RUN.equals(executionCapability)
                && !CapabilityCodes.ATTENDANCE_SOURCE_RETRY.equals(
                        executionCapability)) {
            throw new AttendanceSourceSyncFailure("OA_SYNC_CAPABILITY_INVALID");
        }
        Instant receivedAt = clock.instant();
        var pageState = "SYSTEM".equals(principalId)
                ? syncRepository.lockSystemOaPageForCommit(job.jobId(), job.sourceId())
                : syncRepository.lockOaPageForCommit(job.jobId(), job.sourceId(), principalId,
                        executionCapability, receivedAt);
        if (pageState == null || !"RUNNING".equals(pageState.jobStatus())) {
            throw new AttendanceSourceSyncFailure("ATTENDANCE_SOURCE_SCOPE_REVOKED");
        }
        int accepted = 0;
        int quarantined = isolatedCount;
        int changed = 0;
        var evidenceAsOf = new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
        List<OaDocumentRecord> incoming =
                records == null ? List.<OaDocumentRecord>of() : records;
        for (OaDocumentRecord record : incoming) {
            RecordOutcome outcome = ingestRecord(
                    job, principalId, requestId, record, receivedAt, evidenceAsOf);
            if (outcome.changed) { changed++; }
            if (outcome.accepted) {
                accepted++;
            } else {
                quarantined++;
            }
        }
        if (changed > 0) {
            // A historical refresh changes evidence knowledge, not the durable scan cursor.
            syncRepository.advanceWatermark(job.sourceId(), pageState.watermarkVersion(),
                    pageState.committedCursor(), AttendanceEvidenceDigests.sha256(
                            "OA_REFRESH_V1", job.jobId(), requestId, receivedAt.toString(),
                            Integer.toString(changed)), evidenceAsOf.get());
        }
        syncRepository.incrementJobCounters(job.jobId(), accepted, quarantined);
        return new PageCommitResult(accepted, quarantined, changed, evidenceAsOf.get());
    }

    /** Read-only early check; the transactional ingest still repeats every safety check. */
    public void preflightUnsafeChanges(String sourceId, List<OaDocumentRecord> records) {
        if (records == null) return;
        for (var incoming : records) {
            if (incoming == null || incoming.sourceBusinessKey() == null || incoming.sourceBusinessKey().isBlank()) continue;
            boolean invalidMetadata = incoming.sourceVersion() == null || incoming.sourceVersion().isBlank()
                    || incoming.documentType() == null || incoming.sourceStatus() == null;
            boolean invalidGeometry = incoming.start() == null || incoming.end() == null
                    || incoming.end().isBefore(incoming.start());
            boolean invalidClassification = !incoming.hasValidOvertimeClassification() || !incoming.hasValidLeaveClassification();
            if (!invalidMetadata && !invalidGeometry && !invalidClassification) continue;
            var resolved = evidenceRepository.findLatestOaObservation(sourceId, incoming.sourceBusinessKey(), true);
            try {
                if (invalidMetadata) {
                    failUnsafeChange(resolved, incoming);
                    continue;
                }
                // Non-effective observations deliberately inherit valid old geometry
                // and classification. Rejecting them here would prevent cancellation.
                boolean tombstone = !effectiveForAttendance(incoming) && resolved != null;
                var checked = tombstone ? tombstoneRecord(incoming, resolved) : incoming;
                if (checked.start() == null || checked.end() == null || checked.end().isBefore(checked.start())
                        || (!tombstone && (!checked.hasValidOvertimeClassification() || !checked.hasValidLeaveClassification()))) {
                    failUnsafeChange(resolved, checked);
                }
            } catch (AttendanceSourceSyncFailure unsafe) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                        "oa-report-preflight-rejected source={} keyDigest={} versionDigest={} reason={}",
                        sourceId, AttendanceEvidenceDigests.sha256(incoming.sourceBusinessKey()),
                        AttendanceEvidenceDigests.sha256(incoming.sourceVersion()), unsafe.safeCode());
                throw unsafe;
            }
        }
    }

    // ------------------------------------------------------------------

    private RecordOutcome ingestRecord(
            SourceJobStart job,
            String principalId,
            String requestId,
            OaDocumentRecord record,
            Instant receivedAt,
            java.util.concurrent.atomic.AtomicReference<Instant> evidenceAsOf) {

        if (record == null || record.sourceBusinessKey() == null || record.sourceBusinessKey().isBlank()) {
            return RecordOutcome.QUARANTINED;
        }
        var latest = evidenceRepository.findLatestOaObservation(
                job.sourceId(), record.sourceBusinessKey(), false);
        var resolved = evidenceRepository.findLatestOaObservation(
                job.sourceId(), record.sourceBusinessKey(), true);
        // Use the very same historical tie-break as the report projection.
        // A raw UUID must not make an obsolete cancellation look already current.
        if (latest != null && latest.oaAttendanceDocumentId() != null && resolved != null) {
            latest = resolved;
        }
        if (record.sourceVersion() == null || record.sourceVersion().isBlank()
                || record.documentType() == null || record.sourceStatus() == null) {
            failUnsafeChange(resolved, record);
            return RecordOutcome.QUARANTINED;
        }
        boolean tombstone = !effectiveForAttendance(record) && resolved != null;
        if (tombstone) {
            record = tombstoneRecord(record, resolved);
        }
        if (record.start() == null || record.end() == null || record.end().isBefore(record.start())) {
            failUnsafeChange(resolved, record);
            return RecordOutcome.QUARANTINED;
        }
        boolean interval = record.end().isAfter(record.start());
        boolean overtimeTypeUnclassified = !tombstone && !record.hasValidOvertimeClassification();
        boolean leaveTypeUnclassified = !tombstone && !record.hasValidLeaveClassification();

        // Compare against the CURRENT observation, not any historical version. This
        // permits A -> B -> A and unchanged-timestamp edits while retaining every raw row.
        if (latest != null
                && observationBase(record.sourceVersion()).equals(observationBase(latest.sourceVersion()))
                && rawDigest(job, record.withSourceVersion(latest.sourceVersion()))
                        .equals(latest.canonicalPayloadDigest())) {
            record = record.withSourceVersion(latest.sourceVersion());
        } else if (latest != null) {
            record = record.withSourceVersion(observationVersion(record, latest.rawAttendanceFactId()));
        }
        // SQL timestamps have microsecond precision. A source-scoped lock makes this
        // monotonic clock safe even if two revisions arrive during the same millisecond.
        receivedAt = receivedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (latest != null && !receivedAt.isAfter(latest.receivedAt())) {
            receivedAt = latest.receivedAt().plusNanos(1_000);
        }
        long knowledgeRank = Math.max(receivedAt.toEpochMilli(),
                Math.max(latest != null && latest.knowledgeRank() != null ? latest.knowledgeRank() + 1 : 0,
                        resolved != null && resolved.knowledgeRank() != null ? resolved.knowledgeRank() + 1 : 0));

        String rawDigest = rawDigest(job, record);
        var existing = evidenceRepository.findRawBySourceIdentity(
                job.sourceId(), record.sourceBusinessKey(), record.sourceVersion());
        if (existing != null && !rawDigest.equals(existing.canonicalPayloadDigest())) {
            record = record.withSourceVersion(observationVersion(record, existing.rawAttendanceFactId()));
            rawDigest = rawDigest(job, record);
            existing = evidenceRepository.findRawBySourceIdentity(
                    job.sourceId(), record.sourceBusinessKey(), record.sourceVersion());
        }
        if (existing != null) {
            String existingDocId = evidenceRepository.findOaAttendanceDocumentId(
                    job.sourceId(), record.sourceBusinessKey(), record.sourceVersion());
            if (overtimeTypeUnclassified || leaveTypeUnclassified) {
                failUnsafeChange(resolved, record);
                return RecordOutcome.QUARANTINED;
            }
            if (existingDocId != null) {
                if (ensureOvertimeContext(job, record, receivedAt)) {
                    evidenceAsOf.accumulateAndGet(receivedAt, (left, right) -> left.isAfter(right) ? left : right);
                    return RecordOutcome.ACCEPTED_CHANGED;
                }
                return RecordOutcome.ACCEPTED;
            }
            RecordOutcome outcome = rematchQuarantinedEmployee(job, record, existing, receivedAt, interval, knowledgeRank, resolved);
            if (outcome.changed) {
                evidenceAsOf.accumulateAndGet(receivedAt, (left, right) -> left.isAfter(right) ? left : right);
            }
            return outcome;
        }

        String runtimeContractRevisionId =
                findPublishedOvertimeRuntimeContract(
                        job.sourceId(), record, overtimeTypeUnclassified);

        EvidenceResolutionPolicy.MatchDecision decision = tombstone
                ? new EvidenceResolutionPolicy.MatchDecision(
                        EvidenceResolutionPolicy.MatchStatus.MATCHED, "OA_PRIOR_IDENTITY_TOMBSTONE",
                        resolved.employeeId(), resolved.employmentPeriodId(),
                        resolved.resolverSnapshotDigest(), job.companyId())
                : resolveEmployee(job, record);

        boolean employeeUnresolved =
                decision.status() != EvidenceResolutionPolicy.MatchStatus.MATCHED;
        boolean quarantined = employeeUnresolved
                || overtimeTypeUnclassified
                || leaveTypeUnclassified;

        if (quarantined) { failUnsafeChange(resolved, record); }
        evidenceAsOf.accumulateAndGet(receivedAt, (left, right) -> left.isAfter(right) ? left : right);

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
            return RecordOutcome.QUARANTINED_CHANGED;
        }

        // 4. oa_attendance_document (only when employee resolved and the
        // overtime classification, when applicable, is recognized)
        Instant approvedAt = record.approvedAt();
        Instant revokedAt = record.revokedAt();

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

        return RecordOutcome.ACCEPTED_CHANGED;
    }

    private RecordOutcome rematchQuarantinedEmployee(
            SourceJobStart job,
            OaDocumentRecord record,
            EvidenceRows.RawFactRow existing,
            Instant receivedAt,
            boolean interval,
            long knowledgeRank,
            EvidenceRows.OaObservationRow resolved) {
        EvidenceResolutionPolicy.MatchDecision decision = resolveEmployee(job, record);
        if (decision.status() != EvidenceResolutionPolicy.MatchStatus.MATCHED) {
            failUnsafeChange(resolved, record);
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
                knowledgeRank,
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
        return RecordOutcome.ACCEPTED_CHANGED;
    }

    private static void failUnsafeChange(EvidenceRows.OaObservationRow resolved, OaDocumentRecord record) {
        if ((resolved != null && resolved.oaAttendanceDocumentId() != null)
                || (record.documentType() == DocumentType.LEAVE_REVOCATION
                    && (record.sourceStatus() == SourceStatus.APPROVED
                        || record.sourceStatus() == SourceStatus.MODIFIED
                        || record.sourceStatus() == SourceStatus.SUPPLEMENTED))) {
            throw new AttendanceSourceSyncFailure("OA_REPORT_SOURCE_QUARANTINED");
        }
    }

    private static String observationBase(String version) {
        String base = version.replaceFirst(":obs=[0-9a-f]{32}$", "");
        return base.length() > 90 ? "OA_VERSION:" + AttendanceEvidenceDigests.sha256(base) : base;
    }

    private static String observationVersion(OaDocumentRecord record, String previousRawId) {
        String base = observationBase(record.sourceVersion());
        if (base.length() > 90) {
            base = "OA_VERSION:" + AttendanceEvidenceDigests.sha256(base);
        }
        return base + ":obs=" + AttendanceEvidenceDigests.sha256(
                previousRawId, record.sourceVersion(), UUID.randomUUID().toString()).substring(0, 32);
    }

    private static OaDocumentRecord tombstoneRecord(
            OaDocumentRecord incoming, EvidenceRows.OaObservationRow prior) {
        if (!incoming.documentType().name().equals(prior.documentType())) {
            throw new AttendanceSourceSyncFailure("OA_REPORT_SOURCE_QUARANTINED");
        }
        boolean validInterval = incoming.start() != null && incoming.end() != null
                && !incoming.end().isBefore(incoming.start());
        return new OaDocumentRecord(incoming.sourceBusinessKey(), incoming.sourceVersion(),
                incoming.externalPersonRef(), incoming.employeeNumber(), incoming.documentType(),
                incoming.sourceStatus(), validInterval ? incoming.start() : prior.startInstant(),
                validInterval ? incoming.end() : prior.endInstant(), incoming.sourceTimeZone(),
                incoming.firstSubmittedAt(), incoming.approvedAt(), incoming.modifiedAt(), incoming.revokedAt(),
                incoming.sourceBatch(), false,
                incoming.overtimeType() == null ? prior.overtimeType() : incoming.overtimeType(),
                incoming.leaveType() == null ? prior.leaveType() : incoming.leaveType(),
                incoming.leaveSerial() == null ? prior.leaveSerial() : incoming.leaveSerial(),
                incoming.originalLeaveSerial() == null ? prior.originalLeaveSerial() : incoming.originalLeaveSerial());
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
                "OA_RAW_FACT_V2",
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
                leaveTypeName(record),
                Objects.toString(record.leaveSerial(), ""),
                Objects.toString(record.originalLeaveSerial(), ""));
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

    private static boolean effectiveForAttendance(OaDocumentRecord record) {
        if (record.documentType() == DocumentType.LEAVE_REVOCATION) {
            return record.sourceStatus() == SourceStatus.APPROVED
                    || record.sourceStatus() == SourceStatus.MODIFIED
                    || record.sourceStatus() == SourceStatus.SUPPLEMENTED;
        }
        return com.szsemicon.hr.attendance.domain.OaApprovalStatus.effective(
                com.szsemicon.hr.attendance.domain.OaApprovalStatus.normalizeLegacy(
                        record.sourceStatus().name(), record.sourceVersion()));
    }

    private String findPublishedOvertimeRuntimeContract(
            String sourceId,
            OaDocumentRecord record,
            boolean overtimeTypeUnclassified) {
        boolean effectiveClassifiedOvertime =
                record.documentType() == DocumentType.OVERTIME
                        && effectiveForAttendance(record)
                        && !overtimeTypeUnclassified;
        if (!effectiveClassifiedOvertime) {
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
    private boolean ensureOvertimeContext(
            SourceJobStart job,
            OaDocumentRecord record,
            Instant createdAt) {
        if (record.documentType() != DocumentType.OVERTIME
                || !effectiveForAttendance(record)
                || !record.hasValidOvertimeClassification()) {
            return false;
        }
        String runtimeContractRevisionId =
                evidenceRepository.findLatestPublishedOaRuntimeContractRevisionId(
                        job.sourceId());
        if (runtimeContractRevisionId == null) {
            return false;
        }
        String documentId = evidenceRepository.findOaAttendanceDocumentId(
                job.sourceId(),
                record.sourceBusinessKey(),
                record.sourceVersion());
        if (documentId == null
                || evidenceRepository.hasOaAttendanceDocumentContext(documentId)) {
            return false;
        }
        evidenceRepository.insertOaAttendanceDocumentContext(
                overtimeContext(
                        job,
                        record,
                        documentId,
                        runtimeContractRevisionId,
                        createdAt));
        return true;
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
        ACCEPTED(true, false), QUARANTINED(false, false),
        ACCEPTED_CHANGED(true, true), QUARANTINED_CHANGED(false, true);
        final boolean accepted;
        final boolean changed;
        RecordOutcome(boolean accepted, boolean changed) {
            this.accepted = accepted;
            this.changed = changed;
        }
    }

    public record PageCommitResult(int acceptedCount, int quarantinedCount, int changedCount, Instant evidenceAsOf) {
        public PageCommitResult(int acceptedCount, int quarantinedCount) {
            this(acceptedCount, quarantinedCount, 0, null);
        }
        public PageCommitResult(int acceptedCount, int quarantinedCount, int changedCount) {
            this(acceptedCount, quarantinedCount, changedCount, null);
        }
    }
}
