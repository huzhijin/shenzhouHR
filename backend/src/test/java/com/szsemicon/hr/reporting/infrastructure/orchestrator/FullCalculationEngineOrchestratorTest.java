package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.OaTemporalShape;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.AttendancePolicyRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.EmployeeIdentityIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.EmployeeLateDayCountRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaDocumentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaReportFactRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.HrPunchAdjustmentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchCorrectionRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchExemptionRoleIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchEventRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.SourceInputVersionRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.TimeAccountSnapshotRow;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.PunchSide;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.reporting.application.OvernightReturnFixtures;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
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
        Mockito.lenient().when(mapper.countLateDaysBeforeWindow(
                        Mockito.anyString(),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class)))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findTimeAccountSnapshots(
                        Mockito.anyString(),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findHrPunchAdjustments(
                        Mockito.anyString(),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findStandingPunchExemptionIntervals(
                        Mockito.anyString(),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findCurrentOrganizationGraph(
                        Mockito.anyString()))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findCurrentOrganizationAncestors(
                        Mockito.anyString()))
                .thenReturn(List.of());
    }

    @Test
    void personDayAssembleLoadsOnlyTheScopedEmployee() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 11);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Mockito.lenient().when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(segment(businessDate, "seg-1")));
        Mockito.lenient().when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(
                        "emp-1", businessDate, "WEEKDAY", 1, 1)));
        Mockito.lenient().when(mapper.findAttendancePolicies(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(policy("emp-1", businessDate)));
        Mockito.when(mapper.findEmployeeIdentityIntervalsForEmployee(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq("emp-1")))
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
                        businessDate.plusDays(1),
                        businessDate,
                        businessDate.plusDays(1),
                        businessDate,
                        businessDate.plusDays(1))));
        Mockito.when(mapper.findActivatedPunchEventsForEmployee(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf),
                        Mockito.eq("emp-1")))
                .thenReturn(List.of(
                        lateArrival(businessDate), departure(businessDate)));
        Mockito.when(mapper.findApprovedPunchCorrectionsForEmployee(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf),
                        Mockito.eq("emp-1")))
                .thenReturn(List.of());
        Mockito.when(mapper.findHrPunchAdjustmentsForEmployee(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf),
                        Mockito.eq("emp-1")))
                .thenReturn(List.of());
        Mockito.when(mapper.findPunchExemptionRoleIntervalsForEmployee(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq("emp-1")))
                .thenReturn(List.of());
        Mockito.when(mapper.findStandingPunchExemptionIntervalsForEmployee(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq("emp-1")))
                .thenReturn(List.of());
        Mockito.when(mapper.findEffectiveOaDocumentsForEmployee(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf),
                        Mockito.eq("emp-1")))
                .thenReturn(List.of());
        Mockito.when(mapper.findReportableOaDocumentsForEmployee(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf),
                        Mockito.eq("emp-1")))
                .thenReturn(List.of());
        Mockito.when(mapper.findTimeAccountSnapshotsForEmployee(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf),
                        Mockito.eq("emp-1")))
                .thenReturn(List.of());
        Mockito.when(mapper.countLateDaysBeforeWindowForEmployee(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq("emp-1")))
                .thenReturn(List.of());

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf,
                businessDate,
                businessDate.plusDays(1),
                "emp-1");

        assertThat(command.calculatedFacts())
                .extracting(value -> value.facts().dailyFact().employeeId())
                .containsOnly("emp-1");
        assertThat(command.calculatedFacts())
                .extracting(value -> value.facts().dailyFact().businessDate())
                .containsOnly(businessDate);
        Mockito.verify(mapper, Mockito.never()).findEmployeeIdentityIntervals(
                Mockito.eq(companyId),
                Mockito.any(LocalDate.class),
                Mockito.any(LocalDate.class));
        Mockito.verify(mapper, Mockito.never()).findActivatedPunchEvents(
                Mockito.eq(companyId),
                Mockito.any(Instant.class),
                Mockito.any(Instant.class),
                Mockito.any(Instant.class));
        Mockito.verify(mapper, Mockito.never()).findEffectiveOaDocuments(
                Mockito.eq(companyId),
                Mockito.any(Instant.class),
                Mockito.any(Instant.class),
                Mockito.any(Instant.class));
        Mockito.verify(mapper, Mockito.never()).findReportableOaDocuments(
                Mockito.eq(companyId),
                Mockito.any(Instant.class),
                Mockito.any(Instant.class),
                Mockito.any(Instant.class));
        Mockito.verify(mapper).findEmployeeIdentityIntervalsForEmployee(
                Mockito.eq(companyId),
                Mockito.any(LocalDate.class),
                Mockito.any(LocalDate.class),
                Mockito.eq("emp-1"));
        Mockito.verify(mapper).findActivatedPunchEventsForEmployee(
                Mockito.eq(companyId),
                Mockito.any(Instant.class),
                Mockito.any(Instant.class),
                Mockito.eq(dataAsOf),
                Mockito.eq("emp-1"));
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

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);
        assertThat(command.calculatedFacts()).isEmpty();
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

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);
        assertThat(command.calculatedFacts()).isEmpty();
    }

    @Test
    void missingCalendarOrWorkdayShiftAuthoritySkipsThatDay() {
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

        PublishCommand withoutCalendar = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);
        assertThat(withoutCalendar.calculatedFacts()).isEmpty();

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

        PublishCommand withoutShift = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);
        assertThat(withoutShift.calculatedFacts()).isEmpty();
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
    void oaDocumentForUnrosteredEmployeeNumberIsIgnored() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        OaDocumentRow otherNumber = new OaDocumentRow(
                "OUTING:other-number",
                "OUTING",
                null,
                "OTHER-CO",
                Instant.parse("2026-08-03T03:00:00Z"),
                Instant.parse("2026-08-03T04:00:00Z"),
                "Asia/Shanghai",
                Instant.parse("2026-08-03T02:59:00Z"),
                true);
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of(),
                List.of(otherNumber));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.calculatedFacts()).isNotEmpty();
        assertThat(command.oaDocumentFacts()).isEmpty();
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
                cutoffEndExclusive.plusDays(1),
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
                LocalDate.of(2020, 1, 1),
                null,
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
                                Instant.parse("2026-08-01T10:15:00Z"),
                                "白班")));

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
        assertThat(facts.facts().dailyFact().shiftLabel()).isEqualTo("白班");
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
                        Instant.parse("2026-08-31T22:15:00Z"),
                        "夜班")));
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
                Instant.parse("2026-09-01T04:00:00Z"),
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

        var daily = command.calculatedFacts().stream()
                .map(value -> value.facts().dailyFact())
                .filter(fact -> businessDate.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
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
    void currentExecutiveRoleExemptsDaysBeforeTheAssignmentStart() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-19T02:00:00Z");
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of(new PunchExemptionRoleIntervalRow(
                        "emp-1",
                        Instant.parse("2026-08-19T00:00:00Z"),
                        null)));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        var daily = command.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.missingPunchCount()).isZero();
        assertThat(daily.absenceMinutes()).isZero();
        assertThat(daily.firstPunchAt()).isNull();
        assertThat(command.calculatedFacts().getFirst().facts()
                .exceptionFacts()).isEmpty();
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
        assertThat(daily.firstPunchAt()).isNull();
        assertThat(daily.lastPunchAt()).isEqualTo(punchAt);
    }

    @Test
    void restDayKeepsUnconsumedSaturdayPunchesOnTheDailyFact() {
        String companyId = "company-1";
        LocalDate saturday = LocalDate.of(2026, 8, 15);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant morning = saturday.atTime(8, 30)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant afternoon = saturday.atTime(15, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                saturday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", morning),
                        new PunchEventRow("emp-1", afternoon)),
                List.of(),
                List.of());
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(saturday, "SATURDAY")));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of());

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        var daily = command.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.businessDate()).isEqualTo(saturday);
        assertThat(daily.firstPunchAt()).isEqualTo(morning);
        assertThat(daily.lastPunchAt()).isEqualTo(afternoon);
        assertThat(daily.scheduledMinutes()).isZero();
    }

    @Test
    void restDayDoesNotTakeNextMorningEarlyClockIn() {
        String companyId = "company-1";
        LocalDate sunday = LocalDate.of(2026, 8, 9);
        LocalDate monday = LocalDate.of(2026, 8, 10);
        Instant dataAsOf = Instant.parse("2026-08-24T10:00:00Z");
        Instant earlyMonday = monday.atTime(7, 28)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubSundayMonday(
                companyId,
                sunday,
                monday,
                dataAsOf,
                List.of(new PunchEventRow("emp-1", earlyMonday)),
                List.of());

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

        DailyFact sundayFact = facts.stream()
                .filter(fact -> sunday.equals(fact.businessDate()))
                .findFirst()
                .orElse(null);
        DailyFact mondayFact = facts.stream()
                .filter(fact -> monday.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        if (sundayFact != null) {
            assertThat(sundayFact.firstPunchAt()).isNotEqualTo(earlyMonday);
            assertThat(sundayFact.lastPunchAt()).isNotEqualTo(earlyMonday);
        }
        assertThat(mondayFact.firstPunchAt()).isEqualTo(earlyMonday);
        assertThat(mondayFact.missingPunchCount()).isLessThan(2);
    }

    @Test
    void overnightLeavingPunchAfterWorkDayStaysOnPreviousWorkDay() {
        String companyId = "company-1";
        LocalDate monday = LocalDate.of(2026, 8, 3);
        LocalDate tuesday = LocalDate.of(2026, 8, 4);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant mondayOn = monday.atTime(8, 30)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant overnightOff = tuesday.atTime(5, 50)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant tuesdayOn = tuesday.atTime(8, 32)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubTwoWorkDays(
                companyId,
                monday,
                tuesday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", mondayOn),
                        new PunchEventRow("emp-1", overnightOff),
                        new PunchEventRow("emp-1", tuesdayOn)));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        morningShift(monday, "seg-1"),
                        morningShift(tuesday, "seg-2")));

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

        DailyFact mondayFact = facts.stream()
                .filter(fact -> monday.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        DailyFact tuesdayFact = facts.stream()
                .filter(fact -> tuesday.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        assertThat(mondayFact.lastPunchAt()).isEqualTo(overnightOff);
        assertThat(tuesdayFact.firstPunchAt()).isEqualTo(tuesdayOn);
        assertThat(tuesdayFact.firstPunchAt()).isNotEqualTo(overnightOff);
    }

    @Test
    void nextMorningClockInAfterSixStaysOnThatWorkDay() {
        String companyId = "company-1";
        LocalDate monday = LocalDate.of(2026, 8, 3);
        LocalDate tuesday = LocalDate.of(2026, 8, 4);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant mondayOn = monday.atTime(8, 14)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant mondayOff = monday.atTime(21, 2)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant tuesdayOn = tuesday.atTime(7, 12)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubTwoWorkDays(
                companyId,
                monday,
                tuesday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", mondayOn),
                        new PunchEventRow("emp-1", mondayOff),
                        new PunchEventRow("emp-1", tuesdayOn)));

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

        DailyFact mondayFact = facts.stream()
                .filter(fact -> monday.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        DailyFact tuesdayFact = facts.stream()
                .filter(fact -> tuesday.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        assertThat(mondayFact.lastPunchAt()).isEqualTo(mondayOff);
        assertThat(tuesdayFact.firstPunchAt()).isEqualTo(tuesdayOn);
    }

    @Test
    void earlyMorningClockInWithoutEveningOffDutyIsMissedOnPreviousDay() {
        String companyId = "company-1";
        LocalDate monday = LocalDate.of(2026, 8, 3);
        LocalDate tuesday = LocalDate.of(2026, 8, 4);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant mondayOn = monday.atTime(8, 11)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant tuesdayOn = tuesday.atTime(7, 56)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubTwoWorkDays(
                companyId,
                monday,
                tuesday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", mondayOn),
                        new PunchEventRow("emp-1", tuesdayOn)));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        morningShift(monday, "seg-1"),
                        morningShift(tuesday, "seg-2")));

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
        DailyFact mondayFact = facts.stream()
                .filter(fact -> monday.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        DailyFact tuesdayFact = facts.stream()
                .filter(fact -> tuesday.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        assertThat(mondayFact.lastPunchAt()).isNull();
        assertThat(tuesdayFact.firstPunchAt()).isEqualTo(tuesdayOn);
    }

    @Test
    void jinYuliangOvernightLeavingStaysOnEleventh() {
        String companyId = "company-1";
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        LocalDate eleventh = OvernightReturnFixtures.AUG_11;
        LocalDate twelfth = OvernightReturnFixtures.AUG_12;
        stubTwoWorkDays(
                companyId,
                eleventh,
                twelfth,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", OvernightReturnFixtures.jinAug11On()),
                        new PunchEventRow(
                                "emp-1",
                                OvernightReturnFixtures.jinAug12OvernightOff()),
                        new PunchEventRow("emp-1", OvernightReturnFixtures.jinAug12On())));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        morningShift(eleventh, "seg-11"),
                        morningShift(twelfth, "seg-12")));

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
        DailyFact eleventhFact = facts.stream()
                .filter(fact -> eleventh.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        DailyFact twelfthFact = facts.stream()
                .filter(fact -> twelfth.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        assertThat(eleventhFact.lastPunchAt())
                .isEqualTo(OvernightReturnFixtures.jinAug12OvernightOff());
        assertThat(twelfthFact.firstPunchAt())
                .isEqualTo(OvernightReturnFixtures.jinAug12On());
        assertThat(twelfthFact.firstPunchAt())
                .isNotEqualTo(OvernightReturnFixtures.jinAug12OvernightOff());
        assertThat(eleventhFact.missingPunchCount()).isZero();
    }

    @Test
    void fourReturnPunchesAreNotMissing() {
        String companyId = "company-1";
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        LocalDate fifth = OvernightReturnFixtures.AUG_5;
        LocalDate sixth = OvernightReturnFixtures.AUG_6;
        stubTwoWorkDays(
                companyId,
                fifth,
                sixth,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", OvernightReturnFixtures.wangDayOn()),
                        new PunchEventRow("emp-1", OvernightReturnFixtures.wangGoHome()),
                        new PunchEventRow("emp-1", OvernightReturnFixtures.wangReturn()),
                        new PunchEventRow(
                                "emp-1", OvernightReturnFixtures.wangOvernightOff())));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        morningShift(fifth, "seg-5"),
                        morningShift(sixth, "seg-6")));

        DailyFact fifthFact = orchestrator.assemble(
                        companyId,
                        YearMonth.of(2026, 8),
                        PeriodState.OPEN,
                        "admin-1",
                        dataAsOf)
                .calculatedFacts()
                .stream()
                .map(value -> value.facts().dailyFact())
                .filter(fact -> fifth.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        assertThat(fifthFact.firstPunchAt())
                .isEqualTo(OvernightReturnFixtures.wangDayOn());
        assertThat(fifthFact.lastPunchAt())
                .isEqualTo(OvernightReturnFixtures.wangOvernightOff());
        assertThat(fifthFact.missingPunchCount()).isZero();
    }

    @Test
    void jinOvernightWithoutFormRaisesUndeclaredAndLongSpan() {
        String companyId = "company-1";
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        LocalDate eleventh = OvernightReturnFixtures.AUG_11;
        LocalDate twelfth = OvernightReturnFixtures.AUG_12;
        stubTwoWorkDays(
                companyId,
                eleventh,
                twelfth,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", OvernightReturnFixtures.jinAug11On()),
                        new PunchEventRow(
                                "emp-1",
                                OvernightReturnFixtures.jinAug12OvernightOff()),
                        new PunchEventRow("emp-1", OvernightReturnFixtures.jinAug12On())));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        morningShift(eleventh, "seg-11"),
                        morningShift(twelfth, "seg-12")));

        List<String> types = orchestrator.assemble(
                        companyId,
                        YearMonth.of(2026, 8),
                        PeriodState.OPEN,
                        "admin-1",
                        dataAsOf)
                .calculatedFacts()
                .stream()
                .filter(value -> eleventh.equals(
                        value.facts().dailyFact().businessDate()))
                .flatMap(value -> value.facts().exceptionFacts().stream())
                .map(ExceptionFact::exceptionType)
                .toList();
        assertThat(types).contains(
                "OVERTIME_DOCUMENT_MISSING_OR_LATE",
                "LONG_PUNCH_SPAN_REVIEW");
    }

    @Test
    void coveringOvernightFormDropsUndeclaredButKeepsLongSpan() {
        String companyId = "company-1";
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        LocalDate eleventh = OvernightReturnFixtures.AUG_11;
        LocalDate twelfth = OvernightReturnFixtures.AUG_12;
        stubTwoWorkDays(
                companyId,
                eleventh,
                twelfth,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", OvernightReturnFixtures.jinAug11On()),
                        new PunchEventRow(
                                "emp-1",
                                OvernightReturnFixtures.jinAug12OvernightOff())));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        morningShift(eleventh, "seg-11"),
                        morningShift(twelfth, "seg-12")));
        Mockito.when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(oaDocument(
                        "ot-jin",
                        "OVERTIME",
                        OvertimeType.PAID,
                        OvernightReturnFixtures.shanghai(
                                eleventh.atTime(18, 0)),
                        OvernightReturnFixtures.shanghai(
                                twelfth.atTime(0, 30)))));

        List<String> types = orchestrator.assemble(
                        companyId,
                        YearMonth.of(2026, 8),
                        PeriodState.OPEN,
                        "admin-1",
                        dataAsOf)
                .calculatedFacts()
                .stream()
                .filter(value -> eleventh.equals(
                        value.facts().dailyFact().businessDate()))
                .flatMap(value -> value.facts().exceptionFacts().stream())
                .map(ExceptionFact::exceptionType)
                .toList();
        assertThat(types).doesNotContain("OVERTIME_DOCUMENT_MISSING_OR_LATE");
        assertThat(types).contains("LONG_PUNCH_SPAN_REVIEW");
    }

    @Test
    void sameDayEveningOffWithoutFormIsUndeclaredNotLongSpan() {
        String companyId = "company-1";
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        LocalDate tenth = OvernightReturnFixtures.AUG_10;
        stubOneEmployeeDay(
                companyId,
                tenth,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", OvernightReturnFixtures.jinAug10On()),
                        new PunchEventRow("emp-1", OvernightReturnFixtures.jinAug10Off())),
                List.of(),
                List.of());
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(morningShift(tenth, "seg-10")));

        List<String> types = orchestrator.assemble(
                        companyId,
                        YearMonth.of(2026, 8),
                        PeriodState.OPEN,
                        "admin-1",
                        dataAsOf)
                .calculatedFacts()
                .stream()
                .filter(value -> tenth.equals(
                        value.facts().dailyFact().businessDate()))
                .flatMap(value -> value.facts().exceptionFacts().stream())
                .map(ExceptionFact::exceptionType)
                .toList();
        assertThat(types).contains("OVERTIME_DOCUMENT_MISSING_OR_LATE");
        assertThat(types).doesNotContain("LONG_PUNCH_SPAN_REVIEW");
    }

    @Test
    void clockOutAtEighteenOhFiveWithoutFormIsNotUndeclaredOvertime() {
        String companyId = "company-1";
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        LocalDate day = LocalDate.of(2026, 8, 10);
        Instant on = day.atTime(8, 30).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant off = day.atTime(18, 5).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        stubOneEmployeeDay(
                companyId,
                day,
                dataAsOf,
                List.of(new PunchEventRow("emp-1", on), new PunchEventRow("emp-1", off)),
                List.of(),
                List.of());
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(morningShift(day, "seg-10")));

        List<String> types = orchestrator.assemble(
                        companyId,
                        YearMonth.of(2026, 8),
                        PeriodState.OPEN,
                        "admin-1",
                        dataAsOf)
                .calculatedFacts()
                .stream()
                .filter(value -> day.equals(value.facts().dailyFact().businessDate()))
                .flatMap(value -> value.facts().exceptionFacts().stream())
                .map(ExceptionFact::exceptionType)
                .toList();
        assertThat(types).doesNotContain("OVERTIME_DOCUMENT_MISSING_OR_LATE");
    }

    @Test
    void clockOutAtEighteenThirtyOneWithoutFormIsUndeclaredOvertime() {
        String companyId = "company-1";
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        LocalDate day = LocalDate.of(2026, 8, 10);
        Instant on = day.atTime(8, 30).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant off = day.atTime(18, 31).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        stubOneEmployeeDay(
                companyId,
                day,
                dataAsOf,
                List.of(new PunchEventRow("emp-1", on), new PunchEventRow("emp-1", off)),
                List.of(),
                List.of());
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(morningShift(day, "seg-10")));

        List<String> types = orchestrator.assemble(
                        companyId,
                        YearMonth.of(2026, 8),
                        PeriodState.OPEN,
                        "admin-1",
                        dataAsOf)
                .calculatedFacts()
                .stream()
                .filter(value -> day.equals(value.facts().dailyFact().businessDate()))
                .flatMap(value -> value.facts().exceptionFacts().stream())
                .map(ExceptionFact::exceptionType)
                .toList();
        assertThat(types).contains("OVERTIME_DOCUMENT_MISSING_OR_LATE");
    }

    @Test
    void overnightLeavingWithoutFormIsUndeclaredOvertime() {
        String companyId = "company-1";
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        LocalDate eleventh = OvernightReturnFixtures.AUG_11;
        LocalDate twelfth = OvernightReturnFixtures.AUG_12;
        stubTwoWorkDays(
                companyId,
                eleventh,
                twelfth,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", OvernightReturnFixtures.jinAug11On()),
                        new PunchEventRow(
                                "emp-1",
                                OvernightReturnFixtures.jinAug12OvernightOff()),
                        new PunchEventRow("emp-1", OvernightReturnFixtures.jinAug12On())));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        morningShift(eleventh, "seg-11"),
                        morningShift(twelfth, "seg-12")));

        List<String> types = orchestrator.assemble(
                        companyId,
                        YearMonth.of(2026, 8),
                        PeriodState.OPEN,
                        "admin-1",
                        dataAsOf)
                .calculatedFacts()
                .stream()
                .filter(value -> eleventh.equals(
                        value.facts().dailyFact().businessDate()))
                .flatMap(value -> value.facts().exceptionFacts().stream())
                .map(ExceptionFact::exceptionType)
                .toList();
        assertThat(types).contains("OVERTIME_DOCUMENT_MISSING_OR_LATE");
    }

    @Test
    void liYinSaturdayFormPastLastPunchIsCappedAndFlagged() {
        String companyId = "company-1";
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        LocalDate friday = LocalDate.of(2026, 8, 7);
        LocalDate saturday = LocalDate.of(2026, 8, 8);
        Instant saturdayOn = saturday.atTime(8, 28)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant saturdayOff = saturday.atTime(18, 16)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant formStart = saturday.atTime(9, 30)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant formEnd = saturday.atTime(20, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubFridaySaturday(
                companyId,
                friday,
                saturday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", saturdayOn),
                        new PunchEventRow("emp-1", saturdayOff)));
        Mockito.when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(oaDocument(
                        "ot-liyin-sat",
                        "OVERTIME",
                        OvertimeType.PAID,
                        formStart,
                        formEnd)));

        var assembled = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);
        DailyFact saturdayFact = assembled.calculatedFacts()
                .stream()
                .map(value -> value.facts().dailyFact())
                .filter(fact -> saturday.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        assertThat(saturdayFact.recognizedOvertimeMinutes()).isEqualTo(450L);
        assertThat(saturdayFact.recognizedOvertimeMinutes()).isPositive();
        assertThat(assembled.calculatedFacts()
                .stream()
                .filter(value -> saturday.equals(value.facts().dailyFact().businessDate()))
                .flatMap(value -> value.facts().exceptionFacts().stream())
                .map(ExceptionFact::exceptionType))
                .contains("OVERTIME_FORM_BEYOND_LAST_PUNCH");
    }

    @Test
    void liYinWeekdayFormEndingBeforeLastPunchIsNotFlagged() {
        String companyId = "company-1";
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        LocalDate day = LocalDate.of(2026, 8, 4);
        Instant on = day.atTime(8, 28).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant off = day.atTime(22, 12).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        stubOneEmployeeDay(
                companyId,
                day,
                dataAsOf,
                List.of(new PunchEventRow("emp-1", on), new PunchEventRow("emp-1", off)),
                List.of(),
                List.of());
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(morningShift(day, "seg-4")));
        Mockito.when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(oaDocument(
                        "ot-liyin-tue",
                        "OVERTIME",
                        OvertimeType.PAID,
                        day.atTime(18, 30).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                        day.atTime(21, 30).atZone(ZoneId.of("Asia/Shanghai")).toInstant())));

        var assembled = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);
        DailyFact fact = assembled.calculatedFacts()
                .stream()
                .map(value -> value.facts().dailyFact())
                .filter(value -> day.equals(value.businessDate()))
                .findFirst()
                .orElseThrow();
        assertThat(fact.recognizedOvertimeMinutes()).isEqualTo(180L);
        assertThat(assembled.calculatedFacts()
                .stream()
                .flatMap(value -> value.facts().exceptionFacts().stream())
                .map(ExceptionFact::exceptionType))
                .doesNotContain("OVERTIME_FORM_BEYOND_LAST_PUNCH");
    }

    @Test
    void duplicateOvertimeFormsCountHoursOnce() {
        String companyId = "company-1";
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        LocalDate friday = LocalDate.of(2026, 8, 7);
        LocalDate saturday = LocalDate.of(2026, 8, 8);
        Instant saturdayOn = saturday.atTime(8, 28)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant saturdayOff = saturday.atTime(20, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant formStart = saturday.atTime(9, 30)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant formEnd = saturday.atTime(20, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubFridaySaturday(
                companyId,
                friday,
                saturday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", saturdayOn),
                        new PunchEventRow("emp-1", saturdayOff)));
        Mockito.when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(
                        oaDocument("ot-a", "OVERTIME", OvertimeType.PAID, formStart, formEnd),
                        oaDocument("ot-b", "OVERTIME", OvertimeType.PAID, formStart, formEnd)));

        DailyFact saturdayFact = orchestrator.assemble(
                        companyId,
                        YearMonth.of(2026, 8),
                        PeriodState.OPEN,
                        "admin-1",
                        dataAsOf)
                .calculatedFacts()
                .stream()
                .map(value -> value.facts().dailyFact())
                .filter(fact -> saturday.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        assertThat(saturdayFact.recognizedOvertimeMinutes()).isEqualTo(540L);
    }

    @Test
    void fridayNightIntoSaturdayHoursStayOnFriday() {
        String companyId = "company-1";
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        LocalDate friday = LocalDate.of(2026, 8, 7);
        LocalDate saturday = LocalDate.of(2026, 8, 8);
        Instant fridayOn = friday.atTime(8, 30)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant fridayOff = saturday.atTime(2, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubFridaySaturday(
                companyId,
                friday,
                saturday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", fridayOn),
                        new PunchEventRow("emp-1", fridayOff)));
        Mockito.when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(oaDocument(
                        "ot-overnight",
                        "OVERTIME",
                        OvertimeType.PAID,
                        friday.atTime(21, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                        saturday.atTime(2, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant())));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        morningShift(friday, "seg-fri"),
                        morningShift(saturday, "seg-sat")));

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
        DailyFact fridayFact = facts.stream()
                .filter(fact -> friday.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        DailyFact saturdayFact = facts.stream()
                .filter(fact -> saturday.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        assertThat(fridayFact.recognizedOvertimeMinutes()).isEqualTo(300L);
        assertThat(saturdayFact.recognizedOvertimeMinutes()).isZero();
    }

    @Test
    void fridayDoesNotTakeSaturdayMorningClockIn() {
        String companyId = "company-1";
        LocalDate friday = LocalDate.of(2026, 8, 7);
        LocalDate saturday = LocalDate.of(2026, 8, 8);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant fridayOn = friday.atTime(8, 13)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant fridayOff = friday.atTime(18, 10)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant saturdayOn = saturday.atTime(8, 26)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubFridaySaturday(
                companyId,
                friday,
                saturday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", fridayOn),
                        new PunchEventRow("emp-1", fridayOff),
                        new PunchEventRow("emp-1", saturdayOn)));

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

        DailyFact fridayFact = facts.stream()
                .filter(fact -> friday.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        DailyFact saturdayFact = facts.stream()
                .filter(fact -> saturday.equals(fact.businessDate()))
                .findFirst()
                .orElseThrow();
        assertThat(fridayFact.lastPunchAt()).isEqualTo(fridayOff);
        assertThat(saturdayFact.firstPunchAt()).isEqualTo(saturdayOn);
    }

    @Test
    void extraMorningAndAfternoonPunchesKeepEarliestOnAndLatestOff() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant firstOn = businessDate.atTime(8, 13)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant extraOn = businessDate.atTime(8, 24)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant lunch = businessDate.atTime(12, 5)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant extraOff = businessDate.atTime(18, 1)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant lastOff = businessDate.atTime(18, 10)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", firstOn),
                        new PunchEventRow("emp-1", extraOn),
                        new PunchEventRow("emp-1", lunch),
                        new PunchEventRow("emp-1", extraOff),
                        new PunchEventRow("emp-1", lastOff)),
                List.of(),
                List.of());
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(businessDate, "WEEKDAY")));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(morningShift(businessDate, "seg-1")));

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

        assertThat(daily.firstPunchAt()).isEqualTo(firstOn);
        assertThat(daily.lastPunchAt()).isEqualTo(lastOff);
        assertThat(daily.missingPunchCount()).isZero();
    }

    @Test
    void twoMorningPunchesWithoutAfternoonAreOffDutyMiss() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 11);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant firstOn = businessDate.atTime(8, 13)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant extraOn = businessDate.atTime(8, 24)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", firstOn),
                        new PunchEventRow("emp-1", extraOn)),
                List.of(),
                List.of());
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(businessDate, "WEEKDAY")));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(morningShift(businessDate, "seg-1")));

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

        assertThat(daily.firstPunchAt()).isEqualTo(firstOn);
        assertThat(daily.lastPunchAt()).isNull();
        assertThat(daily.missingPunchCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void restDayKeepsEarliestAndLatestPunchesEvenAfterNoon() {
        String companyId = "company-1";
        LocalDate saturday = LocalDate.of(2026, 8, 8);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant first = saturday.atTime(15, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant last = saturday.atTime(18, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                saturday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", first),
                        new PunchEventRow("emp-1", last)),
                List.of(),
                List.of());
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(saturday, "SATURDAY")));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of());

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

        assertThat(daily.firstPunchAt()).isEqualTo(first);
        assertThat(daily.lastPunchAt()).isEqualTo(last);
    }

    @Test
    void approvedOaMakeupPunchCountsAsOnDutyClock() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 10);
        Instant dataAsOf = Instant.parse("2026-08-24T10:00:00Z");
        Instant makeup = businessDate.atTime(8, 30)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant offDuty = businessDate.atTime(18, 1)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(new PunchEventRow("emp-1", offDuty)),
                List.of(),
                List.of(),
                List.of(oaDocument(
                        "PUNCH_CORRECTION:204",
                        "PUNCH_CORRECTION",
                        makeup,
                        makeup)));
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(businessDate, "WEEKDAY")));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(segment(businessDate, "seg-1")));

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
        assertThat(daily.firstPunchAt()).isEqualTo(makeup);
        assertThat(daily.lastPunchAt()).isEqualTo(offDuty);
        assertThat(daily.missingPunchCount()).isZero();
    }

    @Test
    void approvedOaMakeupIsCopiedAsPointFactForMonthMatrix() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 10);
        Instant dataAsOf = Instant.parse("2026-08-24T10:00:00Z");
        Instant makeup = businessDate.atTime(18, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(businessDate, "WEEKDAY")));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(segment(businessDate, "seg-1")));
        Mockito.when(mapper.findReportableOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new OaReportFactRow(
                        "oa-makeup-1",
                        "PUNCH_CORRECTION:204",
                        "PUNCH_CORRECTION",
                        null,
                        "emp-1",
                        "E001",
                        "assignment-1",
                        makeup,
                        null,
                        "APPROVED",
                        "oa-v1")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.documentType()).isEqualTo("PUNCH_CORRECTION");
            assertThat(fact.temporalShape()).isEqualTo(OaTemporalShape.POINT);
            assertThat(fact.pointInstant()).isEqualTo(makeup);
            assertThat(fact.intervalStart()).isNull();
            assertThat(fact.recognizedMinutes()).isZero();
        });
    }

    @Test
    void hrMakeupAndOutingAreCopiedAsOaFactsForMonthMatrix() {
        String companyId = "company-1";
        LocalDate businessDate = LocalDate.of(2026, 8, 25);
        Instant dataAsOf = Instant.parse("2026-08-26T10:00:00Z");
        Instant onDuty = businessDate.atTime(8, 30)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                businessDate,
                dataAsOf,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(businessDate, "WEEKDAY")));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(segment(businessDate, "seg-1")));
        Mockito.when(mapper.findHrPunchAdjustments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new HrPunchAdjustmentRow(
                        "adj-1",
                        "emp-1",
                        businessDate,
                        onDuty,
                        null,
                        dataAsOf.minusSeconds(60),
                        240,
                        "MISSING_PUNCH",
                        "OUTING")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.oaDocumentFacts())
                .anySatisfy(fact -> {
                    assertThat(fact.documentType()).isEqualTo("PUNCH_CORRECTION");
                    assertThat(fact.temporalShape()).isEqualTo(OaTemporalShape.POINT);
                    assertThat(fact.pointInstant()).isEqualTo(onDuty);
                    assertThat(fact.sourceOrigin()).isEqualTo("PAPER");
                })
                .anySatisfy(fact -> {
                    assertThat(fact.documentType()).isEqualTo("OUTING");
                    assertThat(fact.temporalShape()).isEqualTo(OaTemporalShape.INTERVAL);
                    assertThat(fact.sourceStatus()).isEqualTo("APPROVED");
                    assertThat(fact.sourceOrigin()).isEqualTo("PAPER");
                });
    }

    @Test
    void approvedOutingWithoutPunchGrantsAttendance() {
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
        assertThat(daily.actualAttendanceDays()).isEqualTo(1);
        assertThat(daily.absenceMinutes()).isZero();
        assertThat(daily.missingPunchCount()).isZero();
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
                        Instant.parse("2026-08-03T11:00:00Z"),
                        "白班")));

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
        assertThat(daily.actualAttendanceDays()).isEqualTo(1);
        assertThat(daily.absenceMinutes()).isZero();
        assertThat(daily.lateMinutes()).isGreaterThan(0);
        assertThat(calculated.facts().exceptionFacts())
                .extracting(value -> value.exceptionType())
                .contains("LATE");
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
        assertThat(facts.get(1).lateMinutes()).isEqualTo(30);
        assertThat(facts.get(1).absenceMinutes()).isZero();
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

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);
        assertThat(command.calculatedFacts()).isEmpty();
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

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);
        assertThat(command.calculatedFacts()).isEmpty();
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

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);
        assertThat(command.calculatedFacts()).isEmpty();
    }

    @Test
    void approvedLeaveRevocationReplacesOriginalLeaveInterval() {
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

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.calculatedFacts()).isNotEmpty();
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
    void restDayOvertimeHoursAreNotZeroedByMissingShiftOverlap() {
        String companyId = "company-1";
        LocalDate saturday = LocalDate.of(2026, 8, 15);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant start = saturday.atTime(9, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant end = saturday.atTime(17, 30)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                saturday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", start),
                        new PunchEventRow("emp-1", end)),
                List.of(),
                List.of(),
                List.of(new OaDocumentRow(
                        "ot-sat",
                        "OVERTIME",
                        OvertimeType.PAID,
                        "E001",
                        start,
                        end,
                        "Asia/Shanghai",
                        start,
                        true)));
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(saturday, "WEEKEND")));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of());
        Mockito.when(mapper.findReportableOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new OaReportFactRow(
                        "oa-ot-1",
                        "ot-sat",
                        "OVERTIME",
                        null,
                        "emp-1",
                        "E001",
                        "assignment-1",
                        start,
                        end,
                        "APPROVED",
                        "oa-v1")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.recognizedMinutes()).isEqualTo(450L);
            assertThat(fact.intervalStart()).isEqualTo(start);
            assertThat(fact.intervalEndExclusive()).isEqualTo(end);
        });
        assertThat(command.calculatedFacts()).isNotEmpty();
        DailyFact daily = command.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.recognizedOvertimeMinutes()).isEqualTo(450L);
        assertThat(daily.paidOvertimeMinutes()).isEqualTo(450L);
    }

    @Test
    void weekdayAfterShiftOvertimeDoesNotNeedWorkSegmentOverlap() {
        String companyId = "company-1";
        LocalDate weekday = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant otStart = weekday.atTime(18, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant otEnd = weekday.atTime(21, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant inPunch = weekday.atTime(9, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                weekday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", inPunch),
                        new PunchEventRow("emp-1", otEnd)),
                List.of(),
                List.of(),
                List.of(new OaDocumentRow(
                        "ot-eve",
                        "OVERTIME",
                        OvertimeType.PAID,
                        "E001",
                        otStart,
                        otEnd,
                        "Asia/Shanghai",
                        otStart,
                        true)));
        Mockito.when(mapper.findReportableOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new OaReportFactRow(
                        "oa-ot-2",
                        "ot-eve",
                        "OVERTIME",
                        null,
                        "emp-1",
                        "E001",
                        "assignment-1",
                        otStart,
                        otEnd,
                        "APPROVED",
                        "oa-v1")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact ->
                assertThat(fact.recognizedMinutes()).isEqualTo(150L));
        DailyFact daily = command.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.paidOvertimeMinutes()).isEqualTo(150L);
        assertThat(daily.recognizedOvertimeMinutes()).isEqualTo(150L);
    }

    @Test
    void lastPunchAt2002CapsWeekdayEveningFormToOneAndAHalfHours() {
        String companyId = "company-1";
        LocalDate weekday = LocalDate.of(2026, 8, 26);
        Instant dataAsOf = Instant.parse("2026-08-28T10:00:00Z");
        Instant otStart = weekday.atTime(18, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant otEnd = weekday.atTime(21, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant lastPunch = weekday.atTime(20, 2)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                weekday,
                dataAsOf,
                List.of(
                        new PunchEventRow(
                                "emp-1",
                                weekday.atTime(8, 24)
                                        .atZone(ZoneId.of("Asia/Shanghai"))
                                        .toInstant()),
                        new PunchEventRow("emp-1", lastPunch)),
                List.of(),
                List.of(),
                List.of(new OaDocumentRow(
                        "ot-lixin",
                        "OVERTIME",
                        OvertimeType.PAID,
                        "E001",
                        otStart,
                        otEnd,
                        "Asia/Shanghai",
                        otStart,
                        true)));
        Mockito.when(mapper.findReportableOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new OaReportFactRow(
                        "oa-ot-lixin",
                        "ot-lixin",
                        "OVERTIME",
                        null,
                        "emp-1",
                        "E001",
                        "assignment-1",
                        otStart,
                        otEnd,
                        "APPROVED",
                        "oa-v1")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact ->
                assertThat(fact.recognizedMinutes()).isEqualTo(90L));
        DailyFact daily = command.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.paidOvertimeMinutes()).isEqualTo(90L);
    }

    @Test
    void weekdayEveningOvertimeHoursDoNotNeedPunches() {
        String companyId = "company-1";
        LocalDate weekday = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant otStart = weekday.atTime(18, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant otEnd = weekday.atTime(21, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                weekday,
                dataAsOf,
                List.of(),
                List.of(),
                List.of(),
                List.of(new OaDocumentRow(
                        "ot-eve",
                        "OVERTIME",
                        OvertimeType.PAID,
                        "E001",
                        otStart,
                        otEnd,
                        "Asia/Shanghai",
                        otStart,
                        true)));
        Mockito.when(mapper.findReportableOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new OaReportFactRow(
                        "oa-ot-2",
                        "ot-eve",
                        "OVERTIME",
                        null,
                        "emp-1",
                        "E001",
                        "assignment-1",
                        otStart,
                        otEnd,
                        "APPROVED",
                        "oa-v1")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact ->
                assertThat(fact.recognizedMinutes()).isEqualTo(150L));
        DailyFact daily = command.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.paidOvertimeMinutes()).isEqualTo(150L);
        assertThat(daily.recognizedOvertimeMinutes()).isEqualTo(150L);
    }

    @Test
    void septemberEveningOvertimeWithoutPunchIsZero() {
        String companyId = "company-1";
        LocalDate weekday = LocalDate.of(2026, 9, 2);
        Instant dataAsOf = Instant.parse("2026-09-04T10:00:00Z");
        Instant otStart = weekday.atTime(18, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant otEnd = weekday.atTime(21, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                weekday,
                dataAsOf,
                List.of(),
                List.of(),
                List.of(),
                List.of(new OaDocumentRow(
                        "ot-eve",
                        "OVERTIME",
                        OvertimeType.PAID,
                        "E001",
                        otStart,
                        otEnd,
                        "Asia/Shanghai",
                        otStart,
                        true)));
        Mockito.when(mapper.findReportableOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new OaReportFactRow(
                        "oa-ot-2",
                        "ot-eve",
                        "OVERTIME",
                        null,
                        "emp-1",
                        "E001",
                        "assignment-1",
                        otStart,
                        otEnd,
                        "APPROVED",
                        "oa-v1")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 9),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact ->
                assertThat(fact.recognizedMinutes()).isEqualTo(0L));
        DailyFact daily = command.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.paidOvertimeMinutes()).isEqualTo(0L);
    }

    @Test
    void weekdayEveningOvertimeIgnoresLastPunchTail() {
        String companyId = "company-1";
        LocalDate weekday = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant otStart = weekday.atTime(18, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant otEnd = weekday.atTime(21, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant inPunch = weekday.atTime(9, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant latePunch = weekday.atTime(21, 12)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                weekday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", inPunch),
                        new PunchEventRow("emp-1", latePunch)),
                List.of(),
                List.of(),
                List.of(new OaDocumentRow(
                        "ot-eve",
                        "OVERTIME",
                        OvertimeType.PAID,
                        "E001",
                        otStart,
                        otEnd,
                        "Asia/Shanghai",
                        otStart,
                        true)));
        Mockito.when(mapper.findReportableOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new OaReportFactRow(
                        "oa-ot-2",
                        "ot-eve",
                        "OVERTIME",
                        null,
                        "emp-1",
                        "E001",
                        "assignment-1",
                        otStart,
                        otEnd,
                        "APPROVED",
                        "oa-v1")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        DailyFact daily = command.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.paidOvertimeMinutes()).isEqualTo(150L);
        assertThat(daily.recognizedOvertimeMinutes()).isEqualTo(150L);
    }

    @Test
    void weekdayFormCoveringShiftStartRecognizesTwoAndAHalfHours() {
        String companyId = "company-1";
        LocalDate weekday = LocalDate.of(2026, 8, 4);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        Instant otStart = weekday.atTime(8, 30).atZone(shanghai).toInstant();
        Instant otEnd = weekday.atTime(21, 0).atZone(shanghai).toInstant();
        stubOneEmployeeDay(
                companyId,
                weekday,
                dataAsOf,
                List.of(
                        new PunchEventRow(
                                "emp-1",
                                weekday.atTime(8, 28).atZone(shanghai).toInstant()),
                        new PunchEventRow(
                                "emp-1",
                                weekday.atTime(21, 0).atZone(shanghai).toInstant())),
                List.of(),
                List.of(),
                List.of(new OaDocumentRow(
                        "ot-day",
                        "OVERTIME",
                        OvertimeType.PAID,
                        "E001",
                        otStart,
                        otEnd,
                        "Asia/Shanghai",
                        otStart,
                        true)));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        new ShiftSegmentRow(
                                "emp-1",
                                weekday,
                                "seg-am",
                                weekday.atTime(8, 30).atZone(shanghai).toInstant(),
                                weekday.atTime(12, 0).atZone(shanghai).toInstant(),
                                weekday.atTime(8, 0).atZone(shanghai).toInstant(),
                                weekday.atTime(9, 0).atZone(shanghai).toInstant(),
                                weekday.atTime(11, 30).atZone(shanghai).toInstant(),
                                weekday.atTime(12, 30).atZone(shanghai).toInstant(),
                                "白班"),
                        new ShiftSegmentRow(
                                "emp-1",
                                weekday,
                                "seg-pm",
                                weekday.atTime(13, 0).atZone(shanghai).toInstant(),
                                weekday.atTime(18, 0).atZone(shanghai).toInstant(),
                                weekday.atTime(12, 30).atZone(shanghai).toInstant(),
                                weekday.atTime(13, 30).atZone(shanghai).toInstant(),
                                weekday.atTime(17, 30).atZone(shanghai).toInstant(),
                                weekday.atTime(18, 30).atZone(shanghai).toInstant(),
                                "白班")));
        Mockito.when(mapper.findReportableOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new OaReportFactRow(
                        "oa-ot-day",
                        "ot-day",
                        "OVERTIME",
                        null,
                        "emp-1",
                        "E001",
                        "assignment-1",
                        otStart,
                        otEnd,
                        "APPROVED",
                        "oa-v1")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact ->
                assertThat(fact.recognizedMinutes()).isEqualTo(150L));
        assertThat(command.calculatedFacts().getFirst().facts().dailyFact()
                .recognizedOvertimeMinutes()).isEqualTo(150L);
    }

    @Test
    void saturdayDaytimeOvertimeIgnoresOddPunchCount() {
        String companyId = "company-1";
        LocalDate saturday = LocalDate.of(2026, 8, 15);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant start = saturday.atTime(8, 30)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant mid = saturday.atTime(12, 10)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant end = saturday.atTime(17, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                saturday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", start),
                        new PunchEventRow("emp-1", mid),
                        new PunchEventRow("emp-1", end)),
                List.of(),
                List.of(),
                List.of(new OaDocumentRow(
                        "ot-sat",
                        "OVERTIME",
                        OvertimeType.PAID,
                        "E001",
                        start,
                        end,
                        "Asia/Shanghai",
                        start,
                        true)));
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(saturday, "WEEKEND")));
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of());
        Mockito.when(mapper.findReportableOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new OaReportFactRow(
                        "oa-ot-sat",
                        "ot-sat",
                        "OVERTIME",
                        null,
                        "emp-1",
                        "E001",
                        "assignment-1",
                        start,
                        end,
                        "APPROVED",
                        "oa-v1")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact ->
                assertThat(fact.recognizedMinutes()).isEqualTo(450L));
    }

    @Test
    void saturdayEveningOvertimeDoesNotDeductLunch() {
        String companyId = "company-1";
        LocalDate saturday = LocalDate.of(2026, 8, 15);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant start = saturday.atTime(18, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant end = saturday.atTime(21, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                saturday,
                dataAsOf,
                List.of(new PunchEventRow("emp-1", end)),
                List.of(),
                List.of(),
                List.of(new OaDocumentRow(
                        "ot-sat-eve",
                        "OVERTIME",
                        OvertimeType.PAID,
                        "E001",
                        start,
                        end,
                        "Asia/Shanghai",
                        start,
                        true)));
        Mockito.when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(saturday, "WEEKEND")));
        Mockito.when(mapper.findReportableOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new OaReportFactRow(
                        "oa-ot-sat-eve",
                        "ot-sat-eve",
                        "OVERTIME",
                        null,
                        "emp-1",
                        "E001",
                        "assignment-1",
                        start,
                        end,
                        "APPROVED",
                        "oa-v1")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact ->
                assertThat(fact.recognizedMinutes()).isEqualTo(150L));
    }

    @Test
    void fakeOvertimeStillProjectsExceptionWithoutZeroingFormHours() {
        String companyId = "company-1";
        LocalDate weekday = LocalDate.of(2026, 8, 3);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant inPunch = weekday.atTime(9, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant start = weekday.atTime(17, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant end = weekday.atTime(21, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        stubOneEmployeeDay(
                companyId,
                weekday,
                dataAsOf,
                List.of(
                        new PunchEventRow("emp-1", inPunch),
                        new PunchEventRow("emp-1", end)),
                List.of(),
                List.of(),
                List.of(new OaDocumentRow(
                        "ot-fake",
                        "OVERTIME",
                        OvertimeType.PAID,
                        "E001",
                        start,
                        end,
                        "Asia/Shanghai",
                        start,
                        true)));
        Mockito.when(mapper.findReportableOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new OaReportFactRow(
                        "oa-ot-fake",
                        "ot-fake",
                        "OVERTIME",
                        null,
                        "emp-1",
                        "E001",
                        "assignment-1",
                        start,
                        end,
                        "APPROVED",
                        "oa-v1")));

        PublishCommand command = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf);

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact ->
                assertThat(fact.recognizedMinutes()).isEqualTo(150L));
        assertThat(command.calculatedFacts().getFirst().facts().exceptionFacts())
                .extracting(fact -> fact.exceptionType())
                .contains("FAKE_OVERTIME");
    }

    @Test
    void leaveWithoutShiftStillRecognizesStandardWorkWindows() {
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
                List.of());
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of());
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

        assertThat(command.oaDocumentFacts()).singleElement().satisfies(fact ->
                assertThat(fact.recognizedMinutes()).isZero());
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

    @Test
    void partialWindowCountsStoredLateDaysBeforeTheWriteStart() {
        String companyId = "company-1";
        LocalDate windowStart = LocalDate.of(2026, 8, 24);
        Instant dataAsOf = Instant.parse("2026-08-26T00:00:00Z");
        stubOneEmployeeDay(
                companyId,
                windowStart,
                dataAsOf,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        Mockito.when(mapper.countLateDaysBeforeWindow(
                        companyId,
                        LocalDate.of(2026, 8, 1),
                        windowStart))
                .thenReturn(List.of(new EmployeeLateDayCountRow("emp-1", 1)));

        orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf,
                windowStart,
                LocalDate.of(2026, 8, 27));

        Mockito.verify(mapper).countLateDaysBeforeWindow(
                companyId,
                LocalDate.of(2026, 8, 1),
                windowStart);
    }

    @Test
    void leaveDayWithoutOffDutyPunchGetsShiftEndPlusOneMinuteAndIsNotMissing() {
        String companyId = "company-1";
        LocalDate leaveDay = LocalDate.of(2026, 8, 11);
        LocalDate endExclusive = leaveDay.plusDays(1);
        Instant dataAsOf = Instant.parse("2026-08-17T10:00:00Z");
        Instant onDuty = leaveDay.atTime(8, 30)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant expectedOff = leaveDay.atTime(18, 1)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Mockito.when(mapper.findEmployeeIdentityIntervals(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(
                        "emp-1",
                        "emp-v-1",
                        "SZST0674",
                        "徐利民",
                        "assignment-1",
                        "org-1",
                        "org-v-1",
                        "工程二部-RF-F组",
                        LocalDate.of(2020, 1, 1),
                        endExclusive,
                        LocalDate.of(2020, 1, 1),
                        endExclusive,
                        LocalDate.of(2020, 1, 1),
                        endExclusive)));
        Mockito.when(mapper.findActivatedPunchEvents(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(new PunchEventRow("emp-1", onDuty)));
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
        Mockito.lenient().when(mapper.findPublishedCalendarDays(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(new CalendarDayRow(leaveDay, "WEEKDAY")));
        Mockito.lenient().when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(morningShift(leaveDay, "seg-1")));
        Mockito.lenient().when(mapper.findAttendancePolicies(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(policy("emp-1", leaveDay)));

        var calculated = orchestrator.assemble(
                companyId,
                YearMonth.of(2026, 8),
                PeriodState.OPEN,
                "admin-1",
                dataAsOf)
                .calculatedFacts()
                .getFirst();
        var daily = calculated.facts().dailyFact();
        assertThat(daily.businessDate()).isEqualTo(leaveDay);
        assertThat(daily.firstPunchAt()).isEqualTo(onDuty);
        assertThat(daily.lastPunchAt()).isEqualTo(expectedOff);
        assertThat(daily.missingPunchCount()).isZero();
        assertThat(daily.shiftLabel()).contains("离职");
        assertThat(calculated.facts().exceptionFacts())
                .extracting(ExceptionFact::exceptionType)
                .noneMatch(type -> type != null && type.contains("MISSING"));
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
                        LocalDate.of(2020, 1, 1),
                        nextDay,
                        LocalDate.of(2020, 1, 1),
                        null,
                        LocalDate.of(2020, 1, 1),
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
        Mockito.lenient().when(mapper.findEffectiveOaDocuments(
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
                .thenReturn(List.of(segment(businessDate, "seg-1")));
        Mockito.lenient().when(mapper.findAttendancePolicies(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(policy("emp-1", businessDate)));
    }

    private void stubSundayMonday(
            String companyId,
            LocalDate sunday,
            LocalDate monday,
            Instant dataAsOf,
            List<PunchEventRow> punches,
            List<OaDocumentRow> oaDocuments) {
        LocalDate endExclusive = monday.plusDays(1);
        Mockito.lenient().when(mapper.findEmployeeIdentityIntervals(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(
                        "emp-1",
                        "emp-v-1",
                        "E001",
                        "吴根银",
                        "assignment-1",
                        "org-1",
                        "org-v-1",
                        "行政部",
                        sunday,
                        endExclusive,
                        sunday,
                        null,
                        sunday,
                        endExclusive)));
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
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        new CalendarDayRow(sunday, "SUNDAY"),
                        new CalendarDayRow(monday, "WEEKDAY")));
        Mockito.when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(oaDocuments);
        Mockito.when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(morningShift(monday, "seg-mon")));
        Mockito.when(mapper.findAttendancePolicies(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(
                        policy("emp-1", sunday),
                        policy("emp-1", monday)));
    }

    private void stubFridaySaturday(
            String companyId,
            LocalDate friday,
            LocalDate saturday,
            Instant dataAsOf,
            List<PunchEventRow> punches) {
        LocalDate endExclusive = saturday.plusDays(1);
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
                        LocalDate.of(2020, 1, 1),
                        endExclusive,
                        LocalDate.of(2020, 1, 1),
                        null,
                        LocalDate.of(2020, 1, 1),
                        endExclusive)));
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
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        new CalendarDayRow(friday, "WEEKDAY"),
                        new CalendarDayRow(saturday, "SATURDAY")));
        Mockito.lenient().when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(segment(friday, "seg-fri")));
        Mockito.when(mapper.findAttendancePolicies(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(
                        policy("emp-1", friday),
                        policy("emp-1", saturday)));
    }

    private void stubTwoWorkDays(
            String companyId,
            LocalDate first,
            LocalDate second,
            Instant dataAsOf,
            List<PunchEventRow> punches) {
        LocalDate endExclusive = second.plusDays(1);
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
                        LocalDate.of(2020, 1, 1),
                        endExclusive,
                        LocalDate.of(2020, 1, 1),
                        null,
                        LocalDate.of(2020, 1, 1),
                        endExclusive)));
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
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        new CalendarDayRow(first, "WEEKDAY"),
                        new CalendarDayRow(second, "WEEKDAY")));
        Mockito.lenient().when(mapper.findEffectiveOaDocuments(
                        Mockito.eq(companyId),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of());
        Mockito.lenient().when(mapper.findScheduledWorkSegments(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.any(Instant.class)))
                .thenReturn(List.of(
                        segment(first, "seg-1"),
                        segment(second, "seg-2")));
        Mockito.when(mapper.findAttendancePolicies(
                        Mockito.eq(companyId),
                        Mockito.any(LocalDate.class),
                        Mockito.any(LocalDate.class),
                        Mockito.eq(dataAsOf)))
                .thenReturn(List.of(
                        policy("emp-1", first),
                        policy("emp-1", second)));
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

    private static ShiftSegmentRow morningShift(
            LocalDate businessDate,
            String segmentId) {
        Instant start = businessDate
                .atTime(8, 30)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant end = businessDate
                .atTime(18, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant arrivalStart = businessDate
                .atTime(6, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        return new ShiftSegmentRow(
                "emp-1",
                businessDate,
                segmentId,
                start,
                end,
                arrivalStart,
                start.plusSeconds(60 * 60),
                end.minusSeconds(60 * 60),
                end.plusSeconds(60 * 60),
                "白班");
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
                end.plusSeconds(60 * 60),
                "白班");
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
        return oaDocument(sourceBusinessKey, documentType, null, start, end);
    }

    private static OaDocumentRow oaDocument(
            String sourceBusinessKey,
            String documentType,
            OvertimeType overtimeType,
            Instant start,
            Instant end) {
        return new OaDocumentRow(
                sourceBusinessKey,
                documentType,
                overtimeType,
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
