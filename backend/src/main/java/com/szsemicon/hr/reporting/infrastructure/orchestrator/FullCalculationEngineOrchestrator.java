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
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.SourceInputVersionRow;
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
import java.util.Set;
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
    private static final Set<String> REQUIRED_ATTENDANCE_SOURCES = Set.of(
            "DELI_CLOUD", "OA_ATTENDANCE");
    private static final List<String> MODEL_SOURCE_VERSIONS = List.of(
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
        LocalDate knowledgeDateEndExclusive = dataAsOf
                .atZone(BUSINESS_ZONE)
                .toLocalDate()
                .plusDays(1);
        LocalDate calculationEndExclusive = periodEndExclusive.isBefore(
                knowledgeDateEndExclusive)
                ? periodEndExclusive
                : knowledgeDateEndExclusive;
        if (!calculationEndExclusive.isAfter(periodStart)) {
            return emptyCommand(
                    companyId,
                    period,
                    periodState,
                    principalId,
                    dataAsOf);
        }
        Instant windowStart = periodStart.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant windowEnd = calculationEndExclusive
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();

        // Establish the local committed-source snapshot before loading facts.
        // Raw provider cursors never leave the persistence adapter.
        List<String> sourceVersions = sourceVersions(
                mapper.findAttendanceSourceVersions(companyId, dataAsOf));

        // Load all base data in batch
        List<EmployeeIdentityIntervalRow> identities =
                mapper.findEmployeeIdentityIntervals(
                        companyId, periodStart, calculationEndExclusive);
        List<ShiftSegmentRow> shiftSegments =
                mapper.findScheduledWorkSegments(
                        companyId,
                        periodStart,
                        calculationEndExclusive,
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
                        calculationEndExclusive,
                        dataAsOf);
        List<PunchExemptionRoleIntervalRow> punchExemptionRoles =
                mapper.findPunchExemptionRoleIntervals(
                        companyId, evidenceWindowStart, evidenceWindowEnd);
        List<CalendarDayRow> calendarDays =
                mapper.findPublishedCalendarDays(
                        companyId,
                        periodStart,
                        calculationEndExclusive,
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
                        calculationEndExclusive,
                        dataAsOf);
        List<AttendancePolicyRow> attendancePolicies =
                mapper.findAttendancePolicies(
                        companyId,
                        periodStart,
                        calculationEndExclusive,
                        dataAsOf);

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
        Map<EmployeeBusinessDate, DayType> dayTypes =
                buildDayTypeMap(calendarDays);
        Map<EmployeeBusinessDate, AttendancePolicyRow> policyByEmployeeAndDate =
                indexAttendancePolicies(attendancePolicies);

        // Index OA documents by employee number, then convert to employee ID
        Map<String, List<OaDocumentRow>> oaByEmployeeNumber =
                groupBy(oaDocuments, OaDocumentRow::employeeNumber);
        Map<String, List<IntervalEvidence>> oaEvidenceByEmployee =
                mapOaToEmployeeId(oaByEmployeeNumber, identities);

        // Calculate for each employee-day
        List<VerifiedCalculatedFacts> allFacts = new ArrayList<>();
        for (Map.Entry<String, List<EmployeeIdentityIntervalRow>> entry :
                identitiesByEmployee.entrySet()) {
            String employeeId = entry.getKey();
            int monthlyGraceUsed = 0;
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
                    businessDate.isBefore(calculationEndExclusive);
                    businessDate = businessDate.plusDays(1)) {
                EmployeeIdentityIntervalRow identity =
                        findUnambiguousIdentity(entry.getValue(), businessDate);
                if (identity == null) {
                    continue;
                }

                DayType dayType = requireDayType(
                        dayTypes, employeeId, businessDate);
                List<ShiftSegmentRow> daySegments =
                        employeeSegments.getOrDefault(
                                businessDate, List.of());
                requireScheduledSegments(
                        employeeId,
                        businessDate,
                        dayType,
                        daySegments);
                AttendancePolicyRow policyRow = policyByEmployeeAndDate.get(
                        new EmployeeBusinessDate(employeeId, businessDate));
                CalculationPolicy policy = buildPolicy(
                        requireAttendancePolicy(
                                policyRow,
                                employeeId,
                                businessDate),
                        businessDate);
                DailyAttendanceResult result = calculateDay(
                        companyId,
                        identity,
                        businessDate,
                        dayType,
                        daySegments,
                        employeePunches,
                        employeeCorrections,
                        employeePunchExemptionRoles,
                        employeeOaEvidence,
                        policy,
                        period,
                        monthlyGraceUsed,
                        dataAsOf);
                if (consumedMonthlyGrace(
                        result,
                        policy,
                        monthlyGraceUsed)) {
                    monthlyGraceUsed++;
                }
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
                        calculationEndExclusive,
                        timeAccountSnapshots,
                        identities);

        VerifiedProjectionMetadata metadata = new VerifiedProjectionMetadata(
                companyId,
                period,
                periodState,
                FORMULA_CATALOG_VERSION,
                sourceVersions,
                sourceSnapshotDigest(
                        companyId,
                        period,
                        sourceVersions,
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

    private PublishCommand emptyCommand(
            String companyId,
            YearMonth period,
            PeriodState periodState,
            String principalId,
            Instant dataAsOf) {
        List<VerifiedCalculatedFacts> calculatedFacts = List.of();
        List<VerifiedOaDocumentFact> oaFacts = List.of();
        List<VerifiedTimeAccountFact> timeAccountFacts = List.of();
        VerifiedProjectionMetadata metadata = new VerifiedProjectionMetadata(
                companyId,
                period,
                periodState,
                FORMULA_CATALOG_VERSION,
                MODEL_SOURCE_VERSIONS,
                sourceSnapshotDigest(
                        companyId,
                        period,
                        MODEL_SOURCE_VERSIONS,
                        calculatedFacts,
                        oaFacts,
                        timeAccountFacts),
                dataAsOf,
                principalId);
        return new PublishCommand(
                metadata,
                calculatedFacts,
                List.of(),
                oaFacts,
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
            int monthlyGraceUsed,
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
                        monthlyGraceUsed,
                        graceConsumptionDigest(
                                identity.employeeId(),
                                period,
                                monthlyGraceUsed)),
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
                "realtime-calc:" + companyId + ":"
                        + identity.employeeId() + ":" + businessDate,
                "realtime-period:" + companyId + ":" + period);

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
            LocalDate businessDate) {
        validatePolicyAuthority(policyRow);
        int overtimeDeadlineMinutes;
        try {
            overtimeDeadlineMinutes = Math.multiplyExact(
                    policyRow.overtimeSubmissionDeadlineHours(),
                    60);
        } catch (ArithmeticException exception) {
            throw invalidPolicy(
                    policyRow,
                    "overtime deadline exceeds minute range");
        }
        Instant correctionDeadline = businessDate
                .plusDays((long) policyRow.correctionWindowDays() + 1)
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();
        return new CalculationPolicy(
                policyRow.lateGraceMinutes(),
                policyRow.monthlyLateExemptionUses(),
                correctionDeadline,
                false,
                null,
                overtimeDeadlineMinutes,
                List.of());
    }

    private Map<EmployeeBusinessDate, AttendancePolicyRow>
            indexAttendancePolicies(List<AttendancePolicyRow> policies) {
        Map<EmployeeBusinessDate, AttendancePolicyRow> result =
                new HashMap<>();
        for (AttendancePolicyRow policy : policies) {
            Objects.requireNonNull(policy, "attendance policy row");
            EmployeeBusinessDate key = new EmployeeBusinessDate(
                    Objects.requireNonNull(
                            policy.employeeId(),
                            "attendance policy employeeId"),
                    Objects.requireNonNull(
                            policy.businessDate(),
                            "attendance policy businessDate"));
            AttendancePolicyRow existing = result.putIfAbsent(key, policy);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate attendance policy summary for employee "
                                + key.employeeId()
                                + " on "
                                + key.businessDate());
            }
        }
        return result;
    }

    private AttendancePolicyRow requireAttendancePolicy(
            AttendancePolicyRow policy,
            String employeeId,
            LocalDate businessDate) {
        if (policy == null) {
            throw new IllegalStateException(
                    "Attendance policy authority missing for employee "
                            + employeeId
                            + " on "
                            + businessDate);
        }
        return policy;
    }

    private void validatePolicyAuthority(AttendancePolicyRow policy) {
        if (policy.attendanceGroupAuthorityCount() != 1) {
            throw invalidPolicy(
                    policy,
                    "attendance-group authority count must be exactly one");
        }
        requireSinglePolicy(
                policy,
                "LATE_GRACE",
                policy.lateGracePolicyCount());
        requireSinglePolicy(
                policy,
                "MONTHLY_LATE_EXEMPTION",
                policy.monthlyLateExemptionPolicyCount());
        requireSinglePolicy(
                policy,
                "MISSING_PUNCH",
                policy.missingPunchPolicyCount());
        requireSinglePolicy(
                policy,
                "OVERTIME_RECOGNITION",
                policy.overtimePolicyCount());
        if (!Boolean.TRUE.equals(policy.lateGraceEnabled())
                || !Boolean.TRUE.equals(
                        policy.monthlyLateExemptionEnabled())) {
            throw invalidPolicy(
                    policy,
                    "late-grace policies must both be enabled");
        }
        if (policy.lateGraceMinutes() == null
                || policy.lateGraceMinutes() < 0
                || policy.lateGraceMinutes() > 240
                || policy.monthlyLateExemptionGraceMinutes() == null
                || !policy.lateGraceMinutes().equals(
                        policy.monthlyLateExemptionGraceMinutes())) {
            throw invalidPolicy(
                    policy,
                    "late-grace minutes must be valid and consistent");
        }
        if (policy.monthlyLateExemptionUses() == null
                || policy.monthlyLateExemptionUses() < 0
                || policy.monthlyLateExemptionUses() > 31
                || !Boolean.FALSE.equals(policy.resetOnGroupChange())) {
            throw invalidPolicy(
                    policy,
                    "monthly grace usage must preserve employee-month state");
        }
        if (!Boolean.TRUE.equals(policy.missingPunchEnabled())
                || policy.correctionWindowDays() == null
                || policy.correctionWindowDays() < 0
                || policy.correctionWindowDays() > 365
                || !"NEXT_DAY_START_AFTER_FULL_DAYS".equals(
                        policy.correctionDeadlineMode())) {
            throw invalidPolicy(
                    policy,
                    "missing-punch deadline policy is invalid");
        }
        if (!Boolean.TRUE.equals(policy.overtimeEnabled())
                || policy.overtimeSubmissionDeadlineHours() == null
                || policy.overtimeSubmissionDeadlineHours() < 0
                || policy.overtimeSubmissionDeadlineHours() > 720) {
            throw invalidPolicy(
                    policy,
                    "overtime deadline policy is invalid");
        }
    }

    private void requireSinglePolicy(
            AttendancePolicyRow policy,
            String policyKind,
            int candidateCount) {
        if (candidateCount != 1) {
            throw invalidPolicy(
                    policy,
                    policyKind + " candidate count must be exactly one");
        }
    }

    private IllegalStateException invalidPolicy(
            AttendancePolicyRow policy,
            String reason) {
        return new IllegalStateException(
                "Invalid attendance policy authority for employee "
                        + policy.employeeId()
                        + " on "
                        + policy.businessDate()
                        + ": "
                        + reason);
    }

    private boolean consumedMonthlyGrace(
            DailyAttendanceResult result,
            CalculationPolicy policy,
            int usedBeforeDay) {
        if (policy.lateGraceMaxMinutes() == 0
                || usedBeforeDay >= policy.monthlyLateGraceUses()) {
            return false;
        }
        return result.ruleHits().stream()
                .map(hit -> hit.ruleCode())
                .anyMatch(code -> "MONTHLY_LATE_GRACE_CONSUMED".equals(code)
                        || "LATE_CHARGEABLE".equals(code)
                        || "LATE_CONVERTED_TO_ABSENCE".equals(code));
    }

    private String graceConsumptionDigest(
            String employeeId,
            YearMonth period,
            int used) {
        return "grace-usage:" + employeeId + ":" + period + ":" + used;
    }

    private Map<String, List<IntervalEvidence>> mapOaToEmployeeId(
            Map<String, List<OaDocumentRow>> oaByEmployeeNumber,
            List<EmployeeIdentityIntervalRow> identities) {
        Map<String, List<IntervalEvidence>> result = new HashMap<>();
        for (Map.Entry<String, List<OaDocumentRow>> entry :
                oaByEmployeeNumber.entrySet()) {
            for (OaDocumentRow document : entry.getValue()) {
                LocalDate occurrenceDate = document.startInstant()
                        .atZone(BUSINESS_ZONE)
                        .toLocalDate();
                List<String> employeeIds = identities.stream()
                        .filter(identity -> identity.employeeNumber()
                                .equals(entry.getKey()))
                        .filter(identity -> identity.validOn(occurrenceDate))
                        .map(EmployeeIdentityIntervalRow::employeeId)
                        .distinct()
                        .toList();
                if (employeeIds.size() != 1) {
                    throw new IllegalStateException(
                            "OA employee identity is missing or ambiguous: "
                                    + entry.getKey());
                }
                result.computeIfAbsent(
                                employeeIds.getFirst(),
                                ignored -> new ArrayList<>())
                        .addAll(OaDocumentConverter.toIntervalEvidence(
                                List.of(document)));
            }
        }
        result.replaceAll((ignored, evidence) -> List.copyOf(evidence));
        return Map.copyOf(result);
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

    private Map<EmployeeBusinessDate, DayType> buildDayTypeMap(
            List<CalendarDayRow> days) {
        Map<EmployeeBusinessDate, DayType> result = new HashMap<>();
        for (CalendarDayRow day : days) {
            if (day.authorityCount() != 1
                    || day.distinctDayTypeCount() != 1) {
                throw new IllegalStateException(
                        "Ambiguous calendar authority for employee "
                                + day.employeeId()
                                + " on "
                                + day.businessDate());
            }
            DayType mapped = reportDayType(day);
            EmployeeBusinessDate key = new EmployeeBusinessDate(
                    day.employeeId(), day.businessDate());
            DayType existing = result.putIfAbsent(key, mapped);
            if (existing != null && existing != mapped) {
                throw new IllegalStateException(
                        "Ambiguous calendar authority for employee "
                                + day.employeeId()
                                + " on "
                                + day.businessDate());
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
        boolean hasEffectiveCandidate = candidates.stream()
                .anyMatch(candidate -> candidate.validOn(businessDate));
        EmployeeIdentityIntervalRow selected =
                AttendanceReportCalculationRows.latestEffectiveAssignment(
                candidates, businessDate);
        if (hasEffectiveCandidate && selected == null) {
            throw new IllegalStateException(
                    "Ambiguous employee identity for business date "
                            + businessDate);
        }
        return selected;
    }

    private DayType requireDayType(
            Map<EmployeeBusinessDate, DayType> dayTypes,
            String employeeId,
            LocalDate businessDate) {
        DayType dayType = dayTypes.get(new EmployeeBusinessDate(
                employeeId, businessDate));
        if (dayType == null) {
            // Compatibility for isolated tests and the non-primary legacy
            // orchestrator; production rows are always employee-scoped.
            dayType = dayTypes.get(new EmployeeBusinessDate(
                    null, businessDate));
        }
        if (dayType == null) {
            throw new IllegalStateException(
                    "Calendar authority missing for employee "
                            + employeeId
                            + " on "
                            + businessDate);
        }
        return dayType;
    }

    private void requireScheduledSegments(
            String employeeId,
            LocalDate businessDate,
            DayType dayType,
            List<ShiftSegmentRow> segments) {
        if ((dayType == DayType.WEEKDAY
                || dayType == DayType.ADJUSTED_WORKDAY)
                && segments.isEmpty()) {
            throw new IllegalStateException(
                    "Scheduled shift authority missing for employee "
                            + employeeId
                            + " on "
                            + businessDate);
        }
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
            List<String> sourceVersions,
            List<VerifiedCalculatedFacts> facts,
            List<VerifiedOaDocumentFact> oaFacts,
            List<VerifiedTimeAccountFact> timeAccountFacts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, companyId, period.toString());
            sourceVersions.stream()
                    .sorted()
                    .forEach(version -> updateDigest(digest, version));
            updateDigest(digest, Integer.toString(facts.size()));
            facts.stream()
                    .sorted(Comparator.comparing(value ->
                            value.facts().dailyFact().factId()))
                    .forEach(fact -> {
                        updateDigest(
                                digest,
                                fact.employeeVersionId(),
                                fact.employmentAssignmentId(),
                                fact.facts().dailyFact().toString());
                        fact.facts().exceptionFacts().stream()
                                .sorted(Comparator.comparing(
                                        value -> value.caseId()))
                                .forEach(value -> updateDigest(
                                        digest, value.toString()));
                    });
            updateDigest(digest, Integer.toString(oaFacts.size()));
            oaFacts.stream()
                    .sorted(Comparator.comparing(
                            VerifiedOaDocumentFact::oaAttendanceDocumentId))
                    .forEach(fact -> updateDigest(
                            digest, fact.toString()));
            updateDigest(digest, Integer.toString(timeAccountFacts.size()));
            timeAccountFacts.stream()
                    .sorted(Comparator.comparing(
                            VerifiedTimeAccountFact::accountId))
                    .forEach(fact -> updateDigest(
                            digest, fact.toString()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void updateDigest(
            MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = Objects.requireNonNull(value, "digest value")
                    .getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(bytes.length)
                    .array());
            digest.update(bytes);
        }
    }

    private static List<String> sourceVersions(
            List<SourceInputVersionRow> rows) {
        if (rows == null) {
            throw new IllegalStateException(
                    "attendance source versions are unavailable");
        }
        List<SourceInputVersionRow> sourceRows = List.copyOf(rows);
        for (String requiredType : REQUIRED_ATTENDANCE_SOURCES) {
            List<SourceInputVersionRow> matching = sourceRows.stream()
                    .filter(row -> requiredType.equals(row.sourceType()))
                    .toList();
            if (matching.isEmpty()) {
                throw new IllegalStateException(
                        "Required attendance source is not active: "
                                + requiredType);
            }
            if (matching.stream().anyMatch(
                    row -> row.committedAt() == null)) {
                throw new IllegalStateException(
                        "Required attendance source is not synchronized: "
                                + requiredType);
            }
        }
        List<String> versions = new ArrayList<>(MODEL_SOURCE_VERSIONS);
        sourceRows.stream()
                .map(SourceInputVersionRow::canonicalVersion)
                .sorted()
                .forEach(versions::add);
        return List.copyOf(versions);
    }

    private record EmployeeBusinessDate(
            String employeeId,
            LocalDate businessDate) {
    }
}
