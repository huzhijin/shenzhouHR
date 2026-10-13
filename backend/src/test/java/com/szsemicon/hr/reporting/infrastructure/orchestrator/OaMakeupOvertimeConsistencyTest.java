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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Actual September 22 intervals; no source status is changed by report projection. */
class OaMakeupOvertimeConsistencyTest {
    private static final String COMPANY = "company";
    private static final String EMPLOYEE = "adc64602-f9d5-5348-ac06-a1442f64819b";
    private static final String NUMBER = "SZST0630";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant CUTOFF = Instant.parse("2026-10-06T00:00:00Z");

    @ParameterizedTest(name = "{0}: effective={1}, punch scenario={2}, expected={3}")
    @CsvSource({
            "APPROVED,true,oa-both,300",
            "PENDING,true,oa-both,300",
            "PENDING,false,oa-both,0",
            "REVOKED,false,oa-both,0",
            "DRAFT,false,oa-both,0",
            "APPROVED,true,real-exit-oa-entry,300",
            "APPROVED,true,real-entry-oa-exit,300",
            "APPROVED,true,real-both,300",
            "APPROVED,true,none,0",
            "APPROVED,true,oa-entry-only,0",
            "APPROVED,true,oa-exit-only,0",
            "APPROVED,true,foreign-employee,0"
    })
    void effectiveOaCorrectionPointsAreSharedByDailyAndDocumentOvertime(
            String correctionStatus, boolean effective, String scenario, long expected) {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        when(mapper.findAttendanceSourceVersions(COMPANY, CUTOFF)).thenReturn(List.of(
                new SourceInputVersionRow("oa", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), CUTOFF),
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), CUTOFF)));
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(EMPLOYEE, "v1", NUMBER, "龙隆", "assignment",
                        "org", "org-v1", "技术支持中心/现场服务部/现场技术服务组",
                        LocalDate.of(2026, 5, 18), null, LocalDate.of(2026, 5, 18), null,
                        LocalDate.of(2026, 5, 18), null)));
        List<CalendarDayRow> calendar = new ArrayList<>();
        List<ShiftSegmentRow> shifts = new ArrayList<>();
        for (LocalDate day = LocalDate.of(2026, 9, 1); day.isBefore(LocalDate.of(2026, 10, 2)); day = day.plusDays(1)) {
            boolean rest = day.getDayOfWeek().getValue() > 5;
            calendar.add(new CalendarDayRow(EMPLOYEE, day,
                    rest ? (day.getDayOfWeek().getValue() == 6 ? "SATURDAY" : "SUNDAY") : "WEEKDAY", 1, 1));
            if (!rest) {
                shifts.add(segment(day, "am", 8, 30, 12, 0));
                shifts.add(segment(day, "pm", 13, 30, 18, 0));
            }
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
        var overtime = new OaDocumentRow("OVERTIME:-4286441259637521285", "OVERTIME", OvertimeType.PAID,
                null, NUMBER, at(DAY, 18, 30), at(DAY, 23, 30), "Asia/Shanghai", CUTOFF, true, null, null, "oa", EMPLOYEE);
        var outing = new OaDocumentRow("OUTING:8242336396638505936", "OUTING", null,
                null, NUMBER, at(DAY, 8, 30), at(DAY, 23, 30), "Asia/Shanghai", CUTOFF, true, null, null, "oa", EMPLOYEE);
        List<OaDocumentRow> documents = new ArrayList<>(List.of(overtime, outing));
        List<OaReportFactRow> report = new ArrayList<>(List.of(new OaReportFactRow("ot-doc", overtime.sourceBusinessKey(),
                "OVERTIME", null, EMPLOYEE, NUMBER, "assignment", overtime.startInstant(), overtime.endInstant(),
                "APPROVED", "v1", OvertimeType.PAID)));
        boolean entry = List.of("oa-both", "oa-entry-only", "real-exit-oa-entry", "foreign-employee").contains(scenario);
        boolean exit = List.of("oa-both", "oa-exit-only", "real-entry-oa-exit", "foreign-employee").contains(scenario);
        if (entry) addCorrection(documents, report, "entry", at(DAY, 8, 30), effective, correctionStatus, scenario);
        if (exit) addCorrection(documents, report, "exit", at(DAY, 23, 30), effective, correctionStatus, scenario);
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(documents);
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(report);
        List<PunchEventRow> realPunches = new ArrayList<>();
        if (List.of("real-both", "real-entry-oa-exit").contains(scenario)) realPunches.add(new PunchEventRow(EMPLOYEE, at(DAY, 8, 30)));
        if (List.of("real-both", "real-exit-oa-entry").contains(scenario)) realPunches.add(new PunchEventRow(EMPLOYEE, at(DAY, 23, 30)));
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(realPunches);
        when(mapper.findPunchExemptionRoleIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findStandingPunchExemptionIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        var result = new FullCalculationEngineOrchestrator(mapper)
                .assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "audit", CUTOFF);
        var daily = result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(DAY)).findFirst().orElseThrow();
        var fact = result.oaDocumentFacts().stream().filter(f -> f.documentType().equals("OVERTIME"))
                .findFirst().orElseThrow();
        assertThat(daily.totalOvertimeMinutes()).isEqualTo(expected);
        assertThat(daily.paidOvertimeMinutes()).isEqualTo(expected);
        assertThat(fact.recognizedMinutes()).isEqualTo(expected);
        assertThat(fact.sourceStatus()).isEqualTo("APPROVED");
        assertThat(fact.intervalStart()).isEqualTo(at(DAY, 18, 30));
        assertThat(fact.intervalEndExclusive()).isEqualTo(at(DAY, 23, 30));
        result.oaDocumentFacts().stream().filter(f -> f.documentType().equals("PUNCH_CORRECTION"))
                .forEach(f -> assertThat(f.sourceStatus()).isEqualTo(correctionStatus));
    }

    private static void addCorrection(List<OaDocumentRow> documents, List<OaReportFactRow> report, String side,
            Instant point, boolean effective, String status, String scenario) {
        String employee = scenario.equals("foreign-employee") ? "foreign-employee" : EMPLOYEE;
        documents.add(new OaDocumentRow("PUNCH_CORRECTION:" + side, "PUNCH_CORRECTION", null, null,
                NUMBER, point, point, "Asia/Shanghai", CUTOFF, effective, null, null, "oa", employee));
        // Real production queries never return rejected/foreign rows for consumption;
        // retain those hostile effective-list fixtures to prove the identity/effective guards.
        if (effective && employee.equals(EMPLOYEE)) {
            report.add(new OaReportFactRow("pc-" + side, "PUNCH_CORRECTION:" + side, "PUNCH_CORRECTION", null,
                    employee, NUMBER, "assignment", point, point, status,
                    "PENDING".equals(status) ? "2026-09-24T18:59:24:state=0:Asia/Shanghai" : "v1", null));
        }
    }
    private static ShiftSegmentRow segment(LocalDate day, String name, int sh, int sm, int eh, int em) {
        var start = at(day, sh, sm); var end = at(day, eh, em);
        return new ShiftSegmentRow(EMPLOYEE, day, name + day, start, end,
                start.minusSeconds(3600), start.plusSeconds(3600), end.minusSeconds(3600), end.plusSeconds(3600));
    }
    private static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(ZONE).toInstant();
    }
}
