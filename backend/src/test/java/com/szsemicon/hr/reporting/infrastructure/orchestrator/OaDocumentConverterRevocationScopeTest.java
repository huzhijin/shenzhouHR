package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.EmployeeIdentityIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaDocumentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaReportFactRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchEventRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.AttendancePolicyRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.PunchExemptionRoleIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.SourceInputVersionRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OaDocumentConverterRevocationScopeTest {

    private static final Instant START = Instant.parse("2026-09-09T00:00:00Z");
    private static final Instant END = START.plusSeconds(3 * 3600);

    @Test
    void fullRevocationClearsCalculationAndReportMinutes() {
        var rows = List.of(leave("leave-1", "source-1", "employee-1", "L1"),
                revocation("source-1", "employee-1", "L1", START));
        assertThat(OaDocumentConverter.toIntervalEvidence(rows)).isEmpty();
        assertThat(OaDocumentConverter.amendedLeaveIntervals(rows))
                .containsEntry("leave-1", List.of());
    }

    @Test
    void partialRevocationPreservesTwoHourActualLeave() {
        var rows = List.of(leave("leave-1", "source-1", "employee-1", "L1"),
                revocation("source-1", "employee-1", "L1", START.plusSeconds(2 * 3600)));
        assertThat(OaDocumentConverter.toIntervalEvidence(rows)).singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.interval().start()).isEqualTo(START);
                    assertThat(evidence.interval().end()).isEqualTo(START.plusSeconds(2 * 3600));
                });
        assertThat(OaDocumentConverter.amendedLeaveIntervals(rows).get("leave-1"))
                .singleElement().satisfies(interval ->
                        assertThat(interval.end()).isEqualTo(START.plusSeconds(2 * 3600)));
    }

    @Test
    void sameSerialNeverMatchesAnotherEmployeeOrSourceEvenWithSameEmployeeNumber() {
        var leave = leave("leave-1", "source-1", "employee-1", "L1");
        for (var other : List.of(
                revocation("source-1", "employee-2", "L1", START),
                revocation("source-2", "employee-1", "L1", START))) {
            var rows = List.of(leave, other);
            assertThat(OaDocumentConverter.toIntervalEvidence(rows)).singleElement()
                    .satisfies(evidence -> assertThat(evidence.interval().end()).isEqualTo(END));
            assertThat(OaDocumentConverter.amendedLeaveIntervals(rows)).isEmpty();
        }
    }

    @Test
    void missingReferenceCanUseOnlyOneContainingOriginalForTheExactSourceAndEmployee() {
        var rows = List.of(leave("leave-1", "source-1", "employee-1", "L1"),
                leave("leave-other", "source-1", "employee-2", "L2"),
                revocation("source-1", "employee-1", null, START));
        assertThat(OaDocumentConverter.toIntervalEvidence(rows)).singleElement()
                .satisfies(evidence -> assertThat(evidence.sourceReference()).isEqualTo("leave-other"));
        assertThat(OaDocumentConverter.amendedLeaveIntervals(rows))
                .containsOnlyKeys("leave-1").containsEntry("leave-1", List.of());
    }

    @Test
    void missingReferenceCannotCancelMultipleLeavesOrBlockTheWholeReport() {
        var rows = List.of(leave("leave-1", "source-1", "employee-1", null),
                leave("leave-2", "source-1", "employee-1", null),
                revocation("source-1", "employee-1", null, START));
        assertThat(OaDocumentConverter.toIntervalEvidence(rows)).hasSize(2);
        assertThat(OaDocumentConverter.amendedLeaveIntervals(rows)).isEmpty();
    }

    @Test
    void missingReferenceWithoutAnyContainingOriginalLeavesExistingLeaveUntouched() {
        var rows = List.of(leave("leave-1", "source-1", "employee-1", null),
                revocation("source-1", "employee-1", null, END.plusSeconds(3600)));
        assertThat(OaDocumentConverter.toIntervalEvidence(rows)).singleElement()
                .satisfies(evidence -> assertThat(evidence.interval().end()).isEqualTo(END));
        assertThat(OaDocumentConverter.amendedLeaveIntervals(rows)).isEmpty();
    }

    @Test
    void duplicateOriginalSerialFailsClosedInsteadOfAmendingBothLeaves() {
        assertUnresolved(List.of(leave("leave-1", "source-1", "employee-1", "L1"),
                leave("leave-2", "source-1", "employee-1", "L1"),
                revocation("source-1", "employee-1", "L1", START)));
    }

    @Test
    void explicitUniqueReferenceCanExtendTheActualLeave() {
        var rows = List.of(leave("leave-1", "source-1", "employee-1", "L1"),
                revocation("source-1", "employee-1", "L1", END.plusSeconds(3600)));
        var daily = OaDocumentConverter.toIntervalEvidence(rows);
        assertThat(daily).singleElement().satisfies(evidence -> {
            assertThat(evidence.interval().start()).isEqualTo(START);
            assertThat(evidence.interval().end()).isEqualTo(END.plusSeconds(3600));
        });
        assertThat(OaDocumentConverter.amendedLeaveIntervals(rows).get("leave-1"))
                .containsExactly(daily.getFirst().interval());
    }

    @Test
    void explicitReferenceStillRejectsMissingOrReversedActualTimesAndInvalidOriginal() {
        var original = leave("leave-1", "source-1", "employee-1", "L1");
        for (var times : List.of(
                new Instant[] {null, END}, new Instant[] {START, null},
                new Instant[] {END, START})) {
            assertUnresolved(List.of(original, interval("revocation", "LEAVE_REVOCATION",
                    "source-1", "employee-1", null, "L1", times[0], times[1], true)));
        }
        assertUnresolved(List.of(interval("leave-1", "LEAVE", "source-1", "employee-1",
                        "L1", null, END, START, true),
                revocation("source-1", "employee-1", "L1", START)));
    }

    @Test
    void movedActualIntervalCannotAmendForeignIdentityOrAnUnreferencedLeave() {
        var original = leave("leave-1", "source-1", "employee-1", "L1");
        for (var correction : List.of(
                interval("moved", "LEAVE_REVOCATION", "source-2", "employee-1", null, "L1", END, END.plusSeconds(3600), true),
                interval("moved", "LEAVE_REVOCATION", "source-1", "employee-2", null, "L1", END, END.plusSeconds(3600), true),
                interval("moved", "LEAVE_REVOCATION", "source-1", "employee-1", null, null, END, END.plusSeconds(3600), true),
                interval("moved", "LEAVE_REVOCATION", "source-1", "employee-1", null, "different", END, END.plusSeconds(3600), true),
                interval("moved", "LEAVE_REVOCATION", "source-1", "employee-1", null, "L1", END, END.plusSeconds(3600), false))) {
            var rows = List.of(original, correction);
            assertThat(OaDocumentConverter.toIntervalEvidence(rows)).singleElement()
                    .satisfies(e -> assertThat(e.interval().end()).isEqualTo(END));
            assertThat(OaDocumentConverter.amendedLeaveIntervals(rows)).isEmpty();
        }
    }

    @Test
    void approvedWangKaixiangAfternoonChangeKeepsIndependentMorningLeaveAndDailyReportAgreement() {
        var original = interval("LEAVE:-5340365157834535998", "LEAVE", "source-1", "employee-1",
                "QJD-20260901022", null, at("2026-09-02", "08:30:00"), at("2026-09-02", "13:30:00"), true);
        var other = interval("LEAVE:-6080146494035308782", "LEAVE", "source-1", "employee-1",
                "QJD-20260902004", null, at("2026-09-02", "09:30:00"), at("2026-09-02", "13:30:00"), true);
        var change = interval("LEAVE_REVOCATION:9031331062614542089", "LEAVE_REVOCATION", "source-1", "employee-1",
                "XJD-20260902001", original.leaveSerial(), at("2026-09-02", "12:00:00"), at("2026-09-02", "18:00:00"), true);
        var command = assemble(List.of(original, other, change), List.of(original, other),
                List.of(new PunchEventRow("employee-1", at("2026-09-02", "08:28:17"))));
        assertThat(command.oaDocumentFacts()).filteredOn(f -> f.oaAttendanceDocumentId().equals(original.sourceBusinessKey()))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.recognizedMinutes()).isEqualTo(270);
                    assertThat(fact.recognizedMinutesByDate()).isEqualTo(Map.of(LocalDate.parse("2026-09-02"), 270L));
                    assertThat(fact.intervalStart()).isEqualTo(original.startInstant());
                    assertThat(fact.intervalEndExclusive()).isEqualTo(original.endInstant());
                });
        assertThat(command.oaDocumentFacts()).filteredOn(f -> f.oaAttendanceDocumentId().equals(other.sourceBusinessKey()))
                .singleElement().satisfies(f -> assertThat(f.recognizedMinutes()).isEqualTo(150));
        assertThat(command.calculatedFacts()).filteredOn(f -> f.facts().dailyFact().businessDate().equals(LocalDate.parse("2026-09-02")))
                .singleElement().satisfies(f -> {
                    assertThat(f.facts().dailyFact().leaveOrTimeOffMinutes()).isEqualTo(420);
                    assertThat(f.facts().dailyFact().actualWorkMinutes()).isEqualTo(60);
                });
    }

    @Test
    void approvedExplicitReferenceCanMoveLeaveToThePreviousDayWithoutLeavingOldDailyMinutes() {
        var original = new OaDocumentRow("LEAVE:8786142468757799653", "LEAVE", null, LeaveType.PERSONAL,
                "E001", at("2026-09-16", "13:30:00"), at("2026-09-16", "18:00:00"), "Asia/Shanghai", START,
                true, "QJD-20260915002", null, "source-1", "employee-1");
        var change = interval("LEAVE_REVOCATION:-7045060531683637073", "LEAVE_REVOCATION", "source-1", "employee-1",
                "XJD-20260915002", original.leaveSerial(), at("2026-09-15", "13:30:00"), at("2026-09-15", "18:00:00"), true);
        var rows = List.of(original, change);
        var evidence = OaDocumentConverter.toIntervalEvidence(rows);
        assertThat(evidence).singleElement().satisfies(e -> {
            assertThat(e.interval().start()).isEqualTo(change.startInstant());
            assertThat(e.interval().end()).isEqualTo(change.endInstant());
        });
        assertThat(OaDocumentConverter.amendedLeaveIntervals(rows).get(original.sourceBusinessKey()))
                .containsExactly(evidence.getFirst().interval());
        var command = assemble(rows, List.of(original), List.of());
        assertThat(command.oaDocumentFacts()).singleElement().satisfies(f -> {
            assertThat(f.recognizedMinutes()).isEqualTo(270);
            assertThat(f.recognizedMinutesByDate()).isEqualTo(Map.of(LocalDate.parse("2026-09-15"), 270L));
            assertThat(f.intervalStart()).isEqualTo(original.startInstant());
            assertThat(f.intervalEndExclusive()).isEqualTo(original.endInstant());
        });
        assertThat(command.calculatedFacts()).filteredOn(f -> f.facts().dailyFact().businessDate().equals(LocalDate.parse("2026-09-15")))
                .singleElement().satisfies(f -> assertThat(f.facts().dailyFact().leaveOrTimeOffMinutes()).isEqualTo(270));
        assertThat(command.calculatedFacts()).filteredOn(f -> f.facts().dailyFact().businessDate().equals(LocalDate.parse("2026-09-16")))
                .singleElement().satisfies(f -> assertThat(f.facts().dailyFact().leaveOrTimeOffMinutes()).isZero());
    }

    @Test
    void duplicateRevocationRowsDoNotDuplicateActualMinutes() {
        var revocation = revocation("source-1", "employee-1", "L1", START.plusSeconds(7200));
        var rows = List.of(leave("leave-1", "source-1", "employee-1", "L1"), revocation, revocation);
        assertThat(OaDocumentConverter.toIntervalEvidence(rows)).hasSize(1);
        assertThat(OaDocumentConverter.amendedLeaveIntervals(rows).get("leave-1")).hasSize(1);
    }

    @Test
    void sameBusinessKeyFromAnotherSourceCannotReceiveAnAmendmentInReportMap() {
        var rows = List.of(leave("leave-1", "source-1", "employee-1", "L1"),
                leave("leave-1", "source-2", "employee-1", "L1"),
                revocation("source-1", "employee-1", "L1", START));
        assertThatThrownBy(() -> OaDocumentConverter.amendedLeaveIntervals(rows))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OaDocumentConverter.UNRESOLVED_LEAVE_REVOCATION);
    }

    private static void assertUnresolved(List<OaDocumentRow> rows) {
        assertThatThrownBy(() -> OaDocumentConverter.toIntervalEvidence(rows))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OaDocumentConverter.UNRESOLVED_LEAVE_REVOCATION);
        assertThatThrownBy(() -> OaDocumentConverter.amendedLeaveIntervals(rows))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OaDocumentConverter.UNRESOLVED_LEAVE_REVOCATION);
    }

    private static OaDocumentRow leave(String key, String sourceId, String employeeId, String serial) {
        return new OaDocumentRow(key, "LEAVE", null, LeaveType.PERSONAL, "E001",
                START, END, "Asia/Shanghai", START, true, serial, null, sourceId, employeeId);
    }

    private static OaDocumentRow revocation(
            String sourceId, String employeeId, String serial, Instant actualEnd) {
        return new OaDocumentRow("revocation-1", "LEAVE_REVOCATION", null, null, "E001",
                START, actualEnd, "Asia/Shanghai", START, true, null, serial, sourceId, employeeId);
    }

    private static OaDocumentRow interval(String key, String type, String source, String employee,
            String serial, String originalSerial, Instant start, Instant end, boolean effective) {
        return new OaDocumentRow(key, type, null, "LEAVE".equals(type) ? LeaveType.COMPENSATORY : null,
                "E001", start, end, "Asia/Shanghai", START, effective, serial, originalSerial, source, employee);
    }

    private static Instant at(String date, String time) {
        return java.time.LocalDateTime.parse(date + "T" + time).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }

    private static PublishCommand assemble(List<OaDocumentRow> effective, List<OaDocumentRow> reportable,
            List<PunchEventRow> punches) {
        String company = "company-1";
        Instant cutoff = Instant.parse("2026-10-06T00:00:00Z");
        var mapper = mock(AttendanceReportCalculationMapper.class);
        when(mapper.findAttendanceSourceVersions(company, cutoff)).thenReturn(List.of(
                new SourceInputVersionRow("source-1", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), cutoff),
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), cutoff)));
        when(mapper.findEmployeeIdentityIntervals(eq(company), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow("employee-1", "version-1", "E001", "测试员工",
                        "assignment-1", "org-1", "org-version-1", "技术中心", LocalDate.of(2026, 1, 1), null,
                        LocalDate.of(2026, 1, 1), null, LocalDate.of(2026, 1, 1), null)));
        var shifts = new ArrayList<ShiftSegmentRow>();
        var calendar = new ArrayList<CalendarDayRow>();
        for (LocalDate date = LocalDate.of(2026, 9, 1); date.isBefore(LocalDate.of(2026, 10, 1)); date = date.plusDays(1)) {
            calendar.add(new CalendarDayRow("employee-1", date, "WEEKDAY", 1, 1));
            for (var times : List.of(new String[] {"08:30:00", "12:00:00"}, new String[] {"13:30:00", "18:00:00"})) {
                var start = at(date.toString(), times[0]);
                var end = at(date.toString(), times[1]);
                shifts.add(new ShiftSegmentRow("employee-1", date, date + times[0], start, end,
                        start.minusSeconds(3600), start.plusSeconds(3600), end.minusSeconds(3600), end.plusSeconds(3600)));
            }
        }
        when(mapper.findPublishedCalendarDays(eq(company), any(LocalDate.class), any(LocalDate.class), eq(cutoff))).thenReturn(calendar);
        when(mapper.findScheduledWorkSegments(eq(company), any(LocalDate.class), any(LocalDate.class), eq(cutoff))).thenReturn(shifts);
        var policies = calendar.stream()
                .map(c -> new AttendancePolicyRow(
                        "employee-1", c.businessDate(),
                        1, 1, true, 15,
                        1, true, 15, 1, false,
                        1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS",
                        1, true, 48))
                .toList();
        when(mapper.findAttendancePolicies(eq(company), any(LocalDate.class), any(LocalDate.class), eq(cutoff))).thenReturn(policies);
        when(mapper.findEffectiveOaDocuments(eq(company), any(Instant.class), any(Instant.class), eq(cutoff))).thenReturn(effective);
        when(mapper.findReportableOaDocuments(eq(company), any(Instant.class), any(Instant.class), eq(cutoff))).thenReturn(reportable.stream()
                .map(row -> new OaReportFactRow(row.sourceBusinessKey(), row.sourceBusinessKey(), row.documentType(), row.leaveType(),
                        "employee-1", "E001", "assignment-1", row.startInstant(), row.endInstant(), "APPROVED", "v1")).toList());
        when(mapper.findActivatedPunchEvents(eq(company), any(Instant.class), any(Instant.class), eq(cutoff))).thenReturn(punches);
        when(mapper.findPunchExemptionRoleIntervals(eq(company), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findStandingPunchExemptionIntervals(eq(company), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        return new FullCalculationEngineOrchestrator(mapper).assemble(company, YearMonth.of(2026, 9), PeriodState.OPEN, "audit", cutoff);
    }
}
