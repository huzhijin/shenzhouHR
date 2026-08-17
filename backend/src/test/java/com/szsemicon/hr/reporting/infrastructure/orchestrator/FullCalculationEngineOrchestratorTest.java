package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.TimeAccountSnapshotRow;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.PunchSide;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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

        Mockito.when(mapper.findAttendancePolicy(companyId))
                .thenReturn(null);

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

        Mockito.when(mapper.findAttendancePolicy(companyId))
                .thenReturn(new AttendancePolicyRow(
                        15,
                        1,
                        Instant.parse("2026-09-01T00:00:00Z"),
                        2880));

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
        Mockito.when(mapper.findAttendancePolicy(companyId))
                .thenReturn(new AttendancePolicyRow(
                        15,
                        1,
                        Instant.parse("2026-10-01T00:00:00Z"),
                        2880));

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
        Mockito.when(mapper.findAttendancePolicy(companyId))
                .thenReturn(new AttendancePolicyRow(
                        15,
                        1,
                        Instant.parse("2026-09-01T00:00:00Z"),
                        2880));
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
}
