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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliPunchPageTransaction {

    private static final LocalTime CROSS_DAY_CUTOFF = LocalTime.of(6, 0);
    private static final int MAX_CANDIDATE_DATES = 4;

    /**
     * Safe failure code raised when a punch's business date falls outside
     * every published attendance period, leaving its period state
     * undetermined. Such a record is quarantined on its own rather than
     * aborting the page it travelled in.
     */
    private static final String PERIOD_PROTECTION_UNAVAILABLE =
            "ATTENDANCE_PERIOD_PROTECTION_UNAVAILABLE";

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
        var pageState = syncRepository.lockPageForCommit(
                job.jobId(),
                job.sourceId(),
                principalId,
                executionCapability,
                committedAt);
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

        int accepted = 0;
        int quarantined = 0;
        for (var record : page.records()) {
            RecordOutcome outcome;
            try {
                outcome = ingestRecord(
                        job,
                        principalId,
                        requestId,
                        record,
                        configurationResolver,
                        periodProtection,
                        committedAt);
            } catch (AttendanceSourceSyncFailure failure) {
                // A punch whose business date falls outside every published
                // attendance period cannot have its period state determined,
                // so it is not admissible as effective evidence. Quarantine
                // that single record instead of rolling back the whole page:
                // one source page legitimately mixes dates from adjacent
                // periods, and aborting would also reject the in-period
                // records travelling alongside it.
                //
                // Only the UNDETERMINED case is tolerated here. A protected
                // (closed) period, an unavailable configuration resolver, or
                // any other failure still aborts the page — those indicate a
                // system or authority problem, not per-record scope, and must
                // not be silently absorbed.
                if (!PERIOD_PROTECTION_UNAVAILABLE.equals(
                        failure.safeCode())) {
                    throw failure;
                }
                outcome = RecordOutcome.QUARANTINED;
            }
            if (outcome == RecordOutcome.ACCEPTED) {
                accepted++;
            } else {
                quarantined++;
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
        if (!decision.commitPage() || !decision.advanceWatermark()) {
            throw failure("DELI_" + decision.reason());
        }

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
        syncRepository.advanceWatermark(
                job.sourceId(),
                pageState.watermarkVersion(),
                decision.committedCursor(),
                page.pageDigest(),
                committedAt);
        syncRepository.incrementJobCounters(
                job.jobId(), accepted, quarantined);
        return new PageCommitResult(accepted, quarantined);
    }

    private RecordOutcome ingestRecord(
            SourceJobStart job,
            String principalId,
            String requestId,
            DeliPunchSourcePort.DeliPunchRecord record,
            AttendanceConfigurationResolverPort configurationResolver,
            AttendancePeriodProtectionPort periodProtection,
            Instant receivedAt) {
        validateRecord(record);
        String rawDigest = rawDigest(job, record);
        var existing = evidenceRepository.findRawBySourceIdentity(
                job.sourceId(),
                record.sourceRecordId(),
                record.sourceVersion());
        if (existing != null) {
            if (!rawDigest.equals(existing.canonicalPayloadDigest())) {
                throw failure("DELI_SOURCE_IDENTITY_COLLISION");
            }
            return RecordOutcome.ACCEPTED;
        }

        var decision = EvidenceResolutionPolicy.resolve(
                employeeResolver,
                job.companyId(),
                record.employeeNumber(),
                null,
                record.deviceRef(),
                record.externalPersonRef(),
                record.externalPersonRefKind(),
                record.punchInstant());
        String resolverDigest = resolverDigest(job, record, decision);

        ResolvedEffectiveContext effectiveContext = null;
        if (decision.status() == EvidenceResolutionPolicy.MatchStatus.MATCHED) {
            effectiveContext = resolveEffectiveContext(
                    job,
                    record,
                    decision,
                    configurationResolver,
                    periodProtection);
        }

        String rawId = UUID.randomUUID().toString();
        String normalizedId = UUID.randomUUID().toString();
        String matchId = UUID.randomUUID().toString();
        boolean quarantined =
                decision.status() != EvidenceResolutionPolicy.MatchStatus.MATCHED;
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
                        issueCode(decision.status()),
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
            return RecordOutcome.QUARANTINED;
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
        return RecordOutcome.ACCEPTED;
    }

    private ResolvedEffectiveContext resolveEffectiveContext(
            SourceJobStart job,
            DeliPunchSourcePort.DeliPunchRecord record,
            EvidenceResolutionPolicy.MatchDecision decision,
            AttendanceConfigurationResolverPort configurationResolver,
            AttendancePeriodProtectionPort periodProtection) {
        AttendanceConfigurationResolverPort.Resolution configuration;
        try {
            configuration = configurationResolver.resolve(
                    job.companyId(),
                    decision.employeeId(),
                    record.punchInstant());
        } catch (RuntimeException exception) {
            throw failure("ATTENDANCE_CONFIGURATION_UNAVAILABLE");
        }
        if (configuration == null
                || !configuration.authoritative()
                || configuration.businessTimeZone() == null
                || !isDigest(configuration.resolverSnapshotDigest())) {
            throw failure("ATTENDANCE_CONFIGURATION_UNAVAILABLE");
        }
        Set<LocalDate> dates = candidateBusinessDates(
                configuration, record.punchInstant());
        List<ProtectedDate> protectedDates = new ArrayList<>();
        for (LocalDate date : dates) {
            AttendancePeriodProtectionPort.Protection protection;
            try {
                protection = periodProtection.protectionFor(
                        job.companyId(), decision.employeeId(), date);
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
        evidenceRepository.lockSubject(
                job.companyId(), decision.employeeId(), createdAt);
        List<EvidenceRows.EffectiveEventRow> exact =
                evidenceRepository.findExactEvents(
                        job.companyId(),
                        decision.employeeId(),
                        record.punchInstant(),
                        record.direction().name());
        if (exact == null || exact.size() > 1) {
            throw failure("DELI_EFFECTIVE_EVENT_AMBIGUOUS");
        }
        if (exact.size() == 1) {
            evidenceRepository.insertEvidenceLink(
                    new EvidenceRows.EvidenceLinkRow(
                            UUID.randomUUID().toString(),
                            exact.getFirst().effectiveAttendanceEventId(),
                            rawId,
                            normalizedId,
                            matchId,
                            "EXACT_DUPLICATE",
                            createdAt));
            return;
        }

        String eventId = UUID.randomUUID().toString();
        String eventDigest = AttendanceEvidenceDigests.sha256(
                "DELI_EFFECTIVE_EVENT_V1",
                job.companyId(),
                decision.employeeId(),
                record.punchInstant().toString(),
                record.direction().name());
        evidenceRepository.insertEffectiveEvent(
                new EvidenceRows.EffectiveEventRow(
                        eventId,
                        job.companyId(),
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
                            job.companyId(),
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

    private enum RecordOutcome {
        ACCEPTED,
        QUARANTINED
    }

    private record ProtectedDate(
            LocalDate businessDate,
            AttendancePeriodProtectionPort.Protection protection) {
    }

    private record ResolvedEffectiveContext(
            String configurationSnapshotDigest,
            List<ProtectedDate> protectedDates) {
    }

    public record PageCommitResult(
            int acceptedCount,
            int quarantinedCount) {
    }
}
