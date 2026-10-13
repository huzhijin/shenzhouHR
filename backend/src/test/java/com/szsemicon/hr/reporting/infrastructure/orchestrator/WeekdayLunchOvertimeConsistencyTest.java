package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Regression uses the actual September 29 shift and Xu Yujie's two Deli points. */
class WeekdayLunchOvertimeConsistencyTest {
    private static final String COMPANY = "company";
    private static final String EMPLOYEE = "e93783b6-1c18-51f5-a9d3-e0f6a549f501";
    private static final String NUMBER = "SZST0055";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);
    private static final Instant CUTOFF = Instant.parse("2026-10-06T03:00:00Z");

    @ParameterizedTest(name = "{0} {1} {2}-{3}, punches={4}: {5} minutes")
    @CsvSource({
            "PAID,APPROVED,08:30,21:00,real,150",
            "COMPENSATORY,APPROVED,08:30,21:00,real,150",
            "VOLUNTARY,APPROVED,08:30,21:00,real,150",
            "PAID,PENDING,08:30,21:00,real,150",
            "PAID,APPROVED,08:30,12:00,real,0",
            "PAID,APPROVED,13:00,13:30,real,0",
            "PAID,APPROVED,13:00,21:00,real,150",
            "PAID,APPROVED,18:30,21:00,real,150",
            "PAID,APPROVED,08:30,21:00,early-exit,90",
            "PAID,APPROVED,08:30,21:00,single,0",
            "PAID,APPROVED,08:30,21:00,none,0",
            "PAID,APPROVED,08:30,21:00,exempt,150",
            "PAID,APPROVED,18:30,21:00,exempt-evening,150",
            "PAID,APPROVED,08:30,01:00,overnight,390"
    })
    void dailyAndOaDetailsUseTheSameWorkAndMealDeductions(
            OvertimeType type, String status, String startTime, String endTime, String punches, long expected) {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        when(mapper.findAttendanceSourceVersions(COMPANY, CUTOFF)).thenReturn(List.of(
                new SourceInputVersionRow("oa", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), CUTOFF),
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), CUTOFF)));
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(EMPLOYEE, "v1", NUMBER, "徐玉杰", "assignment",
                        "org", "org-v1", "制造中心/MATCH2组", LocalDate.of(2020, 1, 1), null,
                        LocalDate.of(2020, 1, 1), null, LocalDate.of(2020, 1, 1), null)));
        List<CalendarDayRow> calendar = new ArrayList<>();
        List<ShiftSegmentRow> shifts = new ArrayList<>();
        for (LocalDate day = LocalDate.of(2026, 9, 1); day.isBefore(LocalDate.of(2026, 10, 2)); day = day.plusDays(1)) {
            boolean rest = day.getDayOfWeek().getValue() > 5;
            calendar.add(new CalendarDayRow(EMPLOYEE, day,
                    rest ? (day.getDayOfWeek().getValue() == 6 ? "SATURDAY" : "SUNDAY") : "WEEKDAY", 1, 1));
            if (!rest) shifts.addAll(SiteShiftTemplates.workSegments(EMPLOYEE, day, SiteShiftTemplates.Kind.YANGZHOU));
        }
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(calendar);
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(shifts);
        List<AttendancePolicyRow> policies = new ArrayList<>();
        for (LocalDate day = LocalDate.of(2026, 9, 1); day.isBefore(LocalDate.of(2026, 10, 2)); day = day.plusDays(1)) {
            policies.add(new AttendancePolicyRow(EMPLOYEE, day, 1, 1, true, 15, 1, true, 15, 1, false, 1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS", 1, true, 48));
        }
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(policies);
        Instant start = at(DAY, startTime);
        Instant end = at(punches.equals("overnight") ? DAY.plusDays(1) : DAY, endTime);
        var overtime = new OaDocumentRow("OVERTIME:-5993879732436250814", "OVERTIME", type, null,
                NUMBER, start, end, "Asia/Shanghai", CUTOFF, true, null, null, "oa", EMPLOYEE);
        List<OaDocumentRow> documents = new ArrayList<>(List.of(overtime));
        boolean exempt = punches.startsWith("exempt");
        if (exempt) {
            documents.add(new OaDocumentRow("EXEMPT_PUNCH:valid", "EXEMPT_PUNCH", null, null, NUMBER,
                    at(DAY, "00:00"), at(DAY.plusDays(1), "00:00"), "Asia/Shanghai", CUTOFF,
                    true, null, null, "oa", EMPLOYEE));
        }
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(documents);
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new OaReportFactRow("ot-doc", overtime.sourceBusinessKey(), "OVERTIME", null,
                        EMPLOYEE, NUMBER, "assignment", start, end, status, "v1", type)));
        List<PunchEventRow> points = new ArrayList<>();
        if (!exempt && !List.of("none", "single").contains(punches)) {
            points.add(new PunchEventRow(EMPLOYEE, at(DAY, "08:26:58")));
        }
        if (!exempt && !punches.equals("none")) {
            points.add(new PunchEventRow(EMPLOYEE, punches.equals("overnight")
                    ? at(DAY.plusDays(1), "01:02:00")
                    : at(DAY, punches.equals("early-exit") ? "20:02:00" : "21:01:21")));
        }
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(points);
        var result = new FullCalculationEngineOrchestrator(mapper)
                .assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "audit", CUTOFF);
        var daily = result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(DAY)).findFirst().orElseThrow();
        var fact = result.oaDocumentFacts().stream().filter(f -> f.documentType().equals("OVERTIME"))
                .findFirst().orElseThrow();
        assertThat(daily.totalOvertimeMinutes()).isEqualTo(expected);
        assertThat(daily.paidOvertimeMinutes()).isEqualTo(type == OvertimeType.PAID ? expected : 0L);
        assertThat(daily.compensatoryOvertimeMinutes()).isEqualTo(type == OvertimeType.COMPENSATORY ? expected : 0L);
        assertThat(daily.voluntaryOvertimeMinutes()).isEqualTo(type == OvertimeType.VOLUNTARY ? expected : 0L);
        assertThat(fact.recognizedMinutes()).isEqualTo(expected);
        assertThat(fact.recognizedMinutesByDate()).isEqualTo(expected == 0 ? Map.of() : Map.of(DAY, expected));
        assertThat(fact.sourceStatus()).isEqualTo(status);
        assertThat(fact.intervalStart()).isEqualTo(start);
        assertThat(fact.intervalEndExclusive()).isEqualTo(end);
        // This meal regression does not change normal-work evidence priority.
        // A no-punch, full-day OT overlaps EXEMPT in the existing core resolver;
        // the independent evening-only exemption case verifies normal 480.
        if (!List.of("none", "single", "exempt").contains(punches)) {
            assertThat(daily.confirmedScheduledWorkMinutes()).isEqualTo(480);
            assertThat(daily.missingPunchCount()).isZero();
        }
    }

    private static Instant at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(OvertimeMealDeductions.BUSINESS_ZONE).toInstant();
    }
}
