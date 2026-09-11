package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.DailyFactWrite;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.ExceptionFactWrite;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.OaDocumentFactWrite;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.ProjectionDraft;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.StoredProjection;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.TimeAccountFactWrite;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublicationResult;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedCalculatedFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedCurrentExceptionFact;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedOaDocumentFact;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedProjectionMetadata;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedTimeAccountFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes one immutable, server-derived reporting projection for a legal
 * entity and calendar month. There is intentionally no REST entry point:
 * upstream calculation/OA/ledger adapters must first construct the verified
 * command types.
 */
@Service
public class AttendanceReportProjectionPublisher
        implements AttendanceReportProjectionPublicationUseCase {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern SAFE_EVIDENCE_SUMMARY = Pattern.compile(
            "(?:[^；]+；)?原因码=[A-Z0-9_:-]{1,64}；证据数量=(0|[1-9][0-9]{0,6})");
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_:-]{1,64}");
    private static final String DIGEST_FORMAT =
            "ATTENDANCE_REPORT_PROJECTION_CANONICAL_V1";

    private final AttendanceReportProjectionWriter writer;
    private final Clock clock;

    public AttendanceReportProjectionPublisher(
            AttendanceReportProjectionWriter writer, Clock clock) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    @Override
    public PublicationResult publish(PublishCommand command) {
        return publish(command, null, null);
    }

    @Transactional
    @Override
    public PublicationResult publish(
            PublishCommand command,
            LocalDate copyBefore,
            LocalDate copyFromExclusive) {
        return publish(command, copyBefore, copyFromExclusive, (String) null);
    }

    @Transactional
    @Override
    public PublicationResult publish(
            PublishCommand command,
            LocalDate copyBefore,
            LocalDate copyFromExclusive,
            java.util.Collection<String> employeeIds) {
        if (employeeIds == null || employeeIds.size() <= 1) {
            String employeeId = employeeIds == null || employeeIds.isEmpty()
                    ? null
                    : employeeIds.iterator().next();
            return publish(command, copyBefore, copyFromExclusive, employeeId);
        }
        return publishScoped(
                command, copyBefore, copyFromExclusive, employeeIds);
    }

    @Transactional
    @Override
    public PublicationResult publish(
            PublishCommand command,
            LocalDate copyBefore,
            LocalDate copyFromExclusive,
            String employeeId) {
        return publishScoped(
                command,
                copyBefore,
                copyFromExclusive,
                employeeId == null || employeeId.isBlank()
                        ? List.of()
                        : List.of(employeeId));
    }

    private PublicationResult publishScoped(
            PublishCommand command,
            LocalDate copyBefore,
            LocalDate copyFromExclusive,
            java.util.Collection<String> employeeIds) {
        CanonicalPublication publication = canonicalize(command);
        VerifiedProjectionMetadata metadata = publication.metadata();
        Instant observedNow = clock.instant();
        if (metadata.dataAsOf().isAfter(observedNow)) {
            throw new IllegalArgumentException(
                    "report projection dataAsOf cannot be in the future");
        }
        if (!writer.lockCompany(metadata.companyId())) {
            throw new IllegalArgumentException(
                    "report projection company does not exist");
        }
        var existing = writer.findByDigest(
                metadata.companyId(),
                metadata.period().atDay(1),
                publication.projectionDigest());
        if (existing.isPresent()) {
            return idempotent(existing.orElseThrow(), publication);
        }
        var latest = writer.findLatestPublished(
                        metadata.companyId(),
                        metadata.period().atDay(1));
        latest.ifPresent(value -> validateSuccessor(value, metadata));
        Instant now = monotonicPublicationTime(
                clock.instant(),
                latest.map(StoredProjection::publishedAt).orElse(null));

        String projectionId = UUID.randomUUID().toString();
        writer.createDraft(new ProjectionDraft(
                projectionId,
                metadata.companyId(),
                metadata.period().atDay(1),
                metadata.period().plusMonths(1).atDay(1),
                metadata.periodState(),
                publication.projectionVersion(),
                metadata.formulaCatalogVersion(),
                metadata.sourceVersions(),
                metadata.sourceSnapshotDigest(),
                publication.projectionDigest(),
                metadata.dataAsOf(),
                metadata.createdByPrincipalId(),
                now));

        if (copyBefore != null
                && copyFromExclusive != null
                && latest.isPresent()) {
            List<String> scopedEmployees = employeeIds == null
                    ? List.of()
                    : List.copyOf(employeeIds);
            String employeeId = scopedEmployees.size() == 1
                    ? scopedEmployees.getFirst()
                    : null;
            writer.copyFactsOutsideRange(
                    latest.orElseThrow().projectionId(),
                    projectionId,
                    copyBefore,
                    copyFromExclusive,
                    now,
                    employeeId,
                    scopedEmployees);
            if (!scopedEmployees.isEmpty()) {
                Instant windowStart = copyBefore
                        .atStartOfDay(BUSINESS_ZONE)
                        .toInstant();
                Instant windowEnd = copyFromExclusive
                        .atStartOfDay(BUSINESS_ZONE)
                        .toInstant();
                writer.copyOaDocumentFactsExceptEmployeeWindow(
                        latest.orElseThrow().projectionId(),
                        projectionId,
                        windowStart,
                        windowEnd,
                        now,
                        employeeId,
                        scopedEmployees);
                writer.copyTimeAccountFactsExceptEmployee(
                        latest.orElseThrow().projectionId(),
                        projectionId,
                        now,
                        employeeId,
                        scopedEmployees);
            }
        }

        List<DailyFactWrite> dailyWrites = new ArrayList<>();
        List<ExceptionFactWrite> exceptionWrites = new ArrayList<>();
        List<OaDocumentFactWrite> oaWrites = new ArrayList<>();
        List<TimeAccountFactWrite> accountWrites = new ArrayList<>();
        for (VerifiedCalculatedFacts calculated :
                publication.calculatedFacts()) {
            DailyFact daily = calculated.facts().dailyFact();
            dailyWrites.add(new DailyFactWrite(
                    UUID.randomUUID().toString(),
                    projectionId,
                    calculated.employeeVersionId(),
                    calculated.employmentAssignmentId(),
                    daily,
                    now));
            for (ExceptionFact exception :
                    calculated.facts().exceptionFacts()) {
                exceptionWrites.add(new ExceptionFactWrite(
                        UUID.randomUUID().toString(),
                        projectionId,
                        daily.companyId(),
                        calculated.employeeVersionId(),
                        calculated.employmentAssignmentId(),
                        daily.organizationVersionId(),
                        exception,
                        now));
            }
        }
        for (VerifiedCurrentExceptionFact current :
                publication.currentExceptionFacts()) {
            exceptionWrites.add(new ExceptionFactWrite(
                    UUID.randomUUID().toString(),
                    projectionId,
                    current.companyId(),
                    current.employeeVersionId(),
                    current.employmentAssignmentId(),
                    current.organizationVersionId(),
                    current.fact(),
                    now));
        }
        for (VerifiedOaDocumentFact oa : publication.oaDocumentFacts()) {
            oaWrites.add(new OaDocumentFactWrite(
                    UUID.randomUUID().toString(),
                    projectionId,
                    oa.companyId(),
                    oa.oaAttendanceDocumentId(),
                    oa.employeeId(),
                    oa.employeeVersionId(),
                    oa.employmentAssignmentId(),
                    oa.organizationId(),
                    oa.organizationVersionId(),
                    oa.documentType(),
                    oa.leaveTypeCode(),
                    oa.temporalShape(),
                    oa.pointInstant(),
                    oa.intervalStart(),
                    oa.intervalEndExclusive(),
                    oa.recognizedMinutes(),
                    oa.sourceStatus(),
                    oa.sourceVersion(),
                    now));
        }
        for (VerifiedTimeAccountFact account :
                publication.timeAccountFacts()) {
            accountWrites.add(new TimeAccountFactWrite(
                    UUID.randomUUID().toString(),
                    projectionId,
                    account.companyId(),
                    account.accountId(),
                    account.employeeId(),
                    account.employeeVersionId(),
                    account.organizationId(),
                    account.organizationVersionId(),
                    account.accountType(),
                    account.openingHours(),
                    account.grantedHours(),
                    account.overtimeCreditHours(),
                    account.manualIncreaseHours(),
                    account.usedHours(),
                    account.expiredHours(),
                    account.returnedHours(),
                    account.manualDeductionHours(),
                    account.ledgerVersion(),
                    now));
        }
        writer.appendDailyFacts(dailyWrites);
        writer.appendExceptionFacts(exceptionWrites);
        writer.appendOaDocumentFacts(oaWrites);
        writer.appendTimeAccountFacts(accountWrites);
        writer.markPublished(projectionId, now);
        return new PublicationResult(
                projectionId,
                publication.projectionVersion(),
                publication.projectionDigest(),
                metadata.periodState(),
                metadata.dataAsOf(),
                now,
                true);
    }

    private Instant monotonicPublicationTime(
            Instant candidate, Instant latestPublishedAt) {
        Instant databaseCandidate = candidate.truncatedTo(ChronoUnit.MICROS);
        if (latestPublishedAt == null
                || databaseCandidate.isAfter(latestPublishedAt)) {
            return databaseCandidate;
        }
        return latestPublishedAt.plusNanos(1_000);
    }

    private PublicationResult idempotent(
            StoredProjection stored, CanonicalPublication publication) {
        VerifiedProjectionMetadata metadata = publication.metadata();
        boolean sameMetadata =
                stored.companyId().equals(metadata.companyId())
                        && stored.periodStart().equals(
                                metadata.period().atDay(1))
                        && stored.periodEndExclusive().equals(
                                metadata.period().plusMonths(1).atDay(1))
                        && stored.periodState() == metadata.periodState()
                        && stored.projectionVersion().equals(
                                publication.projectionVersion())
                        && stored.formulaCatalogVersion().equals(
                                metadata.formulaCatalogVersion())
                        && stored.sourceVersions().equals(
                                metadata.sourceVersions())
                        && stored.sourceSnapshotDigest().equals(
                                metadata.sourceSnapshotDigest())
                        && stored.projectionDigest().equals(
                                publication.projectionDigest())
                        && stored.dataAsOf().equals(metadata.dataAsOf());
        if (!sameMetadata
                || !"PUBLISHED".equals(stored.status())
                || stored.publishedAt() == null) {
            throw new IllegalStateException(
                    "stored report projection conflicts with canonical content");
        }
        return new PublicationResult(
                stored.projectionId(),
                stored.projectionVersion(),
                stored.projectionDigest(),
                stored.periodState(),
                stored.dataAsOf(),
                stored.publishedAt(),
                false);
    }

    private void validateSuccessor(
            StoredProjection latest,
            VerifiedProjectionMetadata incoming) {
        if (!"PUBLISHED".equals(latest.status())
                || !latest.companyId().equals(
                        incoming.companyId())
                || !latest.periodStart().equals(
                        incoming.period().atDay(1))
                || latest.publishedAt() == null) {
            throw new IllegalStateException(
                    "latest report projection metadata is invalid");
        }
        if (incoming.dataAsOf().isBefore(latest.dataAsOf())) {
            throw new IllegalArgumentException(
                    "report projection cannot publish an older snapshot");
        }
        boolean transitionAllowed = switch (latest.periodState()) {
            case OPEN -> incoming.periodState() == PeriodState.OPEN
                    || incoming.periodState() == PeriodState.FROZEN
                    || incoming.periodState() == PeriodState.CLOSED;
            case FROZEN ->
                incoming.periodState() == PeriodState.CLOSED;
            case CLOSED ->
                incoming.periodState() == PeriodState.REOPENED;
            case REOPENED ->
                incoming.periodState() == PeriodState.REOPENED
                        || incoming.periodState() == PeriodState.FROZEN
                        || incoming.periodState() == PeriodState.CLOSED;
        };
        if (!transitionAllowed) {
            throw new IllegalArgumentException(
                    "report projection period-state transition is not allowed");
        }
    }

    private CanonicalPublication canonicalize(PublishCommand command) {
        Objects.requireNonNull(command, "command");
        VerifiedProjectionMetadata metadata = command.metadata();
        List<VerifiedCalculatedFacts> calculated = new ArrayList<>(
                command.calculatedFacts());
        calculated.sort(Comparator.comparing(
                        (VerifiedCalculatedFacts value) ->
                                value.facts().dailyFact().businessDate())
                .thenComparing(value ->
                        value.facts().dailyFact().employeeId())
                .thenComparing(value ->
                        value.facts().dailyFact().factId()));
        List<VerifiedOaDocumentFact> oa =
                new ArrayList<>(command.oaDocumentFacts());
        oa.sort(Comparator.comparing(
                VerifiedOaDocumentFact::oaAttendanceDocumentId));
        List<VerifiedCurrentExceptionFact> currentExceptions =
                new ArrayList<>(command.currentExceptionFacts());
        currentExceptions.sort(Comparator.comparing(
                value -> value.fact().caseId()));
        List<VerifiedTimeAccountFact> accounts =
                new ArrayList<>(command.timeAccountFacts());
        accounts.sort(Comparator.comparing(
                VerifiedTimeAccountFact::accountId));

        Set<String> caseIds = validateCalculated(metadata, calculated);
        validateCurrentExceptions(metadata, currentExceptions, caseIds);
        validateOa(metadata, oa);
        validateAccounts(metadata, accounts);

        CanonicalDigest digest = new CanonicalDigest();
        digest.add(DIGEST_FORMAT);
        digest.add(metadata.companyId());
        digest.add(metadata.period().toString());
        digest.add(metadata.periodState().name());
        digest.add(metadata.formulaCatalogVersion());
        digest.add(metadata.sourceSnapshotDigest());
        digest.add(metadata.dataAsOf().toString());
        digest.add(Integer.toString(metadata.sourceVersions().size()));
        metadata.sourceVersions().forEach(digest::add);

        digest.add(Integer.toString(calculated.size()));
        calculated.forEach(value -> appendCalculated(digest, value));
        digest.add(Integer.toString(currentExceptions.size()));
        currentExceptions.forEach(value -> appendCurrentException(
                digest, value));
        digest.add(Integer.toString(oa.size()));
        oa.forEach(value -> appendOa(digest, value));
        digest.add(Integer.toString(accounts.size()));
        accounts.forEach(value -> appendAccount(digest, value));
        String projectionDigest = digest.finish();
        return new CanonicalPublication(
                metadata,
                List.copyOf(calculated),
                List.copyOf(currentExceptions),
                List.copyOf(oa),
                List.copyOf(accounts),
                projectionDigest,
                "ARP1-" + projectionDigest);
    }

    private Set<String> validateCalculated(
            VerifiedProjectionMetadata metadata,
            List<VerifiedCalculatedFacts> calculatedFacts) {
        Set<String> dailyKeys = new HashSet<>();
        Set<String> caseIds = new HashSet<>();
        for (VerifiedCalculatedFacts calculated : calculatedFacts) {
            AttendanceReportPublicationModels.databaseId(
                    calculated.employeeVersionId(), "employeeVersionId");
            AttendanceReportPublicationModels.databaseId(
                    calculated.employmentAssignmentId(),
                    "employmentAssignmentId");
            DailyFact daily = calculated.facts().dailyFact();
            validateDaily(metadata, daily);
            if (!dailyKeys.add(
                    daily.employeeId() + "\u001f" + daily.businessDate())) {
                throw new IllegalArgumentException(
                        "report projection contains a duplicate employee day");
            }
            for (ExceptionFact exception :
                    calculated.facts().exceptionFacts()) {
                validateException(daily, exception);
                if (!caseIds.add(exception.caseId())) {
                    throw new IllegalArgumentException(
                            "report projection contains a duplicate exception");
                }
            }
        }
        return caseIds;
    }

    private void validateCurrentExceptions(
            VerifiedProjectionMetadata metadata,
            List<VerifiedCurrentExceptionFact> facts,
            Set<String> caseIds) {
        for (VerifiedCurrentExceptionFact verified : facts) {
            ExceptionFact fact = verified.fact();
            if (!metadata.companyId().equals(verified.companyId())
                    || !metadata.period().equals(
                            java.time.YearMonth.from(fact.businessDate()))
                    || !caseIds.add(fact.caseId())) {
                throw new IllegalArgumentException(
                        "current exception is outside scope or duplicated");
            }
            AttendanceReportPublicationModels.databaseId(
                    fact.employeeId(), "employeeId");
            AttendanceReportPublicationModels.databaseId(
                    fact.organizationId(), "organizationId");
            AttendanceReportPublicationModels.reference(
                    fact.employeeNumber(), "employeeNumber", 128);
            AttendanceReportPublicationModels.reference(
                    fact.employeeName(), "employeeName", 100);
            AttendanceReportPublicationModels.reference(
                    fact.organizationName(), "organizationName", 200);
            validateExceptionFields(fact);
        }
    }

    private void validateDaily(
            VerifiedProjectionMetadata metadata, DailyFact fact) {
        if (!metadata.companyId().equals(fact.companyId())
                || !metadata.period().equals(
                        java.time.YearMonth.from(fact.businessDate()))) {
            throw new IllegalArgumentException(
                    "daily fact is outside the projection scope");
        }
        AttendanceReportPublicationModels.reference(
                fact.factId(), "dailyFactId", 128);
        AttendanceReportPublicationModels.databaseId(
                fact.employeeId(), "employeeId");
        AttendanceReportPublicationModels.reference(
                fact.employeeNumber(), "employeeNumber", 128);
        AttendanceReportPublicationModels.reference(
                fact.employeeName(), "employeeName", 100);
        AttendanceReportPublicationModels.databaseId(
                fact.organizationId(), "organizationId");
        AttendanceReportPublicationModels.databaseId(
                fact.organizationVersionId(), "organizationVersionId");
        AttendanceReportPublicationModels.reference(
                fact.organizationName(), "organizationName", 200);
        AttendanceReportPublicationModels.reference(
                fact.shiftLabel(), "shiftLabel", 200);
        AttendanceReportPublicationModels.unsignedInt(
                fact.scheduledMinutes(), "scheduledMinutes");
        AttendanceReportPublicationModels.unsignedInt(
                fact.confirmedScheduledWorkMinutes(),
                "confirmedScheduledWorkMinutes");
        AttendanceReportPublicationModels.unsignedInt(
                fact.recognizedOvertimeMinutes(),
                "recognizedOvertimeMinutes");
        AttendanceReportPublicationModels.unsignedInt(
                fact.paidOvertimeMinutes(), "paidOvertimeMinutes");
        AttendanceReportPublicationModels.unsignedInt(
                fact.compensatoryOvertimeMinutes(),
                "compensatoryOvertimeMinutes");
        AttendanceReportPublicationModels.unsignedInt(
                fact.voluntaryOvertimeMinutes(),
                "voluntaryOvertimeMinutes");
        AttendanceReportPublicationModels.unsignedInt(
                fact.totalOvertimeMinutes(), "totalOvertimeMinutes");
        AttendanceReportPublicationModels.unsignedInt(
                fact.leaveOrTimeOffMinutes(), "leaveOrTimeOffMinutes");
        AttendanceReportPublicationModels.unsignedInt(
                fact.absenceMinutes(), "absenceMinutes");
        AttendanceReportPublicationModels.unsignedInt(
                fact.actualWorkMinutes(), "actualWorkMinutes");
        AttendanceReportPublicationModels.unsignedInt(
                fact.lateMinutes(), "lateMinutes");
        AttendanceReportPublicationModels.unsignedInt(
                fact.penalizedLateMinutes(), "penalizedLateMinutes");
        AttendanceReportPublicationModels.unsignedInt(
                fact.earlyDepartureMinutes(), "earlyDepartureMinutes");
        AttendanceReportPublicationModels.unsignedInt(
                fact.missingPunchCount(), "missingPunchCount");
        AttendanceReportPublicationModels.version(
                fact.calculationVersionId(), "calculationVersionId");
        AttendanceReportPublicationModels.digest(
                fact.resultDigest(), "resultDigest");
        if (fact.firstPunchAt() != null) {
            AttendanceReportPublicationModels.databaseInstant(
                    fact.firstPunchAt(), "firstPunchAt");
        }
        if (fact.lastPunchAt() != null) {
            AttendanceReportPublicationModels.databaseInstant(
                    fact.lastPunchAt(), "lastPunchAt");
        }
    }

    private void validateException(
            DailyFact daily, ExceptionFact exception) {
        boolean sameCalculatedSubject =
                exception.employeeId().equals(daily.employeeId())
                        && exception.employeeNumber().equals(
                                daily.employeeNumber())
                        && exception.employeeName().equals(daily.employeeName())
                        && exception.organizationId().equals(
                                daily.organizationId())
                        && exception.organizationName().equals(
                                daily.organizationName())
                        && exception.businessDate().equals(daily.businessDate())
                        && exception.calculationVersionId().equals(
                                daily.calculationVersionId());
        if (!sameCalculatedSubject) {
            throw new IllegalArgumentException(
                    "exception fact is not bound to its daily result");
        }
        if ("LATE".equals(exception.exceptionType())
                && daily.penalizedLateMinutes() == 0
                && exception.minutes() > 0) {
            throw new IllegalArgumentException(
                    "grace-exempt late cannot be published as an exception");
        }
        validateExceptionFields(exception);
    }

    private void validateExceptionFields(ExceptionFact exception) {
        AttendanceReportPublicationModels.reference(
                exception.caseId(), "exceptionCaseId", 128);
        AttendanceReportPublicationModels.version(
                exception.calculationVersionId(), "calculationVersionId");
        if (!CODE.matcher(exception.exceptionType()).matches()
                || !SAFE_EVIDENCE_SUMMARY
                        .matcher(exception.safeEvidenceSummary())
                        .matches()) {
            throw new IllegalArgumentException(
                    "exception fact contains non-report-safe evidence");
        }
        AttendanceReportPublicationModels.unsignedInt(
                exception.minutes(), "exceptionMinutes");
    }

    private void validateOa(
            VerifiedProjectionMetadata metadata,
            List<VerifiedOaDocumentFact> facts) {
        Set<String> documentIds = new HashSet<>();
        Instant periodStart = metadata.period()
                .atDay(1)
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();
        Instant periodEnd = metadata.period()
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();
        for (VerifiedOaDocumentFact fact : facts) {
            if (!metadata.companyId().equals(fact.companyId())
                    || !documentIds.add(fact.oaAttendanceDocumentId())) {
                throw new IllegalArgumentException(
                        "OA fact is outside scope or duplicated");
            }
            boolean intersects = fact.temporalShape()
                            == AttendanceReportPublicationModels
                                    .OaTemporalShape.POINT
                    ? !fact.pointInstant().isBefore(periodStart)
                            && fact.pointInstant().isBefore(periodEnd)
                    : fact.intervalStart().isBefore(periodEnd)
                            && fact.intervalEndExclusive().isAfter(periodStart);
            if (!intersects) {
                throw new IllegalArgumentException(
                        "OA fact does not intersect the projection month");
            }
        }
    }

    private void validateAccounts(
            VerifiedProjectionMetadata metadata,
            List<VerifiedTimeAccountFact> facts) {
        Set<String> accountIds = new HashSet<>();
        for (VerifiedTimeAccountFact fact : facts) {
            if (!metadata.companyId().equals(fact.companyId())
                    || !accountIds.add(fact.accountId())) {
                throw new IllegalArgumentException(
                        "time-account fact is outside scope or duplicated");
            }
        }
    }

    private static void appendCalculated(
            CanonicalDigest digest, VerifiedCalculatedFacts value) {
        DailyFact fact = value.facts().dailyFact();
        digest.add(value.employeeVersionId());
        digest.add(value.employmentAssignmentId());
        digest.add(fact.companyId());
        digest.add(fact.employeeId());
        digest.add(fact.organizationId());
        digest.add(fact.organizationVersionId());
        digest.add(fact.businessDate().toString());
        digest.add(fact.dayType().name());
        digest.add(fact.shiftLabel());
        add(digest, fact.scheduledMinutes());
        add(digest, fact.confirmedScheduledWorkMinutes());
        add(digest, fact.recognizedOvertimeMinutes());
        add(digest, fact.paidOvertimeMinutes());
        add(digest, fact.compensatoryOvertimeMinutes());
        add(digest, fact.voluntaryOvertimeMinutes());
        add(digest, fact.totalOvertimeMinutes());
        add(digest, fact.leaveOrTimeOffMinutes());
        add(digest, fact.absenceMinutes());
        add(digest, fact.actualWorkMinutes());
        add(digest, fact.lateMinutes());
        add(digest, fact.penalizedLateMinutes());
        add(digest, fact.earlyDepartureMinutes());
        add(digest, fact.missingPunchCount());
        digest.add(nullable(fact.firstPunchAt()));
        digest.add(nullable(fact.lastPunchAt()));
        digest.add(fact.calculationVersionId());
        digest.add(fact.resultDigest());
        List<ExceptionFact> exceptions =
                new ArrayList<>(value.facts().exceptionFacts());
        exceptions.sort(Comparator.comparing(ExceptionFact::caseId));
        digest.add(Integer.toString(exceptions.size()));
        exceptions.forEach(exception -> {
            digest.add(exception.caseId());
            digest.add(exception.exceptionType());
            digest.add(exception.severity().name());
            digest.add(exception.state().name());
            add(digest, exception.minutes());
            digest.add(exception.safeEvidenceSummary());
            digest.add(exception.calculationVersionId());
        });
    }

    private static void appendOa(
            CanonicalDigest digest, VerifiedOaDocumentFact fact) {
        digest.add(fact.companyId());
        digest.add(fact.oaAttendanceDocumentId());
        digest.add(fact.employeeId());
        digest.add(fact.employeeVersionId());
        digest.add(fact.employmentAssignmentId());
        digest.add(fact.organizationId());
        digest.add(fact.organizationVersionId());
        digest.add(fact.documentType());
        digest.add(fact.leaveTypeCode() == null ? "" : fact.leaveTypeCode());
        digest.add(fact.temporalShape().name());
        digest.add(nullable(fact.pointInstant()));
        digest.add(nullable(fact.intervalStart()));
        digest.add(nullable(fact.intervalEndExclusive()));
        add(digest, fact.recognizedMinutes());
        digest.add(fact.sourceStatus());
        digest.add(fact.sourceVersion());
        digest.add(fact.sourceOrigin());
    }

    private static void appendCurrentException(
            CanonicalDigest digest, VerifiedCurrentExceptionFact value) {
        ExceptionFact fact = value.fact();
        digest.add(value.companyId());
        digest.add(value.employeeVersionId());
        digest.add(value.employmentAssignmentId());
        digest.add(value.organizationVersionId());
        digest.add(fact.caseId());
        digest.add(fact.employeeId());
        digest.add(fact.organizationId());
        digest.add(fact.businessDate().toString());
        digest.add(fact.exceptionType());
        digest.add(fact.severity().name());
        digest.add(fact.state().name());
        add(digest, fact.minutes());
        digest.add(fact.safeEvidenceSummary());
        digest.add(fact.calculationVersionId());
    }

    private static void appendAccount(
            CanonicalDigest digest, VerifiedTimeAccountFact fact) {
        digest.add(fact.companyId());
        digest.add(fact.accountId());
        digest.add(fact.employeeId());
        digest.add(fact.employeeVersionId());
        digest.add(fact.organizationId());
        digest.add(fact.organizationVersionId());
        digest.add(fact.accountType().name());
        digest.add(fact.openingHours().toPlainString());
        digest.add(fact.grantedHours().toPlainString());
        digest.add(fact.overtimeCreditHours().toPlainString());
        digest.add(fact.manualIncreaseHours().toPlainString());
        digest.add(fact.usedHours().toPlainString());
        digest.add(fact.expiredHours().toPlainString());
        digest.add(fact.returnedHours().toPlainString());
        digest.add(fact.manualDeductionHours().toPlainString());
        digest.add(fact.ledgerVersion());
    }

    private static void add(CanonicalDigest digest, long value) {
        digest.add(Long.toString(value));
    }

    private static String nullable(Instant value) {
        return value == null ? "" : value.toString();
    }

    private record CanonicalPublication(
            VerifiedProjectionMetadata metadata,
            List<VerifiedCalculatedFacts> calculatedFacts,
            List<VerifiedCurrentExceptionFact> currentExceptionFacts,
            List<VerifiedOaDocumentFact> oaDocumentFacts,
            List<VerifiedTimeAccountFact> timeAccountFacts,
            String projectionDigest,
            String projectionVersion) {
    }

    private static final class CanonicalDigest {

        private final MessageDigest digest;

        private CanonicalDigest() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "required report digest is unavailable", exception);
            }
        }

        private void add(String value) {
            byte[] bytes = Objects.requireNonNull(value, "canonical value")
                    .getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(bytes.length)
                    .array());
            digest.update(bytes);
        }

        private String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
