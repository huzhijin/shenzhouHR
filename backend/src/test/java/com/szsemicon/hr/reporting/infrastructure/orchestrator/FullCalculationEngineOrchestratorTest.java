package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
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
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.PunchSide;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FullCalculationEngineOrchestratorTest {

    @Mock
    private AttendanceReportCalculationMapper mapper;

    @InjectMocks
    private FullCalculationEngineOrchestrator orchestrator;

    @BeforeEach
    void defaultProjectionSideFacts() {
        Mockito.lenient().when(mapper.findAttendanceSourceVersions(
                        Mockito.anyString(), Mockito.any(Instant.class)))
                .thenReturn(committedSourceVersions(
                        "a".repeat(64),
                        Instant.parse("2026-08-01T00:00:00Z"),
                        1L));
        Mockito.lenient().when(mapper.findReportableOaDocuments(
                        Mockito.anyString(),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findTimeAccountSnapshots(
                        Mockito.anyString(),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of());
    }

    @Test
    void committedSourceWatermarkChangesTheRealtimeSnapshotToken() {
        String companyId = "company-1";
        YearMonth period = YearMonth.of(2026, 8);
        Instant dataAsOf = Instant.parse("2026-08-17T07:00:15Z");
        Mockito.when(mapper.findAttendanceSourceVersions(
                        companyId, dataAsOf))
                .thenReturn(committedSourceVersions(
                        "a".repeat(64),
                        Instant.parse("2026-08-17T06:59:00Z"),
                        7L));

        PublishCommand first = orchestrator.assemble(
                companyId,
                period,
                PeriodState.OPEN,
                "principal-1",
                dataAsOf);

        Mockito.when(mapper.findAttendanceSourceVersions(
                        companyId, dataAsOf))
                .thenReturn(committedSourceVersions(
                        "b".repeat(64),
                        Instant.parse("2026-08-17T07:00:00Z"),
                        8L));

        PublishCommand second = orchestrator.assemble(
                companyId,
                period,
                PeriodState.OPEN,
                "principal-1",
                dataAsOf);

        assertThat(first.metadata().sourceVersions())
                .anyMatch(version -> version.contains(
                        "SOURCE.OA_ATTENDANCE:2026-08-17T06:59:00Z:"));
        assertThat(first.metadata().sourceVersions())
                .allMatch(version -> version.length() <= 128);
        assertThat(second.metadata().sourceSnapshotDigest())
                .isNotEqualTo(first.metadata().sourceSnapshotDigest());
    }

    @Test
    void missingOrUnsynchronizedRequiredSourceFailsClosed() {
        String companyId = "company-1";
        YearMonth period = YearMonth.of(2026, 8);
        Instant dataAsOf = Instant.parse("2026-08-17T07:00:15Z");
        SourceInputVersionRow deli = committedSourceVersions(
                "a".repeat(64),
                Instant.parse("2026-08-17T07:00:00Z"),
                7L).getFirst();
        Mockito.when(mapper.findAttendanceSourceVersions(
                        companyId, dataAsOf))
                .thenReturn(List.of(deli));

        assertThatThrownBy(() -> orchestrator.assemble(
                companyId,
                period,
                PeriodState.OPEN,
                "principal-1",
                dataAsOf))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OA_ATTENDANCE")
                .hasMessageContaining("not active");

        Mockito.when(mapper.findAttendanceSourceVersions(
                        companyId, dataAsOf))
                .thenReturn(List.of(
                        deli,
                        new SourceInputVersionRow(
                                "source-oa-1",
                                "OA_ATTENDANCE",
                                2,
                                0,
                                null,
                                null,
                                null)));

        assertThatThrownBy(() -> orchestrator.assemble(
                companyId,
                period,
                PeriodState.OPEN,
                "principal-1",
                dataAsOf))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OA_ATTENDANCE")
                .hasMessageContaining("not synchronized");
    }

    @Test
    void tiedEffectiveEmployeeIdentityFailsClosed() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of());
        Mockito.when(mapper.findEmployeeIdentityIntervals(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class)))
                .thenReturn(List.of(
                        identityRow(
                                "emp-1", "emp-v-1", "E001",
                                "assignment-1", "org-1", "org-v-1",
                                "研发部", businessDate),
                        identityRow(
                                "emp-1", "emp-v-2", "E001",
                                "assignment-2", "org-2", "org-v-2",
                                "制造部", businessDate)));

        assertThatThrownBy(() -> orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous employee identity");
    }

    @Test
    void conflictingCalendarAuthorityFailsClosed() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of());
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(
                        new CalendarDayRow(businessDate, "WEEKDAY"),
                        new CalendarDayRow(
                                businessDate, "PUBLIC_HOLIDAY")));

        assertThatThrownBy(() -> orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous calendar authority");
    }

    @Test
    void missingCalendarOrWorkdayShiftAuthorityFailsClosed() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of());
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Calendar authority missing");

        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new CalendarDayRow(
                        businessDate, "WEEKDAY")));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Scheduled shift authority missing");
    }

    @Test
    void ambiguousOaEmployeeNumberAtOccurrenceTimeFailsClosed() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        OaDocumentRow document = oaDocument(
                "OUTING:ambiguous",
                "OUTING",
                Instant.parse("2026-08-03T03:00:00Z"),
                Instant.parse("2026-08-03T04:00:00Z"));
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of(),
                List.of(document));
        Mockito.when(mapper.findEmployeeIdentityIntervals(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class)))
                .thenReturn(List.of(
                        identityRow(
                                "emp-1", "emp-v-1", "E001",
                                "assignment-1", "org-1", "org-v-1",
                                "研发部", businessDate),
                        identityRow(
                                "emp-2", "emp-v-2", "E001",
                                "assignment-2", "org-2", "org-v-2",
                                "制造部", businessDate)));

        assertThatThrownBy(() -> orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "OA employee identity is missing or ambiguous");
    }

    @Test
    void employeeAndOrganizationDisplayChangesAlterTheSnapshotToken() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of());

        PublishCommand first = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);
        Mockito.when(mapper.findEmployeeIdentityIntervals(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(
                        "emp-1",
                        "emp-v-2",
                        "E001",
                        "李四",
                        "assignment-1",
                        "org-1",
                        "org-v-2",
                        "先进制造部",
                        businessDate,
                        businessDate.plusDays(1),
                        businessDate,
                        businessDate.plusDays(1),
                        businessDate,
                        businessDate.plusDays(1))));

        PublishCommand second = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(second.metadata().sourceSnapshotDigest())
                .isNotEqualTo(first.metadata().sourceSnapshotDigest());
    }

    @Test
    void assemble_withNoEmployees_returnsEmptyCommand() {
        // Given
        String companyId = "company-1";
        YearMonth period = YearMonth.of(2026, 8);
        PeriodState periodState = PeriodState.OPEN;
        String principalId = "admin-1";
        Instant dataAsOf = Instant.parse("2026-08-13T10:00:00Z");

        // Mock empty data
        Mockito.when(mapper.findEmployeeIdentityIntervals(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class)))
                .thenReturn(List.of());

        Mockito.when(mapper.findActivatedPunchEvents(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of());

        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of());

        Mockito.when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of());

        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of());

        Mockito.when(mapper.findAttendancePolicies(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of());

        // When
        PublishCommand command = orchestrator.assemble(
                companyId, period, periodState, principalId, dataAsOf);

        // Then
        assertThat(command).isNotNull();
        assertThat(command.metadata().companyId()).isEqualTo(companyId);
        assertThat(command.metadata().period()).isEqualTo(period);
        assertThat(command.calculatedFacts()).isEmpty();
    }

    @Test
    void currentPeriodStopsDailyAndOaWindowsAtKnowledgeDateEnd() {
        String companyId = "company-1";
        YearMonth period = YearMonth.of(2026, 8);
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        LocalDate cutoffEndExclusive = businessDate.plusDays(1);
        Instant dataAsOf = Instant.parse("2026-08-03T04:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of());
        Mockito.when(mapper.findEmployeeIdentityIntervals(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(
                        "emp-1",
                        "emp-v-1",
                        "E001",
                        "张三",
                        "assignment-1",
                        "org-1",
                        "org-v-1",
                        "研发部",
                        businessDate,
                        businessDate.plusDays(3),
                        businessDate,
                        businessDate.plusDays(3),
                        businessDate,
                        businessDate.plusDays(3))));

        PublishCommand command = orchestrator.assemble(
                companyId,
                period,
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.calculatedFacts())
                .extracting(value -> value.facts().dailyFact().businessDate())
                .containsExactly(businessDate);
        Mockito.verify(mapper).findScheduledWorkSegments(
                companyId,
                period.atDay(1),
                cutoffEndExclusive,
                dataAsOf);
        Mockito.verify(mapper).findAttendancePolicies(
                companyId,
                period.atDay(1),
                cutoffEndExclusive,
                dataAsOf);
        Mockito.verify(mapper).findReportableOaDocuments(
                companyId,
                period.atDay(1)
                        .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                        .toInstant(),
                cutoffEndExclusive
                        .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                        .toInstant(),
                dataAsOf);
    }

    @Test
    void futurePeriodReturnsNoFactsWithoutReadingFutureAuthority() {
        PublishCommand command = orchestrator.assemble(
                "company-1",
                YearMonth.of(2026, 9),
                PeriodState.OPEN,
                "admin-1",
                Instant.parse("2026-08-17T04:00:00Z"));

        assertThat(command.calculatedFacts()).isEmpty();
        assertThat(command.oaDocumentFacts()).isEmpty();
        assertThat(command.timeAccountFacts()).isEmpty();
        Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void assemble_withOneEmployeeOneDay_calculatesCorrectly() {
        // Given
        String companyId = "company-1";
        YearMonth period = YearMonth.of(2026, 8);
        LocalDate day1 = LocalDate.of(2026, 8, 1);
        PeriodState periodState = PeriodState.OPEN;
        String principalId = "admin-1";
        Instant dataAsOf = Instant.parse("2026-08-13T10:00:00Z");

        // Limit the identity to only 2026-08-01 so the orchestrator produces
        // exactly one fact row for that single day.
        LocalDate day2 = day1.plusDays(1);
        EmployeeIdentityIntervalRow identity = new EmployeeIdentityIntervalRow(
                "emp-1",
                "emp-v-1",
                "E001",
                "张三",
                "assignment-1",
                "org-1",
                "org-v-1",
                "研发部",
                day1,
                day2,
                day1,
                day2,
                day1,
                day2);

        // Mock data
        Mockito.when(mapper.findEmployeeIdentityIntervals(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class)))
                .thenReturn(List.of(identity));

        Mockito.when(mapper.findActivatedPunchEvents(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(
                        new PunchEventRow(
                                "emp-1",
                                Instant.parse("2026-08-01T01:00:00Z")), // 09:00 CST
                        new PunchEventRow(
                                "emp-1",
                                Instant.parse("2026-08-01T10:00:00Z")))); // 18:00 CST

        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        new CalendarDayRow(day1, "WEEKDAY")));

        Mockito.when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of());

        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        new ShiftSegmentRow(
                                "emp-1",
                                day1,
                                "seg-1",
                                Instant.parse("2026-08-01T01:00:00Z"), // 09:00 CST
                                Instant.parse("2026-08-01T10:00:00Z"), // 18:00 CST
                                Instant.parse("2026-08-01T00:45:00Z"),
                                Instant.parse("2026-08-01T01:15:00Z"),
                                Instant.parse("2026-08-01T09:45:00Z"),
                                Instant.parse("2026-08-01T10:15:00Z"))));

        Mockito.when(mapper.findAttendancePolicies(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(policy("emp-1", day1)));

        // When
        PublishCommand command = orchestrator.assemble(
                companyId, period, periodState, principalId, dataAsOf);

        // Then
        assertThat(command).isNotNull();
        assertThat(command.metadata().companyId()).isEqualTo(companyId);
        assertThat(command.calculatedFacts()).hasSize(1);

        var facts = command.calculatedFacts().get(0);
        assertThat(facts.facts().dailyFact().employeeId()).isEqualTo("emp-1");
        assertThat(facts.facts().dailyFact().businessDate()).isEqualTo(day1);
        assertThat(facts.facts().dailyFact().firstPunchAt())
                .isEqualTo(Instant.parse("2026-08-01T01:00:00Z"));
        assertThat(facts.facts().dailyFact().lastPunchAt())
                .isEqualTo(Instant.parse("2026-08-01T10:00:00Z"));
    }

    @Test
    void monthEndOvernightShiftLoadsAndConsumesNextDayDeparturePunch() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 31);
        LocalDate nextDay = businessDate.plusDays(1);
        Instant dataAsOf = Instant.parse("2026-09-02T00:00:00Z");
        Mockito.when(mapper.findEmployeeIdentityIntervals(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(
                        "emp-1",
                        "emp-v-1",
                        "E001",
                        "张三",
                        "assignment-1",
                        "org-1",
                        "org-v-1",
                        "研发部",
                        businessDate,
                        nextDay,
                        businessDate,
                        nextDay,
                        businessDate,
                        nextDay)));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new ShiftSegmentRow(
                        "emp-1",
                        businessDate,
                        "night-segment",
                        Instant.parse("2026-08-31T14:00:00Z"),
                        Instant.parse("2026-08-31T22:00:00Z"),
                        Instant.parse("2026-08-31T13:45:00Z"),
                        Instant.parse("2026-08-31T14:15:00Z"),
                        Instant.parse("2026-08-31T21:45:00Z"),
                        Instant.parse("2026-08-31T22:15:00Z"))));
        Mockito.when(mapper.findActivatedPunchEvents(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(
                        new PunchEventRow(
                                "emp-1",
                                Instant.parse("2026-08-31T14:00:00Z")),
                        new PunchEventRow(
                                "emp-1",
                                Instant.parse("2026-08-31T22:00:00Z"))));
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new CalendarDayRow(
                        businessDate, "WORKDAY")));
        Mockito.when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of());
        Mockito.when(mapper.findAttendancePolicies(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(policy("emp-1", businessDate)));

        var daily = orchestrator.assemble(
                        companyId,
                        YearMonth.of(2026, 8),
                        PeriodState.OPEN,
                        "admin-1",
                        dataAsOf)
                .calculatedFacts()
                .getFirst()
                .facts()
                .dailyFact();

        assertThat(daily.businessDate()).isEqualTo(businessDate);
        assertThat(daily.actualAttendanceDays()).isEqualTo(1);
        assertThat(daily.firstPunchAt())
                .isEqualTo(Instant.parse("2026-08-31T14:00:00Z"));
        assertThat(daily.lastPunchAt())
                .isEqualTo(Instant.parse("2026-08-31T22:00:00Z"));
        Mockito.verify(mapper).findActivatedPunchEvents(
                companyId,
                Instant.parse("2026-07-31T16:00:00Z"),
                Instant.parse("2026-08-31T22:15:00Z"),
                dataAsOf);
    }

    @Test
    void executiveRoleGrantsAttendanceWithoutPunchOrMissingCount() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of(new PunchExemptionRoleIntervalRow(
                        "emp-1",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        null)));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        var daily = command.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.actualAttendanceDays()).isEqualTo(1);
        assertThat(daily.scheduledAttendanceDays()).isEqualTo(1);
        assertThat(daily.missingPunchCount()).isZero();
        assertThat(daily.firstPunchAt()).isNull();
    }

    @Test
    void approvedBothSideCorrectionBecomesSupplementedPunchEvidence() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(new PunchCorrectionRow(
                        "correction-1",
                        "emp-1",
                        businessDate,
                        PunchSide.BOTH,
                        Instant.parse("2026-08-16T10:00:00Z"))),
                List.of());

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        var daily = command.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.actualAttendanceDays()).isEqualTo(1);
        assertThat(daily.missingPunchCount()).isZero();
        assertThat(daily.firstPunchAt())
                .isEqualTo(Instant.parse("2026-08-03T01:00:00Z"));
        assertThat(daily.lastPunchAt())
                .isEqualTo(Instant.parse("2026-08-03T10:00:00Z"));
    }

    @Test
    void approvedExemptionFlowsThroughFullCalculationWithoutPunch() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of(),
                List.of(oaDocument(
                        "EXEMPT_PUNCH:202",
                        "EXEMPT_PUNCH",
                        Instant.parse("2026-08-02T16:00:00Z"),
                        Instant.parse("2026-08-03T16:00:00Z"))));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        var daily = command.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.scheduledAttendanceDays()).isEqualTo(1);
        assertThat(daily.actualAttendanceDays()).isEqualTo(1);
        assertThat(daily.confirmedScheduledWorkMinutes()).isEqualTo(540);
        assertThat(daily.missingPunchCount()).isZero();
        assertThat(daily.firstPunchAt()).isNull();
    }

    @Test
    void approvedOutingAndDailyPunchFlowThroughFullCalculation() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant punchAt = Instant.parse("2026-08-03T08:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(new PunchEventRow("emp-1", punchAt)),
                List.of(),
                List.of(),
                List.of(oaDocument(
                        "OUTING:252",
                        "OUTING",
                        Instant.parse("2026-08-03T03:00:00Z"),
                        Instant.parse("2026-08-03T04:00:00Z"))));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        var daily = command.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.actualAttendanceDays()).isEqualTo(1);
        assertThat(daily.confirmedScheduledWorkMinutes()).isEqualTo(540);
        assertThat(daily.absenceMinutes()).isZero();
        assertThat(daily.missingPunchCount()).isZero();
        assertThat(daily.firstPunchAt()).isEqualTo(punchAt);
        assertThat(daily.lastPunchAt()).isEqualTo(punchAt);
    }

    @Test
    void approvedOutingWithoutPunchFlowsThroughAsAbsence() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of(),
                List.of(oaDocument(
                        "OUTING:252",
                        "OUTING",
                        Instant.parse("2026-08-03T03:00:00Z"),
                        Instant.parse("2026-08-03T04:00:00Z"))));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        var daily = command.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.scheduledAttendanceDays()).isEqualTo(1);
        assertThat(daily.actualAttendanceDays()).isZero();
        assertThat(daily.absenceMinutes()).isEqualTo(540);
        assertThat(daily.missingPunchCount()).isEqualTo(2);
    }

    @Test
    void severeLateUsesProjectorMetricsExceptionsAndCalendarDayType() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(
                        new PunchEventRow(
                                "emp-1",
                                Instant.parse("2026-08-03T01:50:00Z")),
                        new PunchEventRow(
                                "emp-1",
                                Instant.parse("2026-08-03T10:00:00Z"))),
                List.of(),
                List.of());
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(
                        businessDate, "SPECIAL_WORKDAY")));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new ShiftSegmentRow(
                        "emp-1",
                        businessDate,
                        "seg-1",
                        Instant.parse("2026-08-03T01:00:00Z"),
                        Instant.parse("2026-08-03T10:00:00Z"),
                        Instant.parse("2026-08-03T00:00:00Z"),
                        Instant.parse("2026-08-03T02:00:00Z"),
                        Instant.parse("2026-08-03T09:00:00Z"),
                        Instant.parse("2026-08-03T11:00:00Z"))));

        var calculated = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf)
                .calculatedFacts()
                .getFirst();
        var daily = calculated.facts().dailyFact();

        assertThat(daily.dayType())
                .isEqualTo(com.szsemicon.hr.reporting.domain
                        .AttendanceReportModels.DayType.ADJUSTED_WORKDAY);
        assertThat(daily.actualAttendanceDays()).isZero();
        assertThat(daily.lateMinutes()).isZero();
        assertThat(daily.penalizedLateMinutes()).isZero();
        assertThat(daily.absenceMinutes()).isEqualTo(540);
        assertThat(calculated.facts().exceptionFacts())
                .extracting(value -> value.exceptionType())
                .containsExactly("LATE_CONVERTED_TO_ABSENCE");
    }

    @Test
    void monthlyGraceIsConsumedOnFirstLateDateAndNotRepeated() {
        String companyId = "company-1";
        LocalDate firstDate = LocalDate.of(2026, 8, 3);
        LocalDate secondDate = firstDate.plusDays(1);
        LocalDate endExclusive = secondDate.plusDays(1);
        Instant dataAsOf = Instant.parse("2026-08-20T00:00:00Z");
        Mockito.when(mapper.findEmployeeIdentityIntervals(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(
                        "emp-1",
                        "emp-v-1",
                        "E001",
                        "张三",
                        "assignment-1",
                        "org-1",
                        "org-v-1",
                        "研发部",
                        firstDate,
                        endExclusive,
                        firstDate,
                        endExclusive,
                        firstDate,
                        endExclusive)));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(
                        segment(firstDate, "seg-1"),
                        segment(secondDate, "seg-2")));
        Mockito.when(mapper.findActivatedPunchEvents(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(
                        lateArrival(firstDate),
                        departure(firstDate),
                        lateArrival(secondDate),
                        departure(secondDate)));
        Mockito.when(mapper.findApprovedPunchCorrections(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of());
        Mockito.when(mapper.findPunchExemptionRoleIntervals(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of());
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(
                        new CalendarDayRow(firstDate, "WORKDAY"),
                        new CalendarDayRow(secondDate, "WORKDAY")));
        Mockito.when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of());
        Mockito.when(mapper.findAttendancePolicies(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(
                        policy("emp-1", firstDate),
                        policy("emp-1", secondDate)));

        List<DailyFact> facts = orchestrator.assemble(
                                companyId,
                                YearMonth.of(2026, 8),
                                PeriodState.OPEN,
                                "admin-1",
                                dataAsOf)
                        .calculatedFacts()
                        .stream()
                        .map(value -> value.facts().dailyFact())
                        .toList();

        assertThat(facts).hasSize(2);
        assertThat(facts.get(0).businessDate()).isEqualTo(firstDate);
        assertThat(facts.get(0).lateMinutes()).isEqualTo(30);
        assertThat(facts.get(0).penalizedLateMinutes()).isEqualTo(15);
        assertThat(facts.get(0).absenceMinutes()).isZero();
        assertThat(facts.get(1).businessDate()).isEqualTo(secondDate);
        assertThat(facts.get(1).lateMinutes()).isZero();
        assertThat(facts.get(1).absenceMinutes()).isEqualTo(540);
    }

    @Test
    void missingAttendancePolicyForEffectiveEmployeeDateFailsClosed() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of());
        Mockito.lenient().when(mapper.findAttendancePolicies(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                orchestrator.assemble(
                        companyId,
                        YearMonth.of(2026, 8),
                        PeriodState.OPEN,
                        "admin-1",
                        dataAsOf))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Attendance policy authority missing");
    }

    @Test
    void ambiguousLateGracePolicyFailsClosed() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of());
        Mockito.when(mapper.findAttendancePolicies(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(policy(
                        "emp-1",
                        businessDate,
                        1,
                        2,
                        1)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                orchestrator.assemble(
                        companyId,
                        YearMonth.of(2026, 8),
                        PeriodState.OPEN,
                        "admin-1",
                        dataAsOf))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "LATE_GRACE candidate count must be exactly one");
    }

    @Test
    void sickLeaveCountsButPersonalLeaveDoesNotAndBothPersistTheDailyType() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(companyId, businessDate, dataAsOf, List.of(),
                List.of(), List.of(), List.of(oaLeaveDocument("leave-sick", LeaveType.SICK)));

        var sick = orchestrator.assemble(companyId, YearMonth.of(2026, 8),
                PeriodState.OPEN, "admin-1", dataAsOf)
                .calculatedFacts().getFirst().facts().dailyFact();
        assertThat(sick.leaveType()).isEqualTo(LeaveType.SICK);
        assertThat(sick.actualAttendanceDays()).isEqualTo(1);

        stubOneEmployeeDay(companyId, businessDate, dataAsOf, List.of(),
                List.of(), List.of(), List.of(oaLeaveDocument("leave-personal", LeaveType.PERSONAL)));
        var personal = orchestrator.assemble(companyId, YearMonth.of(2026, 8),
                PeriodState.OPEN, "admin-1", dataAsOf)
                .calculatedFacts().getFirst().facts().dailyFact();
        assertThat(personal.leaveType()).isEqualTo(LeaveType.PERSONAL);
        assertThat(personal.actualAttendanceDays()).isZero();
    }

    @Test
    void conflictingLeaveTypesForOneBusinessDayFailClosed() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(companyId, businessDate, dataAsOf, List.of(),
                List.of(), List.of(), List.of(
                        oaLeaveDocument("leave-sick", LeaveType.SICK),
                        oaLeaveDocument("leave-annual", LeaveType.ANNUAL)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                orchestrator.assemble(companyId, YearMonth.of(2026, 8),
                        PeriodState.OPEN, "admin-1", dataAsOf))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting leave types");
    }

    @Test
    void unresolvedLeaveRevocationCannotLeaveOriginalIntervalFullyEffective() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(companyId, businessDate, dataAsOf, List.of(),
                List.of(), List.of(), List.of(
                        oaLeaveDocument("leave-sick", LeaveType.SICK),
                        oaDocument(
                                "leave-revocation-1",
                                "LEAVE_REVOCATION",
                                Instant.parse("2026-08-03T03:00:00Z"),
                                Instant.parse("2026-08-03T04:00:00Z"))));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                orchestrator.assemble(companyId, YearMonth.of(2026, 8),
                        PeriodState.OPEN, "admin-1", dataAsOf))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        OaDocumentConverter.UNRESOLVED_LEAVE_REVOCATION);
    }

    @Test
    void approvedLeaveIsCopiedIntoMonthlyOaReportFacts() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of(),
                List.of(oaLeaveDocument("leave-sick", LeaveType.SICK)));
        Mockito.when(mapper.findReportableOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new OaReportFactRow(
                        "oa-doc-1",
                        "leave-sick",
                        "LEAVE",
                        LeaveType.SICK,
                        "emp-1",
                        "E001",
                        "assignment-1",
                        Instant.parse("2026-08-02T16:00:00Z"),
                        Instant.parse("2026-08-03T16:00:00Z"),
                        "APPROVED",
                        "oa-v1")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.oaAttendanceDocumentId()).isEqualTo("oa-doc-1");
            assertThat(fact.leaveTypeCode()).isEqualTo("SICK");
            assertThat(fact.recognizedMinutes()).isEqualTo(540);
            assertThat(fact.employeeVersionId()).isEqualTo("emp-v-1");
            assertThat(fact.organizationVersionId()).isEqualTo("org-v-1");
        });
    }

    @Test
    void ledgerSnapshotIsCopiedIntoMonthlyTimeAccountFacts() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of());
        Mockito.when(mapper.findTimeAccountSnapshots(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new TimeAccountSnapshotRow(
                        "annual-account-1",
                        "emp-1",
                        "assignment-1",
                        TimeAccountType.ANNUAL_LEAVE,
                        new BigDecimal("40.00"),
                        new BigDecimal("8.00"),
                        new BigDecimal("0.00"),
                        new BigDecimal("1.00"),
                        new BigDecimal("8.00"),
                        new BigDecimal("0.00"),
                        new BigDecimal("0.00"),
                        new BigDecimal("0.00"),
                        "TIME_ACCOUNT:annual-account-1:V2:S4")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.timeAccountFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.accountId()).isEqualTo("annual-account-1");
            assertThat(fact.accountType()).isEqualTo(TimeAccountType.ANNUAL_LEAVE);
            assertThat(fact.employeeVersionId()).isEqualTo("emp-v-1");
            assertThat(fact.organizationId()).isEqualTo("org-1");
            assertThat(fact.usedHours()).isEqualByComparingTo("8.00");
        });
    }

    private void stubOneEmployeeDay(
            String companyId,
            LocalDate businessDate,
            Instant dataAsOf,
            List<PunchEventRow> punches,
            List<PunchCorrectionRow> corrections,
            List<PunchExemptionRoleIntervalRow> roles) {
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                punches,
                corrections,
                roles,
                List.of());
    }

    private void stubOneEmployeeDay(
            String companyId,
            LocalDate businessDate,
            Instant dataAsOf,
            List<PunchEventRow> punches,
            List<PunchCorrectionRow> corrections,
            List<PunchExemptionRoleIntervalRow> roles,
            List<OaDocumentRow> oaDocuments) {
        LocalDate nextDay = businessDate.plusDays(1);
        Mockito.lenient().when(mapper.findEmployeeIdentityIntervals(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(
                        "emp-1",
                        "emp-v-1",
                        "E001",
                        "张三",
                        "assignment-1",
                        "org-1",
                        "org-v-1",
                        "研发部",
                        businessDate,
                        nextDay,
                        businessDate,
                        nextDay,
                        businessDate,
                        nextDay)));
        Mockito.when(mapper.findActivatedPunchEvents(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(punches);
        Mockito.when(mapper.findApprovedPunchCorrections(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(corrections);
        Mockito.when(mapper.findPunchExemptionRoleIntervals(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class)))
                .thenReturn(roles);
        Mockito.lenient().when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(
                        businessDate, "WEEKDAY")));
        Mockito.when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(oaDocuments);
        Mockito.lenient().when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new ShiftSegmentRow(
                        "emp-1",
                        businessDate,
                        "seg-1",
                        Instant.parse("2026-08-03T01:00:00Z"),
                        Instant.parse("2026-08-03T10:00:00Z"),
                        Instant.parse("2026-08-03T00:45:00Z"),
                        Instant.parse("2026-08-03T01:15:00Z"),
                        Instant.parse("2026-08-03T09:45:00Z"),
                        Instant.parse("2026-08-03T10:15:00Z"))));
        Mockito.lenient().when(mapper.findAttendancePolicies(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(policy("emp-1", businessDate)));
    }

    private static AttendancePolicyRow policy(
            String employeeId,
            LocalDate businessDate) {
        return policy(employeeId, businessDate, 1, 1, 1);
    }

    private static AttendancePolicyRow policy(
            String employeeId,
            LocalDate businessDate,
            int attendanceGroupAuthorityCount,
            int lateGracePolicyCount,
            int monthlyLateExemptionPolicyCount) {
        return new AttendancePolicyRow(
                employeeId,
                businessDate,
                attendanceGroupAuthorityCount,
                lateGracePolicyCount,
                true,
                15,
                monthlyLateExemptionPolicyCount,
                true,
                15,
                1,
                false,
                1,
                true,
                7,
                "NEXT_DAY_START_AFTER_FULL_DAYS",
                1,
                true,
                48);
    }

    private static ShiftSegmentRow segment(
            LocalDate businessDate,
            String segmentId) {
        Instant start = businessDate
                .atTime(9, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant end = businessDate
                .atTime(18, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        return new ShiftSegmentRow(
                "emp-1",
                businessDate,
                segmentId,
                start,
                end,
                start.minusSeconds(60 * 60),
                start.plusSeconds(60 * 60),
                end.minusSeconds(60 * 60),
                end.plusSeconds(60 * 60));
    }

    private static PunchEventRow lateArrival(LocalDate businessDate) {
        return new PunchEventRow(
                "emp-1",
                businessDate
                        .atTime(9, 30)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toInstant());
    }

    private static PunchEventRow departure(LocalDate businessDate) {
        return new PunchEventRow(
                "emp-1",
                businessDate
                        .atTime(18, 0)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toInstant());
    }

    private static OaDocumentRow oaDocument(
            String sourceBusinessKey,
            String documentType,
            Instant start,
            Instant end) {
        return new OaDocumentRow(
                sourceBusinessKey,
                documentType,
                null,
                "E001",
                start,
                end,
                "Asia/Shanghai",
                start.minusSeconds(60),
                true);
    }

    private static OaDocumentRow oaLeaveDocument(String sourceBusinessKey,
                                                   LeaveType leaveType) {
        return new OaDocumentRow(
                sourceBusinessKey,
                "LEAVE",
                null,
                leaveType,
                "E001",
                Instant.parse("2026-08-02T16:00:00Z"),
                Instant.parse("2026-08-03T16:00:00Z"),
                "Asia/Shanghai",
                Instant.parse("2026-08-02T15:00:00Z"),
                true);
    }

    private static List<SourceInputVersionRow> committedSourceVersions(
            String oaDigest,
            Instant oaCommittedAt,
            long oaWatermarkVersion) {
        return List.of(
                new SourceInputVersionRow(
                        "source-deli-1",
                        "DELI_CLOUD",
                        1,
                        0,
                        1L,
                        "d".repeat(64),
                        Instant.parse("2026-08-01T00:00:00Z")),
                new SourceInputVersionRow(
                        "source-oa-1",
                        "OA_ATTENDANCE",
                        2,
                        0,
                        oaWatermarkVersion,
                        oaDigest,
                        oaCommittedAt));
    }

    private static EmployeeIdentityIntervalRow identityRow(
            String employeeId,
            String employeeVersionId,
            String employeeNumber,
            String assignmentId,
            String organizationId,
            String organizationVersionId,
            String organizationName,
            LocalDate businessDate) {
        return new EmployeeIdentityIntervalRow(
                employeeId,
                employeeVersionId,
                employeeNumber,
                employeeId + "-name",
                assignmentId,
                organizationId,
                organizationVersionId,
                organizationName,
                businessDate,
                businessDate.plusDays(1),
                businessDate,
                businessDate.plusDays(1),
                businessDate,
                businessDate.plusDays(1));
    }
}
