package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.AttendancePolicyRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.EmployeeIdentityIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaReportFactRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchEventRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.SourceInputVersionRow;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Orchestrator-level regression: cross-month SICK leave published to a Sep
 * projection must have no October dates in its recognizedMinutesByDate map.
 * The amended-interval path (销假) must also clip to the period boundary.
 *
 * Real data: 唐勇 SZST0026 second sick-leave doc spans Sep 17 → Oct 16 2026.
 */
@ExtendWith(MockitoExtension.class)
class OrchestratorCrossMonthLeaveClipTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String COMPANY = "company-sep";

    @Mock
    private AttendanceReportCalculationMapper mapper;

    @InjectMocks
    private FullCalculationEngineOrchestrator orchestrator;

    @BeforeEach
    void defaultEmptyMocks() {
        Mockito.lenient().when(mapper.findAttendanceSourceVersions(
                        Mockito.anyString(), Mockito.any(Instant.class)))
                .thenReturn(List.of(new SourceInputVersionRow(
                        "src-1", "DELI", 1L, 1, null,
                        "a".repeat(64), Instant.parse("2026-09-01T00:00:00Z"))));
        Mockito.lenient().when(mapper.countLateDaysBeforeWindow(
                        Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findTimeAccountSnapshots(
                        Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findHrPunchAdjustments(
                        Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findStandingPunchExemptionIntervals(
                        Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findCurrentOrganizationGraph(Mockito.anyString()))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findCurrentOrganizationAncestors(Mockito.anyString()))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findEffectiveOaDocuments(
                        Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findApprovedPunchCorrections(
                        Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findPunchExemptionRoleIntervals(
                        Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findActivatedPunchEvents(
                        Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findReportableOaDocuments(
                        Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** AM shift 08:30–12:00 */
    private static ShiftSegmentRow amShift(LocalDate date) {
        Instant s = date.atTime(8, 30).atZone(SHANGHAI).toInstant();
        Instant e = date.atTime(12, 0).atZone(SHANGHAI).toInstant();
        return new ShiftSegmentRow("emp-1", date, "am-" + date, s, e,
                s.minusSeconds(1800), s.plusSeconds(1800),
                e.minusSeconds(1800), e.plusSeconds(1800));
    }

    /** PM shift 13:30–18:00 */
    private static ShiftSegmentRow pmShift(LocalDate date) {
        Instant s = date.atTime(13, 30).atZone(SHANGHAI).toInstant();
        Instant e = date.atTime(18, 0).atZone(SHANGHAI).toInstant();
        return new ShiftSegmentRow("emp-1", date, "pm-" + date, s, e,
                s.minusSeconds(1800), s.plusSeconds(1800),
                e.minusSeconds(1800), e.plusSeconds(1800));
    }

    private static AttendancePolicyRow policy(LocalDate date) {
        return new AttendancePolicyRow(
                "emp-1", date,
                1, 1, true, 15,
                1, true, 15, 1, false,
                1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS",
                1, true, 48);
    }

    private static EmployeeIdentityIntervalRow identity(LocalDate from, LocalDate toExcl) {
        return new EmployeeIdentityIntervalRow(
                "emp-1", "emp-v-1", "E001", "唐勇", "assignment-1",
                "org-1", "org-v-1", "研发部",
                from, toExcl,
                LocalDate.of(2020, 1, 1), null,
                from, toExcl);
    }

    private static List<CalendarDayRow> sepCalendar() {
        return List.of(
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 17), "WEEKDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 18), "WEEKDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 19), "SATURDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 20), "ADJUSTED_WORKDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 21), "WEEKDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 22), "WEEKDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 23), "WEEKDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 24), "WEEKDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 25), "PUBLIC_HOLIDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 26), "PUBLIC_HOLIDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 27), "PUBLIC_HOLIDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 28), "WEEKDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 29), "WEEKDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 30), "WEEKDAY", 1, 1));
    }

    private static List<ShiftSegmentRow> sepShifts() {
        List<ShiftSegmentRow> s = new java.util.ArrayList<>();
        for (int d : new int[]{17, 18, 20, 21, 22, 23, 24, 28, 29, 30}) {
            LocalDate date = LocalDate.of(2026, 9, d);
            s.add(amShift(date));
            s.add(pmShift(date));
        }
        return s;
    }

    // ── assemble()-level test ─────────────────────────────────────────────────

    @Test
    void crossMonthSickLeavePublishedFactContainsOnlySeptemberDates() {
        Instant docStart = Instant.parse("2026-09-17T00:30:00Z");
        Instant docEnd   = Instant.parse("2026-10-16T16:00:00Z");
        Instant dataAsOf = Instant.parse("2026-10-04T16:00:00Z");
        stubSepMonth(dataAsOf, docStart, docEnd);

        PublishCommand command = orchestrator.assemble(
                COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "admin-1", dataAsOf);

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact -> {
            Map<LocalDate, Long> byDate = fact.recognizedMinutesByDate();
            assertThat(byDate).isNotNull().isNotEmpty();
            assertThat(byDate.keySet()).allSatisfy(d ->
                    assertThat(d.getMonthValue()).as("date %s must be Sep", d).isEqualTo(9));

            // REST44: REST_DAY SICK dates have explicit 0-minute coverage (9/19, 9/25, 9/26, 9/27)
            // These show "病假" badge but contribute 0 working minutes
            assertThat(byDate).containsEntry(LocalDate.of(2026, 9, 19), 0L);
            assertThat(byDate).containsEntry(LocalDate.of(2026, 9, 25), 0L);
            assertThat(byDate).containsEntry(LocalDate.of(2026, 9, 26), 0L);
            assertThat(byDate).containsEntry(LocalDate.of(2026, 9, 27), 0L);

            // Workdays have positive minutes
            assertThat(byDate).containsKey(LocalDate.of(2026, 9, 20));
            assertThat(byDate).containsKey(LocalDate.of(2026, 9, 17));
            assertThat(byDate.get(LocalDate.of(2026, 9, 20))).isGreaterThan(0L);
            assertThat(byDate.get(LocalDate.of(2026, 9, 17))).isGreaterThan(0L);
        });
    }

    @Test
    void crossMonthSickLeaveRecognizedMinutesMatchesSepByDateSum() {
        Instant docStart = Instant.parse("2026-09-17T00:30:00Z");
        Instant docEnd   = Instant.parse("2026-10-16T16:00:00Z");
        Instant dataAsOf = Instant.parse("2026-10-04T16:00:00Z");
        stubSepMonth(dataAsOf, docStart, docEnd);

        PublishCommand command = orchestrator.assemble(
                COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "admin-1", dataAsOf);

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact -> {
            Map<LocalDate, Long> byDate = fact.recognizedMinutesByDate();
            long sum = byDate.values().stream().mapToLong(Long::longValue).sum();
            assertThat(fact.recognizedMinutes()).isEqualTo(sum);
            // Sep 17+18+20+21+22+23+24+28+29+30 = 10 days × 480 = 4800
            assertThat(fact.recognizedMinutes()).isEqualTo(4800L);
        });
    }

    // ── reflection-based recognizedMinutesForRow tests ───────────────────────

    /**
     * Invoke the private recognizedMinutesForRow via reflection and return
     * the RecognizedOaMinutes record's byDate map.
     */
    @SuppressWarnings("unchecked")
    private Map<LocalDate, Long> invokeRecognizedMinutesForRow(
            OaReportFactRow document,
            Instant snappedStart,
            Instant snappedEnd,
            Map<String, List<TimeInterval>> amendedLeaveIntervals,
            String employeeId,
            String organizationName,
            List<ShiftSegmentRow> shiftSegments,
            List<CalendarDayRow> calendarDays,
            Instant periodStartInstant,
            Instant periodEndExclusiveInstant,
            List<EmployeeIdentityIntervalRow> employeeIdentities) throws Exception {
        Method method = FullCalculationEngineOrchestrator.class.getDeclaredMethod(
                "recognizedMinutesForRow",
                OaReportFactRow.class,
                Instant.class,
                Instant.class,
                Map.class,
                String.class,
                String.class,
                List.class,
                List.class,
                List.class,
                boolean.class,
                boolean.class,
                Instant.class,
                Instant.class,
                Instant.class,
                Map.class,
                List.class);  // employeeIdentities
        method.setAccessible(true);
        Object result = method.invoke(orchestrator,
                document,
                snappedStart,
                snappedEnd,
                amendedLeaveIntervals,
                employeeId,
                organizationName,
                shiftSegments,
                calendarDays,
                List.of(),    // punchRows
                false,        // punchRequired
                false,        // makeupOffDuty
                null,         // hrOffDuty
                periodStartInstant,
                periodEndExclusiveInstant,
                Map.of(),     // authorizedDailySegments
                employeeIdentities);  // employeeIdentities
        // RecognizedOaMinutes is a private record — access its byDate and minutes fields
        Method byDateMethod = result.getClass().getDeclaredMethod("byDate");
        byDateMethod.setAccessible(true);
        return (Map<LocalDate, Long>) byDateMethod.invoke(result);
    }

    @Test
    void amendedIntervalsEntirelyInOctProduceZeroSepMinutesViaReflection() throws Exception {
        // 唐勇 second doc: original interval Sep 17 → Oct 16.
        // Amendment replaces the interval with Oct 8–10 only.
        // Sep period [Aug 31 16:00Z, Sep 30 16:00Z) = [Sep 1, Oct 1) CST.
        // Amended interval is entirely in Oct → must produce empty byDate and 0 minutes.
        Instant docStart    = Instant.parse("2026-09-17T00:30:00Z");
        Instant docEnd      = Instant.parse("2026-10-16T16:00:00Z");
        Instant amendStart  = Instant.parse("2026-10-07T16:00:00Z"); // Oct 8 00:00 CST
        Instant amendEnd    = Instant.parse("2026-10-09T16:00:00Z"); // Oct 10 00:00 CST
        Instant periodStart = Instant.parse("2026-08-31T16:00:00Z"); // Sep 1 00:00 CST
        Instant periodEnd   = Instant.parse("2026-09-30T16:00:00Z"); // Oct 1 00:00 CST

        List<ShiftSegmentRow> shifts = List.of(
                amShift(LocalDate.of(2026, 10, 8)), pmShift(LocalDate.of(2026, 10, 8)),
                amShift(LocalDate.of(2026, 10, 9)), pmShift(LocalDate.of(2026, 10, 9)));
        List<CalendarDayRow> calendar = List.of(
                new CalendarDayRow("emp-1", LocalDate.of(2026, 10, 8), "WEEKDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 10, 9), "WEEKDAY", 1, 1));

        OaReportFactRow doc = new OaReportFactRow(
                "oa-amended", "leave-sick-2", "LEAVE", LeaveType.SICK,
                "emp-1", "E001", "assignment-1",
                docStart, docEnd, "APPROVED", "v1");

        Map<String, List<TimeInterval>> amended = Map.of(
                "leave-sick-2", List.of(new TimeInterval(amendStart, amendEnd)));

        // Valid employment covering the entire period
        List<EmployeeIdentityIntervalRow> employeeIdentities = List.of(
                identity(LocalDate.of(2026, 1, 1), null));

        Map<LocalDate, Long> byDate = invokeRecognizedMinutesForRow(
                doc, docStart, docEnd, amended,
                "emp-1", "研发部", shifts, calendar,
                periodStart, periodEnd, employeeIdentities);

        // Amended interval is entirely outside Sep period → empty map, 0 minutes
        assertThat(byDate).isEmpty();
        assertThat(byDate.values().stream().mapToLong(Long::longValue).sum()).isZero();
    }

    @Test
    void amendedIntervalStraddlingSep30Oct2CountsOnlySep30ViaReflection() throws Exception {
        // Amendment interval: Sep 30 00:00 CST → Oct 2 00:00 CST.
        // Sep period [Aug 31 16:00Z, Sep 30 16:00Z) = [Sep 1, Oct 1) CST.
        // Only Sep 30 falls within the period; Oct 1 is excluded.
        Instant docStart    = Instant.parse("2026-09-29T16:00:00Z"); // Sep 30 00:00 CST
        Instant docEnd      = Instant.parse("2026-10-01T16:00:00Z"); // Oct 2 00:00 CST
        Instant amendStart  = Instant.parse("2026-09-29T16:00:00Z"); // Sep 30 00:00 CST
        Instant amendEnd    = Instant.parse("2026-10-01T16:00:00Z"); // Oct 2 00:00 CST
        Instant periodStart = Instant.parse("2026-08-31T16:00:00Z"); // Sep 1 00:00 CST
        Instant periodEnd   = Instant.parse("2026-09-30T16:00:00Z"); // Oct 1 00:00 CST

        List<ShiftSegmentRow> shifts = List.of(
                amShift(LocalDate.of(2026, 9, 30)), pmShift(LocalDate.of(2026, 9, 30)),
                amShift(LocalDate.of(2026, 10, 1)), pmShift(LocalDate.of(2026, 10, 1)));
        List<CalendarDayRow> calendar = List.of(
                new CalendarDayRow("emp-1", LocalDate.of(2026, 9, 30), "WEEKDAY", 1, 1),
                new CalendarDayRow("emp-1", LocalDate.of(2026, 10, 1), "WEEKDAY", 1, 1));

        OaReportFactRow doc = new OaReportFactRow(
                "oa-straddle", "leave-straddle", "LEAVE", LeaveType.SICK,
                "emp-1", "E001", "assignment-1",
                docStart, docEnd, "APPROVED", "v1");

        Map<String, List<TimeInterval>> amended = Map.of(
                "leave-straddle", List.of(new TimeInterval(amendStart, amendEnd)));

        // Valid employment covering the entire period
        List<EmployeeIdentityIntervalRow> employeeIdentities = List.of(
                identity(LocalDate.of(2026, 1, 1), null));

        Map<LocalDate, Long> byDate = invokeRecognizedMinutesForRow(
                doc, docStart, docEnd, amended,
                "emp-1", "研发部", shifts, calendar,
                periodStart, periodEnd, employeeIdentities);

        // Sep 30 inside [Sep 1, Oct 1); Oct 1 excluded
        assertThat(byDate).containsKey(LocalDate.of(2026, 9, 30));
        assertThat(byDate).doesNotContainKey(LocalDate.of(2026, 10, 1));
        assertThat(byDate.get(LocalDate.of(2026, 9, 30))).isEqualTo(480L);
        assertThat(byDate.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(480L);
    }

    // ── shared stub ───────────────────────────────────────────────────────────

    private void stubSepMonth(Instant dataAsOf, Instant docStart, Instant docEnd) {
        LocalDate from = LocalDate.of(2026, 9, 17);
        LocalDate toExcl = LocalDate.of(2026, 10, 1);
        Mockito.when(mapper.findAttendanceSourceVersions(
                        Mockito.eq(COMPANY), Mockito.any()))
                .thenReturn(List.of(
                        new SourceInputVersionRow(
                                "source-deli-1", "DELI_CLOUD", 1L, 0, 1L,
                                "0".repeat(64),
                                Instant.parse("2026-08-01T00:00:00Z")),
                        new SourceInputVersionRow(
                                "source-oa-1", "OA_ATTENDANCE", 1L, 0, 1L,
                                "1".repeat(64),
                                Instant.parse("2026-08-01T00:00:00Z"))));
        Mockito.when(mapper.findEmployeeIdentityIntervals(
                        Mockito.eq(COMPANY), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(identity(from, toExcl)));
        Mockito.when(mapper.findActivatedPunchEvents(
                        Mockito.eq(COMPANY), Mockito.any(), Mockito.any(), Mockito.eq(dataAsOf)))
                .thenReturn(List.of());
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(COMPANY), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(sepCalendar());
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(COMPANY), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(sepShifts());
        Mockito.when(mapper.findAttendancePolicies(
                        Mockito.eq(COMPANY), Mockito.any(), Mockito.any(), Mockito.eq(dataAsOf)))
                .thenReturn(sepCalendar().stream()
                        .map(c -> policy(c.businessDate()))
                        .toList());
        Mockito.when(mapper.findApprovedPunchCorrections(
                        Mockito.eq(COMPANY), Mockito.any(), Mockito.any(), Mockito.eq(dataAsOf)))
                .thenReturn(List.of());
        Mockito.when(mapper.findPunchExemptionRoleIntervals(
                        Mockito.eq(COMPANY), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());
        Mockito.when(mapper.findReportableOaDocuments(
                        Mockito.eq(COMPANY), Mockito.any(), Mockito.any(), Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new OaReportFactRow(
                        "oa-sick-2", "leave-sick-2", "LEAVE", LeaveType.SICK,
                        "emp-1", "E001", "assignment-1",
                        docStart, docEnd, "APPROVED", "oa-v2")));
    }
}