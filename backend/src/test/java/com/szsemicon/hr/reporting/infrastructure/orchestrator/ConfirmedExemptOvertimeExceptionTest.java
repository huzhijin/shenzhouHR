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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Actual September source intervals from the independently checked eleven-person roster. */
class ConfirmedExemptOvertimeExceptionTest {
    private static final String COMPANY = "company";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant CUTOFF = Instant.parse("2026-10-06T10:00:00Z");
    private static final String BEYOND = "OVERTIME_FORM_BEYOND_LAST_PUNCH";

    @ParameterizedTest(name = "{0}")
    @MethodSource("confirmedPeople")
    void confirmedPeopleKeepActualHoursAndTreatmentsWithoutFalsePunchWarnings(ActualCase person) {
        var result = assemble(person, true);
        long paid = person.forms().stream().filter(form -> form.type() == OvertimeType.PAID)
                .mapToLong(Form::minutes).sum();
        long comp = person.forms().stream().filter(form -> form.type() == OvertimeType.COMPENSATORY)
                .mapToLong(Form::minutes).sum();
        var day = result.calculatedFacts().stream()
                .filter(value -> person.day().equals(value.facts().dailyFact().businessDate()))
                .findFirst().orElseThrow();
        assertThat(day.facts().dailyFact().paidOvertimeMinutes()).isEqualTo(paid);
        assertThat(day.facts().dailyFact().compensatoryOvertimeMinutes()).isEqualTo(comp);
        assertThat(day.facts().dailyFact().recognizedOvertimeMinutes()).isEqualTo(paid + comp);
        assertThat(day.facts().dailyFact().firstPunchAt()).isNull();
        assertThat(day.facts().dailyFact().lastPunchAt()).isNull();
        assertThat(day.facts().dailyFact().missingPunchCount()).isZero();
        assertThat(day.facts().exceptionFacts()).noneSatisfy(exception ->
                assertThat(exception.exceptionType()).isEqualTo(BEYOND));
        assertThat(result.oaDocumentFacts()).hasSize(person.forms().size());
        for (Form form : person.forms()) {
            assertThat(result.oaDocumentFacts().stream()
                    .filter(fact -> fact.oaAttendanceDocumentId().equals("report-" + form.key())).toList())
                    .singleElement().satisfies(fact -> {
                        assertThat(fact.recognizedMinutes()).isEqualTo(form.minutes());
                        assertThat(fact.sourceStatus()).isEqualTo("APPROVED");
                    });
        }
    }

    @Test
    void separateNonexemptFormsStillHaveSeparateZeroMinuteMissingPunchWarnings() {
        var person = confirmedPeople().filter(value -> value.number().equals("SZST0422"))
                .findFirst().orElseThrow();
        var result = assemble(person, false);
        var exceptions = result.calculatedFacts().stream()
                .filter(value -> person.day().equals(value.facts().dailyFact().businessDate()))
                .flatMap(value -> value.facts().exceptionFacts().stream())
                .filter(value -> BEYOND.equals(value.exceptionType())).toList();
        assertThat(exceptions).hasSize(2);
        assertThat(exceptions).extracting(value -> value.caseId()).doesNotHaveDuplicates();
        assertThat(exceptions).allSatisfy(value -> assertThat(value.minutes()).isZero());
    }

    private static Stream<ActualCase> confirmedPeople() {
        return Stream.of(
                person("SZST0145", "夏平", "2026-09-01", form("-2713228771289251602", "2026-09-01T18:30", "2026-09-01T21:00", "PAID", 150)),
                person("SZST0231", "陈保林", "2026-09-03", form("-8369469330151946373", "2026-09-03T18:30", "2026-09-03T21:00", "PAID", 150)),
                person("SZST0266", "田梦贤", "2026-09-01", form("4916203202864038848", "2026-09-01T21:00", "2026-09-01T23:00", "PAID", 120)),
                person("SZST0349", "李立", "2026-09-06", form("-5420792878183396988", "2026-09-06T08:30", "2026-09-06T20:30", "PAID", 630)),
                person("SZST0422", "李发成", "2026-09-07", form("5652525610335817234", "2026-09-07T01:00", "2026-09-07T03:30", "PAID", 150),
                        form("7851881275901952136", "2026-09-07T23:00", "2026-09-08T01:30", "PAID", 150)),
                person("SZST0464", "朱加俊", "2026-09-19", form("-7854000474737848585", "2026-09-19T09:00", "2026-09-19T18:00", "COMPENSATORY", 480),
                        form("4209069022121903098", "2026-09-19T18:30", "2026-09-19T20:00", "PAID", 90)),
                person("SZST0475", "胡军", "2026-09-25", form("-2052463867695249663", "2026-09-25T04:00", "2026-09-25T07:00", "PAID", 180),
                        form("7680144834847656434", "2026-09-25T14:30", "2026-09-26T00:00", "PAID", 540)),
                person("SZST0494", "蒲发鑫", "2026-09-03", form("4230619088831463460", "2026-09-03T18:30", "2026-09-03T21:00", "PAID", 150)),
                person("SZST0517", "向佳文", "2026-09-06", form("-3806679776428275505", "2026-09-06T08:30", "2026-09-06T18:00", "PAID", 510)),
                person("SZST0696", "赵浩旭", "2026-09-24", form("-938153831172258440", "2026-09-24T18:30", "2026-09-24T19:30", "PAID", 60)),
                person("SZST0748", "刘梓轩", "2026-09-19", form("1861555707154549302", "2026-09-19T08:30", "2026-09-19T20:00", "PAID", 600)));
    }

