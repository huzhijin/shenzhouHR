package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.AttendancePolicyRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.EmployeeIdentityIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaDocumentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaReportFactRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchEventRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.SourceInputVersionRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real engine regression: mutable numbers cannot override a source's matched employee ID. */
class OaMatchedEmployeeScopeTest {
    private static final String COMPANY = "target-company";
    private static final String EMPLOYEE = "target-employee";
    private static final String NUMBER = "SZST0687";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
    private static final Instant CUTOFF = Instant.parse("2026-10-04T00:00:00Z");
    private AttendanceReportCalculationMapper mapper;
    private FullCalculationEngineOrchestrator engine;

    @BeforeEach
    void targetRosterAndAnOrdinaryWorkday() {
        mapper = mock(AttendanceReportCalculationMapper.class);
        engine = new FullCalculationEngineOrchestrator(mapper);
        when(mapper.findAttendanceSourceVersions(COMPANY, CUTOFF)).thenReturn(List.of(
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "a".repeat(64), CUTOFF),
                new SourceInputVersionRow("shared-oa", "OA_ATTENDANCE", 1, 1, 1L, "b".repeat(64), CUTOFF)));
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(EMPLOYEE, "employee-version", NUMBER,
                        "Target Employee", "assignment", "organization", "organization-version", "研发部",
                        DATE.withDayOfMonth(1), DATE.plusDays(1), DATE.withDayOfMonth(1), null,
                        DATE.withDayOfMonth(1), DATE.plusDays(1))));
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(List.of(new CalendarDayRow(EMPLOYEE, DATE, "WEEKDAY", 1, 1)));
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(List.of(new ShiftSegmentRow(EMPLOYEE, DATE, "shift", at(9, 0), at(18, 0),
                        at(8, 0), at(10, 0), at(17, 0), at(19, 0), "白班")));
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(List.of(new AttendancePolicyRow(EMPLOYEE, DATE, 1, 1, true, 15,
                        1, true, 15, 1, false, 1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS", 1, true, 48)));
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new PunchEventRow(EMPLOYEE, at(9, 0)), new PunchEventRow(EMPLOYEE, at(21, 0))));
        // Employee-scoped runs push the set into SQL; serve the same company rows
        // so the orchestrator's in-memory guard is still exercised.
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF),
                anyCollection())).thenAnswer(inv -> mapper.findPublishedCalendarDays(COMPANY,
                        inv.getArgument(1), inv.getArgument(2), CUTOFF));
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF),
                anyCollection())).thenAnswer(inv -> mapper.findScheduledWorkSegments(COMPANY,
                        inv.getArgument(1), inv.getArgument(2), CUTOFF));
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF),
                anyCollection())).thenAnswer(inv -> mapper.findAttendancePolicies(COMPANY,
                        inv.getArgument(1), inv.getArgument(2), CUTOFF));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SZST0687", "SZT0687"})
    void foreignMatchedOvertimeCannotInflateOrReplaceTargetOvertimeAfterNumberChanges(String foreignNumber) {
        var target = document("OVERTIME:target", "OVERTIME", EMPLOYEE, NUMBER, at(18, 0), at(19, 0));
        documents(List.of(target));
        PublishCommand before = assemble(List.of());
        var foreign = document("OVERTIME:foreign", "OVERTIME", "foreign-employee", foreignNumber,
                at(18, 0), at(21, 0));
        documents(List.of(target, foreign));

        PublishCommand after = assemble(List.of());

        assertThat(after.calculatedFacts()).isEqualTo(before.calculatedFacts());
        assertThat(after.oaDocumentFacts()).isEqualTo(before.oaDocumentFacts());
        assertThat(after.oaDocumentFacts()).singleElement()
                .satisfies(fact -> assertThat(fact.employeeId()).isEqualTo(EMPLOYEE));
        assertThat(after.calculatedFacts().getFirst().facts().dailyFact().paidOvertimeMinutes()).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SZST0687", "SZT0687"})
    void foreignMatchedCorrectionCannotRepairTargetMissingPunchOrAppearInItsReport(String foreignNumber) {
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new PunchEventRow(EMPLOYEE, at(18, 0))));
        documents(List.of());
        PublishCommand before = assemble(List.of());
        documents(List.of(document("PUNCH_CORRECTION:foreign", "PUNCH_CORRECTION", "foreign-employee",
                foreignNumber, at(9, 0), at(9, 0))));

        PublishCommand after = assemble(List.of());

        assertThat(after.calculatedFacts()).isEqualTo(before.calculatedFacts());
        assertThat(after.oaDocumentFacts()).isEmpty();
        assertThat(after.calculatedFacts().getFirst().facts().dailyFact().missingPunchCount()).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SZST0687", "SZT0687"})
    void sameMatchedEmployeeRetainsOvertimeAndAliasInCompanyAndBatchWindows(String number) {
        documents(List.of(document("OVERTIME:target", "OVERTIME", EMPLOYEE, number, at(18, 0), at(20, 0))));

        PublishCommand company = assemble(List.of());
        // Two scoped IDs use the batched company read; only the real target has a roster row.
        PublishCommand batch = assemble(List.of(EMPLOYEE, "another-target-employee"));

        assertThat(company.oaDocumentFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.employeeId()).isEqualTo(EMPLOYEE);
            assertThat(fact.recognizedMinutes()).isPositive();
        });
        assertThat(company.calculatedFacts().getFirst().facts().dailyFact().paidOvertimeMinutes()).isPositive();
        assertThat(batch.oaDocumentFacts()).isEqualTo(company.oaDocumentFacts());
        assertThat(batch.calculatedFacts().getFirst().facts().dailyFact().paidOvertimeMinutes())
                .isEqualTo(company.calculatedFacts().getFirst().facts().dailyFact().paidOvertimeMinutes());
    }

    @Test
    void nullEmployeeIdLegacyRowsRetainTheirExistingNumberBasedCompatibility() {
        documents(List.of(document("OVERTIME:legacy", "OVERTIME", null, NUMBER, at(18, 0), at(20, 0))));
        var result = assemble(List.of());
        assertThat(result.calculatedFacts().getFirst().facts().dailyFact().paidOvertimeMinutes()).isPositive();
        assertThat(result.oaDocumentFacts()).singleElement()
                .satisfies(fact -> assertThat(fact.employeeId()).isEqualTo(EMPLOYEE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SZST0687", "SZT0687"})
    void sameMatchedEmployeeCorrectionAndAliasRepairOnlyItsOwnMissingPunch(String number) {
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new PunchEventRow(EMPLOYEE, at(18, 0))));
        documents(List.of(document("PUNCH_CORRECTION:target", "PUNCH_CORRECTION", EMPLOYEE,
                number, at(9, 0), at(9, 0))));

        var result = assemble(List.of());

        var daily = result.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.firstPunchAt()).isEqualTo(at(9, 0));
        assertThat(daily.lastPunchAt()).isEqualTo(at(18, 0));
        assertThat(daily.missingPunchCount()).isZero();
        assertThat(result.oaDocumentFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.employeeId()).isEqualTo(EMPLOYEE);
            assertThat(fact.pointInstant()).isEqualTo(at(9, 0));
        });
    }

    private PublishCommand assemble(List<String> employees) {
        return engine.assemble(COMPANY, YearMonth.from(DATE), PeriodState.OPEN, "admin", CUTOFF,
                DATE, DATE.plusDays(1), employees);
    }

    private void documents(List<OaDocumentRow> rows) {
        when(mapper.findEffectiveOaDocuments(anyString(), any(Instant.class), any(Instant.class), any(Instant.class)))
                .thenReturn(rows);
        when(mapper.findReportableOaDocuments(anyString(), any(Instant.class), any(Instant.class), any(Instant.class)))
                .thenReturn(rows.stream().map(row -> new OaReportFactRow("oa-" + row.sourceBusinessKey(),
                        row.sourceBusinessKey(), row.documentType(), row.leaveType(), row.employeeId(),
                        row.employeeNumber(), "assignment", row.startInstant(), row.endInstant(),
                        "APPROVED", "v1", row.overtimeType())).toList());
    }

    private static OaDocumentRow document(String key, String type, String employeeId, String number,
                                           Instant start, Instant end) {
        return new OaDocumentRow(key, type, "OVERTIME".equals(type) ? OvertimeType.PAID : null, null,
                number, start, end, "Asia/Shanghai", start.minusSeconds(3600), true,
                null, null, "shared-oa", employeeId);
    }

    private static Instant at(int hour, int minute) {
        return DATE.atTime(hour, minute).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }
}
