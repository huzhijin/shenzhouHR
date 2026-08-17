package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationInputSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationPolicy;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.GraceConsumptionSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.IntervalEvidence;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchDirection;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchEvent;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ScheduledWorkSegment;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.SegmentKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.attendance.calculation.domain.DeterministicAttendanceCalculator;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.PunchSide;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.application.AttendanceReportCalculationOrchestrator;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionContext;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedCalculatedFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedOaDocumentFact;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedProjectionMetadata;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedTimeAccountFact;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.OaTemporalShape;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.AttendancePolicyRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.EmployeeIdentityIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaDocumentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaReportFactRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchCorrectionRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchExemptionRoleIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchEventRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.TimeAccountSnapshotRow;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full-featured calculation orchestrator that uses DeterministicAttendanceCalculator
 * to process all evidence types including OA documents, punches, and scheduled shifts.
 *
 * <p>This replaces the simplified PUNCH_SPAN_V1 orchestrator with a complete
 * attendance calculation engine that handles:
 * <ul>
 *   <li>Scheduled work segments from shift templates
 *   <li>Punch events with direction and timing
 *   <li>OA documents (leave, overtime, outing, exemption, time off, etc.)
 *   <li>Late/early departure/absence detection
 *   <li>Overtime calculation
 *   <li>Exception handling
 * </ul>
 */
