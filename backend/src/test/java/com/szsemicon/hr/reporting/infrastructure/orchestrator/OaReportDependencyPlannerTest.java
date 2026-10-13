package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaDocumentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ProjectedLeaveUsageRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.EmployeeIdentityIntervalRow;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OaReportDependencyPlannerTest {
    private static final YearMonth MONTH = YearMonth.of(2026, 9);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 29);
    private static final LocalDate TO = LocalDate.of(2026, 10, 1);

    @Test void unchangedLongDocumentsDoNotExpandWriteOrSourceToTheWholeMonth() {
        var leave = row("LEAVE:1", "LEAVE", "e", 1, 31);
        var exempt = row("EXEMPT:1", "EXEMPTION", "e", 1, 31);
        var plan = plan(List.of(leave, exempt), List.of(leave, exempt), Set.of());
        assertThat(plan.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
        assertThat(plan.refreshFromInclusive()).isEqualTo(MONTH.atDay(1).minusDays(1));
    }
    @Test void rawSecondsAndPersistedGridRepresentTheSameUnchangedContribution() {
        var pinned = row("LEAVE:1", "LEAVE", "e", 1, 31);
        var raw = new OaDocumentRow(pinned.sourceBusinessKey(), pinned.documentType(), null,
                pinned.leaveType(), pinned.employeeNumber(), pinned.startInstant().plusSeconds(5),
                pinned.endInstant().plusSeconds(5), pinned.sourceTimeZone(), pinned.firstSubmittedAt(),
                true, pinned.leaveSerial(), null, pinned.sourceId(), pinned.employeeId());
        assertThat(plan(List.of(pinned), List.of(raw), Set.of()).writeStartInclusive()).isEqualTo(MONTH.atDay(1));
    }
    @Test void movedIntervalExpandsBothOldAndNewDays() {
        var plan = plan(List.of(row("LEAVE:1", "LEAVE", "e", 24, 25)),
                List.of(row("LEAVE:1", "LEAVE", "e", 30, 31)), Set.of());
        assertThat(plan.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
        assertThat(plan.writeEndExclusive()).isEqualTo(TO);
    }
    @Test void changedIdentityAndDatesRemainOneBusinessDocumentDependency() {
        var plan = plan(List.of(row("LEAVE:1", "LEAVE", "old-employee", 24, 25)),
                List.of(row("LEAVE:1", "LEAVE", "new-employee", 30, 31)), Set.of());
        assertThat(plan.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
    }
    @Test void changedUnrelatedEarlyMonthDocumentDoesNotWidenTheWindow() {
        var plan = plan(List.of(row("LEAVE:1", "LEAVE", "e", 9, 10)), List.of(), Set.of());
        assertThat(plan.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
    }
    @Test void revokedCrossWindowLeaveRemovesEveryPreviouslyContributingDay() {
        var plan = plan(List.of(row("LEAVE:1", "LEAVE", "e", 25, 31)), List.of(), Set.of());
        assertThat(plan.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
    }
    @Test void revokedRevocationRestoresOriginalWholeScopeEvenWhenOriginalRowDidNotChange() {
        var original = row("LEAVE:1", "LEAVE", "e", 20, 31);
        var plan = plan(List.of(original), List.of(original), Set.of("source:LEAVE:1"));
        assertThat(plan.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
    }
    @Test void fixedPointIncludesASecondChangedDocumentTouchingExpandedDays() {
        var plan = plan(List.of(row("LEAVE:1", "LEAVE", "e", 26, 27),
                        row("LEAVE:2", "LEAVE", "e", 23, 24)),
                List.of(row("LEAVE:1", "LEAVE", "e", 30, 31),
                        row("LEAVE:2", "LEAVE", "e", 26, 27)), Set.of());
        assertThat(plan.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
    }
    @Test void copiedOldFactRemainsBaselineForSecondPartialEvenAfterGlobalCutoffAdvanced() {
        var old = row("LEAVE:1", "LEAVE", "e", 24, 25);
        var current = row("LEAVE:1", "LEAVE", "e", 30, 31);
        // First publication copied the old row. A later cutoff never replaces
        // that pin's actual row with a retrospective current-context lookup.
        var first = plan(List.of(old), List.of(current), Set.of());
        var second = plan(List.of(old), List.of(current), Set.of());
        assertThat(second).isEqualTo(first);
        assertThat(second.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
    }
    @Test void longOvertimeNeedsItsOriginalDayMakeupAndPunchesWithoutExpandingUnchangedWrites() {
        var overtime = row("OVERTIME:1", "OVERTIME", "e", 26, 31);
        var plan = plan(List.of(overtime), List.of(overtime), Set.of());
        assertThat(plan.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
        assertThat(plan.refreshFromInclusive()).isEqualTo(MONTH.atDay(1).minusDays(1));
        assertThat(plan.punchStartInclusive()).isEqualTo(at(0));
    }
    @Test void unrelatedMonthlyShiftDoesNotExpandTheConsumedEvidence() {
        var oldShift = new ShiftSegmentRow("e", MONTH.atDay(1), "old", at(1), at(2),
                at(1), at(1), at(2), at(2));
        var plan = OaReportDependencyPlanner.plan("company", MONTH, FROM, TO,
                List.of(), List.of(), List.of(), List.of(oldShift), Set.of());
        assertThat(plan.refreshFromInclusive()).isEqualTo(MONTH.atDay(1).minusDays(1));
        assertThat(plan.refreshToExclusive()).isEqualTo(LocalDate.of(2026, 10, 2));
    }
    @Test void oldNonContributingCrossYearGhostDoesNotExpandDailyWrite() {
        var ghost = new OaDocumentRow("OVERTIME:ghost", "OVERTIME", null, null, "TEST",
                at(1).minus(Duration.ofDays(365)), at(31), "Asia/Shanghai", at(1), false,
                null, null, "source", "e");
        assertThat(plan(List.of(ghost), List.of(), Set.of()).writeStartInclusive()).isEqualTo(MONTH.atDay(1));
    }
    @Test void movedZeroHourOldOvertimeStillRemovesOldOaRowOutsideRequestedDates() {
        var old = row("OVERTIME:1", "OVERTIME", "e", 24, 25);
        old = new OaDocumentRow(old.sourceBusinessKey(), old.documentType(), old.overtimeType(), null,
                old.employeeNumber(), old.startInstant(), old.endInstant(), old.sourceTimeZone(),
                old.firstSubmittedAt(), false, old.leaveSerial(), null, old.sourceId(), old.employeeId());
        var result = plan(List.of(old), List.of(row("OVERTIME:1", "OVERTIME", "e", 30, 31)), Set.of());
        assertThat(result.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
    }
    @Test void previouslyZeroContributionActivatedWithSameDatesStillExpandsOriginalDependency() {
        var active = row("OVERTIME:1", "OVERTIME", "e", 26, 31);
        var old = new OaDocumentRow(active.sourceBusinessKey(), active.documentType(), active.overtimeType(),
                null, active.employeeNumber(), active.startInstant(), active.endInstant(), active.sourceTimeZone(),
                active.firstSubmittedAt(), false, active.leaveSerial(), null, active.sourceId(), active.employeeId());
        assertThat(plan(List.of(old), List.of(active), Set.of()).writeStartInclusive())
                .isEqualTo(MONTH.atDay(1));
    }
    @Test void currentApprovedMovedLeaveClosesOriginalAndActualDaysFromEitherSide() {
        var original = row("LEAVE:1", "LEAVE", "e", 16, 17);
        var moved = amendment("source", "e", "serial", 15, 16, true);
        for (int day : List.of(15, 16)) {
            var result = partial(day, List.of(original), List.of(original, moved), List.of(), Set.of());
            assertThat(result.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
            assertThat(result.writeEndExclusive()).isEqualTo(MONTH.plusMonths(1).atDay(1));
        }
    }
    @Test void movedRevocationNeverAssociatesForeignEmployeeSourceMissingOrAmbiguousSerial() {
        var original = row("LEAVE:1", "LEAVE", "e", -10, -9);
        for (var moved : List.of(amendment("source", "foreign", "serial", 15, 16, true),
                amendment("foreign", "e", "serial", 15, 16, true),
                amendment("source", "e", null, 15, 16, true),
                amendment("source", "e", "serial", 15, 16, false))) {
            assertThat(partial(15, List.of(original), List.of(original, moved), List.of(), Set.of()).writeEndExclusive())
                    .isEqualTo(MONTH.plusMonths(1).atDay(1));
            assertThat(partial(15, List.of(original), List.of(original, moved), List.of(), Set.of()).evidenceStartInclusive())
                    .isEqualTo(at(0));
        }
        var duplicate = row("LEAVE:2", "LEAVE", "e", -9, -8);
        assertThat(partial(15, List.of(original, duplicate), List.of(original, duplicate,
                amendment("source", "e", "serial", 15, 16, true)), List.of(), Set.of()).writeEndExclusive())
                .isEqualTo(MONTH.plusMonths(1).atDay(1));
        assertThat(partial(15, List.of(original, duplicate), List.of(original, duplicate,
                amendment("source", "e", "serial", 15, 16, true)), List.of(), Set.of()).evidenceStartInclusive())
                .isEqualTo(at(0));
    }
    @Test void retiredDifferentKeyWithSameSerialDoesNotHideUniqueCurrentOriginal() {
        var original = row("LEAVE:1", "LEAVE", "e", 16, 17);
        var retired = row("LEAVE:retired", "LEAVE", "e", 5, 6);
        var result = partial(15, List.of(original, retired), List.of(original,
                amendment("source", "e", "serial", 15, 16, true)), List.of(), Set.of());
        assertThat(result.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
        assertThat(result.writeEndExclusive()).isEqualTo(MONTH.plusMonths(1).atDay(1));
    }
    @Test void cancelledAmendmentClearsPreviouslyPublishedActualDayEvenThoughNoRevocationFactExists() {
        var original = row("LEAVE:1", "LEAVE", "e", 16, 17);
        var usage = usage("{\"2026-09-15\":270}", 270);
        for (int day : List.of(15, 16)) {
            var result = partial(day, List.of(original), List.of(original), List.of(usage), Set.of("source:LEAVE:1"));
            assertThat(result.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
            assertThat(result.writeEndExclusive()).isEqualTo(MONTH.plusMonths(1).atDay(1));
        }
    }
    @Test void changingAnAlreadyMovedLeaveClosesOldActualOriginalAndNewActualDays() {
        var original = row("LEAVE:1", "LEAVE", "e", 16, 17);
        var result = partial(20, List.of(original), List.of(original,
                amendment("source", "e", "serial", 20, 21, true)),
                List.of(usage("{\"2026-09-15\":270}", 270)), Set.of("source:LEAVE:1"));
        assertThat(result.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
        assertThat(result.writeEndExclusive()).isEqualTo(MONTH.plusMonths(1).atDay(1));
    }
    @Test void fullCancellationPointClosesTheOldActualDayAndOriginalScope() {
        var original = row("LEAVE:1", "LEAVE", "e", 16, 17);
        var result = partial(15, List.of(original), List.of(original,
                amendment("source", "e", "serial", 16, 16, true)),
                List.of(usage("{\"2026-09-15\":270}", 270)), Set.of("source:LEAVE:1"));
        assertThat(result.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
        assertThat(result.writeEndExclusive()).isEqualTo(MONTH.plusMonths(1).atDay(1));
    }
    @Test void crossMonthMovesWidenReadsButNeverWriteOutsideTheRequestedMonth() {
        var original = row("LEAVE:1", "LEAVE", "e", -10, -9);
        var result = partial(15, List.of(original), List.of(original,
                amendment("source", "e", "serial", 15, 16, true)), List.of(), Set.of());
        assertThat(result.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
        assertThat(result.writeEndExclusive()).isEqualTo(MONTH.plusMonths(1).atDay(1));
        assertThat(result.evidenceStartInclusive()).isBeforeOrEqualTo(original.startInstant());
        var old = row("LEAVE:1", "LEAVE", "e", 15, 16);
        var outside = partial(15, List.of(old), List.of(old,
                amendment("source", "e", "serial", 35, 36, true)), List.of(), Set.of());
        assertThat(outside.writeEndExclusive()).isEqualTo(MONTH.plusMonths(1).atDay(1));
        assertThat(outside.evidenceEndExclusive()).isAfterOrEqualTo(at(36));
    }
    @Test void legacyMissingMapIsNotAnEmptyFullCancellationAndCannotGuessOldMovedDates() {
        var original = row("LEAVE:1", "LEAVE", "e", 16, 17);
        assertThatThrownBy(() -> partial(15, List.of(original), List.of(original),
                List.of(usage(null, 270)), Set.of("source:LEAVE:1")))
                .hasMessageContaining("OA_LEGACY_AMENDED_LEAVE_REQUIRES_FULL_MONTH");
        assertThat(partial(15, List.of(original), List.of(original),
                List.of(usage("{}", 0)), Set.of("source:LEAVE:1"))).isNotNull();
        assertThat(OaReportDependencyPlanner.plan("company", MONTH, MONTH.atDay(1), MONTH.plusMonths(1).atDay(1),
                List.of("e"), List.of(original), List.of(original), List.of(), Set.of("source:LEAVE:1"),
                List.of(usage(null, 270)))).isNotNull();
    }
    @Test void invalidPinnedDayMapFailsInsteadOfTreatingItAsLegacy() {
        var original = row("LEAVE:1", "LEAVE", "e", 16, 17);
        assertThatThrownBy(() -> partial(15, List.of(original), List.of(original),
                List.of(usage("{\"2026-09-15\":240}", 270)), Set.of("source:LEAVE:1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyOriginalOutsideMonthCannotBeSafelyCopiedEvenForWholeEmployeeMonth() {
        var original = row("LEAVE:1", "LEAVE", "e", -10, -9);
        assertThatThrownBy(() -> OaReportDependencyPlanner.plan("company", MONTH, MONTH.atDay(1), MONTH.plusMonths(1).atDay(1),
                List.of("e"), List.of(original), List.of(original), List.of(), Set.of("source:LEAVE:1"), List.of(usage(null, 270))))
                .hasMessageContaining("OA_LEGACY_OUTSIDE_MONTH_LEAVE_REQUIRES_FULL_REBUILD");
    }
    @Test void finalFactWriteUsesActualLeaveDaysWithoutChangingTheOriginalTraceInterval() {
        var fact = new com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedOaDocumentFact(
                "company", "document", "e", "version", "assignment", "org", "orgv", "LEAVE", "PERSONAL",
                com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.OaTemporalShape.INTERVAL,
                null, at(-10), at(-9), 270, "APPROVED", "v1")
                .withRecognizedMinutesByDate(Map.of(MONTH.atDay(15), 270L));
        assertThat(FullCalculationEngineOrchestrator.hasActualLeaveDayInWindow(fact, MONTH.atDay(15), MONTH.atDay(16))).isTrue();
        assertThat(FullCalculationEngineOrchestrator.hasActualLeaveDayInWindow(fact, MONTH.atDay(16), MONTH.atDay(17))).isFalse();
        assertThat(FullCalculationEngineOrchestrator.hasActualLeaveDayInWindow(fact.withRecognizedMinutesByDate(null),
                MONTH.atDay(15), MONTH.atDay(16))).isFalse();
        assertThat(fact.intervalStart()).isEqualTo(at(-10));
    }
    @Test void unchangedNonAmendedLongLeaveWithDayMapDoesNotExpandAWindow() {
        var original = row("LEAVE:1", "LEAVE", "e", 1, 31);
        assertThat(partial(15, List.of(original), List.of(original),
                List.of(usage("{\"2026-09-15\":270}", 270)), Set.of()).writeStartInclusive())
                .isEqualTo(MONTH.atDay(1));
    }
    @Test void productionPlanWindowReadsTheExactPinActualMapAndExcludesForeignRequestedEmployees() {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        Instant cutoff = Instant.parse("2026-10-06T00:00:00Z");
        var original = row("LEAVE:1", "LEAVE", "e", 16, 17);
        when(mapper.findEmployeeIdentityIntervals(eq("company"), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(
                new EmployeeIdentityIntervalRow("e", "v", "TEST", "Employee", "assignment", "org", "orgv", "研发中心",
                        MONTH.atDay(1), null, MONTH.atDay(1), null, MONTH.atDay(1), null)));
        when(mapper.findProjectedOaDependencies("company", "pin", Set.of("e"))).thenReturn(List.of(original));
        when(mapper.findEffectiveOaDocuments(eq("company"), any(Instant.class), any(Instant.class), eq(cutoff))).thenReturn(List.of(original));
        when(mapper.findHistoricallyAmendedLeaveKeys(eq("company"), any(Instant.class), any(Instant.class), eq(cutoff), eq(List.of("e")))).thenReturn(List.of("source:LEAVE:1"));
        when(mapper.findProjectedLeaveUsageDependencies("company", "pin")).thenReturn(List.of(
                usage("{\"2026-09-15\":270}", 270), new ProjectedLeaveUsageRow("foreign", "LEAVE:foreign", "other", 270, "invalid")));
        var result = new FullCalculationEngineOrchestrator(mapper).planWindow("company", MONTH, "pin", cutoff,
                MONTH.atDay(15), MONTH.atDay(16), List.of("e"));
        assertThat(result.writeStartInclusive()).isEqualTo(MONTH.atDay(1));
        assertThat(result.writeEndExclusive()).isEqualTo(MONTH.plusMonths(1).atDay(1));
        verify(mapper).findProjectedLeaveUsageDependencies("company", "pin");
        // Verify OA candidates cover the whole month for leave partner allocation
        verify(mapper).findEffectiveOaDocuments(eq("company"), 
                eq(MONTH.atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()),
                eq(MONTH.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()),
                eq(cutoff));
        verify(mapper).findHistoricallyAmendedLeaveKeys(eq("company"),
                eq(MONTH.atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()),
                eq(MONTH.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()),
                eq(cutoff), eq(List.of("e")));
    }
    @Test void scopedPlanWindowPushesEmployeeSetIntoPinnedOaAndShiftReads() {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        Instant cutoff = Instant.parse("2026-10-06T00:00:00Z");
        var engine = new FullCalculationEngineOrchestrator(mapper);
        engine.planWindow("company", MONTH, "pin", cutoff, MONTH.atDay(15), MONTH.atDay(16), List.of("e", "f"));
        verify(mapper).findProjectedOaDependencies("company", "pin", Set.of("e", "f"));
        verify(mapper).findScheduledWorkSegments("company", MONTH.atDay(1).minusDays(1),
                MONTH.plusMonths(1).atDay(2), cutoff, Set.of("e", "f"));
        verify(mapper, never()).findProjectedOaDependencies("company", "pin");
        verify(mapper, never()).findScheduledWorkSegments("company", MONTH.atDay(1).minusDays(1),
                MONTH.plusMonths(1).atDay(2), cutoff);
    }
    @Test void companyAndEmptyScopePlanWindowKeepCompanyWideReads() {
        Instant cutoff = Instant.parse("2026-10-06T00:00:00Z");
        for (List<String> scope : Arrays.asList(null, List.<String>of())) {
            var mapper = mock(AttendanceReportCalculationMapper.class);
            new FullCalculationEngineOrchestrator(mapper).planWindow("company", MONTH, "pin", cutoff,
                    MONTH.atDay(15), MONTH.atDay(16), scope);
            // Company/empty scope uses the unscoped overloads (which delegate with
            // employeeIds = null, i.e. the unchanged company-wide SQL).
            verify(mapper).findProjectedOaDependencies("company", "pin");
            verify(mapper).findScheduledWorkSegments("company", MONTH.atDay(1).minusDays(1),
                    MONTH.plusMonths(1).atDay(2), cutoff);
            verify(mapper, never()).findProjectedOaDependencies(eq("company"), eq("pin"),
                    org.mockito.ArgumentMatchers.anyCollection());
            verify(mapper, never()).findScheduledWorkSegments(eq("company"), any(LocalDate.class),
                    any(LocalDate.class), eq(cutoff), org.mockito.ArgumentMatchers.anyCollection());
        }
    }
    @Test void scopedPinnedOaGuardStillDropsForeignRowsReturnedBySql() {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        Instant cutoff = Instant.parse("2026-10-06T00:00:00Z");
        var own = row("LEAVE:1", "LEAVE", "e", 16, 17);
        var foreign = row("LEAVE:2", "LEAVE", "other", 16, 17);
        when(mapper.findProjectedOaDependencies("company", "pin", Set.of("e"))).thenReturn(List.of(own, foreign));
        var scoped = new FullCalculationEngineOrchestrator(mapper).planWindow("company", MONTH, "pin", cutoff,
                MONTH.atDay(15), MONTH.atDay(16), List.of("e"));
        var mapper2 = mock(AttendanceReportCalculationMapper.class);
        when(mapper2.findProjectedOaDependencies("company", "pin", Set.of("e"))).thenReturn(List.of(own));
        var expected = new FullCalculationEngineOrchestrator(mapper2).planWindow("company", MONTH, "pin", cutoff,
                MONTH.atDay(15), MONTH.atDay(16), List.of("e"));
        assertThat(scoped).isEqualTo(expected);
    }
    private static ProjectedLeaveUsageRow usage(String json, long total) {
        return new ProjectedLeaveUsageRow("source", "LEAVE:1", "e", total, json);
    }
    private static OaDocumentRow amendment(String source, String employee, String serial, int from, int to, boolean effective) {
        return new OaDocumentRow("LEAVE_REVOCATION:1", "LEAVE_REVOCATION", null, null, "TEST", at(from), at(to),
                "Asia/Shanghai", at(1), effective, null, serial, source, employee);
    }
    private static com.szsemicon.hr.reporting.application.AttendanceReportDependencyPlan partial(int day,
            List<OaDocumentRow> before, List<OaDocumentRow> after, List<ProjectedLeaveUsageRow> usage, Set<String> amended) {
        return OaReportDependencyPlanner.plan("company", MONTH, MONTH.atDay(day), MONTH.atDay(day).plusDays(1), List.of("e"),
                before, after, List.of(), amended, usage);
    }
    private static com.szsemicon.hr.reporting.application.AttendanceReportDependencyPlan plan(
            List<OaDocumentRow> before, List<OaDocumentRow> after, Set<String> amended) {
        return OaReportDependencyPlanner.plan("company", MONTH, FROM, TO, List.of(),
                before, after, List.of(), amended);
    }
    private static Instant at(int day) { return MONTH.atDay(1).plusDays(day - 1).atStartOfDay(ZONE).toInstant(); }
    private static OaDocumentRow row(String key, String type, String employee, int from, int to) {
        return new OaDocumentRow(key, type, "OVERTIME".equals(type) ? OvertimeType.PAID : null,
                "LEAVE".equals(type) ? LeaveType.PERSONAL : null, "TEST", at(from), at(to),
                "Asia/Shanghai", at(1), true, "serial", null, "source", employee);
    }
}
