package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.CommittedPage;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.SourceJobStart;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceResolutionPolicy;
import com.szsemicon.hr.evidenceingestion.domain.SourcePageCommitPolicy;
import com.szsemicon.hr.evidenceingestion.port.AttendanceConfigurationResolverPort;
import com.szsemicon.hr.evidenceingestion.port.AttendancePeriodProtectionPort;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliPunchPageTransaction {

    private static final Logger log =
            LoggerFactory.getLogger(DeliPunchPageTransaction.class);
    private static final LocalTime CROSS_DAY_CUTOFF = LocalTime.of(6, 0);
    private static final int MAX_CANDIDATE_DATES = 4;

    private final AttendanceSourceSyncRepository syncRepository;
    private final AttendanceEvidenceRepository evidenceRepository;
    private final EmployeeEmploymentResolverPort employeeResolver;
    private final Clock clock;

    public DeliPunchPageTransaction(
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
            String requestId,
            String executionCapability,
            int pageNumber,
            DeliPunchSourcePort.DeliPage page,
            AttendanceConfigurationResolverPort configurationResolver,
            AttendancePeriodProtectionPort periodProtection) {
        return commitPage(
                job,
                principalId,
                requestId,
                executionCapability,
                pageNumber,
                page,
                configurationResolver,
                periodProtection,
                PunchStream.CHECKIN,
                true,
                false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PageCommitResult commitKqPage(
            SourceJobStart job,
            String principalId,
            String requestId,
            String executionCapability,
            int pageNumber,
            DeliPunchSourcePort.DeliPage page,
            AttendanceConfigurationResolverPort configurationResolver,
            AttendancePeriodProtectionPort periodProtection) {
        return commitPage(
                job,
                principalId,
                requestId,
                executionCapability,
                pageNumber,
                page,
                configurationResolver,
                periodProtection,
                PunchStream.KQ,
                true,
                false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PageCommitResult commitPageWithoutAdvancingWatermark(
            SourceJobStart job,
            String principalId,
            String requestId,
            String executionCapability,
            int pageNumber,
            DeliPunchSourcePort.DeliPage page,
            AttendanceConfigurationResolverPort configurationResolver,
            AttendancePeriodProtectionPort periodProtection,
            boolean kqStream) {
        return commitPage(
                job,
                principalId,
                requestId,
                executionCapability,
                pageNumber,
                page,
                configurationResolver,
                periodProtection,
                kqStream ? PunchStream.KQ : PunchStream.CHECKIN,
                false,
                false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PageCommitResult commitReplayPage(
            SourceJobStart job,
            String principalId,
            String requestId,
            String executionCapability,
            int pageNumber,
            DeliPunchSourcePort.DeliPage page,
            AttendanceConfigurationResolverPort configurationResolver,
            AttendancePeriodProtectionPort periodProtection,
            boolean kqStream) {
        return commitPage(
                job,
                principalId,
                requestId,
                executionCapability,
                pageNumber,
                page,
                configurationResolver,
                periodProtection,
                kqStream ? PunchStream.KQ : PunchStream.CHECKIN,
                false,
                true);
    }

    private PageCommitResult commitPage(
            SourceJobStart job,
            String principalId,
            String requestId,
            String executionCapability,
            int pageNumber,
            DeliPunchSourcePort.DeliPage page,
            AttendanceConfigurationResolverPort configurationResolver,
            AttendancePeriodProtectionPort periodProtection,
            PunchStream stream,
            boolean advanceWatermark,
            boolean replayIdentity) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(configurationResolver, "configurationResolver");
        Objects.requireNonNull(periodProtection, "periodProtection");
        requireIdentifier(principalId, 36, "DELI_SYNC_ACTOR_INVALID");
        requireReference(requestId, 64, "DELI_SYNC_REQUEST_INVALID");
        if (!CapabilityCodes.ATTENDANCE_SOURCE_RUN.equals(executionCapability)
                && !CapabilityCodes.ATTENDANCE_SOURCE_RETRY.equals(
                        executionCapability)) {
            throw failure("DELI_SYNC_CAPABILITY_INVALID");
        }
        if (pageNumber < 1) {
            throw failure("DELI_PAGE_NUMBER_INVALID");
        }
        validatePage(page);

        Instant committedAt = clock.instant();
        AttendanceSourceSyncModels.PageState pageState = null;
        if (!replayIdentity) {
            pageState = lockPage(
                    job, principalId, executionCapability, stream, committedAt);
            if (pageState == null) {
                throw failure("ATTENDANCE_SOURCE_SCOPE_REVOKED");
            }
            if (!"RUNNING".equals(pageState.jobStatus())
                    || pageState.committedPages() + 1 != pageNumber
                    || !sameCursor(
                            pageState.committedCursor(),
                            page.inputCursor())) {
                throw failure("DELI_WATERMARK_STALE");
            }
        }

        int accepted = 0;
        int quarantined = 0;
        int identityReplayed = 0;
        int identityMoved = 0;
        List<IdentityQuarantineNote> stillQuarantined = new ArrayList<>();
        for (var record : page.records()) {
            RecordOutcome outcome = ingestRecord(
                    job,
                    principalId,
                    requestId,
                    record,
                    configurationResolver,
                    periodProtection,
                    committedAt,
                    replayIdentity,
                    stillQuarantined);
            if (outcome.status() == RecordStatus.ACCEPTED) {
                accepted++;
            } else {
                quarantined++;
            }
            if (outcome.identityReplayed()) {
                identityReplayed++;
            }
            if (outcome.identityMoved()) {
                identityMoved++;
            }
        }

        var decision = SourcePageCommitPolicy.decide(
                new SourcePageCommitPolicy.PageOutcome(
                        page.inputCursor(),
                        page.nextCursor(),
                        page.records().size(),
                        accepted,
                        quarantined,
                        page.pageDigest(),
                        true,
                        true,
                        true));
        // Unmatched or configuration-quarantined punches must still advance
        // the vendor cursor so a full re-fetch can complete before master
        // data is re-imported.
        boolean allQuarantined =
                "ALL_RECORDS_QUARANTINED".equals(decision.reason());
        if ((!decision.commitPage() || !decision.advanceWatermark())
                && !allQuarantined) {
            throw failure("DELI_" + decision.reason());
        }
        String committedCursor = decision.committedCursor() != null
                ? decision.committedCursor()
                : page.nextCursor();

        if (!replayIdentity) {
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
                    requestId,
                    committedAt));
            if (advanceWatermark && pageState != null) {
                if (stream == PunchStream.KQ) {
                    syncRepository.advanceKqWatermark(
                            job.sourceId(),
                            pageState.watermarkVersion(),
                            committedCursor,
                            page.pageDigest(),
                            committedAt);
                } else {
                    syncRepository.advanceWatermark(
                            job.sourceId(),
                            pageState.watermarkVersion(),
                            committedCursor,
                            page.pageDigest(),
                            committedAt);
                }
            }
            syncRepository.incrementJobCounters(
                    job.jobId(), accepted, quarantined);
        }
        return new PageCommitResult(
                accepted,
                quarantined,
                identityReplayed,
                identityMoved,
                List.copyOf(stillQuarantined));
    }

    private AttendanceSourceSyncModels.PageState lockPage(
            SourceJobStart job,
            String principalId,
            String executionCapability,
            PunchStream stream,
            Instant committedAt) {
        if (stream == PunchStream.KQ) {
            return "SYSTEM".equals(principalId)
                    ? syncRepository.lockSystemKqPageForCommit(
                            job.jobId(), job.sourceId())
                    : syncRepository.lockKqPageForCommit(
                            job.jobId(),
                            job.sourceId(),
                            principalId,
                            executionCapability,
                            committedAt);
        }
        return "SYSTEM".equals(principalId)
                ? syncRepository.lockSystemPageForCommit(
                        job.jobId(), job.sourceId())
                : syncRepository.lockPageForCommit(
                        job.jobId(),
                        job.sourceId(),
                        principalId,
                        executionCapability,
                        committedAt);
    }

    private enum PunchStream {
        CHECKIN,
        KQ
    }

    private RecordOutcome ingestRecord(
            SourceJobStart job,
            String principalId,
            String requestId,
            DeliPunchSourcePort.DeliPunchRecord record,
            AttendanceConfigurationResolverPort configurationResolver,
            AttendancePeriodProtectionPort periodProtection,
            Instant receivedAt,
            boolean replayIdentity,
            List<IdentityQuarantineNote> stillQuarantined) {
        validateRecord(record);
        String rawDigest = rawDigest(job, record);
        var existing = evidenceRepository.findRawBySourceIdentity(
                job.sourceId(),
                record.sourceRecordId(),
                record.sourceVersion());
        if (existing != null) {
            // Replay may rewrite empno via the live directory; the source
            // record id is the identity, so a digest change must not abort.
            if (!replayIdentity
                    && !rawDigest.equals(existing.canonicalPayloadDigest())) {
                throw failure("DELI_SOURCE_IDENTITY_COLLISION");
            }
            if (replayIdentity) {
                return replayExistingIdentity(
                        job,
                        principalId,
                        requestId,
                        record,
                        existing,
                        configurationResolver,
                        periodProtection,
                        receivedAt,
                        stillQuarantined);
            }
            return RecordOutcome.accepted();
        }

        var decision = EvidenceResolutionPolicy.resolve(
                employeeResolver,
                job.sourceId(),
                job.companyId(),
                record.employeeNumber(),
                null,
                record.deviceRef(),
                record.externalPersonRef(),
                record.externalPersonRefKind(),
                record.memberName(),
                record.punchInstant());
        String resolverDigest = resolverDigest(job, record, decision);

        ResolvedEffectiveContext effectiveContext = null;
        String configurationIssue = null;
        if (decision.status() == EvidenceResolutionPolicy.MatchStatus.MATCHED) {
            try {
                effectiveContext = resolveEffectiveContext(
                        job,
                        record,
                        decision,
                        configurationResolver,
                        periodProtection);
            } catch (AttendanceSourceSyncFailure exception) {
                if (!continuableIngestFailure(exception.safeCode())) {
                    throw exception;
                }
                configurationIssue = exception.safeCode();
                log.warn(
                        "Deli punch quarantined without stopping the page"
                                + " code={} sourceRecordId={} employeeNumber={}",
                        exception.safeCode(),
                        record.sourceRecordId(),
                        record.employeeNumber());
            }
        }

        String rawId = UUID.randomUUID().toString();
        String normalizedId = UUID.randomUUID().toString();
        String matchId = UUID.randomUUID().toString();
        boolean quarantined =
                decision.status() != EvidenceResolutionPolicy.MatchStatus.MATCHED
                        || configurationIssue != null;
        String normalizedDigest = AttendanceEvidenceDigests.sha256(
                "DELI_NORMALIZED_V1",
                rawDigest,
                record.punchInstant().toString(),
                record.direction().name());

        evidenceRepository.insertRawFact(new EvidenceRows.RawFactRow(
                rawId,
                job.sourceId(),
                job.companyId(),
                "PUNCH_POINT",
                record.sourceRecordId(),
                record.sourceVersion(),
                null,
                record.originalTimeText(),
                record.sourceTimeZone(),
                record.punchInstant(),
                null,
                null,
                rawDigest,
                null,
                requestId,
                receivedAt,
                principalId));
        evidenceRepository.insertNormalizedRecord(
                new EvidenceRows.NormalizedRecordRow(
                        normalizedId,
                        rawId,
                        1,
                        "DELI_CHECKIN_V1",
                        "PUNCH_POINT",
                        record.direction().name(),
                        record.punchInstant(),
                        null,
                        null,
                        quarantined ? "QUARANTINED" : "VALID",
                        configurationIssue != null
                                ? configurationIssue
                                : issueCode(decision.status()),
                        normalizedDigest,
                        null,
                        receivedAt));
        evidenceRepository.insertMatchDecision(
                new EvidenceRows.MatchDecisionRow(
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
            if (replayIdentity) {
                stillQuarantined.add(quarantineNote(
                        record,
                        configurationIssue != null
                                ? configurationIssue
                                : decision.reason()));
            }
            return RecordOutcome.quarantined();
        }

        appendEffectiveEvent(
                job,
                principalId,
                requestId,
                record,
                decision,
                effectiveContext,
                rawId,
                normalizedId,
                matchId,
                receivedAt);
        return replayIdentity
                ? RecordOutcome.replayed(true, false)
                : RecordOutcome.accepted();
    }

    private ResolvedEffectiveContext resolveEffectiveContext(
            SourceJobStart job,
            DeliPunchSourcePort.DeliPunchRecord record,
            EvidenceResolutionPolicy.MatchDecision decision,
            AttendanceConfigurationResolverPort configurationResolver,
            AttendancePeriodProtectionPort periodProtection) {
        String companyId = subjectCompanyId(job, decision);
        AttendanceConfigurationResolverPort.Resolution configuration;
        try {
            configuration = configurationResolver.resolve(
                    companyId,
                    decision.employeeId(),
                    record.punchInstant());
        } catch (RuntimeException exception) {
            log.error(
                    "Deli attendance configuration resolve failed"
                            + " company={} employee={} punchInstant={}",
                    companyId,
                    decision.employeeId(),
                    record.punchInstant(),
                    exception);
            throw failure("ATTENDANCE_CONFIGURATION_UNAVAILABLE");
        }
        if (configuration == null
                || !configuration.authoritative()
                || configuration.businessTimeZone() == null
                || !isDigest(configuration.resolverSnapshotDigest())) {
            log.warn(
                    "Deli attendance configuration not authoritative"
                            + " company={} employee={} punchInstant={}"
                            + " authoritative={}",
                    companyId,
                    decision.employeeId(),
                    record.punchInstant(),
                    configuration == null
                            ? null
                            : configuration.authoritative());
            throw failure("ATTENDANCE_CONFIGURATION_UNAVAILABLE");
        }
        Set<LocalDate> dates = candidateBusinessDates(
                configuration, record.punchInstant());
        List<ProtectedDate> protectedDates = new ArrayList<>();
        for (LocalDate date : dates) {
            AttendancePeriodProtectionPort.Protection protection;
            try {
                protection = periodProtection.protectionFor(
                        companyId, decision.employeeId(), date);
            } catch (RuntimeException exception) {
                throw failure("ATTENDANCE_PERIOD_PROTECTION_UNAVAILABLE");
            }
            if (protection == null
                    || protection.status()
                            == AttendancePeriodProtectionPort.PeriodStatus.UNKNOWN
                    || !isReference(protection.periodVersion(), 128)
                    || !isDigest(protection.snapshotDigest())) {
                throw failure("ATTENDANCE_PERIOD_PROTECTION_UNAVAILABLE");
            }
            if (!protection.allowsEffectiveMutation()) {
                throw failure("ATTENDANCE_PERIOD_PROTECTED");
            }
            protectedDates.add(new ProtectedDate(date, protection));
        }
        return new ResolvedEffectiveContext(
                configuration.resolverSnapshotDigest(),
                List.copyOf(protectedDates));
    }

    private void appendEffectiveEvent(
            SourceJobStart job,
            String principalId,
            String requestId,
            DeliPunchSourcePort.DeliPunchRecord record,
            EvidenceResolutionPolicy.MatchDecision decision,
            ResolvedEffectiveContext context,
            String rawId,
            String normalizedId,
            String matchId,
            Instant createdAt) {
        String companyId = subjectCompanyId(job, decision);
        evidenceRepository.lockSubject(
                companyId, decision.employeeId(), createdAt);
        List<EvidenceRows.EffectiveEventRow> exact =
                evidenceRepository.findExactEvents(
                        companyId,
                        decision.employeeId(),
                        record.punchInstant(),
                        record.direction().name());
        if (exact == null || exact.size() > 1) {
            throw failure("DELI_EFFECTIVE_EVENT_AMBIGUOUS");
        }
        if (exact.size() == 1) {
            String eventId = exact.getFirst().effectiveAttendanceEventId();
            if (!evidenceRepository.hasEvidenceLink(eventId, rawId)) {
                evidenceRepository.insertEvidenceLink(
                        new EvidenceRows.EvidenceLinkRow(
                                UUID.randomUUID().toString(),
                                eventId,
                                rawId,
                                normalizedId,
                                matchId,
                                "EXACT_DUPLICATE",
                                createdAt));
            }
            return;
        }

        String eventId = UUID.randomUUID().toString();
        String eventDigest = AttendanceEvidenceDigests.sha256(
                "DELI_EFFECTIVE_EVENT_V1",
                companyId,
                decision.employeeId(),
                record.punchInstant().toString(),
                record.direction().name());
        evidenceRepository.insertEffectiveEvent(
                new EvidenceRows.EffectiveEventRow(
                        eventId,
                        companyId,
                        decision.employeeId(),
                        "PUNCH_POINT",
                        record.direction().name(),
                        record.punchInstant(),
                        null,
                        null,
                        eventDigest,
                        createdAt));
        String lifecycleDigest = AttendanceEvidenceDigests.sha256(
                "DELI_ACTIVATED_V1", eventId, requestId);
        evidenceRepository.insertLifecycleFact(
                new EvidenceRows.LifecycleFactRow(
                        UUID.randomUUID().toString(),
                        eventId,
                        "ACTIVATED",
                        null,
                        null,
                        createdAt,
                        principalId,
                        requestId,
                        "DELI_SYNC",
                        lifecycleDigest));
        evidenceRepository.insertEvidenceLink(
                new EvidenceRows.EvidenceLinkRow(
                        UUID.randomUUID().toString(),
                        eventId,
                        rawId,
                        normalizedId,
                        matchId,
                        "PRIMARY",
                        createdAt));

        for (ProtectedDate protectedDate : context.protectedDates()) {
            var protection = protectedDate.protection();
            String snapshotDigest = AttendanceEvidenceDigests.sha256(
                    "DELI_RECALC_RESOLVER_V1",
                    decision.resolverSnapshotDigest(),
                    context.configurationSnapshotDigest(),
                    protection.snapshotDigest());
            String intentDigest = AttendanceEvidenceDigests.sha256(
                    "DELI_PUNCH_RECALC_V1",
                    eventId,
                    protectedDate.businessDate().toString(),
                    snapshotDigest,
                    protection.periodVersion());
            evidenceRepository.insertRecalculationIntent(
                    new EvidenceRows.RecalculationIntentRow(
                            UUID.randomUUID().toString(),
                            companyId,
                            decision.employeeId(),
                            protectedDate.businessDate(),
                            "DELI_PUNCH_INGESTED",
                            eventId,
                            snapshotDigest,
                            protection.periodVersion(),
                            requestId,
                            intentDigest,
                            createdAt));
        }
    }

    private static Set<LocalDate> candidateBusinessDates(
            AttendanceConfigurationResolverPort.Resolution configuration,
            Instant punchInstant) {
        LinkedHashSet<LocalDate> dates =
                new LinkedHashSet<>(configuration.candidateBusinessDates());
        ZoneId zone = configuration.businessTimeZone();
        var local = punchInstant.atZone(zone);
        dates.add(local.toLocalDate());
        if (local.toLocalTime().isBefore(CROSS_DAY_CUTOFF)) {
            dates.add(local.toLocalDate().minusDays(1));
        }
        if (dates.isEmpty() || dates.size() > MAX_CANDIDATE_DATES) {
            throw failure("ATTENDANCE_CONFIGURATION_UNAVAILABLE");
        }
        return Set.copyOf(dates);
    }

    private static String rawDigest(
            SourceJobStart job,
            DeliPunchSourcePort.DeliPunchRecord record) {
        return AttendanceEvidenceDigests.sha256(
                "DELI_RAW_FACT_V1",
                job.sourceId(),
                job.companyId(),
                record.sourceRecordId(),
                record.sourceVersion(),
                record.externalPersonRef(),
                record.externalPersonRefKind().name(),
                record.employeeNumber(),
                record.punchInstant().toString(),
                record.originalTimeText(),
                record.sourceTimeZone(),
                record.direction().name(),
                record.verificationMethod(),
                record.deviceRef(),
                record.coordinateSystemTag(),
                Boolean.toString(record.forbiddenPayloadDropped()));
    }

    private static String resolverDigest(
            SourceJobStart job,
            DeliPunchSourcePort.DeliPunchRecord record,
            EvidenceResolutionPolicy.MatchDecision decision) {
        if (decision.status() == EvidenceResolutionPolicy.MatchStatus.MATCHED) {
            if (!isDigest(decision.resolverSnapshotDigest())) {
                throw failure("EMPLOYEE_RESOLVER_UNAVAILABLE");
            }
            return decision.resolverSnapshotDigest();
        }
        return AttendanceEvidenceDigests.sha256(
                "DELI_UNRESOLVED_MATCH_V1",
                job.companyId(),
                record.employeeNumber(),
                record.externalPersonRef(),
                record.externalPersonRefKind().name(),
                record.punchInstant().toString(),
                decision.status().name(),
                decision.reason());
    }

    private static boolean continuableIngestFailure(String safeCode) {
        return "ATTENDANCE_CONFIGURATION_UNAVAILABLE".equals(safeCode);
    }

    private static String issueCode(
            EvidenceResolutionPolicy.MatchStatus status) {
        return switch (status) {
            case MATCHED -> null;
            case UNMATCHED -> "EMPLOYEE_UNMATCHED";
            case AMBIGUOUS -> "EMPLOYEE_AMBIGUOUS";
        };
    }

    private static void validatePage(DeliPunchSourcePort.DeliPage page) {
        if (page.records() == null
                || !isReference(page.inputCursor(), 512)
                || !isReference(page.nextCursor(), 512)
                || !isDigest(page.pageDigest())) {
            throw failure("DELI_PAGE_CONTRACT_INVALID");
        }
        if (page.nextCursor().equals(page.inputCursor())) {
            throw failure("DELI_CURSOR_LOOP");
        }
    }

    private static void validateRecord(
            DeliPunchSourcePort.DeliPunchRecord record) {
        if (record == null
                || !isReference(record.sourceRecordId(), 191)
                || !isReference(record.sourceVersion(), 128)
                || !isReference(record.externalPersonRef(), 128)
                || record.externalPersonRefKind() == null
                || (record.employeeNumber() != null
                        && !isReference(record.employeeNumber(), 128))
                || record.punchInstant() == null
                || !isReference(record.originalTimeText(), 128)
                || !isZone(record.sourceTimeZone())
                || record.direction() == null
                || !isReference(record.verificationMethod(), 64)
                || !isReference(record.deviceRef(), 191)) {
            throw failure("DELI_RECORD_CONTRACT_INVALID");
        }
    }

    private static boolean isZone(String value) {
        if (!isReference(value, 64)) {
            return false;
        }
        try {
            ZoneId.of(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean isReference(String value, int maximumLength) {
        return value != null
                && !value.isBlank()
                && value.length() <= maximumLength
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static void requireIdentifier(
            String value, int maximumLength, String safeCode) {
        if (!isReference(value, maximumLength)) {
            throw failure(safeCode);
        }
    }

    private static void requireReference(
            String value, int maximumLength, String safeCode) {
        if (!isReference(value, maximumLength)) {
            throw failure(safeCode);
        }
    }

    private static boolean sameCursor(String committed, String input) {
        return Objects.equals(committed, input)
                || (committed == null && "0".equals(input));
    }

    private static AttendanceSourceSyncFailure failure(String safeCode) {
        return new AttendanceSourceSyncFailure(safeCode);
    }

    private RecordOutcome replayExistingIdentity(
            SourceJobStart job,
            String principalId,
            String requestId,
            DeliPunchSourcePort.DeliPunchRecord record,
            EvidenceRows.RawFactRow existing,
            AttendanceConfigurationResolverPort configurationResolver,
            AttendancePeriodProtectionPort periodProtection,
            Instant receivedAt,
            List<IdentityQuarantineNote> stillQuarantined) {
        var current = evidenceRepository.findReplayStateBySourceIdentity(
                job.sourceId(),
                record.sourceRecordId(),
                record.sourceVersion());
        var decision = EvidenceResolutionPolicy.resolve(
                employeeResolver,
                job.sourceId(),
                job.companyId(),
                record.employeeNumber(),
                null,
                record.deviceRef(),
                record.externalPersonRef(),
                record.externalPersonRefKind(),
                record.memberName(),
                record.punchInstant());
        boolean matched =
                decision.status() == EvidenceResolutionPolicy.MatchStatus.MATCHED;
        String currentEmployee = current == null
                ? null
                : current.matchEmployeeId();
        boolean wasMatched = current != null
                && "MATCHED".equals(current.matchStatus())
                && currentEmployee != null;
        boolean sameEmployee = matched
                && wasMatched
                && currentEmployee.equals(decision.employeeId());
        boolean alreadyEffective = sameEmployee
                && current != null
                && "VALID".equals(current.validationStatus())
                && current.effectiveAttendanceEventId() != null;
        if (alreadyEffective) {
            return RecordOutcome.accepted();
        }
        if (!matched && !wasMatched) {
            stillQuarantined.add(quarantineNote(record, decision.reason()));
            log.warn(
                    "Deli identity replay still quarantined sourceRecordId={} empno={} personId={} reason={}",
                    record.sourceRecordId(),
                    record.employeeNumber(),
                    record.externalPersonRef(),
                    decision.reason());
            return RecordOutcome.quarantined();
        }

        ResolvedEffectiveContext effectiveContext = null;
        String configurationIssue = null;
        if (matched) {
            try {
                effectiveContext = resolveEffectiveContext(
                        job,
                        record,
                        decision,
                        configurationResolver,
                        periodProtection);
            } catch (AttendanceSourceSyncFailure exception) {
                if (!continuableIngestFailure(exception.safeCode())) {
                    throw exception;
                }
                configurationIssue = exception.safeCode();
                matched = false;
            }
        }
        int nextRevision = current == null || current.normalizationRevision() == null
                ? 1
                : current.normalizationRevision() + 1;
        String normalizedId = UUID.randomUUID().toString();
        String matchId = UUID.randomUUID().toString();
        boolean quarantined = !matched || configurationIssue != null;
        evidenceRepository.insertNormalizedRecord(
                new EvidenceRows.NormalizedRecordRow(
                        normalizedId,
                        existing.rawAttendanceFactId(),
                        nextRevision,
                        "DELI_CHECKIN_V1",
                        "PUNCH_POINT",
                        record.direction().name(),
                        record.punchInstant(),
                        null,
                        null,
                        quarantined ? "QUARANTINED" : "VALID",
                        configurationIssue != null
                                ? configurationIssue
                                : issueCode(decision.status()),
                        AttendanceEvidenceDigests.sha256(
                                "DELI_NORMALIZED_REPLAY_V1",
                                existing.canonicalPayloadDigest(),
                                record.punchInstant().toString(),
                                Integer.toString(nextRevision)),
                        current == null
                                ? null
                                : current.normalizedAttendanceRecordId(),
                        receivedAt));
        evidenceRepository.insertMatchDecision(
                new EvidenceRows.MatchDecisionRow(
                        matchId,
                        normalizedId,
                        decision.status().name(),
                        decision.reason(),
                        decision.employeeId(),
                        decision.employmentPeriodId(),
                        null,
                        resolverDigest(job, record, decision),
                        receivedAt));
        if (current != null
                && current.effectiveAttendanceEventId() != null
                && !quarantined
                && currentEmployee != null
                && currentEmployee.equals(decision.employeeId())) {
            return RecordOutcome.replayed(true, false);
        }
        if (current != null && current.effectiveAttendanceEventId() != null) {
            String lifecycleDigest = AttendanceEvidenceDigests.sha256(
                    "DELI_REPLAY_SUPERSEDE_V1",
                    current.effectiveAttendanceEventId(),
                    requestId);
            evidenceRepository.insertLifecycleFact(
                    new EvidenceRows.LifecycleFactRow(
                            UUID.randomUUID().toString(),
                            current.effectiveAttendanceEventId(),
                            "SUPERSEDED",
                            null,
                            null,
                            receivedAt,
                            principalId,
                            requestId,
                            "DELI_IDENTITY_REPLAY",
                            lifecycleDigest));
        }
        if (quarantined) {
            String reason = configurationIssue != null
                    ? configurationIssue
                    : decision.reason();
            stillQuarantined.add(quarantineNote(record, reason));
            log.warn(
                    "Deli identity replay still quarantined sourceRecordId={} empno={} personId={} reason={}",
                    record.sourceRecordId(),
                    record.employeeNumber(),
                    record.externalPersonRef(),
                    reason);
            return RecordOutcome.replayed(false, wasMatched);
        }
        appendEffectiveEvent(
                job,
                principalId,
                requestId,
                record,
                decision,
                effectiveContext,
                existing.rawAttendanceFactId(),
                normalizedId,
                matchId,
                receivedAt);
        boolean moved = wasMatched
                && currentEmployee != null
                && !currentEmployee.equals(decision.employeeId());
        return RecordOutcome.replayed(true, moved);
    }

    private static String subjectCompanyId(
            SourceJobStart job,
            EvidenceResolutionPolicy.MatchDecision decision) {
        // Matching ignores the Deli source company. Setup and events must
        // follow the matched employee, otherwise another company's punches
        // stay quarantined as ATTENDANCE_CONFIGURATION_UNAVAILABLE.
        if (isReference(decision.companyId(), 36)) {
            return decision.companyId();
        }
        return job.companyId();
    }

    private enum RecordStatus {
        ACCEPTED,
        QUARANTINED
    }

    private record RecordOutcome(
            RecordStatus status,
            boolean identityReplayed,
            boolean identityMoved) {

        static RecordOutcome accepted() {
            return new RecordOutcome(RecordStatus.ACCEPTED, false, false);
        }

        static RecordOutcome quarantined() {
            return new RecordOutcome(RecordStatus.QUARANTINED, false, false);
        }

        static RecordOutcome replayed(boolean accepted, boolean moved) {
            return new RecordOutcome(
                    accepted ? RecordStatus.ACCEPTED : RecordStatus.QUARANTINED,
                    true,
                    moved);
        }
    }

    public record IdentityQuarantineNote(
            String sourceRecordId,
            String employeeNumber,
            String deliPersonId,
            Instant punchInstant,
            String reason) {
    }

    private static IdentityQuarantineNote quarantineNote(
            DeliPunchSourcePort.DeliPunchRecord record, String reason) {
        return new IdentityQuarantineNote(
                record.sourceRecordId(),
                record.employeeNumber(),
                record.externalPersonRef(),
                record.punchInstant(),
                reason);
    }

    public record PageCommitResult(
            int acceptedCount,
            int quarantinedCount,
            int identityReplayedCount,
            int identityMovedCount,
            List<IdentityQuarantineNote> stillQuarantined) {

        public PageCommitResult {
            stillQuarantined = stillQuarantined == null
                    ? List.of()
                    : List.copyOf(stillQuarantined);
        }

        public PageCommitResult(int acceptedCount, int quarantinedCount) {
            this(acceptedCount, quarantinedCount, 0, 0, List.of());
        }
    }

    private record ProtectedDate(
            LocalDate businessDate,
            AttendancePeriodProtectionPort.Protection protection) {
    }

    private record ResolvedEffectiveContext(
            String configurationSnapshotDigest,
            List<ProtectedDate> protectedDates) {
    }
}