@Service
@Primary
public class FullCalculationEngineOrchestrator
        implements AttendanceReportCalculationOrchestrator {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String FORMULA_CATALOG_VERSION =
            "FULL_CALCULATION_OVERTIME_CLASSIFICATION_V2";
    private static final String CALCULATION_VERSION_ID =
            "ATTENDANCE.FULL_CALC:V2";
    private static final List<String> SOURCE_VERSIONS = List.of(
            "PEOPLE.EMPLOYEE_VERSION:V1",
            "PEOPLE.EMPLOYMENT_ASSIGNMENT:V1",
            "ORGANIZATION.ORGANIZATION_VERSION:V1",
            "ATTENDANCE.EFFECTIVE_EVENT:V1",
            "ATTENDANCE.PUNCH_CORRECTION:V1",
            "ATTENDANCE.WORK_CALENDAR_DAY:V1",
            "ATTENDANCE.SHIFT_SEGMENT:V1",
            "AUTH.PUNCH_EXEMPT_ROLE:V1",
            "ATTENDANCE.OA_DOCUMENT:V2",
            "LEAVE.TIME_ACCOUNT_LEDGER:V1");

    private final AttendanceReportCalculationMapper mapper;
    private final DeterministicAttendanceCalculator calculator;
    private final AttendanceReportFactProjector factProjector;

    public FullCalculationEngineOrchestrator(
            AttendanceReportCalculationMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.calculator = new DeterministicAttendanceCalculator();
        this.factProjector = new AttendanceReportFactProjector();
    }

    @Override
    @Transactional(readOnly = true)
    public PublishCommand assemble(
            String companyId,
            YearMonth period,
            PeriodState periodState,
            String principalId,
            Instant dataAsOf) {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(periodState, "periodState");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(dataAsOf, "dataAsOf");

        LocalDate periodStart = period.atDay(1);
        LocalDate periodEndExclusive = period.plusMonths(1).atDay(1);
        Instant windowStart = periodStart.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant windowEnd = periodEndExclusive.atStartOfDay(BUSINESS_ZONE).toInstant();

        // Load all base data in batch
        List<EmployeeIdentityIntervalRow> identities =
                mapper.findEmployeeIdentityIntervals(
                        companyId, periodStart, periodEndExclusive);
        List<ShiftSegmentRow> shiftSegments =
                mapper.findScheduledWorkSegments(
                        companyId,
                        periodStart,
                        periodEndExclusive,
                        dataAsOf);
        Instant evidenceWindowStart = shiftSegments.stream()
                .map(this::segmentEvidenceStart)
                .min(Comparator.naturalOrder())
                .filter(value -> value.isBefore(windowStart))
                .orElse(windowStart);
        Instant evidenceWindowEnd = shiftSegments.stream()
                .map(this::segmentEvidenceEnd)
                .max(Comparator.naturalOrder())
                .filter(value -> value.isAfter(windowEnd))
                .orElse(windowEnd);
        List<PunchEventRow> punchRows =
                mapper.findActivatedPunchEvents(
                        companyId,
                        evidenceWindowStart,
                        evidenceWindowEnd,
                        dataAsOf);
        List<PunchCorrectionRow> punchCorrections =
                mapper.findApprovedPunchCorrections(
                        companyId,
                        periodStart,
                        periodEndExclusive,
                        dataAsOf);
        List<PunchExemptionRoleIntervalRow> punchExemptionRoles =
                mapper.findPunchExemptionRoleIntervals(
                        companyId, evidenceWindowStart, evidenceWindowEnd);
        List<CalendarDayRow> calendarDays =
                mapper.findPublishedCalendarDays(
                        companyId,
                        periodStart,
                        periodEndExclusive,
                        dataAsOf);
        List<OaDocumentRow> oaDocuments =
                mapper.findEffectiveOaDocuments(
                        companyId,
                        evidenceWindowStart,
                        evidenceWindowEnd,
                        dataAsOf);
        List<OaReportFactRow> reportableOaDocuments =
                mapper.findReportableOaDocuments(
                        companyId,
                        windowStart,
                        windowEnd,
                        dataAsOf);
        List<TimeAccountSnapshotRow> timeAccountSnapshots =
                mapper.findTimeAccountSnapshots(
                        companyId,
                        periodStart,
                        periodEndExclusive,
                        dataAsOf);
        AttendancePolicyRow policyRow = mapper.findAttendancePolicy(companyId);

        // Index data by employee
        Map<String, List<EmployeeIdentityIntervalRow>> identitiesByEmployee =
                groupBy(identities, EmployeeIdentityIntervalRow::employeeId);
        Map<String, List<PunchEventRow>> punchesByEmployee =
                groupBy(punchRows, PunchEventRow::employeeId);
        Map<String, List<PunchCorrectionRow>> correctionsByEmployee =
                groupBy(punchCorrections, PunchCorrectionRow::employeeId);
        Map<String, List<PunchExemptionRoleIntervalRow>> rolesByEmployee =
                groupBy(
                        punchExemptionRoles,
                        PunchExemptionRoleIntervalRow::employeeId);
        Map<String, Map<LocalDate, List<ShiftSegmentRow>>> segmentsByEmployeeAndDate =
                indexShiftSegments(shiftSegments);
        Map<LocalDate, DayType> dayTypes = buildDayTypeMap(calendarDays);

        // Index OA documents by employee number, then convert to employee ID
        Map<String, List<OaDocumentRow>> oaByEmployeeNumber =
                groupBy(oaDocuments, OaDocumentRow::employeeNumber);
        Map<String, List<IntervalEvidence>> oaEvidenceByEmployee =
                mapOaToEmployeeId(oaByEmployeeNumber, identities);

        CalculationPolicy policy = buildPolicy(policyRow, periodEndExclusive);

        // Calculate for each employee-day
        List<VerifiedCalculatedFacts> allFacts = new ArrayList<>();
        for (Map.Entry<String, List<EmployeeIdentityIntervalRow>> entry :
                identitiesByEmployee.entrySet()) {
            String employeeId = entry.getKey();
            List<PunchEventRow> employeePunches =
                    punchesByEmployee.getOrDefault(employeeId, List.of());
            List<PunchCorrectionRow> employeeCorrections =
                    correctionsByEmployee.getOrDefault(employeeId, List.of());
            List<PunchExemptionRoleIntervalRow> employeePunchExemptionRoles =
                    rolesByEmployee.getOrDefault(employeeId, List.of());
            List<IntervalEvidence> employeeOaEvidence =
                    oaEvidenceByEmployee.getOrDefault(employeeId, List.of());
            Map<LocalDate, List<ShiftSegmentRow>> employeeSegments =
                    segmentsByEmployeeAndDate.getOrDefault(employeeId, Map.of());

            for (LocalDate businessDate = periodStart;
                    businessDate.isBefore(periodEndExclusive);
                    businessDate = businessDate.plusDays(1)) {
                EmployeeIdentityIntervalRow identity =
                        findUnambiguousIdentity(entry.getValue(), businessDate);
                if (identity == null) {
                    continue;
                }

                DayType dayType = dayTypes.getOrDefault(
                        businessDate, weekdayDayType(businessDate));
                DailyAttendanceResult result = calculateDay(
                        companyId,
                        identity,
                        businessDate,
                        dayType,
                        employeeSegments.getOrDefault(businessDate, List.of()),
                        employeePunches,
                        employeeCorrections,
                        employeePunchExemptionRoles,
                        employeeOaEvidence,
                        policy,
                        period,
                        dataAsOf);
                LeaveType leaveType = leaveTypeForDay(
                        employeeOaEvidence, businessDate);

                allFacts.add(convertToVerifiedFacts(
                        companyId,
                        identity,
                        businessDate,
                        dayType,
                        result,
                        leaveType));
            }
        }

        allFacts.sort((left, right) ->
                left.facts().dailyFact().factId()
                        .compareTo(right.facts().dailyFact().factId()));
        List<VerifiedOaDocumentFact> oaReportFacts = projectOaReportFacts(
                companyId,
                reportableOaDocuments,
                identities,
                shiftSegments);
        List<VerifiedTimeAccountFact> timeAccountFacts =
                projectTimeAccountFacts(
                        companyId,
                        periodStart,
                        periodEndExclusive,
                        timeAccountSnapshots,
                        identities);

        VerifiedProjectionMetadata metadata = new VerifiedProjectionMetadata(
                companyId,
                period,
                periodState,
                FORMULA_CATALOG_VERSION,
                SOURCE_VERSIONS,
                sourceSnapshotDigest(
                        companyId,
                        period,
                        allFacts,
                        oaReportFacts,
                        timeAccountFacts),
                dataAsOf,
                principalId);

        return new PublishCommand(
                metadata,
                allFacts,
                List.of(),
                oaReportFacts,
                timeAccountFacts);
    }

    private List<VerifiedOaDocumentFact> projectOaReportFacts(
            String companyId,
            List<OaReportFactRow> rows,
            List<EmployeeIdentityIntervalRow> identities,
            List<ShiftSegmentRow> shiftSegments) {
        return rows.stream()
                .map(row -> {
                    LocalDate occurrenceDate = row.startInstant()
                            .atZone(BUSINESS_ZONE)
                            .toLocalDate();
                    List<EmployeeIdentityIntervalRow> candidates = identities.stream()
                            .filter(candidate -> candidate.employeeId()
                                    .equals(row.employeeId()))
                            .filter(candidate -> candidate.employmentAssignmentId()
                                    .equals(row.employmentAssignmentId()))
                            .toList();
                    EmployeeIdentityIntervalRow identity =
                            AttendanceReportCalculationRows
                                    .latestEffectiveAssignment(
                                            candidates,
                                            occurrenceDate);
                    if (identity == null) {
                        throw new IllegalStateException(
                                "No unambiguous occurrence-time identity for OA document "
                                        + row.oaAttendanceDocumentId());
                    }
                    long recognizedMinutes = recognizedScheduledMinutes(
                            row,
                            shiftSegments);
                    return new VerifiedOaDocumentFact(
                            companyId,
                            row.oaAttendanceDocumentId(),
                            identity.employeeId(),
                            identity.employeeVersionId(),
                            identity.employmentAssignmentId(),
                            identity.organizationId(),
                            identity.organizationVersionId(),
                            row.documentType(),
                            row.leaveType() == null
                                    ? null
                                    : row.leaveType().name(),
                            OaTemporalShape.INTERVAL,
                            null,
                            row.startInstant(),
                            row.endInstant(),
                            recognizedMinutes,
                            row.sourceStatus(),
                            row.sourceVersion());
                })
                .sorted(Comparator.comparing(
                        VerifiedOaDocumentFact::oaAttendanceDocumentId))
                .toList();
    }

    private long recognizedScheduledMinutes(
            OaReportFactRow document,
            List<ShiftSegmentRow> shiftSegments) {
        List<TimeInterval> overlaps = shiftSegments.stream()
                .filter(segment -> segment.employeeId()
                        .equals(document.employeeId()))
                .map(segment -> overlap(
                        document.startInstant(),
                        document.endInstant(),
                        segment.segmentStart(),
                        segment.segmentEnd()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(TimeInterval::start))
                .toList();
        if (overlaps.isEmpty()) {
            return 0;
        }
        long minutes = 0;
        Instant currentStart = overlaps.getFirst().start();
        Instant currentEnd = overlaps.getFirst().end();
        for (int index = 1; index < overlaps.size(); index++) {
            TimeInterval next = overlaps.get(index);
            if (!next.start().isAfter(currentEnd)) {
                if (next.end().isAfter(currentEnd)) {
                    currentEnd = next.end();
                }
                continue;
            }
            minutes += Duration.between(currentStart, currentEnd).toMinutes();
            currentStart = next.start();
            currentEnd = next.end();
        }
        return minutes + Duration.between(currentStart, currentEnd).toMinutes();
    }

    private TimeInterval overlap(
            Instant leftStart,
            Instant leftEnd,
            Instant rightStart,
            Instant rightEnd) {
        Instant start = leftStart.isAfter(rightStart) ? leftStart : rightStart;
        Instant end = leftEnd.isBefore(rightEnd) ? leftEnd : rightEnd;
        return start.isBefore(end) ? new TimeInterval(start, end) : null;
    }

    private List<VerifiedTimeAccountFact> projectTimeAccountFacts(
            String companyId,
            LocalDate periodStart,
            LocalDate periodEndExclusive,
            List<TimeAccountSnapshotRow> rows,
            List<EmployeeIdentityIntervalRow> identities) {
        return rows.stream()
                .map(row -> {
                    EmployeeIdentityIntervalRow identity =
                            identityForAccount(
                                    row,
                                    periodStart,
                                    periodEndExclusive,
                                    identities);
                    return new VerifiedTimeAccountFact(
                            companyId,
                            row.accountId(),
                            row.employeeId(),
                            identity.employeeVersionId(),
                            identity.organizationId(),
                            identity.organizationVersionId(),
                            row.accountType(),
                            row.openingHours(),
                            row.grantedHours(),
                            row.overtimeCreditHours(),
                            row.manualIncreaseHours(),
                            row.usedHours(),
                            row.expiredHours(),
                            row.returnedHours(),
                            row.manualDeductionHours(),
                            row.ledgerVersion());
                })
                .sorted(Comparator.comparing(VerifiedTimeAccountFact::accountId))
                .toList();
    }

    private EmployeeIdentityIntervalRow identityForAccount(
            TimeAccountSnapshotRow account,
            LocalDate periodStart,
            LocalDate periodEndExclusive,
            List<EmployeeIdentityIntervalRow> identities) {
        List<EmployeeIdentityIntervalRow> candidates = identities.stream()
                .filter(candidate -> candidate.employeeId()
                        .equals(account.employeeId()))
                .filter(candidate -> candidate.employmentAssignmentId()
                        .equals(account.employmentAssignmentId()))
                .toList();
        for (LocalDate date = periodEndExclusive.minusDays(1);
                !date.isBefore(periodStart);
                date = date.minusDays(1)) {
            EmployeeIdentityIntervalRow selected =
                    AttendanceReportCalculationRows.latestEffectiveAssignment(
                            candidates,
                            date);
            if (selected != null) {
                return selected;
            }
        }
        throw new IllegalStateException(
                "No report-period identity for time account "
                        + account.accountId());
    }

    private DailyAttendanceResult calculateDay(
            String companyId,
            EmployeeIdentityIntervalRow identity,
            LocalDate businessDate,
            DayType dayType,
            List<ShiftSegmentRow> segments,
            List<PunchEventRow> allPunches,
            List<PunchCorrectionRow> allPunchCorrections,
            List<PunchExemptionRoleIntervalRow> punchExemptionRoles,
            List<IntervalEvidence> allOaEvidence,
            CalculationPolicy policy,
            YearMonth period,
            Instant dataAsOf) {

        Instant naturalDayStart =
                businessDate.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant naturalDayEnd = businessDate.plusDays(1)
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();
        Instant evidenceWindowStart = segments.stream()
                .map(this::segmentEvidenceStart)
                .min(Comparator.naturalOrder())
                .orElse(naturalDayStart);
        Instant evidenceWindowEnd = segments.stream()
                .map(this::segmentEvidenceEnd)
                .max(Comparator.naturalOrder())
                .orElse(naturalDayEnd);

        // A business-date shift can cross midnight. Use the union envelope of
        // its configured punch windows instead of cutting evidence at the
        // natural-day boundary; the deterministic calculator still assigns
        // each punch to the exact segment-side window.
        List<PunchEvent> dayPunches = allPunches.stream()
                .filter(p -> !p.pointInstant().isBefore(evidenceWindowStart)
                        && p.pointInstant().isBefore(evidenceWindowEnd))
                .map(p -> new PunchEvent(
                        "punch:" + p.employeeId() + ":" + p.pointInstant(),
                        p.pointInstant(),
                        PunchDirection.AUTO,
                        "punch-evidence:" + p.pointInstant()))
                .collect(Collectors.toCollection(ArrayList::new));
        addApprovedPunchCorrections(
                dayPunches,
                allPunchCorrections,
                businessDate,
                segments,
                dataAsOf);

        boolean punchExempt = punchExemptionRoles.stream()
                .anyMatch(role -> role.validFrom().isBefore(evidenceWindowEnd)
                        && (role.validTo() == null
                                || role.validTo().isAfter(evidenceWindowStart)));

        // Filter OA evidence for this day
        List<IntervalEvidence> dayOaEvidence = allOaEvidence.stream()
                .filter(e -> e.interval().start().isBefore(evidenceWindowEnd)
                        && e.interval().end().isAfter(evidenceWindowStart))
                .collect(Collectors.toList());

        // Convert shift segments
        List<ScheduledWorkSegment> workSegments = segments.stream()
                .map(this::toScheduledSegment)
                .collect(Collectors.toList());

        CalculationInputSnapshot snapshot = new CalculationInputSnapshot(
                companyId,
                identity.employeeId(),
                identity.employmentAssignmentId(),
                businessDate,
                BUSINESS_ZONE,
                dataAsOf,
                workSegments,
                dayPunches,
                dayOaEvidence,
                List.of(), // adjustments - not yet implemented
                new GraceConsumptionSnapshot(
                        identity.employeeId(),
                        period,
                        0, // grace usage tracking not yet implemented
                        "grace-digest-placeholder"),
                punchExempt,
                policy,
                "config-snapshot:" + companyId + ":" + businessDate,
                "config-digest-placeholder",
                "evidence-snapshot:" + identity.employeeId() + ":" + businessDate,
                "evidence-digest-placeholder",
                "adjustment-digest-placeholder",
                "period:" + period,
                1,
                "period-token-v1",
                "w-full-calc-v1",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());

        return calculator.calculate(CALCULATION_VERSION_ID, snapshot);
    }

    private void addApprovedPunchCorrections(
            List<PunchEvent> target,
            List<PunchCorrectionRow> corrections,
            LocalDate businessDate,
            List<ShiftSegmentRow> segments,
            Instant dataAsOf) {
        if (segments.isEmpty()) {
            return;
        }
        Instant entryInstant = segments.stream()
                .map(ShiftSegmentRow::segmentStart)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        Instant exitInstant = segments.stream()
                .map(ShiftSegmentRow::segmentEnd)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        for (PunchCorrectionRow correction : corrections) {
            if (!businessDate.equals(correction.businessDate())
                    || correction.reviewedAt().isAfter(dataAsOf)) {
                continue;
            }
            if (correction.punchSide() != PunchSide.EXIT) {
                target.add(supplementedPunch(
                        correction,
                        PunchDirection.ENTRY,
                        entryInstant));
            }
            if (correction.punchSide() != PunchSide.ENTRY) {
                target.add(supplementedPunch(
                        correction,
                        PunchDirection.EXIT,
                        exitInstant));
            }
        }
    }

    private PunchEvent supplementedPunch(
            PunchCorrectionRow correction,
            PunchDirection direction,
            Instant instant) {
        String source = "supplemented:" + correction.requestId()
                + ":" + direction.name();
        return new PunchEvent(
                "punch:supplemented-" + correction.requestId()
                        + "-" + direction.name() + ":" + instant,
                instant,
                direction,
                source);
    }

    private ScheduledWorkSegment toScheduledSegment(ShiftSegmentRow row) {
        return new ScheduledWorkSegment(
                row.segmentId(),
                row.businessDate(),
                new TimeInterval(row.segmentStart(), row.segmentEnd()),
                new TimeInterval(row.arrivalWindowStart(), row.arrivalWindowEnd()),
                new TimeInterval(row.departureWindowStart(), row.departureWindowEnd()),
                SegmentKind.SCHEDULED_WORK);
    }

    private Instant segmentEvidenceStart(ShiftSegmentRow row) {
        return List.of(
                        row.segmentStart(),
                        row.arrivalWindowStart(),
                        row.departureWindowStart())
                .stream()
                .min(Comparator.naturalOrder())
                .orElseThrow();
    }

    private Instant segmentEvidenceEnd(ShiftSegmentRow row) {
        return List.of(
                        row.segmentEnd(),
                        row.arrivalWindowEnd(),
                        row.departureWindowEnd())
                .stream()
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    private VerifiedCalculatedFacts convertToVerifiedFacts(
            String companyId,
            EmployeeIdentityIntervalRow identity,
            LocalDate businessDate,
            DayType dayType,
            DailyAttendanceResult result,
            LeaveType leaveType) {

        String factId = identity.employeeId() + ":" + businessDate;

        // Extract first/last punch from consumed punch events
        Instant firstPunchAt = null;
        Instant lastPunchAt = null;
        if (!result.consumedPunchEventIds().isEmpty()) {
            // Extract instant from punch ID format "punch:employeeId:instant"
            // Using split limit 3 because Instant strings contain ':' characters
            List<Instant> punchTimes = result.consumedPunchEventIds().stream()
                    .map(id -> {
                        String[] parts = id.split(":", 3);
                        if (parts.length == 3) {
                            try {
                                return Instant.parse(parts[2]);
                            } catch (Exception e) {
                                return null;
                            }
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();

            if (!punchTimes.isEmpty()) {
                firstPunchAt = punchTimes.get(0);
                lastPunchAt = punchTimes.get(punchTimes.size() - 1);
            }
        }

        ProjectionFacts facts = factProjector.project(
                result,
                new ProjectionContext(
                        factId,
                        companyId,
                        identity.employeeId(),
                        identity.employeeNumber(),
                        identity.employeeName(),
                        identity.organizationId(),
                        identity.organizationVersionId(),
                        identity.organizationName(),
                        businessDate,
                        dayType,
                        "计算班次",
                        firstPunchAt,
                        lastPunchAt,
                        leaveType));

        return new VerifiedCalculatedFacts(
                identity.employeeVersionId(),
                identity.employmentAssignmentId(),
                facts);
    }

    private CalculationPolicy buildPolicy(
            AttendancePolicyRow policyRow,
            LocalDate periodEnd) {
        if (policyRow == null) {
            // Default policy
            return new CalculationPolicy(
                    15,
                    1,
                    periodEnd.atStartOfDay(BUSINESS_ZONE).toInstant(),
                    false,
                    null,
                    2880,
                    List.of());
        }

        return new CalculationPolicy(
                policyRow.lateGraceMaxMinutes(),
                policyRow.monthlyLateGraceUses(),
                policyRow.correctionDeadline(),
                false,
                null,
                policyRow.overtimeSubmissionDeadlineMinutes(),
                List.of());
    }

    private Map<String, List<IntervalEvidence>> mapOaToEmployeeId(
            Map<String, List<OaDocumentRow>> oaByEmployeeNumber,
            List<EmployeeIdentityIntervalRow> identities) {
        Map<String, String> employeeNumberToId = identities.stream()
                .collect(Collectors.toMap(
                        EmployeeIdentityIntervalRow::employeeNumber,
                        EmployeeIdentityIntervalRow::employeeId,
                        (a, b) -> a));

        Map<String, List<IntervalEvidence>> result = new HashMap<>();
        for (Map.Entry<String, List<OaDocumentRow>> entry :
                oaByEmployeeNumber.entrySet()) {
            String employeeId = employeeNumberToId.get(entry.getKey());
            if (employeeId != null) {
                result.put(employeeId,
                        OaDocumentConverter.toIntervalEvidence(entry.getValue()));
            }
        }
        return result;
    }

    private LeaveType leaveTypeForDay(
            List<IntervalEvidence> evidence, LocalDate businessDate) {
        Instant dayStart = businessDate.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant dayEnd = businessDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        List<LeaveType> types = evidence.stream()
                .filter(item -> item.kind()
                        == com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind.LEAVE)
                .filter(item -> item.interval().start().isBefore(dayEnd)
                        && item.interval().end().isAfter(dayStart))
                .map(IntervalEvidence::leaveType)
                .distinct()
                .toList();
        if (types.isEmpty()) {
            return null;
        }
        if (types.size() != 1) {
            throw new IllegalStateException(
                    "Conflicting leave types for business date " + businessDate);
        }
        return types.getFirst();
    }

    private Map<String, Map<LocalDate, List<ShiftSegmentRow>>> indexShiftSegments(
            List<ShiftSegmentRow> segments) {
        Map<String, Map<LocalDate, List<ShiftSegmentRow>>> result =
                new HashMap<>();
        for (ShiftSegmentRow segment : segments) {
            result.computeIfAbsent(segment.employeeId(), k -> new HashMap<>())
                    .computeIfAbsent(segment.businessDate(), k -> new ArrayList<>())
                    .add(segment);
        }
        return result;
    }

    private Map<LocalDate, DayType> buildDayTypeMap(List<CalendarDayRow> days) {
        Map<LocalDate, DayType> result = new HashMap<>();
        java.util.Set<LocalDate> ambiguousDates = new java.util.HashSet<>();
        for (CalendarDayRow day : days) {
            if (ambiguousDates.contains(day.businessDate())) {
                continue;
            }
            DayType mapped = reportDayType(day);
            DayType existing = result.putIfAbsent(day.businessDate(), mapped);
            if (existing != null && existing != mapped) {
                result.remove(day.businessDate());
                ambiguousDates.add(day.businessDate());
            }
        }
        return result;
    }

    private DayType reportDayType(CalendarDayRow day) {
        return switch (day.dayType()) {
            case "WORKDAY" -> weekdayDayType(day.businessDate());
            case "WEEKEND" -> weekdayDayType(day.businessDate());
            case "SPECIAL_WORKDAY" -> DayType.ADJUSTED_WORKDAY;
            case "PUBLIC_HOLIDAY" -> DayType.PUBLIC_HOLIDAY;
            // Compatibility with pre-normalized fixtures and projections.
            case "WEEKDAY" -> DayType.WEEKDAY;
            case "SATURDAY" -> DayType.SATURDAY;
            case "SUNDAY" -> DayType.SUNDAY;
            case "ADJUSTED_WORKDAY" -> DayType.ADJUSTED_WORKDAY;
            default -> throw new IllegalArgumentException(
                    "Unsupported calendar day type: " + day.dayType());
        };
    }

    private EmployeeIdentityIntervalRow findUnambiguousIdentity(
            List<EmployeeIdentityIntervalRow> candidates,
            LocalDate businessDate) {
        return AttendanceReportCalculationRows.latestEffectiveAssignment(
                candidates, businessDate);
    }

    private DayType weekdayDayType(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) {
            return DayType.SATURDAY;
        } else if (dow == DayOfWeek.SUNDAY) {
            return DayType.SUNDAY;
        } else {
            return DayType.WEEKDAY;
        }
    }

    private static <T, K> Map<K, List<T>> groupBy(
            List<T> items,
            java.util.function.Function<T, K> keyExtractor) {
        Map<K, List<T>> result = new HashMap<>();
        for (T item : items) {
            result.computeIfAbsent(keyExtractor.apply(item), k -> new ArrayList<>())
                    .add(item);
        }
        return result;
    }

    private String sourceSnapshotDigest(
            String companyId,
            YearMonth period,
            List<VerifiedCalculatedFacts> facts,
            List<VerifiedOaDocumentFact> oaFacts,
            List<VerifiedTimeAccountFact> timeAccountFacts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(companyId.getBytes(StandardCharsets.UTF_8));
            digest.update(period.toString().getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(4);
            buf.putInt(facts.size());
            digest.update(buf.array());
            for (VerifiedCalculatedFacts fact : facts) {
                digest.update(fact.facts().dailyFact().factId()
                        .getBytes(StandardCharsets.UTF_8));
                digest.update(fact.facts().dailyFact().resultDigest()
                        .getBytes(StandardCharsets.UTF_8));
            }
            buf.clear();
            buf.putInt(oaFacts.size());
            digest.update(buf.array());
            for (VerifiedOaDocumentFact fact : oaFacts) {
                digest.update(fact.oaAttendanceDocumentId()
                        .getBytes(StandardCharsets.UTF_8));
                digest.update(fact.sourceVersion()
                        .getBytes(StandardCharsets.UTF_8));
            }
            buf.clear();
            buf.putInt(timeAccountFacts.size());
            digest.update(buf.array());
            for (VerifiedTimeAccountFact fact : timeAccountFacts) {
                digest.update(fact.accountId()
                        .getBytes(StandardCharsets.UTF_8));
                digest.update(fact.ledgerVersion()
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
