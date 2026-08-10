package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedCalculatedFacts;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.EmployeeIdentityIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.PunchEventRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CalculationEngineOrchestratorTest {

    private static final String COMPANY =
            "41000000-0000-0000-0000-000000000003";
    private static final String PRINCIPAL =
            "20000000-0000-0000-0000-000000000001";
    private static final String EMPLOYEE = "employee-1";
    private static final YearMonth PERIOD = YearMonth.of(2026, 2);
    private static final Instant DATA_AS_OF =
            Instant.parse("2026-03-01T00:00:00Z");

    private final AttendanceReportCalculationMapper mapper =
            mock(AttendanceReportCalculationMapper.class);
    private final CalculationEngineOrchestrator orchestrator =
            new CalculationEngineOrchestrator(mapper);

    @Test
    void emitsOneFactPerEmployeeDayCoveredByIdentity() {
        givenIdentities(identity(EMPLOYEE, "2026-02-10", "2026-02-13"));
        givenNoCalendar();
        givenNoPunches();

        PublishCommand command = assemble();

        assertThat(command.calculatedFacts())
                .extracting(value -> value.facts().dailyFact().businessDate())
                .containsExactly(
                        LocalDate.parse("2026-02-10"),
                        LocalDate.parse("2026-02-11"),
                        LocalDate.parse("2026-02-12"));
        assertThat(command.metadata().sourceSnapshotDigest())
                .matches("[0-9a-f]{64}");
        assertThat(command.metadata().sourceVersions()).isNotEmpty();
        assertThat(command.currentExceptionFacts()).isEmpty();
        assertThat(command.oaDocumentFacts()).isEmpty();
        assertThat(command.timeAccountFacts()).isEmpty();
    }

    @Test
    void daysWithoutPunchesCarryZeroWorkAndNoPunchInstants() {
        givenIdentities(identity(EMPLOYEE, "2026-02-10", "2026-02-11"));
        givenNoCalendar();
        givenNoPunches();

        DailyFact fact = onlyFact(assemble());

        assertThat(fact.actualWorkMinutes()).isZero();
        assertThat(fact.confirmedScheduledWorkMinutes()).isZero();
        assertThat(fact.missingPunchCount()).isZero();
        assertThat(fact.firstPunchAt()).isNull();
        assertThat(fact.lastPunchAt()).isNull();
        assertThat(fact.resultDigest()).matches("[0-9a-f]{64}");
    }

    @Test
    void pairedPunchesBecomeTheWorkedSpanInBusinessZone() {
        givenIdentities(identity(EMPLOYEE, "2026-02-10", "2026-02-11"));
        givenNoCalendar();
        // 08:30 and 17:30 Asia/Shanghai on 2026-02-10 => 00:30Z and 09:30Z.
        givenPunches(
                punch("2026-02-10T00:30:00Z"),
                punch("2026-02-10T09:30:00Z"));

        DailyFact fact = onlyFact(assemble());

        assertThat(fact.actualWorkMinutes()).isEqualTo(540);
        assertThat(fact.confirmedScheduledWorkMinutes()).isEqualTo(540);
        assertThat(fact.recognizedOvertimeMinutes()).isZero();
        assertThat(fact.missingPunchCount()).isZero();
        assertThat(fact.firstPunchAt())
                .isEqualTo(Instant.parse("2026-02-10T00:30:00Z"));
        assertThat(fact.lastPunchAt())
                .isEqualTo(Instant.parse("2026-02-10T09:30:00Z"));
    }

    @Test
    void lateEveningPunchStaysOnItsShanghaiBusinessDate() {
        givenIdentities(identity(EMPLOYEE, "2026-02-10", "2026-02-12"));
        givenNoCalendar();
        // 2026-02-10T23:00+08:00 is 2026-02-10T15:00Z: still the 10th locally.
        givenPunches(
                punch("2026-02-10T01:00:00Z"),
                punch("2026-02-10T15:00:00Z"));

        List<VerifiedCalculatedFacts> facts = assemble().calculatedFacts();

        assertThat(facts).hasSize(2);
        assertThat(facts.getFirst().facts().dailyFact().actualWorkMinutes())
                .isEqualTo(840);
        assertThat(facts.get(1).facts().dailyFact().actualWorkMinutes())
                .isZero();
    }

    @Test
    void unpairedPunchCountsOneMissingPunchAndNoWorkedTime() {
        givenIdentities(identity(EMPLOYEE, "2026-02-10", "2026-02-11"));
        givenNoCalendar();
        givenPunches(punch("2026-02-10T00:30:00Z"));

        DailyFact fact = onlyFact(assemble());

        assertThat(fact.actualWorkMinutes()).isZero();
        assertThat(fact.missingPunchCount()).isEqualTo(1);
        assertThat(fact.firstPunchAt())
                .isEqualTo(Instant.parse("2026-02-10T00:30:00Z"));
    }

    @Test
    void publishedCalendarDrivesDayTypeAndWeekdayIsTheFallback() {
        givenIdentities(identity(EMPLOYEE, "2026-02-14", "2026-02-17"));
        givenNoPunches();
        when(mapper.findPublishedCalendarDays(any(), any(), any()))
                .thenReturn(List.of(
                        // Saturday published as an adjusted workday.
                        new CalendarDayRow(
                                LocalDate.parse("2026-02-14"),
                                "SPECIAL_WORKDAY"),
                        // Sunday published as a holiday.
                        new CalendarDayRow(
                                LocalDate.parse("2026-02-15"),
                                "PUBLIC_HOLIDAY")));

        List<VerifiedCalculatedFacts> facts = assemble().calculatedFacts();

        assertThat(facts)
                .extracting(value -> value.facts().dailyFact().dayType())
                .containsExactly(
                        DayType.ADJUSTED_WORKDAY,
                        DayType.PUBLIC_HOLIDAY,
                        // 2026-02-16 is absent from the calendar: weekday.
                        DayType.WEEKDAY);
    }

    @Test
    void weekendCalendarDayIsSplitBySaturdayAndSunday() {
        givenIdentities(identity(EMPLOYEE, "2026-02-14", "2026-02-16"));
        givenNoPunches();
        when(mapper.findPublishedCalendarDays(any(), any(), any()))
                .thenReturn(List.of(
                        new CalendarDayRow(
                                LocalDate.parse("2026-02-14"), "WEEKEND"),
                        new CalendarDayRow(
                                LocalDate.parse("2026-02-15"), "WEEKEND")));

        assertThat(assemble().calculatedFacts())
                .extracting(value -> value.facts().dailyFact().dayType())
                .containsExactly(DayType.SATURDAY, DayType.SUNDAY);
    }

    @Test
    void conflictingCalendarsFallBackToWeekdayClassification() {
        givenIdentities(identity(EMPLOYEE, "2026-02-16", "2026-02-17"));
        givenNoPunches();
        when(mapper.findPublishedCalendarDays(any(), any(), any()))
                .thenReturn(List.of(
                        new CalendarDayRow(
                                LocalDate.parse("2026-02-16"), "WORKDAY"),
                        new CalendarDayRow(
                                LocalDate.parse("2026-02-16"),
                                "PUBLIC_HOLIDAY")));

        assertThat(onlyFact(assemble()).dayType()).isEqualTo(DayType.WEEKDAY);
    }

    @Test
    void ambiguousIdentityDaysAreSkippedInsteadOfGuessed() {
        givenIdentities(
                identity(EMPLOYEE, "2026-02-10", "2026-02-13"),
                // A second assignment overlaps only 2026-02-11.
                new EmployeeIdentityIntervalRow(
                        EMPLOYEE,
                        "employee-version-1",
                        "SZST0001",
                        "员工一",
                        "assignment-2",
                        "organization-1",
                        "organization-version-1",
                        "测试组",
                        LocalDate.parse("2026-02-10"),
                        LocalDate.parse("2026-02-13"),
                        LocalDate.parse("2026-02-11"),
                        LocalDate.parse("2026-02-12"),
                        LocalDate.parse("2026-02-10"),
                        LocalDate.parse("2026-02-13")));
        givenNoCalendar();
        givenNoPunches();

        assertThat(assemble().calculatedFacts())
                .extracting(value -> value.facts().dailyFact().businessDate())
                .containsExactly(
                        LocalDate.parse("2026-02-10"),
                        LocalDate.parse("2026-02-12"));
    }

    @Test
    void identityDaysOutsideTheVersionRangeAreNotPublished() {
        // Organization version only starts mid-period, as in the dev dataset.
        givenIdentities(new EmployeeIdentityIntervalRow(
                EMPLOYEE,
                "employee-version-1",
                "SZST0001",
                "员工一",
                "assignment-1",
                "organization-1",
                "organization-version-1",
                "测试组",
                LocalDate.parse("2026-01-01"),
                null,
                LocalDate.parse("2026-01-01"),
                null,
                LocalDate.parse("2026-02-27"),
                null));
        givenNoCalendar();
        givenNoPunches();

        assertThat(assemble().calculatedFacts())
                .extracting(value -> value.facts().dailyFact().businessDate())
                .containsExactly(
                        LocalDate.parse("2026-02-27"),
                        LocalDate.parse("2026-02-28"));
    }

    @Test
    void factsBindTheExactIdentityVersionsUsedForTheDay() {
        givenIdentities(identity(EMPLOYEE, "2026-02-10", "2026-02-11"));
        givenNoCalendar();
        givenNoPunches();

        VerifiedCalculatedFacts verified =
                assemble().calculatedFacts().getFirst();

        assertThat(verified.employeeVersionId())
                .isEqualTo("employee-version-1");
        assertThat(verified.employmentAssignmentId())
                .isEqualTo("assignment-1");
        assertThat(verified.facts().dailyFact().organizationVersionId())
                .isEqualTo("organization-version-1");
        assertThat(verified.facts().exceptionFacts()).isEmpty();
    }

    @Test
    void snapshotDigestIgnoresDataAsOfButTracksContent() {
        givenIdentities(identity(EMPLOYEE, "2026-02-10", "2026-02-11"));
        givenNoCalendar();
        givenNoPunches();
        String withoutPunches = assemble().metadata().sourceSnapshotDigest();

        String laterCutoff = orchestrator.assemble(
                        COMPANY,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL,
                        DATA_AS_OF.plusSeconds(86_400))
                .metadata()
                .sourceSnapshotDigest();
        givenPunches(
                punch("2026-02-10T00:30:00Z"),
                punch("2026-02-10T09:30:00Z"));
        String withPunches = assemble().metadata().sourceSnapshotDigest();

        assertThat(laterCutoff).isEqualTo(withoutPunches);
        assertThat(withPunches).isNotEqualTo(withoutPunches);
    }

    private PublishCommand assemble() {
        return orchestrator.assemble(
                COMPANY, PERIOD, PeriodState.OPEN, PRINCIPAL, DATA_AS_OF);
    }

    private static DailyFact onlyFact(PublishCommand command) {
        assertThat(command.calculatedFacts()).hasSize(1);
        return command.calculatedFacts().getFirst().facts().dailyFact();
    }

    private void givenIdentities(EmployeeIdentityIntervalRow... rows) {
        when(mapper.findEmployeeIdentityIntervals(any(), any(), any()))
                .thenReturn(List.of(rows));
    }

    private void givenNoCalendar() {
        when(mapper.findPublishedCalendarDays(any(), any(), any()))
                .thenReturn(List.of());
    }

    private void givenNoPunches() {
        when(mapper.findActivatedPunchEvents(any(), any(), any()))
                .thenReturn(List.of());
    }

    private void givenPunches(PunchEventRow... rows) {
        when(mapper.findActivatedPunchEvents(any(), any(), any()))
                .thenReturn(List.of(rows));
    }

    private static PunchEventRow punch(String instant) {
        return new PunchEventRow(EMPLOYEE, Instant.parse(instant));
    }

    private static EmployeeIdentityIntervalRow identity(
            String employeeId, String from, String toExclusive) {
        LocalDate start = LocalDate.parse(from);
        LocalDate end = Optional.ofNullable(toExclusive)
                .map(LocalDate::parse)
                .orElse(null);
        return new EmployeeIdentityIntervalRow(
                employeeId,
                "employee-version-1",
                "SZST0001",
                "员工一",
                "assignment-1",
                "organization-1",
                "organization-version-1",
                "测试组",
                start,
                end,
                start,
                end,
                start,
                end);
    }
}