    private static PublishCommand assemble(ActualCase person, boolean exempt) {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        String employee = "employee-" + person.number();
        LocalDate start = LocalDate.of(2026, 9, person.number().equals("SZST0748") ? 14 : 1);
        LocalDate hired = person.number().equals("SZST0748") ? start : LocalDate.of(2020, 1, 1);
        when(mapper.findAttendanceSourceVersions(COMPANY, CUTOFF)).thenReturn(List.of(
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), CUTOFF),
                new SourceInputVersionRow("oa", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), CUTOFF)));
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(employee, "version", person.number(), person.name(),
                        "assignment", "org", "org-version", "现场技术服务组", hired, null, hired, null, hired, null)));
        String type = person.day().getDayOfMonth() == 25 ? "PUBLIC_HOLIDAY"
                : person.day().getDayOfWeek().getValue() == 6 ? "SATURDAY"
                : person.day().getDayOfWeek().getValue() == 7 ? "SUNDAY" : "WEEKDAY";
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(List.of(new CalendarDayRow(employee, person.day(), type, 1, 1)));
        if (type.equals("WEEKDAY")) {
            when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                    .thenReturn(List.of(segment(employee, person.day(), "am", 8, 30, 12, 0),
                            segment(employee, person.day(), "pm", 13, 30, 18, 0)));
        }
        if (exempt) {
            when(mapper.findStandingPunchExemptionIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of(new PunchExemptionRoleIntervalRow(employee, start.atStartOfDay(ZONE).toInstant(),
                            LocalDate.of(2026, 10, 1).atStartOfDay(ZONE).toInstant())));
            if (type.equals("WEEKDAY")) {
                when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                        .thenReturn(List.of(new AttendancePolicyRow(
                                employee, person.day(),
                                1, 1, true, 15,
                                1, true, 15, 1, false,
                                1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS",
                                1, true, 48)));
            }
        } else {
            when(mapper.findStandingPunchExemptionIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of());
            if (type.equals("WEEKDAY")) {
                when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                        .thenReturn(List.of(new AttendancePolicyRow(
                                employee, person.day(),
                                1, 1, true, 15,
                                1, true, 15, 1, false,
                                1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS",
                                1, true, 48)));
            }
        }
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(person.forms().stream().map(form -> new OaDocumentRow(form.key(), "OVERTIME", form.type(), null,
                        person.number(), form.start(), form.end(), "Asia/Shanghai", form.start().minusSeconds(3600), true,
                        null, null, "oa", employee)).toList());
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(person.forms().stream().map(form -> new OaReportFactRow("report-" + form.key(), form.key(),
                        "OVERTIME", null, employee, person.number(), "assignment", form.start(), form.end(), "APPROVED", "v1", form.type())).toList());
        return new FullCalculationEngineOrchestrator(mapper).assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "admin", CUTOFF);
    }

    private static ActualCase person(String number, String name, String day, Form... forms) {
        return new ActualCase(number, name, LocalDate.parse(day), List.of(forms));
    }
    private static Form form(String key, String start, String end, String type, long minutes) {
        return new Form("OVERTIME:" + key, LocalDateTime.parse(start).atZone(ZONE).toInstant(),
                LocalDateTime.parse(end).atZone(ZONE).toInstant(), OvertimeType.valueOf(type), minutes);
    }
    private static ShiftSegmentRow segment(String employee, LocalDate day, String key, int h, int m, int eh, int em) {
        Instant start = day.atTime(h, m).atZone(ZONE).toInstant();
        Instant end = day.atTime(eh, em).atZone(ZONE).toInstant();
        return new ShiftSegmentRow(employee, day, key, start, end,
                start.minusSeconds(3600), start.plusSeconds(3600), end.minusSeconds(3600), end.plusSeconds(3600));
    }
    private record Form(String key, Instant start, Instant end, OvertimeType type, long minutes) { }
    private record ActualCase(String number, String name, LocalDate day, List<Form> forms) {
        @Override public String toString() { return number + " " + name + " " + day; }
    }
}
