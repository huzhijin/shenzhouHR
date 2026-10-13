package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Both publication paths are exercised with real intervals and no fabricated punches. */
class OaPunchExemptOvertimeTest {
    private static final String COMPANY = "company";
    private static final String EMPLOYEE = "8090432a-6fad-4e06-b9fd-8d39027de38a";
    private static final String NUMBER = "SZST0716";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant CUTOFF = Instant.parse("2026-10-05T06:00:00Z");
    private AttendanceReportCalculationMapper mapper;
    private FullCalculationEngineOrchestrator engine;

    @BeforeEach
    void rosterAndSeptemberCalendar() {
        mapper = mock(AttendanceReportCalculationMapper.class);
        engine = new FullCalculationEngineOrchestrator(mapper);
        when(mapper.findAttendanceSourceVersions(COMPANY, CUTOFF)).thenReturn(List.of(
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), CUTOFF),
                new SourceInputVersionRow("oa", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), CUTOFF)));
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(identity(EMPLOYEE, NUMBER), identity("other-employee", "OTHER")));
        List<CalendarDayRow> calendar = new ArrayList<>();
        List<ShiftSegmentRow> shifts = new ArrayList<>();
        for (LocalDate day = date(1); day.isBefore(LocalDate.of(2026, 10, 2)); day = day.plusDays(1)) {
            boolean holiday = day.getMonthValue() == 9 && day.getDayOfMonth() >= 25 && day.getDayOfMonth() <= 27;
            String type = day.equals(date(20)) ? "ADJUSTED_WORKDAY" : holiday ? "PUBLIC_HOLIDAY" : day.getDayOfWeek().getValue() == 6 ? "SATURDAY"
                    : day.getDayOfWeek().getValue() == 7 ? "SUNDAY" : "WEEKDAY";
            calendar.add(new CalendarDayRow(EMPLOYEE, day, type, 1, 1));
            if ("WEEKDAY".equals(type) || "ADJUSTED_WORKDAY".equals(type)) {
                shifts.add(segment(day, "am", 8, 30, 12, 0));
                shifts.add(segment(day, "pm", 13, 30, 18, 0));
            }
        }
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(calendar);
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(shifts);
        List<AttendancePolicyRow> policies = new ArrayList<>();
        for (LocalDate day = date(1); day.isBefore(LocalDate.of(2026, 10, 2)); day = day.plusDays(1)) {
            policies.add(new AttendancePolicyRow(EMPLOYEE, day, 1, 1, true, 15, 1, true, 15, 1, false, 1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS", 1, true, 48));
            policies.add(new AttendancePolicyRow("other-employee", day, 1, 1, true, 15, 1, true, 15, 1, false, 1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS", 1, true, 48));
        }
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(policies);
    }

    @ParameterizedTest
    @EnumSource(OvertimeType.class)
    void heWeihaoEightRealIntervalsCreditBothReportsAndKeepTreatmentAndPendingStatus(OvertimeType treatment) {
        List<OaDocumentRow> overtime = List.of(
                overtime("17", at(17, 21, 0), at(17, 23, 30), treatment),
                overtime("19", at(19, 8, 30), at(19, 19, 0), treatment),
                overtime("20", at(20, 18, 30), at(20, 21, 30), treatment),
                overtime("25", at(25, 8, 30), at(25, 18, 0), treatment),
                overtime("26", at(26, 8, 30), at(26, 19, 0), treatment),
                overtime("27", at(27, 8, 30), at(27, 18, 0), treatment),
                overtime("29", at(29, 18, 30), at(30, 0, 0), treatment),
                overtime("30", at(30, 18, 0), at(30, 19, 0), treatment));
        var exemption = exemption("approved-exempt", at(10, 0, 0),
                LocalDate.of(2027, 1, 1).atStartOfDay(ZONE).toInstant(), true, EMPLOYEE, "EXEMPT_PUNCH");
        documents(overtime, List.of(exemption), "PENDING");
        var result = assemble();
        Map<LocalDate, Long> expected = Map.of(date(17), 150L, date(19), 540L, date(20), 180L,
                date(25), 510L, date(26), 540L, date(27), 510L, date(29), 330L, date(30), 30L);
        var daily = result.calculatedFacts().stream().map(value -> value.facts().dailyFact())
                .filter(value -> EMPLOYEE.equals(value.employeeId()))
                .collect(Collectors.toMap(value -> value.businessDate(), Function.identity()));
        expected.forEach((day, minutes) -> {
            var fact = daily.get(day);
            assertThat(fact.recognizedOvertimeMinutes()).as("daily %s", day).isEqualTo(minutes);
            assertThat(fact.paidOvertimeMinutes()).isEqualTo(treatment == OvertimeType.PAID ? minutes : 0L);
            assertThat(fact.compensatoryOvertimeMinutes()).isEqualTo(treatment == OvertimeType.COMPENSATORY ? minutes : 0L);
            assertThat(fact.voluntaryOvertimeMinutes()).isEqualTo(treatment == OvertimeType.VOLUNTARY ? minutes : 0L);
            assertThat(fact.firstPunchAt()).isNull();
            assertThat(fact.lastPunchAt()).isNull();
        });
        assertThat(daily.get(date(17)).confirmedScheduledWorkMinutes()).isEqualTo(480);
        assertThat(daily.get(date(17)).missingPunchCount()).isZero();
        assertThat(result.calculatedFacts()).allSatisfy(value ->
                assertThat(value.facts().exceptionFacts()).noneSatisfy(exception ->
                        assertThat(exception.exceptionType()).isEqualTo("OVERTIME_FORM_BEYOND_LAST_PUNCH")));
        assertThat(result.oaDocumentFacts()).hasSize(8).allSatisfy(fact -> {
            LocalDate day = fact.intervalStart().atZone(ZONE).toLocalDate();
            assertThat(fact.recognizedMinutes()).as("OA detail %s", day).isEqualTo(expected.get(day));
            assertThat(fact.recognizedMinutesByDate()).containsExactlyEntriesOf(
                    Map.of(day, expected.get(day)));
            assertThat(fact.sourceStatus()).isEqualTo(day.getDayOfMonth() == 30 ? "PENDING" : "APPROVED");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "inactive", "other-employee", "partial", "gap", "expired",
            "starts-at-end", "ends-at-start", "OUTING", "TRIP"})
    void inadequateExemptionNeverWaivesEitherPunchGate(String scenario) {
        Instant start = at(17, 18, 30);
        Instant end = at(17, 21, 0);
        List<OaDocumentRow> exemptions = switch (scenario) {
            case "none" -> List.of();
            case "inactive" -> List.of(exemption("inactive", start, end, false, EMPLOYEE, "EXEMPT_PUNCH"));
            case "other-employee" -> List.of(exemption("other", start, end, true, "other-employee", "EXEMPT_PUNCH"));
            case "partial" -> List.of(exemption("partial", start, at(17, 20, 0), true, EMPLOYEE, "EXEMPT_PUNCH"));
            case "gap" -> List.of(exemption("early", start, at(17, 19, 0), true, EMPLOYEE, "EXEMPT_PUNCH"),
                    exemption("late", at(17, 19, 30), end, true, EMPLOYEE, "EXEMPT_PUNCH"));
            case "expired" -> List.of(exemption("expired", at(16, 0, 0), at(17, 0, 0), true, EMPLOYEE, "EXEMPT_PUNCH"));
            case "starts-at-end" -> List.of(exemption("next", end, at(18, 0, 0), true, EMPLOYEE, "EXEMPT_PUNCH"));
            case "ends-at-start" -> List.of(exemption("prior", at(17, 0, 0), start, true, EMPLOYEE, "EXEMPT_PUNCH"));
            default -> List.of(exemption("non-exempt", start, end, true, EMPLOYEE, scenario));
        };
        documents(List.of(overtime("test", start, end, OvertimeType.PAID)), exemptions, "APPROVED");
        var result = assemble();
        assertBoth(result, date(17), 0);
        assertBeyondLastPunch(result, date(17), true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void crossNightRequiresCompleteCoverageIncludingNextDay(boolean complete) {
        Instant start = at(29, 22, 0);
        Instant midnight = at(30, 0, 0);
        Instant end = at(30, 2, 0);
        List<OaDocumentRow> exemptions = new ArrayList<>(List.of(
                exemption("before-midnight", start, midnight, true, EMPLOYEE, "EXEMPT_PUNCH")));
        if (complete) {
            exemptions.add(exemption("after-midnight", midnight, end, true, EMPLOYEE, "EXEMPT_PUNCH"));
            exemptions.add(exemption("overlap", at(29, 23, 0), at(30, 1, 0), true, EMPLOYEE, "EXEMPT_PUNCH"));
        }
        documents(List.of(overtime("night", start, end, OvertimeType.PAID)), exemptions, "APPROVED");
        var result = assemble();
        assertBoth(result, date(29), complete ? 240 : 0);
        assertBeyondLastPunch(result, date(29), !complete);
    }

    @Test
    void partialExemptionRetainsExistingRealPunchCap() {
        documents(List.of(overtime("capped", at(17, 18, 30), at(17, 21, 0), OvertimeType.PAID)),
                List.of(exemption("partial", at(17, 18, 30), at(17, 19, 0), true, EMPLOYEE, "EXEMPT_PUNCH")), "APPROVED");
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new PunchEventRow(EMPLOYEE, at(17, 8, 30)), new PunchEventRow(EMPLOYEE, at(17, 20, 0))));
        var result = assemble();
        assertBoth(result, date(17), 90);
        assertBeyondLastPunch(result, date(17), true);
    }

    @Test
    void bothPathsUseTheExistingNormalizedHalfHourGridForCoverage() {
        documents(List.of(overtime("grid", at(17, 18, 45), at(17, 21, 15), OvertimeType.PAID)),
                List.of(exemption("early", at(17, 18, 30), at(17, 19, 10), true, EMPLOYEE, "EXEMPT_PUNCH"),
                        exemption("late", at(17, 19, 20), at(17, 21, 0), true, EMPLOYEE, "EXEMPT_PUNCH")), "APPROVED");
        var result = assemble();
        assertBoth(result, date(17), 150);
        assertBeyondLastPunch(result, date(17), false);
    }

    @Test
    void exemptionAloneCannotCreateOvertime() {
        documents(List.of(), List.of(exemption("only", at(10, 0, 0), at(30, 23, 59), true, EMPLOYEE, "EXEMPT_PUNCH")), "APPROVED");
        var result = assemble();
        assertThat(result.calculatedFacts()).allSatisfy(value ->
                assertThat(value.facts().dailyFact().recognizedOvertimeMinutes()).isZero());
        assertThat(result.oaDocumentFacts()).isEmpty();
    }

    private void assertBoth(PublishCommand result, LocalDate day, long minutes) {
        assertThat(result.calculatedFacts().stream().map(value -> value.facts().dailyFact())
                .filter(value -> EMPLOYEE.equals(value.employeeId()) && day.equals(value.businessDate())).toList())
                .singleElement().satisfies(value -> assertThat(value.recognizedOvertimeMinutes()).isEqualTo(minutes));
        assertThat(result.oaDocumentFacts()).singleElement()
                .satisfies(value -> assertThat(value.recognizedMinutes()).isEqualTo(minutes));
    }
    private static void assertBeyondLastPunch(PublishCommand result, LocalDate day, boolean expected) {
        var exceptions = result.calculatedFacts().stream()
                .filter(value -> EMPLOYEE.equals(value.facts().dailyFact().employeeId())
                        && day.equals(value.facts().dailyFact().businessDate()))
                .flatMap(value -> value.facts().exceptionFacts().stream())
                .filter(value -> "OVERTIME_FORM_BEYOND_LAST_PUNCH".equals(value.exceptionType()))
                .toList();
        assertThat(exceptions).hasSize(expected ? 1 : 0);
    }
    private PublishCommand assemble() {
        return engine.assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "admin", CUTOFF);
    }
    private void documents(List<OaDocumentRow> overtime, List<OaDocumentRow> exemptions, String lastStatus) {
        List<OaDocumentRow> effective = new ArrayList<>(overtime);
        effective.addAll(exemptions);
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(effective);
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(overtime.stream().map(row -> new OaReportFactRow("report-" + row.sourceBusinessKey(),
                        row.sourceBusinessKey(), "OVERTIME", null, EMPLOYEE, NUMBER, "assignment",
                        row.startInstant(), row.endInstant(), row.sourceBusinessKey().endsWith("30") ? lastStatus : "APPROVED",
                        "v1", row.overtimeType())).toList());
    }
    private static OaDocumentRow overtime(String key, Instant start, Instant end, OvertimeType treatment) {
        return new OaDocumentRow("OVERTIME:" + key, "OVERTIME", treatment, null, NUMBER, start, end,
                "Asia/Shanghai", start.minusSeconds(3600), true, null, null, "oa", EMPLOYEE);
    }
    private static OaDocumentRow exemption(String key, Instant start, Instant end, boolean effective, String employee, String type) {
        return new OaDocumentRow(key, type, null, null, employee.equals(EMPLOYEE) ? NUMBER : "OTHER",
                start, end, "Asia/Shanghai", start.minusSeconds(3600), effective, null, null, "oa", employee);
    }
    private static EmployeeIdentityIntervalRow identity(String employee, String number) {
        return new EmployeeIdentityIntervalRow(employee, "version-" + number, number, "何伟豪", "assignment",
                "organization", "organization-version", "技术支持中心/现场服务部/现场技术服务组",
                LocalDate.of(2026, 8, 10), null, LocalDate.of(2026, 8, 10), null, LocalDate.of(2026, 8, 10), null);
    }
    private static ShiftSegmentRow segment(LocalDate day, String key, int startHour, int startMinute, int endHour, int endMinute) {
        Instant start = day.atTime(startHour, startMinute).atZone(ZONE).toInstant();
        Instant end = day.atTime(endHour, endMinute).atZone(ZONE).toInstant();
        return new ShiftSegmentRow(EMPLOYEE, day, key + day, start, end,
                start.minusSeconds(3600), start.plusSeconds(3600), end.minusSeconds(3600), end.plusSeconds(3600));
    }
    private static LocalDate date(int day) { return LocalDate.of(2026, 9, day); }
    private static Instant at(int day, int hour, int minute) { return date(day).atTime(hour, minute).atZone(ZONE).toInstant(); }
}
