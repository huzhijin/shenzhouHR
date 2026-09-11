package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationInputSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationPolicy;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.GraceConsumptionSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.IntervalEvidence;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.MealDeductionRule;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchDirection;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchEvent;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ScheduledWorkSegment;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.SegmentKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.attendance.calculation.domain.DeterministicAttendanceCalculator;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.PunchSide;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaEmployeeNumberCatalog;
import com.szsemicon.hr.reporting.application.AttendanceReportCalculationOrchestrator;
import com.szsemicon.hr.reporting.application.DepartmentPathNames;
import com.szsemicon.hr.reporting.application.HrAttendanceOverride;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionContext;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
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
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OrganizationGraphRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.EmployeeLateDayCountRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaDocumentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaReportFactRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.HrPunchAdjustmentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchCorrectionRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchExemptionRoleIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchEventRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchWindowRow;
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
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log =
            LoggerFactory.getLogger(FullCalculationEngineOrchestrator.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String FORMULA_CATALOG_VERSION =
            "FULL_CALCULATION_OA_FORM_HOURS_V8";
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
            "AUTH.PUNCH_EXEMPT_STANDING:V1",
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
        return assemble(
                companyId,
                period,
                periodState,
                principalId,
                dataAsOf,
                null,
                null);
    }

    @Override
    @Transactional(readOnly = true)
    public PublishCommand assemble(
            String companyId,
            YearMonth period,
            PeriodState periodState,
            String principalId,
            Instant dataAsOf,
            LocalDate writeStartInclusive,
            LocalDate writeEndExclusive) {
        return assemble(
                companyId,
                period,
                periodState,
                principalId,
                dataAsOf,
                writeStartInclusive,
                writeEndExclusive,
                (String) null);
    }

    @Override
    @Transactional(readOnly = true)
    public PublishCommand assemble(
            String companyId,
            YearMonth period,
            PeriodState periodState,
            String principalId,
            Instant dataAsOf,
            LocalDate writeStartInclusive,
            LocalDate writeEndExclusive,
            String employeeIdFilter) {
        return assemble(
                companyId,
                period,
                periodState,
                principalId,
                dataAsOf,
                writeStartInclusive,
                writeEndExclusive,
                employeeIdFilter == null || employeeIdFilter.isBlank()
                        ? List.of()
                        : List.of(employeeIdFilter));
    }

    @Override
    @Transactional(readOnly = true)
    public PublishCommand assemble(
            String companyId,
            YearMonth period,
            PeriodState periodState,
            String principalId,
            Instant dataAsOf,
            LocalDate writeStartInclusive,
            LocalDate writeEndExclusive,
            Collection<String> employeeIds) {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(periodState, "periodState");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(dataAsOf, "dataAsOf");
        Set<String> scoped = new LinkedHashSet<>();
        if (employeeIds != null) {
            for (String employeeId : employeeIds) {
                if (employeeId != null && !employeeId.isBlank()) {
                    scoped.add(employeeId);
                }
            }
        }
        String employeeIdFilter = scoped.size() == 1
                ? scoped.iterator().next()
                : null;
        boolean personDay = !scoped.isEmpty();
        long assembleStarted = System.nanoTime();
        long stageStarted = assembleStarted;

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
        LocalDate factStart = writeStartInclusive == null
                ? periodStart
                : writeStartInclusive.isBefore(periodStart)
                        ? periodStart
                        : writeStartInclusive;
        LocalDate factEndExclusive = writeEndExclusive == null
                ? calculationEndExclusive
                : writeEndExclusive.isAfter(calculationEndExclusive)
                        ? calculationEndExclusive
                        : writeEndExclusive;
        if (!factEndExclusive.isAfter(factStart)) {
            return emptyCommand(
                    companyId,
                    period,
                    periodState,
                    principalId,
                    dataAsOf);
        }
        LocalDate evidenceStart = factStart.minusDays(1).isBefore(periodStart)
                ? periodStart
                : factStart.minusDays(1);
        Instant windowStart = evidenceStart.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant windowEnd = factEndExclusive
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();

        // Establish the local committed-source snapshot before loading facts.
        // Raw provider cursors never leave the persistence adapter.
        List<String> sourceVersions = sourceVersions(
                mapper.findAttendanceSourceVersions(companyId, dataAsOf));

        // Company-month reads stay roster-wide. One employee uses the
        // dedicated query; several employees share the company read and
        // filter in memory so batch import does not wait on the whole roster.
        List<EmployeeIdentityIntervalRow> identities = employeeIdFilter != null
                ? mapper.findEmployeeIdentityIntervalsForEmployee(
                        companyId,
                        periodStart,
                        calculationEndExclusive,
                        employeeIdFilter)
                : mapper.findEmployeeIdentityIntervals(
                        companyId, periodStart, calculationEndExclusive);
        if (personDay && employeeIdFilter == null) {
            identities = identities.stream()
                    .filter(row -> scoped.contains(row.employeeId()))
                    .toList();
        }
        List<ShiftSegmentRow> shiftSegments = applyPunchWindows(
                mapper.findScheduledWorkSegments(
                        companyId,
                        periodStart,
                        calculationEndExclusive.plusDays(1),
                        dataAsOf),
                mapper.findUniquePunchWindows(
                        companyId,
                        periodStart,
                        calculationEndExclusive.plusDays(1),
                        dataAsOf));
        if (personDay) {
            shiftSegments = shiftSegments.stream()
                    .filter(segment -> scoped.contains(segment.employeeId()))
                    .toList();
        }
        long shiftMs = elapsedMs(stageStarted);
        stageStarted = System.nanoTime();
        Instant evidenceWindowStart = shiftSegments.stream()
                .map(this::segmentEvidenceStart)
                .min(Comparator.naturalOrder())
                .filter(value -> value.isBefore(windowStart))
                .orElse(windowStart);
        Instant overnightFetchEnd = factEndExclusive
                .atTime(12, 0)
                .atZone(BUSINESS_ZONE)
                .toInstant();
        Instant evidenceWindowEnd = shiftSegments.stream()
                .map(this::segmentEvidenceEnd)
                .max(Comparator.naturalOrder())
                .filter(value -> value.isAfter(windowEnd))
                .orElse(windowEnd);
        if (overnightFetchEnd.isAfter(evidenceWindowEnd)) {
            evidenceWindowEnd = overnightFetchEnd;
        }
        List<PunchEventRow> punchRows = employeeIdFilter != null
                ? mapper.findActivatedPunchEventsForEmployee(
                        companyId,
                        evidenceWindowStart,
                        evidenceWindowEnd,
                        dataAsOf,
                        employeeIdFilter)
                : mapper.findActivatedPunchEvents(
                        companyId,
                        evidenceWindowStart,
                        evidenceWindowEnd,
                        dataAsOf);
        long punchMs = elapsedMs(stageStarted);
        stageStarted = System.nanoTime();
        List<PunchCorrectionRow> punchCorrections = employeeIdFilter != null
                ? mapper.findApprovedPunchCorrectionsForEmployee(
                        companyId,
                        periodStart,
                        calculationEndExclusive,
                        dataAsOf,
                        employeeIdFilter)
                : mapper.findApprovedPunchCorrections(
                        companyId,
                        periodStart,
                        calculationEndExclusive,
                        dataAsOf);
        List<HrPunchAdjustmentRow> hrPunchAdjustments = employeeIdFilter != null
                ? mapper.findHrPunchAdjustmentsForEmployee(
                        companyId,
                        periodStart,
                        calculationEndExclusive,
                        dataAsOf,
                        employeeIdFilter)
                : mapper.findHrPunchAdjustments(
                        companyId,
                        periodStart,
                        calculationEndExclusive,
                        dataAsOf);
        List<PunchExemptionRoleIntervalRow> punchExemptionRoles =
                new ArrayList<>(employeeIdFilter != null
                        ? mapper.findPunchExemptionRoleIntervalsForEmployee(
                                companyId,
                                evidenceWindowStart,
                                evidenceWindowEnd,
                                employeeIdFilter)
                        : mapper.findPunchExemptionRoleIntervals(
                                companyId,
                                evidenceWindowStart,
                                evidenceWindowEnd));
        punchExemptionRoles.addAll(employeeIdFilter != null
                ? mapper.findStandingPunchExemptionIntervalsForEmployee(
                        companyId,
                        evidenceWindowStart,
                        evidenceWindowEnd,
                        employeeIdFilter)
                : mapper.findStandingPunchExemptionIntervals(
                        companyId, evidenceWindowStart, evidenceWindowEnd));
        if (personDay && employeeIdFilter == null) {
            punchRows = punchRows.stream()
                    .filter(row -> scoped.contains(row.employeeId()))
                    .toList();
            punchCorrections = punchCorrections.stream()
                    .filter(row -> scoped.contains(row.employeeId()))
                    .toList();
            hrPunchAdjustments = hrPunchAdjustments.stream()
                    .filter(row -> scoped.contains(row.employeeId()))
                    .toList();
            punchExemptionRoles = punchExemptionRoles.stream()
                    .filter(row -> scoped.contains(row.employeeId()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        Map<String, DepartmentPathNames.ParentName> organizationGraph =
                mapper.findCurrentOrganizationGraph(companyId).stream()
                        .collect(Collectors.toMap(
                                OrganizationGraphRow::organizationId,
                                row -> new DepartmentPathNames.ParentName(
                                        row.parentOrganizationId(),
                                        row.name(),
                                        row.orgType()),
                                (left, right) -> left));
        Map<String, String> reportDepartments =
                DepartmentPathNames.displayDepartments(
                        organizationGraph,
                        mapper.findCurrentOrganizationAncestors(companyId)
                                .stream()
                                .map(row -> new DepartmentPathNames.AncestorName(
                                        row.organizationId(),
                                        row.ancestorName(),
                                        row.ancestorOrgType()))
                                .toList());
        identities = identities.stream()
                .map(row -> withReportDepartment(row, reportDepartments))
                .toList();
        List<CalendarDayRow> calendarDays =
                mapper.findPublishedCalendarDays(
                        companyId,
                        periodStart,
                        calculationEndExclusive,
                        dataAsOf);
        if (personDay) {
            calendarDays = calendarDays.stream()
                    .filter(day -> day.employeeId() == null
                            || scoped.contains(day.employeeId()))
                    .toList();
        }
        List<OaDocumentRow> oaDocuments = employeeIdFilter != null
                ? mapper.findEffectiveOaDocumentsForEmployee(
                        companyId,
                        evidenceWindowStart,
                        evidenceWindowEnd,
                        dataAsOf,
                        employeeIdFilter)
                : mapper.findEffectiveOaDocuments(
                        companyId,
                        evidenceWindowStart,
                        evidenceWindowEnd,
                        dataAsOf);
        List<OaReportFactRow> reportableOaDocuments = employeeIdFilter != null
                ? mapper.findReportableOaDocumentsForEmployee(
                        companyId,
                        windowStart,
                        windowEnd,
                        dataAsOf,
                        employeeIdFilter)
                : mapper.findReportableOaDocuments(
                        companyId,
                        windowStart,
                        windowEnd,
                        dataAsOf);
        long oaMs = elapsedMs(stageStarted);
        stageStarted = System.nanoTime();
        oaDocuments = relocateOaEmployees(oaDocuments);
        reportableOaDocuments = relocateReportableOaEmployees(reportableOaDocuments);
        List<TimeAccountSnapshotRow> timeAccountSnapshots = employeeIdFilter != null
                ? mapper.findTimeAccountSnapshotsForEmployee(
                        companyId,
                        periodStart,
                        calculationEndExclusive,
                        dataAsOf,
                        employeeIdFilter)
                : mapper.findTimeAccountSnapshots(
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
        if (personDay) {
            attendancePolicies = attendancePolicies.stream()
                    .filter(policy -> scoped.contains(policy.employeeId()))
                    .toList();
        }
        if (personDay && employeeIdFilter == null) {
            Set<String> scopedNumbers = identities.stream()
                    .map(EmployeeIdentityIntervalRow::employeeNumber)
                    .filter(number -> number != null && !number.isBlank())
                    .collect(Collectors.toSet());
            oaDocuments = oaDocuments.stream()
                    .filter(row -> scopedNumbers.contains(row.employeeNumber()))
                    .toList();
            reportableOaDocuments = reportableOaDocuments.stream()
                    .filter(row -> scoped.contains(row.employeeId()))
                    .toList();
            timeAccountSnapshots = timeAccountSnapshots.stream()
                    .filter(row -> scoped.contains(row.employeeId()))
                    .toList();
        }

        // Index data by employee
        Map<String, List<EmployeeIdentityIntervalRow>> identitiesByEmployee =
                groupBy(identities, EmployeeIdentityIntervalRow::employeeId);
        Map<String, List<PunchEventRow>> punchesByEmployee =
                groupBy(punchRows, PunchEventRow::employeeId);
        Map<String, List<PunchCorrectionRow>> correctionsByEmployee =
                groupBy(punchCorrections, PunchCorrectionRow::employeeId);
        Map<String, List<HrPunchAdjustmentRow>> hrAdjustmentsByEmployee =
                groupBy(hrPunchAdjustments, HrPunchAdjustmentRow::employeeId);
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
        Map<String, List<PunchEventRow>> oaMakeupByEmployee =
                mapOaMakeupPunches(oaByEmployeeNumber, identities);

        Map<String, Integer> graceUsedBeforeWindow = new HashMap<>();
        if (factStart.isAfter(periodStart)) {
            List<EmployeeLateDayCountRow> lateDays = personDay
                    ? mapper.countLateDaysBeforeWindowForEmployee(
                            companyId,
                            periodStart,
                            factStart,
                            employeeIdFilter)
                    : mapper.countLateDaysBeforeWindow(
                            companyId, periodStart, factStart);
            for (EmployeeLateDayCountRow row : lateDays) {
                graceUsedBeforeWindow.put(row.employeeId(), row.lateDays());
            }
        }

        // Calculate for each employee-day
        List<VerifiedCalculatedFacts> allFacts = new ArrayList<>();
        for (Map.Entry<String, List<EmployeeIdentityIntervalRow>> entry :
                identitiesByEmployee.entrySet()) {
            String employeeId = entry.getKey();
            if (employeeIdFilter != null
                    && !employeeIdFilter.isBlank()
                    && !employeeIdFilter.equals(employeeId)) {
                continue;
            }
            int monthlyGraceUsed = graceUsedBeforeWindow.getOrDefault(
                    employeeId, 0);
            List<PunchEventRow> employeePunches = new ArrayList<>(
                    punchesByEmployee.getOrDefault(employeeId, List.of()));
            employeePunches.addAll(
                    oaMakeupByEmployee.getOrDefault(employeeId, List.of()));
            List<PunchCorrectionRow> employeeCorrections =
                    correctionsByEmployee.getOrDefault(employeeId, List.of());
            List<HrPunchAdjustmentRow> employeeHrAdjustments =
                    hrAdjustmentsByEmployee.getOrDefault(employeeId, List.of());
            List<PunchExemptionRoleIntervalRow> employeePunchExemptionRoles =
                    rolesByEmployee.getOrDefault(employeeId, List.of());
            List<IntervalEvidence> employeeOaEvidence =
                    oaEvidenceByEmployee.getOrDefault(employeeId, List.of());
            Map<LocalDate, List<ShiftSegmentRow>> employeeSegments =
                    segmentsByEmployeeAndDate.getOrDefault(employeeId, Map.of());
            List<ShiftSegmentRow> allEmployeeSegments = new ArrayList<>();
            employeeSegments.values().forEach(allEmployeeSegments::addAll);

            for (LocalDate businessDate = factStart;
                    businessDate.isBefore(factEndExclusive);
                    businessDate = businessDate.plusDays(1)) {
                try {
                    EmployeeIdentityIntervalRow identity =
                            findUnambiguousIdentity(
                                    entry.getValue(), businessDate);
                    if (identity == null) {
                        continue;
                    }

                    Instant dayStart = businessDate
                            .atStartOfDay(BUSINESS_ZONE)
                            .toInstant();
                    boolean standingExempt = employeePunchExemptionRoles.stream()
                            .anyMatch(role -> role.activeAt(dayStart));
                    boolean siteFullAttendance =
                            SiteShiftTemplates.noClockSiteFullAttendance(
                                    identity.organizationName(),
                                    businessDate);
                    boolean fullAttendance = standingExempt || siteFullAttendance;

                    DayType dayType = requireDayType(
                            dayTypes, employeeId, businessDate);
                    if (dayType == null && fullAttendance) {
                        dayType = weekdayDayType(businessDate);
                    }
                    if (dayType == null) {
                        continue;
                    }
                    List<ShiftSegmentRow> daySegments =
                            employeeSegments.getOrDefault(
                                    businessDate, List.of());
                    if (daySegments.isEmpty()
                            && !isRestDayType(dayType)) {
                        daySegments = SiteShiftTemplates.workSegments(
                                employeeId,
                                businessDate,
                                SiteShiftTemplates.kindFor(
                                        identity.organizationName()));
                        allEmployeeSegments.addAll(daySegments);
                    }
                    if (!hasScheduledSegments(dayType, daySegments)) {
                        continue;
                    }
                    AttendancePolicyRow policyRow =
                            policyByEmployeeAndDate.get(
                                    new EmployeeBusinessDate(
                                            employeeId, businessDate));
                    if (policyRow == null) {
                        policyRow = syntheticPolicy(employeeId, businessDate);
                    }
                    List<ShiftSegmentRow> nextDaySegments =
                            employeeSegments.getOrDefault(
                                    businessDate.plusDays(1), List.of());
                    DayType previousDayType = requireDayType(
                            dayTypes,
                            employeeId,
                            businessDate.minusDays(1));
                    if (previousDayType == null && fullAttendance) {
                        previousDayType = weekdayDayType(
                                businessDate.minusDays(1));
                    }
                    DayType nextDayType = requireDayType(
                            dayTypes,
                            employeeId,
                            businessDate.plusDays(1));
                    if (nextDayType == null && fullAttendance) {
                        nextDayType = weekdayDayType(businessDate.plusDays(1));
                    }
                    if (nextDaySegments.isEmpty()
                            && fullAttendance
                            && nextDayType != null
                            && !isRestDayType(nextDayType)) {
                        nextDaySegments = SiteShiftTemplates.workSegments(
                                employeeId,
                                businessDate.plusDays(1),
                                SiteShiftTemplates.kindFor(
                                        identity.organizationName()));
                    }
                    CalculationPolicy policy = buildPolicy(
                            policyRow,
                            businessDate,
                            dayType,
                            daySegments,
                            employeeId,
                            allEmployeeSegments);
                    CalculatedDay calculated = calculateDay(
                            companyId,
                            identity,
                            businessDate,
                            dayType,
                            previousDayType,
                            nextDayType,
                            daySegments,
                            nextDaySegments,
                            employeePunches,
                            employeeCorrections,
                            employeeHrAdjustments,
                            employeePunchExemptionRoles,
                            employeeOaEvidence,
                            policy,
                            period,
                            monthlyGraceUsed,
                            dataAsOf,
                            fullAttendance);
                    if (consumedMonthlyGrace(
                            calculated.result(),
                            policy,
                            monthlyGraceUsed)) {
                        monthlyGraceUsed++;
                    }
                    LeaveType leaveType = leaveTypeForDay(
                            employeeOaEvidence, businessDate, dayType);

                    allFacts.add(convertToVerifiedFacts(
                            companyId,
                            identity,
                            businessDate,
                            dayType,
                            daySegments,
                            nextDaySegments,
                            calculated.result(),
                            leaveType,
                            calculated.dayPunches(),
                            employeeOaEvidence,
                            formOvertimeFromEvidence(
                                    employeeOaEvidence,
                                    businessDate,
                                    calculated.dayPunches(),
                                    identity.employeeId(),
                                    allEmployeeSegments,
                                    calendarDays,
                                    !fullAttendance),
                            employeeHrAdjustments));
                } catch (RuntimeException exception) {
                    log.warn(
                            "skip realtime day company={} employee={} date={}: {}",
                            companyId,
                            employeeId,
                            businessDate,
                            exception.getMessage());
                }
            }
        }

        allFacts.sort((left, right) ->
                left.facts().dailyFact().factId()
                        .compareTo(right.facts().dailyFact().factId()));
        List<VerifiedOaDocumentFact> oaReportFacts = new ArrayList<>(
                projectOaReportFacts(
                        companyId,
                        reportableOaDocuments,
                        identities,
                        shiftSegments,
                        calendarDays,
                        punchRows,
                        punchExemptionRoles));
        oaReportFacts.addAll(projectHrDayTypeOaFacts(
                companyId,
                hrPunchAdjustments,
                identities,
                dataAsOf));
        oaReportFacts.addAll(projectHrPunchCorrectionOaFacts(
                companyId,
                hrPunchAdjustments,
                identities,
                dataAsOf));
        oaReportFacts.sort(Comparator.comparing(
                VerifiedOaDocumentFact::oaAttendanceDocumentId));
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

        long computeMs = elapsedMs(stageStarted);
        log.info(
                "recalc-stage company={} period={} window={} employees={} "
                        + "shiftMs={} punchMs={} oaMs={} computeMs={} assembleMs={}",
                companyId,
                period,
                recalcWindowType(
                        writeStartInclusive, writeEndExclusive, personDay),
                identities.size(),
                shiftMs,
                punchMs,
                oaMs,
                computeMs,
                elapsedMs(assembleStarted));
        return new PublishCommand(
                metadata,
                allFacts,
                List.of(),
                oaReportFacts,
                timeAccountFacts);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String recalcWindowType(
            LocalDate writeStartInclusive,
            LocalDate writeEndExclusive,
            boolean personDay) {
        if (personDay) {
            return "employee-set";
        }
        if (writeStartInclusive == null && writeEndExclusive == null) {
            return "full-month";
        }
        return "window";
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

    private Map<EmployeeBusinessDate, FormOvertimeMinutes> allocateFormOvertime(
            List<OaDocumentRow> documents,
            List<EmployeeIdentityIntervalRow> identities,
            List<ShiftSegmentRow> shiftSegments,
            List<CalendarDayRow> calendarDays,
            List<PunchEventRow> punchRows) {
        Map<EmployeeBusinessDate, FormOvertimeMinutes> result = new HashMap<>();
        for (OaDocumentRow document : uniqueOvertimeDocuments(documents)) {
            if (!document.effectiveCandidate()
                    || !"OVERTIME".equalsIgnoreCase(document.documentType())) {
                continue;
            }
            TimeInterval snapped = OaIntervalGrid.snapInterval(
                    document.startInstant(), document.endInstant());
            if (snapped == null) {
                continue;
            }
            LocalDate startDate = snapped.start()
                    .atZone(BUSINESS_ZONE)
                    .toLocalDate();
            EmployeeIdentityIntervalRow startIdentity = identityOn(
                    identities,
                    document.employeeNumber(),
                    startDate);
            if (startIdentity == null) {
                continue;
            }
            var days = OvertimeMealDeductions.lookup(
                    startIdentity.employeeId(), shiftSegments, calendarDays);
            for (var day : OvertimeMealDeductions.minutesOnStartDay(
                    snapped, days)) {
                if (day.minutes() <= 0) {
                    continue;
                }
                EmployeeBusinessDate key = new EmployeeBusinessDate(
                        startIdentity.employeeId(), day.date());
                result.merge(
                        key,
                        FormOvertimeMinutes.zero().plus(
                                document.overtimeType(), day.minutes()),
                        FormOvertimeMinutes::add);
            }
        }
        return result;
    }

    private static List<OaDocumentRow> uniqueOvertimeDocuments(
            List<OaDocumentRow> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        Map<String, OaDocumentRow> unique = new LinkedHashMap<>();
        for (OaDocumentRow document : documents) {
            if (document == null
                    || !"OVERTIME".equalsIgnoreCase(document.documentType())) {
                continue;
            }
            String key = overtimeStartKey(
                    document.employeeNumber(),
                    document.startInstant());
            OaDocumentRow existing = unique.get(key);
            if (preferLaterOvertime(
                    document.endInstant(),
                    document.sourceBusinessKey(),
                    existing == null ? null : existing.endInstant(),
                    existing == null ? null : existing.sourceBusinessKey())) {
                unique.put(key, document);
            }
        }
        List<OaDocumentRow> result = new ArrayList<>();
        for (OaDocumentRow document : documents) {
            if (document == null) {
                continue;
            }
            if (!"OVERTIME".equalsIgnoreCase(document.documentType())) {
                result.add(document);
                continue;
            }
            String key = overtimeStartKey(
                    document.employeeNumber(),
                    document.startInstant());
            if (unique.get(key) == document) {
                result.add(document);
            }
        }
        return result;
    }

    private static String overtimeIntervalKey(
            String employeeNumber, Instant start, Instant end) {
        return overtimeStartKey(employeeNumber, start)
                + "|"
                + (end == null ? "" : end.toString());
    }

    private static String overtimeStartKey(String employeeNumber, Instant start) {
        return String.valueOf(employeeNumber)
                + "|"
                + (start == null ? "" : start.toString());
    }

    private static boolean preferLaterOvertime(
            Instant candidateEnd,
            String candidateKey,
            Instant existingEnd,
            String existingKey) {
        if (existingKey == null && existingEnd == null) {
            return true;
        }
        if (candidateEnd != null && existingEnd != null
                && candidateEnd.compareTo(existingEnd) != 0) {
            return candidateEnd.isAfter(existingEnd);
        }
        return compareBusinessKey(candidateKey, existingKey) < 0;
    }

    private static int compareBusinessKey(String left, String right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return left.compareTo(right);
    }

    private List<VerifiedOaDocumentFact> projectOaReportFacts(
            String companyId,
            List<OaReportFactRow> rows,
            List<EmployeeIdentityIntervalRow> identities,
            List<ShiftSegmentRow> shiftSegments,
            List<CalendarDayRow> calendarDays,
            List<PunchEventRow> punchRows,
            List<PunchExemptionRoleIntervalRow> punchExemptionRoles) {
        List<VerifiedOaDocumentFact> facts = new ArrayList<>();
        for (OaReportFactRow row : uniqueOaReportRows(rows)) {
            if ("PUNCH_CORRECTION".equals(row.documentType())) {
                Instant correctionAt = row.startInstant();
                if (correctionAt == null) {
                    continue;
                }
                LocalDate occurrenceDate = correctionAt
                        .atZone(BUSINESS_ZONE)
                        .toLocalDate();
                EmployeeIdentityIntervalRow identity = identityOn(
                        identities, row.employeeNumber(), occurrenceDate);
                if (identity == null) {
                    continue;
                }
                facts.add(new VerifiedOaDocumentFact(
                        companyId,
                        row.oaAttendanceDocumentId(),
                        identity.employeeId(),
                        identity.employeeVersionId(),
                        identity.employmentAssignmentId(),
                        identity.organizationId(),
                        identity.organizationVersionId(),
                        row.documentType(),
                        null,
                        OaTemporalShape.POINT,
                        correctionAt,
                        null,
                        null,
                        0,
                        row.sourceStatus(),
                        row.sourceVersion()));
                continue;
            }
            Instant snappedStart = OaIntervalGrid.snap(row.startInstant());
            Instant snappedEnd = OaIntervalGrid.snap(row.endInstant());
            LocalDate occurrenceDate = snappedStart
                    .atZone(BUSINESS_ZONE)
                    .toLocalDate();
            EmployeeIdentityIntervalRow identity = identityOn(
                    identities, row.employeeNumber(), occurrenceDate);
            if (identity == null) {
                continue;
            }
            long recognizedMinutes = recognizedMinutes(
                    row,
                    snappedStart,
                    snappedEnd,
                    identity.employeeId(),
                    identity.organizationName(),
                    shiftSegments,
                    calendarDays,
                    punchRows,
                    punchRequiredForOvertime(
                            identity,
                            snappedStart,
                            punchExemptionRoles));
            facts.add(new VerifiedOaDocumentFact(
                    companyId,
                    row.oaAttendanceDocumentId(),
                    identity.employeeId(),
                    identity.employeeVersionId(),
                    identity.employmentAssignmentId(),
                    identity.organizationId(),
                    identity.organizationVersionId(),
                    row.documentType(),
                    documentTypeCode(row),
                    OaTemporalShape.INTERVAL,
                    null,
                    snappedStart,
                    OaIntervalGrid.persistEnd(snappedStart, snappedEnd),
                    recognizedMinutes,
                    row.sourceStatus(),
                    row.sourceVersion(),
                    paperOrigin(row.sourceBusinessKey())));
        }
        facts.sort(Comparator.comparing(
                VerifiedOaDocumentFact::oaAttendanceDocumentId));
        return List.copyOf(facts);
    }

    private static String documentTypeCode(OaReportFactRow row) {
        if ("OVERTIME".equalsIgnoreCase(row.documentType())
                && row.overtimeType() != null) {
            return row.overtimeType().name();
        }
        return row.leaveType() == null ? null : row.leaveType().name();
    }

    private static String paperOrigin(String sourceBusinessKey) {
        return sourceBusinessKey != null && sourceBusinessKey.startsWith("PAPER:")
                ? "PAPER"
                : "OA";
    }

    private static EmployeeIdentityIntervalRow identityOn(
            List<EmployeeIdentityIntervalRow> identities,
            String employeeNumber,
            LocalDate occurrenceDate) {
        String aliased = OaEmployeeNumberCatalog.alias(employeeNumber);
        List<EmployeeIdentityIntervalRow> numbered = identities.stream()
                .filter(candidate -> {
                    String number = candidate.employeeNumber();
                    return number.equals(employeeNumber)
                            || (aliased != null && number.equals(aliased));
                })
                .toList();
        List<String> employeeIds = numbered.stream()
                .filter(candidate -> candidate.validOn(occurrenceDate))
                .map(EmployeeIdentityIntervalRow::employeeId)
                .distinct()
                .toList();
        if (employeeIds.isEmpty()) {
            return null;
        }
        if (employeeIds.size() != 1) {
            throw new IllegalStateException(
                    "OA employee identity is missing or ambiguous: "
                            + employeeNumber);
        }
        return AttendanceReportCalculationRows.latestEffectiveAssignment(
                numbered, occurrenceDate);
    }

    private static List<OaDocumentRow> relocateOaEmployees(
            List<OaDocumentRow> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }
        return documents.stream()
                .filter(document -> !OaEmployeeNumberCatalog.suppressOvertime(
                        document.employeeNumber(),
                        document.startInstant(),
                        document.endInstant()))
                .map(document -> {
            String relocated = OaEmployeeNumberCatalog
                    .relocateOvertimeEmployeeNumber(
                            document.employeeNumber(),
                            document.startInstant(),
                            document.endInstant());
            if (relocated == null
                    || relocated.equals(document.employeeNumber())) {
                return document;
            }
            return new OaDocumentRow(
                    document.sourceBusinessKey(),
                    document.documentType(),
                    document.overtimeType(),
                    document.leaveType(),
                    relocated,
                    document.startInstant(),
                    document.endInstant(),
                    document.sourceTimeZone(),
                    document.firstSubmittedAt(),
                    document.effectiveCandidate(),
                    document.leaveSerial(),
                    document.originalLeaveSerial());
        }).toList();
    }

    private static List<OaReportFactRow> relocateReportableOaEmployees(
            List<OaReportFactRow> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }
        return documents.stream()
                .filter(document -> !OaEmployeeNumberCatalog.suppressOvertime(
                        document.employeeNumber(),
                        document.startInstant(),
                        document.endInstant()))
                .map(document -> {
            String relocated = OaEmployeeNumberCatalog
                    .relocateOvertimeEmployeeNumber(
                            document.employeeNumber(),
                            document.startInstant(),
                            document.endInstant());
            if (relocated == null
                    || relocated.equals(document.employeeNumber())) {
                return document;
            }
            return new OaReportFactRow(
                    document.oaAttendanceDocumentId(),
                    document.sourceBusinessKey(),
                    document.documentType(),
                    document.leaveType(),
                    document.employeeId(),
                    relocated,
                    document.employmentAssignmentId(),
                    document.startInstant(),
                    document.endInstant(),
                    document.sourceStatus(),
                    document.sourceVersion(),
                    document.overtimeType());
        }).toList();
    }

    private static boolean august2026AllowsFormWithoutPunch(LocalDate date) {
        return date != null
                && date.getYear() == 2026
                && date.getMonthValue() == 8;
    }

    private boolean punchRequiredForOvertime(
            EmployeeIdentityIntervalRow identity,
            Instant at,
            List<PunchExemptionRoleIntervalRow> exemptions) {
        if (identity != null
                && SiteShiftTemplates.noClockSiteFullAttendance(
                        identity.organizationName(),
                        at == null
                                ? null
                                : at.atZone(BUSINESS_ZONE).toLocalDate())) {
            return false;
        }
        if (identity != null && exemptions != null) {
            boolean exempt = exemptions.stream().anyMatch(role ->
                    identity.employeeId().equals(role.employeeId())
                            && role.activeAt(at));
            if (exempt) {
                return false;
            }
        }
        return true;
    }

    private long recognizedMinutes(
            OaReportFactRow document,
            Instant snappedStart,
            Instant snappedEnd,
            String employeeId,
            String organizationName,
            List<ShiftSegmentRow> shiftSegments,
            List<CalendarDayRow> calendarDays,
            List<PunchEventRow> punchRows,
            boolean punchRequired) {
        String documentType = document.documentType() == null
                ? ""
                : document.documentType().toUpperCase();
        if ("OVERTIME".equals(documentType)) {
            if (!effectiveOaStatus(document.sourceStatus())) {
                return 0L;
            }
            TimeInterval snapped = OaIntervalGrid.snapInterval(
                    document.startInstant(), document.endInstant());
            if (snapped == null) {
                return 0L;
            }
            if (punchRequired) {
                var lookup = OvertimeMealDeductions.lookup(
                        employeeId, shiftSegments, calendarDays);
                Instant last = OvertimeMealDeductions.lastCoveringOffPunch(
                        punchRows == null
                                ? List.of()
                                : punchRows.stream()
                                        .filter(row -> employeeId.equals(
                                                row.employeeId()))
                                        .map(PunchEventRow::pointInstant)
                                        .toList(),
                        snapped,
                        lookup.shiftOff(
                                snapped.start()
                                        .atZone(BUSINESS_ZONE)
                                        .toLocalDate()));
                if (last != null) {
                    snapped = OvertimeMealDeductions.capToSnappedLastPunch(
                            snapped, last);
                    if (snapped == null) {
                        return 0L;
                    }
                } else if (!august2026AllowsFormWithoutPunch(
                        snapped.start().atZone(BUSINESS_ZONE).toLocalDate())) {
                    return 0L;
                }
            }
            return OvertimeMealDeductions.recognizedMinutes(
                    snapped,
                    OvertimeMealDeductions.lookup(
                            employeeId, shiftSegments, calendarDays));
        }
        if (!snappedStart.isBefore(snappedEnd)) {
            return 0L;
        }
        if ("LEAVE".equals(documentType) || "TIME_OFF".equals(documentType)) {
            return LeaveHoursRecognizer.recognizedMinutes(
                    snappedStart,
                    snappedEnd,
                    document.leaveType(),
                    employeeId,
                    shiftSegments,
                    calendarDays == null ? List.of() : calendarDays,
                    organizationName);
        }
        List<TimeInterval> overlaps = shiftSegments.stream()
                .filter(segment -> segment.employeeId()
                        .equals(employeeId))
                .map(segment -> overlap(
                        snappedStart,
                        snappedEnd,
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

    private long overlapMinutes(
            Instant leftStart,
            Instant leftEnd,
            Instant rightStart,
            Instant rightEnd) {
        TimeInterval overlap = overlap(leftStart, leftEnd, rightStart, rightEnd);
        return overlap == null ? 0 : overlap.minutes();
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
                    if (identity == null) {
                        return null;
                    }
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
                .filter(Objects::nonNull)
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
        return null;
    }

    private record CalculatedDay(
            DailyAttendanceResult result, List<PunchEvent> dayPunches) {
    }

    private CalculatedDay calculateDay(
            String companyId,
            EmployeeIdentityIntervalRow identity,
            LocalDate businessDate,
            DayType dayType,
            DayType previousDayType,
            DayType nextDayType,
            List<ShiftSegmentRow> segments,
            List<ShiftSegmentRow> nextDaySegments,
            List<PunchEventRow> allPunches,
            List<PunchCorrectionRow> allPunchCorrections,
            List<HrPunchAdjustmentRow> hrPunchAdjustments,
            List<PunchExemptionRoleIntervalRow> punchExemptionRoles,
            List<IntervalEvidence> allOaEvidence,
            CalculationPolicy policy,
            YearMonth period,
            int monthlyGraceUsed,
            Instant dataAsOf,
            boolean fullAttendance) {

        Instant evidenceWindowStart = evidenceWindowStart(
                dayType, previousDayType, segments, businessDate);
        Instant evidenceWindowEnd = evidenceWindowEnd(
                dayType, nextDayType, nextDaySegments, businessDate);

        // Rest days use the calendar day. Work days after a rest day also
        // start at local midnight so early clock-ins stay today. Work days
        // after a work day still open at overnightCut so a 05:50 leaving
        // punch stays on the previous work day.
        List<PunchEvent> dayPunches = allPunches.stream()
                .filter(p -> !p.pointInstant().isBefore(evidenceWindowStart)
                        && !p.pointInstant().isAfter(evidenceWindowEnd))
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
        addHrPunchAdjustments(
                dayPunches,
                hrPunchAdjustments,
                businessDate,
                dataAsOf,
                allOaEvidence);
        addHireDayMorningDefault(identity, businessDate, dayPunches);
        addLeaveDayAfternoonDefault(
                identity, businessDate, dayType, dayPunches, segments);

        boolean punchExempt = fullAttendance
                || punchExemptionRoles.stream()
                .anyMatch(role -> role.validTo() == null
                        || role.validTo().isAfter(evidenceWindowStart));

        // Filter OA evidence for this day
        List<IntervalEvidence> dayOaEvidence = allOaEvidence.stream()
                .filter(e -> e.kind() != EvidenceKind.PUNCH_CORRECTION)
                .filter(e -> e.interval().start().isBefore(evidenceWindowEnd)
                        && e.interval().end().isAfter(evidenceWindowStart))
                .collect(Collectors.toCollection(ArrayList::new));
        dayOaEvidence.addAll(hrDayTypeEvidence(
                hrPunchAdjustments, businessDate, dataAsOf, allOaEvidence));

        List<ScheduledWorkSegment> workSegments = segments.stream()
                .map(segment -> toScheduledSegment(segment, evidenceWindowEnd))
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

        return new CalculatedDay(
                calculator.calculate(CALCULATION_VERSION_ID, snapshot),
                List.copyOf(dayPunches));
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

    private void addHrPunchAdjustments(
            List<PunchEvent> target,
            List<HrPunchAdjustmentRow> adjustments,
            LocalDate businessDate,
            Instant dataAsOf,
            List<IntervalEvidence> oaEvidence) {
        if (adjustments == null || adjustments.isEmpty()) {
            return;
        }
        if (oaCoversKind(oaEvidence, businessDate, EvidenceKind.PUNCH_CORRECTION)) {
            return;
        }
        for (HrPunchAdjustmentRow adjustment : adjustments) {
            if (!businessDate.equals(adjustment.businessDate())
                    || adjustment.createdAt().isAfter(dataAsOf)) {
                continue;
            }
            if (adjustment.onDutyAt() != null) {
                target.add(hrAdjustmentPunch(
                        adjustment, PunchDirection.ENTRY, adjustment.onDutyAt()));
            }
            if (adjustment.offDutyAt() != null) {
                target.add(hrAdjustmentPunch(
                        adjustment, PunchDirection.EXIT, adjustment.offDutyAt()));
            }
        }
    }

    private PunchEvent hrAdjustmentPunch(
            HrPunchAdjustmentRow adjustment,
            PunchDirection direction,
            Instant instant) {
        return new PunchEvent(
                "punch:hr-adjust-" + adjustment.adjustmentId()
                        + "-" + direction.name() + ":" + instant,
                instant,
                direction,
                "hr-adjust:" + adjustment.adjustmentId());
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

    private Instant evidenceWindowStart(
            DayType dayType,
            DayType previousDayType,
            List<ShiftSegmentRow> daySegments,
            LocalDate date) {
        return overnightCut(daySegments, date);
    }

    private Instant evidenceWindowEnd(
            DayType dayType,
            DayType nextDayType,
            List<ShiftSegmentRow> nextDaySegments,
            LocalDate date) {
        return overnightCut(nextDaySegments, date.plusDays(1));
    }

    private static boolean isRestDay(DayType dayType) {
        return dayType == DayType.SATURDAY
                || dayType == DayType.SUNDAY
                || dayType == DayType.PUBLIC_HOLIDAY;
    }

    private static boolean scopedEmployee(String employeeId) {
        return employeeId != null && !employeeId.isBlank();
    }

    private Instant overnightCut(
            List<ShiftSegmentRow> daySegments, LocalDate date) {
        return date.atTime(6, 0).atZone(BUSINESS_ZONE).toInstant();
    }

    private ScheduledWorkSegment toScheduledSegment(
            ShiftSegmentRow row, Instant overnightDepartureEnd) {
        Instant departureEnd = row.departureWindowEnd();
        if (overnightDepartureEnd != null
                && overnightDepartureEnd.isAfter(departureEnd)) {
            departureEnd = overnightDepartureEnd;
        }
        return new ScheduledWorkSegment(
                row.segmentId(),
                row.businessDate(),
                new TimeInterval(row.segmentStart(), row.segmentEnd()),
                new TimeInterval(row.arrivalWindowStart(), row.arrivalWindowEnd()),
                new TimeInterval(row.departureWindowStart(), departureEnd),
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

    private static EmployeeIdentityIntervalRow withReportDepartment(
            EmployeeIdentityIntervalRow identity,
            Map<String, String> reportDepartments) {
        String display = reportDepartments == null
                ? null
                : reportDepartments.get(identity.organizationId());
        if (display == null
                || Objects.equals(display, identity.organizationName())) {
            return identity;
        }
        return new EmployeeIdentityIntervalRow(
                identity.employeeId(),
                identity.employeeVersionId(),
                identity.employeeNumber(),
                identity.employeeName(),
                identity.employmentAssignmentId(),
                identity.organizationId(),
                identity.organizationVersionId(),
                display,
                identity.employeeVersionFrom(),
                identity.employeeVersionTo(),
                identity.employmentFrom(),
                identity.employmentTo(),
                identity.organizationVersionFrom(),
                identity.organizationVersionTo());
    }

    private VerifiedCalculatedFacts convertToVerifiedFacts(
            String companyId,
            EmployeeIdentityIntervalRow identity,
            LocalDate businessDate,
            DayType dayType,
            List<ShiftSegmentRow> daySegments,
            List<ShiftSegmentRow> nextDaySegments,
            DailyAttendanceResult result,
            LeaveType leaveType,
            List<PunchEvent> dayPunches,
            List<IntervalEvidence> oaEvidence,
            FormOvertimeMinutes formOvertime,
            List<HrPunchAdjustmentRow> hrAdjustments) {

        String factId = identity.employeeId() + ":" + businessDate;

        Instant nextShiftStart = OffScheduleAttendanceExceptions.shiftStart(
                nextDaySegments, businessDate.plusDays(1));
        DayClockSelection clocks = selectDayClocks(
                businessDate, dayPunches, daySegments, dayType, nextShiftStart);

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
                        leaveDayShiftLabel(
                                identity,
                                businessDate,
                                hireDayShiftLabel(
                                        identity,
                                        businessDate,
                                        resolveShiftTemplateName(
                                                identity.employeeId(),
                                                businessDate,
                                                dayType,
                                                daySegments))),
                        clocks.firstPunchAt(),
                        clocks.lastPunchAt(),
                        leaveType));

        HrPunchAdjustmentRow override = latestHrOverride(
                hrAdjustments, businessDate);
        DailyFact daily = withFormOvertime(facts.dailyFact(), formOvertime);
        Set<String> cleared = HrAttendanceOverride.parseClearedTypes(
                override == null ? null : override.clearedExceptionTypes());
        Integer overtimeOverride = override == null
                ? null
                : override.overtimeMinutesOverride();
        if (overtimeOverride != null
                && oaCoversKind(oaEvidence, businessDate, EvidenceKind.OVERTIME)) {
            overtimeOverride = null;
        }
        daily = HrAttendanceOverride.apply(
                daily,
                overtimeOverride,
                cleared);
        boolean hireDay = identity.employmentFrom() != null
                && identity.employmentFrom().equals(businessDate);
        boolean leaveDay = isLeaveDay(identity, businessDate);
        if (hireDay || leaveDay) {
            daily = HrAttendanceOverride.apply(
                    daily, null, Set.of("MISSING_PUNCH"));
        }
        List<ExceptionFact> exceptions = new ArrayList<>(facts.exceptionFacts());
        exceptions.addAll(OffScheduleAttendanceExceptions.extra(
                daily,
                OffScheduleAttendanceExceptions.shiftOff(daySegments),
                nextShiftStart,
                oaEvidence,
                daily.calculationVersionId()));
        exceptions.addAll(OffScheduleAttendanceExceptions.formBeyondLastPunch(
                daily,
                clocks.lastPunchAt(),
                oaEvidence,
                daily.calculationVersionId()));
        Set<String> hidden = new java.util.LinkedHashSet<>(cleared);
        if (hireDay || leaveDay) {
            hidden.add("MISSING_PUNCH");
        }
        return new VerifiedCalculatedFacts(
                identity.employeeVersionId(),
                identity.employmentAssignmentId(),
                new ProjectionFacts(
                        daily,
                        HrAttendanceOverride.filter(exceptions, hidden)));
    }

    private void addHireDayMorningDefault(
            EmployeeIdentityIntervalRow identity,
            LocalDate businessDate,
            List<PunchEvent> dayPunches) {
        if (identity == null
                || businessDate == null
                || identity.employmentFrom() == null
                || !identity.employmentFrom().equals(businessDate)
                || dayPunches == null) {
            return;
        }
        boolean hasMorning = dayPunches.stream().anyMatch(punch -> {
            LocalTime clock = punch.instant().atZone(BUSINESS_ZONE).toLocalTime();
            return !clock.isAfter(LocalTime.NOON);
        });
        if (hasMorning) {
            return;
        }
        Instant at = businessDate.atTime(8, 29).atZone(BUSINESS_ZONE).toInstant();
        dayPunches.add(new PunchEvent(
                "punch:hire-day:" + identity.employeeId() + ":" + businessDate,
                at,
                PunchDirection.ENTRY,
                "hire-day-default:08:29"));
    }

    private void addLeaveDayAfternoonDefault(
            EmployeeIdentityIntervalRow identity,
            LocalDate businessDate,
            DayType dayType,
            List<PunchEvent> dayPunches,
            List<ShiftSegmentRow> segments) {
        if (!isLeaveDay(identity, businessDate)
                || dayPunches == null
                || isRestDayType(dayType)) {
            return;
        }
        boolean hasAfternoon = dayPunches.stream().anyMatch(punch -> {
            LocalTime clock = punch.instant().atZone(BUSINESS_ZONE).toLocalTime();
            return clock.isAfter(LocalTime.NOON);
        });
        if (hasAfternoon) {
            return;
        }
        Instant shiftOff = OffScheduleAttendanceExceptions.shiftOff(segments);
        if (shiftOff == null) {
            shiftOff = OffScheduleAttendanceExceptions.shiftOff(
                    SiteShiftTemplates.workSegments(
                            identity.employeeId(),
                            businessDate,
                            SiteShiftTemplates.kindFor(
                                    identity.organizationName())));
        }
        if (shiftOff == null) {
            return;
        }
        Instant at = shiftOff.plusSeconds(60);
        dayPunches.add(new PunchEvent(
                "punch:leave-day:" + identity.employeeId() + ":" + businessDate,
                at,
                PunchDirection.EXIT,
                "leave-day-default"));
    }

    private static boolean isLeaveDay(
            EmployeeIdentityIntervalRow identity, LocalDate businessDate) {
        return identity != null
                && identity.employmentTo() != null
                && businessDate != null
                && businessDate.plusDays(1).equals(identity.employmentTo());
    }

    private static String leaveDayShiftLabel(
            EmployeeIdentityIntervalRow identity,
            LocalDate businessDate,
            String shiftLabel) {
        if (!isLeaveDay(identity, businessDate)) {
            return shiftLabel;
        }
        if (shiftLabel == null || shiftLabel.isBlank() || "无班次".equals(shiftLabel)) {
            return "离职";
        }
        if (shiftLabel.contains("离职")) {
            return shiftLabel;
        }
        return shiftLabel + " 离职";
    }

    private static String hireDayShiftLabel(
            EmployeeIdentityIntervalRow identity,
            LocalDate businessDate,
            String shiftLabel) {
        if (identity == null
                || businessDate == null
                || identity.employmentFrom() == null
                || !identity.employmentFrom().equals(businessDate)) {
            return shiftLabel;
        }
        if (shiftLabel == null || shiftLabel.isBlank() || "无班次".equals(shiftLabel)) {
            return "入职";
        }
        if (shiftLabel.contains("入职")) {
            return shiftLabel;
        }
        return shiftLabel + " 入职";
    }

    private List<IntervalEvidence> hrDayTypeEvidence(
            List<HrPunchAdjustmentRow> adjustments,
            LocalDate businessDate,
            Instant dataAsOf,
            List<IntervalEvidence> oaEvidence) {
        HrPunchAdjustmentRow latest = latestHrOverride(adjustments, businessDate);
        if (latest == null
                || latest.dayTypes() == null
                || latest.dayTypes().isBlank()) {
            return List.of();
        }
        if (latest.createdAt() != null && latest.createdAt().isAfter(dataAsOf)) {
            return List.of();
        }
        Instant start = businessDate.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant end = businessDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        List<IntervalEvidence> evidence = new ArrayList<>();
        for (String token : latest.dayTypes().split(",")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String type = token.trim().toUpperCase(java.util.Locale.ROOT);
            EvidenceKind kind = switch (type) {
                case "OUTING" -> EvidenceKind.OUTING;
                case "TRIP" -> EvidenceKind.TRIP;
                case "TIME_OFF" -> EvidenceKind.TIME_OFF;
                case "ANNUAL_LEAVE", "PERSONAL_LEAVE", "SICK_LEAVE",
                        "MARRIAGE_LEAVE", "BEREAVEMENT_LEAVE",
                        "MATERNITY_LEAVE", "PATERNITY_LEAVE",
                        "BREASTFEEDING_LEAVE", "WORK_INJURY_LEAVE" ->
                        EvidenceKind.LEAVE;
                default -> null;
            };
            if (kind == null) {
                continue;
            }
            if (oaCoversKind(oaEvidence, businessDate, kind)) {
                continue;
            }
            LeaveType leaveType = switch (type) {
                case "ANNUAL_LEAVE" -> LeaveType.ANNUAL;
                case "PERSONAL_LEAVE" -> LeaveType.PERSONAL;
                case "SICK_LEAVE" -> LeaveType.SICK;
                case "MARRIAGE_LEAVE" -> LeaveType.MARRIAGE;
                case "BEREAVEMENT_LEAVE" -> LeaveType.BEREAVEMENT;
                case "MATERNITY_LEAVE" -> LeaveType.MATERNITY;
                case "PATERNITY_LEAVE" -> LeaveType.PATERNITY;
                case "BREASTFEEDING_LEAVE" -> LeaveType.BREASTFEEDING;
                case "WORK_INJURY_LEAVE" -> LeaveType.WORK_INJURY;
                case "TIME_OFF" -> LeaveType.COMPENSATORY;
                default -> null;
            };
            if (kind != EvidenceKind.LEAVE) {
                leaveType = null;
            }
            evidence.add(new IntervalEvidence(
                    "hr-day-type:" + latest.adjustmentId() + ":" + type,
                    kind,
                    new TimeInterval(start, end),
                    "hr-adjust:" + latest.adjustmentId(),
                    latest.createdAt() == null ? dataAsOf : latest.createdAt(),
                    true,
                    null,
                    leaveType));
        }
        return evidence;
    }

    private List<VerifiedOaDocumentFact> projectHrDayTypeOaFacts(
            String companyId,
            List<HrPunchAdjustmentRow> adjustments,
            List<EmployeeIdentityIntervalRow> identities,
            Instant dataAsOf) {
        if (adjustments == null || adjustments.isEmpty()) {
            return List.of();
        }
        Map<EmployeeBusinessDate, HrPunchAdjustmentRow> latest = new LinkedHashMap<>();
        for (HrPunchAdjustmentRow adjustment : adjustments) {
            if (adjustment.dayTypes() == null || adjustment.dayTypes().isBlank()) {
                continue;
            }
            if (adjustment.createdAt() != null
                    && dataAsOf != null
                    && adjustment.createdAt().isAfter(dataAsOf)) {
                continue;
            }
            EmployeeBusinessDate key = new EmployeeBusinessDate(
                    adjustment.employeeId(), adjustment.businessDate());
            HrPunchAdjustmentRow existing = latest.get(key);
            if (existing == null
                    || adjustment.createdAt().isAfter(existing.createdAt())) {
                latest.put(key, adjustment);
            }
        }
        List<VerifiedOaDocumentFact> facts = new ArrayList<>();
        for (HrPunchAdjustmentRow row : latest.values()) {
            List<EmployeeIdentityIntervalRow> candidates = identities.stream()
                    .filter(identity -> identity.employeeId().equals(row.employeeId()))
                    .toList();
            EmployeeIdentityIntervalRow identity = findUnambiguousIdentity(
                    candidates, row.businessDate());
            if (identity == null) {
                continue;
            }
            Instant start = row.businessDate().atStartOfDay(BUSINESS_ZONE).toInstant();
            Instant end = row.businessDate().plusDays(1)
                    .atStartOfDay(BUSINESS_ZONE)
                    .toInstant();
            for (String token : row.dayTypes().split(",")) {
                if (token == null || token.isBlank()) {
                    continue;
                }
                String type = token.trim().toUpperCase(java.util.Locale.ROOT);
                String documentType;
                String leaveTypeCode;
                switch (type) {
                    case "OUTING" -> {
                        documentType = "OUTING";
                        leaveTypeCode = null;
                    }
                    case "TRIP" -> {
                        documentType = "TRIP";
                        leaveTypeCode = null;
                    }
                    case "TIME_OFF" -> {
                        documentType = "TIME_OFF";
                        leaveTypeCode = "TIME_OFF";
                    }
                    case "ANNUAL_LEAVE" -> {
                        documentType = "LEAVE";
                        leaveTypeCode = "ANNUAL";
                    }
                    case "PERSONAL_LEAVE" -> {
                        documentType = "LEAVE";
                        leaveTypeCode = "PERSONAL";
                    }
                    case "SICK_LEAVE" -> {
                        documentType = "LEAVE";
                        leaveTypeCode = "SICK";
                    }
                    case "MARRIAGE_LEAVE" -> {
                        documentType = "LEAVE";
                        leaveTypeCode = "MARRIAGE";
                    }
                    case "BEREAVEMENT_LEAVE" -> {
                        documentType = "LEAVE";
                        leaveTypeCode = "BEREAVEMENT";
                    }
                    case "MATERNITY_LEAVE" -> {
                        documentType = "LEAVE";
                        leaveTypeCode = "MATERNITY";
                    }
                    case "PATERNITY_LEAVE" -> {
                        documentType = "LEAVE";
                        leaveTypeCode = "PATERNITY";
                    }
                    case "BREASTFEEDING_LEAVE" -> {
                        documentType = "LEAVE";
                        leaveTypeCode = "BREASTFEEDING";
                    }
                    case "WORK_INJURY_LEAVE" -> {
                        documentType = "LEAVE";
                        leaveTypeCode = "WORK_INJURY";
                    }
                    default -> {
                        continue;
                    }
                }
                String documentId = UUID.nameUUIDFromBytes(
                        ("hr-day-type:" + row.adjustmentId() + ":" + type)
                                .getBytes(StandardCharsets.UTF_8))
                        .toString();
                facts.add(new VerifiedOaDocumentFact(
                        companyId,
                        documentId,
                        identity.employeeId(),
                        identity.employeeVersionId(),
                        identity.employmentAssignmentId(),
                        identity.organizationId(),
                        identity.organizationVersionId(),
                        documentType,
                        leaveTypeCode,
                        OaTemporalShape.INTERVAL,
                        null,
                        start,
                        end,
                        0,
                        "APPROVED",
                        "hr-day-type-v1",
                        "PAPER"));
            }
        }
        return facts;
    }

    private List<VerifiedOaDocumentFact> projectHrPunchCorrectionOaFacts(
            String companyId,
            List<HrPunchAdjustmentRow> adjustments,
            List<EmployeeIdentityIntervalRow> identities,
            Instant dataAsOf) {
        if (adjustments == null || adjustments.isEmpty()) {
            return List.of();
        }
        List<VerifiedOaDocumentFact> facts = new ArrayList<>();
        for (HrPunchAdjustmentRow row : adjustments) {
            if (row.onDutyAt() == null && row.offDutyAt() == null) {
                continue;
            }
            if (row.createdAt() != null
                    && dataAsOf != null
                    && row.createdAt().isAfter(dataAsOf)) {
                continue;
            }
            List<EmployeeIdentityIntervalRow> candidates = identities.stream()
                    .filter(identity -> identity.employeeId().equals(row.employeeId()))
                    .toList();
            EmployeeIdentityIntervalRow identity = findUnambiguousIdentity(
                    candidates, row.businessDate());
            if (identity == null) {
                continue;
            }
            if (row.onDutyAt() != null) {
                facts.add(hrPunchCorrectionFact(
                        companyId, identity, row, "ENTRY", row.onDutyAt()));
            }
            if (row.offDutyAt() != null) {
                facts.add(hrPunchCorrectionFact(
                        companyId, identity, row, "EXIT", row.offDutyAt()));
            }
        }
        return facts;
    }

    private static VerifiedOaDocumentFact hrPunchCorrectionFact(
            String companyId,
            EmployeeIdentityIntervalRow identity,
            HrPunchAdjustmentRow row,
            String side,
            Instant at) {
        String documentId = UUID.nameUUIDFromBytes(
                ("hr-punch-correction:" + row.adjustmentId() + ":" + side)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
        return new VerifiedOaDocumentFact(
                companyId,
                documentId,
                identity.employeeId(),
                identity.employeeVersionId(),
                identity.employmentAssignmentId(),
                identity.organizationId(),
                identity.organizationVersionId(),
                "PUNCH_CORRECTION",
                null,
                OaTemporalShape.POINT,
                at,
                null,
                null,
                0,
                "APPROVED",
                "hr-punch-correction-v1",
                "PAPER");
    }

    private static HrPunchAdjustmentRow latestHrOverride(
            List<HrPunchAdjustmentRow> adjustments, LocalDate businessDate) {
        if (adjustments == null || adjustments.isEmpty()) {
            return null;
        }
        HrPunchAdjustmentRow latest = null;
        for (HrPunchAdjustmentRow adjustment : adjustments) {
            if (!businessDate.equals(adjustment.businessDate())) {
                continue;
            }
            if (latest == null
                    || adjustment.createdAt().isAfter(latest.createdAt())) {
                latest = adjustment;
            }
        }
        return latest;
    }

    private static DailyFact withFormOvertime(
            DailyFact fact, FormOvertimeMinutes overtime) {
        long recognized = overtime.recognized();
        return new DailyFact(
                fact.factId(),
                fact.companyId(),
                fact.employeeId(),
                fact.employeeNumber(),
                fact.employeeName(),
                fact.organizationId(),
                fact.organizationVersionId(),
                fact.organizationName(),
                fact.businessDate(),
                fact.dayType(),
                fact.shiftLabel(),
                fact.scheduledMinutes(),
                fact.confirmedScheduledWorkMinutes(),
                recognized,
                overtime.paid(),
                overtime.compensatory(),
                overtime.voluntary(),
                overtime.paid()
                        + overtime.compensatory()
                        + overtime.voluntary(),
                fact.leaveOrTimeOffMinutes(),
                fact.absenceMinutes(),
                fact.confirmedScheduledWorkMinutes() + recognized,
                fact.scheduledAttendanceDays(),
                fact.actualAttendanceDays(),
                fact.lateMinutes(),
                fact.penalizedLateMinutes(),
                fact.earlyDepartureMinutes(),
                fact.missingPunchCount(),
                fact.firstPunchAt(),
                fact.lastPunchAt(),
                fact.calculationVersionId(),
                fact.resultDigest(),
                fact.leaveType());
    }

    private static boolean effectiveOaStatus(String sourceStatus) {
        if (sourceStatus == null) {
            return false;
        }
        return switch (sourceStatus.toUpperCase()) {
            case "APPROVED", "MODIFIED", "SUPPLEMENTED", "UNKNOWN" -> true;
            default -> false;
        };
    }

    private static boolean oaCoversKind(
            List<IntervalEvidence> oaEvidence,
            LocalDate businessDate,
            EvidenceKind kind) {
        if (oaEvidence == null || businessDate == null || kind == null) {
            return false;
        }
        Instant dayStart = businessDate.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant dayEnd = businessDate.plusDays(1)
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();
        for (IntervalEvidence evidence : oaEvidence) {
            if (evidence == null
                    || !evidence.effective()
                    || evidence.kind() != kind
                    || evidence.interval() == null) {
                continue;
            }
            String id = evidence.evidenceId() == null ? "" : evidence.evidenceId();
            if (id.startsWith("hr-")) {
                continue;
            }
            if (kind == EvidenceKind.OVERTIME) {
                LocalDate startDate = evidence.interval()
                        .start()
                        .atZone(BUSINESS_ZONE)
                        .toLocalDate();
                if (businessDate.equals(startDate)) {
                    return true;
                }
                continue;
            }
            if (evidence.interval().start().isBefore(dayEnd)
                    && evidence.interval().end().isAfter(dayStart)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Day-shift clocks: earliest before noon is 上班; latest after noon or a
     * next-day punch strictly before 06:00 is 下班. 06:00 and later belong
     * to the next work day as 上班.
     */
    private static DayClockSelection selectDayClocks(
            LocalDate businessDate,
            List<PunchEvent> dayPunches,
            List<ShiftSegmentRow> daySegments,
            DayType dayType,
            Instant nextShiftStart) {
        if (dayPunches == null || dayPunches.isEmpty()) {
            return new DayClockSelection(null, null);
        }
        Instant overnightEnd = businessDate.plusDays(1)
                .atTime(6, 0)
                .atZone(BUSINESS_ZONE)
                .toInstant();
        if (isRestDay(dayType) || nightShift(daySegments)) {
            List<Instant> times = dayPunches.stream()
                    .map(PunchEvent::instant)
                    .sorted()
                    .toList();
            Instant last = times.getLast();
            if (last.isAfter(overnightEnd)
                    && last.atZone(BUSINESS_ZONE)
                            .toLocalDate()
                            .isAfter(businessDate)
                    && times.size() > 1) {
                last = times.get(times.size() - 2);
            }
            return new DayClockSelection(times.getFirst(), last);
        }
        Instant morningEarliest = null;
        Instant afternoonLatest = null;
        LocalDate nextDay = businessDate.plusDays(1);
        for (PunchEvent punch : dayPunches) {
            Instant at = punch.instant();
            LocalDate date = at.atZone(BUSINESS_ZONE).toLocalDate();
            LocalTime time = at.atZone(BUSINESS_ZONE).toLocalTime();
            boolean morning = date.equals(businessDate)
                    && time.isBefore(LocalTime.NOON);
            boolean afternoon = (date.equals(businessDate)
                    && !time.isBefore(LocalTime.NOON))
                    || (date.equals(nextDay) && at.isBefore(overnightEnd));
            if (morning
                    && (morningEarliest == null || at.isBefore(morningEarliest))) {
                morningEarliest = at;
            }
            if (afternoon
                    && (afternoonLatest == null || at.isAfter(afternoonLatest))) {
                afternoonLatest = at;
            }
        }
        return new DayClockSelection(morningEarliest, afternoonLatest);
    }

    private static boolean nightShift(List<ShiftSegmentRow> daySegments) {
        if (daySegments == null || daySegments.isEmpty()) {
            return false;
        }
        return daySegments.stream()
                .map(segment -> segment.segmentStart()
                        .atZone(BUSINESS_ZONE)
                        .toLocalTime())
                .allMatch(start -> !start.isBefore(LocalTime.NOON));
    }

    private record DayClockSelection(
            Instant firstPunchAt, Instant lastPunchAt) {
    }

    private CalculationPolicy buildPolicy(
            AttendancePolicyRow policyRow,
            LocalDate businessDate,
            DayType dayType,
            List<ShiftSegmentRow> daySegments,
            String employeeId,
            List<ShiftSegmentRow> allSegments) {
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
                weekendMealDeductions(
                        businessDate,
                        dayType,
                        daySegments,
                        employeeId,
                        allSegments));
    }

    private List<MealDeductionRule>
            weekendMealDeductions(
                    LocalDate businessDate,
                    DayType dayType,
                    List<ShiftSegmentRow> daySegments,
                    String employeeId,
                    List<ShiftSegmentRow> allSegments) {
        boolean restDay = dayType == DayType.SATURDAY
                || dayType == DayType.SUNDAY
                || dayType == DayType.PUBLIC_HOLIDAY;
        Instant lunchStart = businessDate.atTime(12, 0)
                .atZone(BUSINESS_ZONE)
                .toInstant();
        Instant lunchEnd = businessDate.atTime(13, 0)
                .atZone(BUSINESS_ZONE)
                .toInstant();
        Instant offTime = daySegments.stream()
                .map(ShiftSegmentRow::segmentEnd)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (offTime == null) {
            LocalTime weekdayOff = OvertimeMealDeductions.lookup(
                    employeeId, allSegments, List.of())
                    .shiftOff(businessDate);
            offTime = businessDate.atTime(weekdayOff)
                    .atZone(BUSINESS_ZONE)
                    .toInstant();
        }
        Instant dinnerEnd = offTime.plusSeconds(30 * 60);
        List<MealDeductionRule> rules = new ArrayList<>();
        if (restDay) {
            rules.add(new MealDeductionRule(
                    "weekend-lunch",
                    new TimeInterval(lunchStart, lunchEnd),
                    60,
                    true));
        }
        rules.add(new MealDeductionRule(
                "dinner-after-shift",
                new TimeInterval(offTime, dinnerEnd),
                30,
                true));
        return rules;
    }

    private AttendancePolicyRow syntheticPolicy(
            String employeeId, LocalDate businessDate) {
        return new AttendancePolicyRow(
                employeeId,
                businessDate,
                1,
                1,
                true,
                15,
                1,
                true,
                15,
                3,
                false,
                1,
                true,
                7,
                "NEXT_DAY_START_AFTER_FULL_DAYS",
                1,
                true,
                48);
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
                if (employeeIds.isEmpty()) {
                    continue;
                }
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

    private Map<String, List<PunchEventRow>> mapOaMakeupPunches(
            Map<String, List<OaDocumentRow>> oaByEmployeeNumber,
            List<EmployeeIdentityIntervalRow> identities) {
        Map<String, List<PunchEventRow>> result = new HashMap<>();
        for (Map.Entry<String, List<OaDocumentRow>> entry :
                oaByEmployeeNumber.entrySet()) {
            for (OaDocumentRow document : entry.getValue()) {
                if (!"PUNCH_CORRECTION".equals(document.documentType())
                        || !document.effectiveCandidate()
                        || document.startInstant() == null) {
                    continue;
                }
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
                    continue;
                }
                result.computeIfAbsent(
                                employeeIds.getFirst(),
                                ignored -> new ArrayList<>())
                        .add(new PunchEventRow(
                                employeeIds.getFirst(),
                                document.startInstant()));
            }
        }
        result.replaceAll((ignored, punches) -> List.copyOf(punches));
        return Map.copyOf(result);
    }

    private LeaveType leaveTypeForDay(
            List<IntervalEvidence> evidence,
            LocalDate businessDate,
            DayType dayType) {
        Instant dayStart = businessDate.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant dayEnd = businessDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        IntervalEvidence chosen = evidence.stream()
                .filter(item -> item.kind()
                        == com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind.LEAVE)
                .filter(item -> item.interval().start().isBefore(dayEnd)
                        && item.interval().end().isAfter(dayStart))
                .filter(item -> item.leaveType() != null)
                .max(Comparator
                        .comparingLong((IntervalEvidence item) ->
                                item.interval().minutes())
                        .thenComparing(item -> item.firstSubmittedAt() == null
                                ? Instant.EPOCH
                                : item.firstSubmittedAt()))
                .orElse(null);
        if (chosen == null) {
            return null;
        }
        LeaveType type = chosen.leaveType();
        if (isRestDayType(dayType) && !type.includesWeekendHours()) {
            return null;
        }
        return type;
    }

    private static boolean isRestDayType(DayType dayType) {
        return dayType == DayType.SATURDAY
                || dayType == DayType.SUNDAY
                || dayType == DayType.PUBLIC_HOLIDAY;
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
                continue;
            }
            DayType mapped = reportDayType(day);
            EmployeeBusinessDate key = new EmployeeBusinessDate(
                    day.employeeId(), day.businessDate());
            DayType existing = result.putIfAbsent(key, mapped);
            if (existing != null && existing != mapped) {
                result.remove(key);
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
        return dayType;
    }

    private String resolveShiftTemplateName(
            String employeeId,
            LocalDate businessDate,
            DayType dayType,
            List<ShiftSegmentRow> segments) {
        List<String> names = segments.stream()
                .map(ShiftSegmentRow::shiftTemplateName)
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (names.size() >= 1) {
            return names.getFirst();
        }
        if (dayType == DayType.WEEKDAY
                || dayType == DayType.ADJUSTED_WORKDAY) {
            return "未命名班次";
        }
        return "无班次";
    }

    static List<ShiftSegmentRow> applyPunchWindows(
            List<ShiftSegmentRow> segments,
            List<PunchWindowRow> windows) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        if (windows == null || windows.isEmpty()) {
            boolean alreadyWindowed = segments.stream()
                    .allMatch(segment -> segment.arrivalWindowStart() != null);
            return alreadyWindowed ? segments : List.of();
        }
        Map<LocalDate, PunchWindowRow> byDate = new HashMap<>();
        for (PunchWindowRow window : windows) {
            if (window == null || window.businessDate() == null) {
                continue;
            }
            PunchWindowRow previous = byDate.putIfAbsent(
                    window.businessDate(), window);
            if (previous != null) {
                return List.of();
            }
        }
        List<ShiftSegmentRow> resolved = new ArrayList<>();
        for (ShiftSegmentRow segment : segments) {
            PunchWindowRow window = byDate.get(segment.businessDate());
            if (window == null) {
                continue;
            }
            resolved.add(segment.withPunchWindow(window));
        }
        return resolved;
    }

    private boolean hasScheduledSegments(
            DayType dayType,
            List<ShiftSegmentRow> segments) {
        if (dayType == DayType.WEEKDAY
                || dayType == DayType.ADJUSTED_WORKDAY) {
            return !segments.isEmpty();
        }
        return true;
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

    private static List<OaReportFactRow> uniqueOaReportRows(
            List<OaReportFactRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<String, OaReportFactRow> overtime = new LinkedHashMap<>();
        List<OaReportFactRow> others = new ArrayList<>();
        for (OaReportFactRow row : rows) {
            if (row == null) {
                continue;
            }
            if (!"OVERTIME".equalsIgnoreCase(row.documentType())) {
                others.add(row);
                continue;
            }
            String key = overtimeStartKey(
                    row.employeeNumber(),
                    row.startInstant());
            OaReportFactRow existing = overtime.get(key);
            if (preferLaterOvertime(
                    row.endInstant(),
                    row.oaAttendanceDocumentId(),
                    existing == null ? null : existing.endInstant(),
                    existing == null ? null : existing.oaAttendanceDocumentId())) {
                overtime.put(key, row);
            }
        }
        others.addAll(overtime.values());
        return others;
    }

    private FormOvertimeMinutes formOvertimeFromEvidence(
            List<IntervalEvidence> oaEvidence,
            LocalDate businessDate,
            List<PunchEvent> dayPunches,
            String employeeId,
            List<ShiftSegmentRow> daySegments,
            List<CalendarDayRow> calendarDays,
            boolean punchRequired) {
        var lookup = OvertimeMealDeductions.lookup(
                employeeId, daySegments, calendarDays);
        FormOvertimeMinutes result = FormOvertimeMinutes.zero();
        Map<String, IntervalEvidence> unique = new LinkedHashMap<>();
        if (oaEvidence != null) {
            for (IntervalEvidence evidence : oaEvidence) {
                if (!evidence.effective()
                        || evidence.kind() != EvidenceKind.OVERTIME) {
                    continue;
                }
                LocalDate startDate = evidence.interval()
                        .start()
                        .atZone(BUSINESS_ZONE)
                        .toLocalDate();
                if (!businessDate.equals(startDate)) {
                    continue;
                }
                String key = overtimeStartKey(
                        employeeId,
                        evidence.interval().start());
                IntervalEvidence existing = unique.get(key);
                if (preferLaterOvertime(
                        evidence.interval().end(),
                        evidence.evidenceId(),
                        existing == null ? null : existing.interval().end(),
                        existing == null ? null : existing.evidenceId())) {
                    unique.put(key, evidence);
                }
            }
        }
        List<Instant> punchInstants = dayPunches == null
                ? List.of()
                : dayPunches.stream().map(PunchEvent::instant).toList();
        for (IntervalEvidence evidence : unique.values()) {
            TimeInterval snapped = OaIntervalGrid.snapInterval(
                    evidence.interval().start(), evidence.interval().end());
            if (snapped == null) {
                continue;
            }
            if (punchRequired) {
                Instant last = OvertimeMealDeductions.lastCoveringOffPunch(
                        punchInstants,
                        snapped,
                        lookup.shiftOff(businessDate));
                if (last != null) {
                    snapped = OvertimeMealDeductions.capToSnappedLastPunch(
                            snapped, last);
                    if (snapped == null) {
                        continue;
                    }
                } else if (!august2026AllowsFormWithoutPunch(businessDate)) {
                    continue;
                }
            }
            long minutes = OvertimeMealDeductions.recognizedMinutes(
                    snapped, lookup);
            result = result.plus(evidence.overtimeType(), minutes);
        }
        return result;
    }

    private record EmployeeBusinessDate(
            String employeeId,
            LocalDate businessDate) {
    }

    private record FormOvertimeMinutes(
            long recognized,
            long paid,
            long compensatory,
            long voluntary) {

        static FormOvertimeMinutes zero() {
            return new FormOvertimeMinutes(0, 0, 0, 0);
        }

        FormOvertimeMinutes plus(OvertimeType type, long minutes) {
            if (minutes <= 0) {
                return this;
            }
            long nextPaid = paid;
            long nextCompensatory = compensatory;
            long nextVoluntary = voluntary;
            if (type == OvertimeType.PAID) {
                nextPaid += minutes;
            } else if (type == OvertimeType.COMPENSATORY) {
                nextCompensatory += minutes;
            } else if (type == OvertimeType.VOLUNTARY) {
                nextVoluntary += minutes;
            }
            return new FormOvertimeMinutes(
                    recognized + minutes,
                    nextPaid,
                    nextCompensatory,
                    nextVoluntary);
        }

        FormOvertimeMinutes add(FormOvertimeMinutes other) {
            return new FormOvertimeMinutes(
                    recognized + other.recognized,
                    paid + other.paid,
                    compensatory + other.compensatory,
                    voluntary + other.voluntary);
        }
    }
}
