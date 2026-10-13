package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.domain.LeaveType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the actual engine projection, not a proportional split of a document total. */
class OaRecognizedMinutesByDateTest {
    private static final String COMPANY = "company";
    private static final String EMPLOYEE = "employee";
    private static final String NUMBER = "E001";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant CUTOFF = Instant.parse("2026-10-06T00:00:00Z");
    private AttendanceReportCalculationMapper mapper;
    private FullCalculationEngineOrchestrator engine;

    @BeforeEach
    void fixture() {
        mapper = mock(AttendanceReportCalculationMapper.class);
        engine = new FullCalculationEngineOrchestrator(mapper);
        when(mapper.findAttendanceSourceVersions(COMPANY, CUTOFF)).thenReturn(List.of(
                new SourceInputVersionRow("oa", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), CUTOFF),
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), CUTOFF)));
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(EMPLOYEE, "employee-v1", NUMBER, "测试员工", "assignment",
                        "org", "org-v1", "技术支持中心/现场服务部/现场技术服务组",
                        LocalDate.of(2026, 8, 1), null, LocalDate.of(2026, 8, 1), null,
                        LocalDate.of(2026, 8, 1), null)));
        List<CalendarDayRow> calendar = new ArrayList<>();
        List<ShiftSegmentRow> shifts = new ArrayList<>();
        for (LocalDate day = date("2026-08-30"); day.isBefore(date("2026-10-04")); day = day.plusDays(1)) {
            boolean rest = day.getDayOfWeek().getValue() > 5;
            calendar.add(new CalendarDayRow(EMPLOYEE, day, rest ? "WEEKEND" : "WORKDAY", 1, 1));
            if (!rest) {
                shifts.add(segment(day, "am", 8, 30, 12, 0));
                shifts.add(segment(day, "pm", 13, 30, 18, 0));
            }
        }
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(calendar);
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(shifts);
        List<AttendancePolicyRow> policies = calendar.stream()
                .map(c -> new AttendancePolicyRow(
                        EMPLOYEE, c.businessDate(),
                        1, 1, true, 15,
                        1, true, 15, 1, false,
                        1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS",
                        1, true, 48))
                .toList();
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(policies);
    }

    @ParameterizedTest
    @EnumSource(value = LeaveType.class, names = {"ANNUAL", "COMPENSATORY"})
    void august31ThroughSeptember4UsesActualWorkDatesAndPreservesTheWholeDocument(LeaveType type) {
        var leave = leave("cross-month", type, at("2026-08-31", 8, 30), at("2026-09-04", 18, 0));
        documents(List.of(leave), List.of(report(leave, "APPROVED")));
        var fact = assemble().oaDocumentFacts().getFirst();
        assertThat(fact.recognizedMinutes()).isEqualTo(1920);
        assertThat(fact.recognizedMinutesByDate()).containsExactlyInAnyOrderEntriesOf(Map.of(
                date("2026-09-01"), 480L, date("2026-09-02"), 480L,
                date("2026-09-03"), 480L, date("2026-09-04"), 480L));
        assertThat(fact.recognizedMinutesByDate().keySet())
                .allMatch(d -> d.getMonthValue() == 9);
        assertThat(fact.recognizedMinutesByDate().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(fact.recognizedMinutes());
        assertThat(fact.recognizedMinutesByDate().entrySet().stream()
                .filter(e -> e.getKey().getMonthValue() == 9).mapToLong(Map.Entry::getValue).sum()).isEqualTo(1920);
        assertThat(fact.intervalStart()).isEqualTo(leave.startInstant());
        assertThat(fact.intervalEndExclusive()).isEqualTo(leave.endInstant());
        assertThat(fact.sourceStatus()).isEqualTo("APPROVED");
    }

    @Test
    void partialRevocationUsesActualIntervalAndLunchGapsInsteadOfDividingOriginalHours() {
        var leave = leave("revoked-part", LeaveType.ANNUAL, at("2026-08-31", 8, 30), at("2026-09-04", 18, 0));
        var actual = revocation("actual", leave, at("2026-09-01", 13, 30), at("2026-09-03", 12, 0));
        documents(List.of(leave, actual), List.of(report(leave, "APPROVED")));
        var fact = assemble().oaDocumentFacts().getFirst();
        assertThat(fact.recognizedMinutes()).isEqualTo(960);
        assertThat(fact.recognizedMinutesByDate()).containsExactlyInAnyOrderEntriesOf(Map.of(
                date("2026-09-01"), 270L, date("2026-09-02"), 480L, date("2026-09-03"), 210L));
        assertThat(fact.intervalStart()).isEqualTo(leave.startInstant());
        assertThat(fact.intervalEndExclusive()).isEqualTo(leave.endInstant());
    }

    @ParameterizedTest
    @CsvSource({
            "SZJN0015,COMPENSATORY,2026-08-31T01:00:00Z,2026-09-04T10:00:00Z,1920,true",
            "SZST0143,ANNUAL,2026-08-31T00:30:00Z,2026-09-01T10:00:00Z,480,false",
            "SZST0665,COMPENSATORY,2026-08-31T00:30:00Z,2026-09-02T10:00:00Z,960,false"
    })
    void actualCrossMonthLeaveIntervalsAllocateOnlySeptemberWorkToSeptember(
            String number, LeaveType type, String start, String end, long septemberMinutes, boolean shanghaiShift) {
        if (shanghaiShift) {
            List<ShiftSegmentRow> shifts = new ArrayList<>();
            // Same background range as fixture(): retain every loaded weekday.
            for (LocalDate day = date("2026-08-30"); day.isBefore(date("2026-10-04")); day = day.plusDays(1)) {
                if (day.getDayOfWeek().getValue() > 5) continue;
                boolean target = !day.isBefore(date("2026-08-31"))
                        && !day.isAfter(date("2026-09-04"));
                if (target) {
                    shifts.add(segment(day, "am", 9, 0, 12, 0));
                    shifts.add(segment(day, "pm", 13, 0, 18, 0));
                } else {
                    shifts.add(segment(day, "am", 8, 30, 12, 0));
                    shifts.add(segment(day, "pm", 13, 30, 18, 0));
                }
            }
            when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                    .thenReturn(shifts);
        }
        var leave = leave(number, type, Instant.parse(start), Instant.parse(end));
        documents(List.of(leave), List.of(report(leave, "APPROVED")));
        var fact = assemble().oaDocumentFacts().getFirst();
        assertThat(fact.recognizedMinutes()).isEqualTo(septemberMinutes);
        assertThat(fact.recognizedMinutesByDate()).doesNotContainKey(date("2026-08-31"));
        assertThat(fact.recognizedMinutesByDate().entrySet().stream()
                .filter(e -> e.getKey().getMonthValue() == 9).mapToLong(Map.Entry::getValue).sum()).isEqualTo(septemberMinutes);
    }

    @Test
    void fullRevocationIsAnExplicitEmptyAllocationNotMissingLegacyMetadata() {
        var leave = leave("revoked-all", LeaveType.COMPENSATORY, at("2026-08-31", 8, 30), at("2026-09-04", 18, 0));
        documents(List.of(leave, revocation("actual", leave, leave.endInstant(), leave.endInstant())),
                List.of(report(leave, "APPROVED")));
        var fact = assemble().oaDocumentFacts().getFirst();
        assertThat(fact.recognizedMinutes()).isZero();
        assertThat(fact.recognizedMinutesByDate()).isNotNull().isEmpty();
        assertThat(fact.intervalStart()).isEqualTo(leave.startInstant());
        assertThat(fact.intervalEndExclusive()).isEqualTo(leave.endInstant());
    }

    @Test
    void overlappingActualLeaveIntervalsAreUnionedBeforeAllocatingAndKeepGaps() {
        var leave = leave("revoked-pieces", LeaveType.ANNUAL, at("2026-08-31", 8, 30), at("2026-09-04", 18, 0));
        documents(List.of(leave,
                revocation("part1", leave, at("2026-08-31", 8, 30), at("2026-09-01", 12, 0)),
                revocation("duplicate", leave, at("2026-08-31", 8, 30), at("2026-09-01", 12, 0)),
                revocation("part2", leave, at("2026-09-03", 13, 30), at("2026-09-04", 18, 0))),
                List.of(report(leave, "APPROVED")));
        var fact = assemble().oaDocumentFacts().getFirst();
        assertThat(fact.recognizedMinutes()).isEqualTo(960);
        assertThat(fact.recognizedMinutesByDate()).containsExactlyInAnyOrderEntriesOf(Map.of(
                date("2026-09-01"), 210L,
                date("2026-09-03"), 270L, date("2026-09-04"), 480L));
        assertThat(fact.recognizedMinutesByDate().keySet())
                .allMatch(d -> d.getMonthValue() == 9);
        assertThat(fact.recognizedMinutesByDate().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(fact.recognizedMinutes());
    }

    @Test
    void approvedAndPendingLeaveKeepSeparateStatusAndSharesEvenWhenDailyEvidenceMerges() {
        var approved = leave("approved", LeaveType.ANNUAL, at("2026-08-31", 8, 30), at("2026-09-01", 12, 0));
        var pending = leave("pending", LeaveType.ANNUAL, at("2026-09-01", 8, 30), at("2026-09-01", 18, 0));
        documents(List.of(approved, pending), List.of(report(approved, "APPROVED"), report(pending, "PENDING")));
        var facts = assemble().oaDocumentFacts();
        assertThat(facts).hasSize(2);
        assertThat(facts).filteredOn(f -> f.sourceStatus().equals("APPROVED")).singleElement().satisfies(f -> {
            assertThat(f.recognizedMinutes()).isEqualTo(210);
            assertThat(f.recognizedMinutesByDate()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    date("2026-09-01"), 210L));
        });
        assertThat(facts).filteredOn(f -> f.sourceStatus().equals("PENDING")).singleElement().satisfies(f -> {
            assertThat(f.recognizedMinutes()).isEqualTo(480);
            assertThat(f.recognizedMinutesByDate()).isEqualTo(Map.of(date("2026-09-01"), 480L));
        });
    }

    @Test
    void sameDaySameTypeApprovedLeavesShareTheirOverlapOnceAndKeepBothDocuments() {
        var first = leave("annual-a", LeaveType.ANNUAL, at("2026-09-08", 9, 0), at("2026-09-08", 11, 0));
        var second = leave("annual-b", LeaveType.ANNUAL, at("2026-09-08", 10, 0), at("2026-09-08", 12, 0));
        documents(List.of(first, second), List.of(report(first, "APPROVED"), report(second, "APPROVED")));
        var result = assemble();
        long daily = result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(date("2026-09-08")))
                .findFirst().orElseThrow().leaveOrTimeOffMinutes();
        assertThat(daily).isEqualTo(180);
        var facts = result.oaDocumentFacts();
        assertThat(facts).extracting(f -> f.oaAttendanceDocumentId())
                .containsExactlyInAnyOrder("doc-annual-a", "doc-annual-b");
        assertThat(facts).allSatisfy(f -> {
            assertThat(f.sourceStatus()).isEqualTo("APPROVED");
            assertThat(f.recognizedMinutesByDate().values().stream().mapToLong(Long::longValue).sum())
                    .isEqualTo(f.recognizedMinutes());
        });
        assertThat(facts.stream().mapToLong(f -> f.recognizedMinutes()).sum()).isEqualTo(daily);
        assertThat(facts.stream().mapToLong(f -> f.recognizedMinutesByDate()
                .getOrDefault(date("2026-09-08"), 0L)).sum()).isEqualTo(180);
        // Original intervals stay intact for audit.
        assertThat(facts).filteredOn(f -> f.oaAttendanceDocumentId().equals("doc-annual-b")).singleElement()
                .satisfies(f -> {
                    assertThat(f.intervalStart()).isEqualTo(second.startInstant());
                    assertThat(f.intervalEndExclusive()).isEqualTo(second.endInstant());
                });
    }

    // Shift in fixture(): 08:30-12:00 and 13:30-18:00 on weekdays; 2026-09-08 is a Tuesday.
    @ParameterizedTest
    @CsvSource({
            // full same-type overlap: the earlier form keeps the day, the other stays as a 0-minute row
            "08:30,18:00,08:30,18:00,480,0,480",
            // partial overlap: 09:00-11:00 + 10:00-12:00 = 180
            "09:00,11:00,10:00,12:00,120,60,180",
            // adjacent, no overlap: unchanged
            "08:30,12:00,13:30,18:00,210,270,480",
            // lunch break: only WORK windows are shared (10-12,13:30-14 / 14-15)
            "10:00,14:00,11:00,15:00,150,60,210"
    })
    void sameTypeFormsShareEffectiveWorkWindowsOnce(String aStart, String aEnd, String bStart, String bEnd,
            long aMinutes, long bMinutes, long daily) {
        var a = leave("annual-a", LeaveType.ANNUAL, time("2026-09-08", aStart), time("2026-09-08", aEnd));
        var b = leave("annual-b", LeaveType.ANNUAL, time("2026-09-08", bStart), time("2026-09-08", bEnd));
        documents(List.of(a, b), List.of(report(a, "APPROVED"), report(b, "APPROVED")));
        var result = assemble();
        assertThat(dailyLeave(result, "2026-09-08")).isEqualTo(daily);
        assertThat(minutes(result, "doc-annual-a")).isEqualTo(aMinutes);
        assertThat(minutes(result, "doc-annual-b")).isEqualTo(bMinutes);
        assertThat(result.oaDocumentFacts()).allSatisfy(f ->
                assertThat(f.allocationSemantics()).isEqualTo("NET_WORK_V1"));
    }

    @Test
    void revokedRemainderNoLongerOverlapsEvenThoughRawIntervalsDo() {
        var a = leave("annual-a", LeaveType.ANNUAL, at("2026-09-08", 8, 30), at("2026-09-08", 18, 0));
        var b = leave("annual-b", LeaveType.ANNUAL, at("2026-09-08", 13, 30), at("2026-09-08", 18, 0));
        var actual = revocation("actual", a, at("2026-09-08", 8, 30), at("2026-09-08", 12, 0));
        documents(List.of(a, b, actual), List.of(report(a, "APPROVED"), report(b, "APPROVED")));
        var result = assemble();
        assertThat(minutes(result, "doc-annual-a")).isEqualTo(210);
        assertThat(minutes(result, "doc-annual-b")).isEqualTo(270);
        assertThat(dailyLeave(result, "2026-09-08")).isEqualTo(480);
    }

    @Test
    void differentLeaveTypesAreNotSharedWithEachOther() {
        var annual = leave("annual", LeaveType.ANNUAL, at("2026-09-08", 8, 30), at("2026-09-08", 12, 0));
        var sick = leave("sick", LeaveType.SICK, at("2026-09-08", 13, 30), at("2026-09-08", 18, 0));
        documents(List.of(annual, sick), List.of(report(annual, "APPROVED"), report(sick, "APPROVED")));
        var result = assemble();
        assertThat(minutes(result, "doc-annual")).isEqualTo(210);
        assertThat(minutes(result, "doc-sick")).isEqualTo(270);
    }

    @Test
    void crossMonthFormSharesOnlyInMonthWorkDays() {
        var wide = leave("annual-a", LeaveType.ANNUAL, at("2026-08-31", 8, 30), at("2026-09-02", 18, 0));
        var inner = leave("annual-b", LeaveType.ANNUAL, at("2026-09-01", 8, 30), at("2026-09-01", 18, 0));
        documents(List.of(wide, inner), List.of(report(wide, "APPROVED"), report(inner, "APPROVED")));
        var result = assemble();
        var wideFact = fact(result, "doc-annual-a");
        assertThat(wideFact.recognizedMinutesByDate()).isEqualTo(Map.of(
                date("2026-09-01"), 480L, date("2026-09-02"), 480L));
        assertThat(minutes(result, "doc-annual-b")).isZero();
        assertThat(wideFact.intervalStart()).isEqualTo(wide.startInstant());
        assertThat(dailyLeave(result, "2026-09-01")).isEqualTo(480);
    }

    @Test
    void windowedRecalcReEmitsAPartnerThatARevocationMovedIntoOverlap() {
        // The revocation moves annual-a from 09-08 (inside the window) to 09-09,
        // where it overlaps annual-b; annual-b lies wholly outside the window.
        var a = leave("annual-a", LeaveType.ANNUAL, at("2026-09-08", 8, 30), at("2026-09-08", 12, 0));
        var b = leave("annual-b", LeaveType.ANNUAL, at("2026-09-09", 8, 30), at("2026-09-09", 18, 0));
        var moved = revocation("moved", a, at("2026-09-09", 8, 30), at("2026-09-09", 12, 0));
        documents(List.of(a, b, moved), List.of(report(a, "APPROVED"), report(b, "APPROVED")));
        var result = engine.assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "audit", CUTOFF,
                date("2026-09-08"), date("2026-09-09"));
        assertThat(result.oaDocumentFacts()).extracting(f -> f.oaAttendanceDocumentId())
                .containsExactlyInAnyOrder("doc-annual-a", "doc-annual-b");
        assertThat(minutes(result, "doc-annual-a")).isEqualTo(210);
        assertThat(minutes(result, "doc-annual-b")).isEqualTo(270);
    }

    @Test
    void windowedRecalcReEmitsAFormWhoseOldOverlapPartnerWasRevokedAway() {
        // Old pin: annual-a (09-07..09-08) overlapped annual-b on 09-07, so b was
        // stored with a reduced share. A revocation now limits a to 09-08 only.
        // The window is 09-08; b lies wholly outside it and no longer overlaps
        // anything, yet its stored share must be replaced by the full 480.
        var a = leave("annual-a", LeaveType.ANNUAL, at("2026-09-07", 8, 30), at("2026-09-08", 18, 0));
        var b = leave("annual-b", LeaveType.ANNUAL, at("2026-09-07", 8, 30), at("2026-09-07", 18, 0));
        var actual = revocation("actual", a, at("2026-09-08", 8, 30), at("2026-09-08", 18, 0));
        documents(List.of(a, b, actual), List.of(report(a, "APPROVED"), report(b, "APPROVED")));
        var result = engine.assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "audit", CUTOFF,
                date("2026-09-08"), date("2026-09-09"));
        assertThat(result.oaDocumentFacts()).extracting(f -> f.oaAttendanceDocumentId())
                .contains("doc-annual-a", "doc-annual-b");
        assertThat(fact(result, "doc-annual-a").recognizedMinutesByDate())
                .isEqualTo(Map.of(date("2026-09-08"), 480L));
        assertThat(fact(result, "doc-annual-b").recognizedMinutesByDate())
                .isEqualTo(Map.of(date("2026-09-07"), 480L));
    }


    @Test
    void windowedRecalcMustAlsoRecalculateDailyFactsForTouchedDaysOutsideTheWindow() {
        // Old pin: employee had annual leave on 09-21. Narrow window (09-08..09-09)
        // triggers planner to expand to whole month, so 09-21 daily is recalculated.
        var earlyLeave = leave("annual-a", LeaveType.ANNUAL, at("2026-09-08", 8, 30), at("2026-09-08", 18, 0));
        var lateLeave = leave("annual-b", LeaveType.ANNUAL, at("2026-09-21", 8, 30), at("2026-09-21", 18, 0));
        var revoked = revocation("revoked", lateLeave, at("2026-09-21", 8, 30), at("2026-09-21", 8, 30));
        documents(List.of(earlyLeave, lateLeave, revoked),
                List.of(report(earlyLeave, "APPROVED"), report(lateLeave, "APPROVED")));
        
        // Stub old pin dependencies (before revocation)
        when(mapper.findProjectedOaDependencies(eq(COMPANY), eq("old-pin"), any()))
                .thenReturn(List.of(earlyLeave, lateLeave));
        when(mapper.findProjectedOaDependencies(eq(COMPANY), eq("old-pin")))
                .thenReturn(List.of(earlyLeave, lateLeave));
        // findHistoricallyAmendedLeaveKeys returns empty List by default (no stub needed)
        
        // Use planner to generate plan for narrow window (09-08..09-09)
        var plan = engine.planWindow(COMPANY, YearMonth.of(2026, 9),
                "old-pin", CUTOFF, date("2026-09-08"), date("2026-09-09"), List.of());
        // Planner expands window to whole month (09-01..10-01) for windowed recalc
        assertThat(plan.writeStartInclusive()).isEqualTo(date("2026-09-01"));
        assertThat(plan.writeEndExclusive()).isEqualTo(date("2026-10-01"));
        
        // Assemble with expanded plan
        var result = engine.assemblePlanned(plan, PeriodState.OPEN, "audit", CUTOFF);
        
        // OA facts: both documents kept for audit (annual-b revoked to 0 minutes)
        assertThat(result.oaDocumentFacts()).extracting(f -> f.oaAttendanceDocumentId())
                .contains("doc-annual-a", "doc-annual-b");
        assertThat(minutes(result, "doc-annual-b")).as("annual-b revoked to zero").isZero();
        
        // Daily facts: 09-21 MUST be recalculated (leave revoked to zero)
        var day21 = result.calculatedFacts().stream()
                .map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(date("2026-09-21")))
                .findFirst();
        assertThat(day21).as("09-21 daily fact must be recalculated").isPresent();
        assertThat(day21.get().leaveOrTimeOffMinutes()).as("09-21 leave revoked to zero").isZero();
    }

    @Test
    void windowedRecalcReEmitsTheSelectedEmployeesWholeMonthLeaveOnly() {
        var inWindow = leave("annual-a", LeaveType.ANNUAL, at("2026-09-08", 8, 30), at("2026-09-08", 18, 0));
        var unrelated = leave("annual-c", LeaveType.ANNUAL, at("2026-09-21", 8, 30), at("2026-09-21", 18, 0));
        var overtime = overtime("ot-late", at("2026-09-22", 18, 30), at("2026-09-22", 20, 30));
        documents(List.of(inWindow, unrelated, overtime, exemption()),
                List.of(report(inWindow, "APPROVED"), report(unrelated, "APPROVED"), report(overtime, "APPROVED")));
        var result = engine.assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "audit", CUTOFF,
                date("2026-09-08"), date("2026-09-09"));
        // Every LEAVE/TIME_OFF form of the run's employees is re-emitted whole;
        // other document types keep the original write-window rule.
        assertThat(result.oaDocumentFacts()).extracting(f -> f.oaAttendanceDocumentId())
                .containsExactlyInAnyOrder("doc-annual-a", "doc-annual-c");
        assertThat(result.calculatedFacts()).extracting(f -> f.facts().dailyFact().businessDate())
                .containsOnly(date("2026-09-08"));
    }

    private static long dailyLeave(PublishCommand result, String iso) {
        return result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(date(iso)))
                .findFirst().orElseThrow().leaveOrTimeOffMinutes();
    }
    private static com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedOaDocumentFact fact(
            PublishCommand result, String id) {
        return result.oaDocumentFacts().stream()
                .filter(f -> f.oaAttendanceDocumentId().equals(id)).findFirst().orElseThrow();
    }
    private static long minutes(PublishCommand result, String id) { return fact(result, id).recognizedMinutes(); }
    private static Instant time(String iso, String clock) {
        return date(iso).atTime(java.time.LocalTime.parse(clock)).atZone(ZONE).toInstant();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-08-31", "2026-09-30"})
    void overnightCompensatoryOvertimeBelongsToItsStartMonthWithoutSplittingAtMidnight(String iso) {
        LocalDate day = date(iso);
        var overtime = overtime("night", day.atTime(22, 0).atZone(ZONE).toInstant(),
                day.plusDays(1).atTime(2, 0).atZone(ZONE).toInstant());
        documents(List.of(overtime, exemption()), List.of(report(overtime, "APPROVED")));
        var fact = assemble().oaDocumentFacts().getFirst();
        assertThat(fact.recognizedMinutes()).isEqualTo(240);
        assertThat(fact.recognizedMinutesByDate()).isEqualTo(Map.of(day, 240L));
    }

    @Test
    void duplicateOvertimeFormsStillProduceOneShareAfterTheExistingDeduplication() {
        var first = overtime("one", at("2026-09-30", 18, 30), at("2026-10-01", 2, 0));
        var duplicate = overtime("two", first.startInstant(), first.endInstant());
        documents(List.of(first, duplicate, exemption()), List.of(report(first, "APPROVED"), report(duplicate, "APPROVED")));
        var result = assemble();
        assertThat(result.oaDocumentFacts()).singleElement().satisfies(f -> {
            assertThat(f.recognizedMinutes()).isEqualTo(450);
            assertThat(f.recognizedMinutesByDate()).isEqualTo(Map.of(date("2026-09-30"), 450L));
        });
        assertThat(result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(date("2026-09-30"))).findFirst().orElseThrow()
                .compensatoryOvertimeMinutes()).isEqualTo(450);
    }

    @Test
    void existingLongOvertimeIsNotTruncatedByAnInventedDailyMaximum() {
        var overtime = overtime("long-night", at("2026-08-31", 18, 30), at("2026-09-01", 21, 0));
        documents(List.of(overtime, exemption()), List.of(report(overtime, "APPROVED")));
        var fact = assemble().oaDocumentFacts().getFirst();
        assertThat(fact.recognizedMinutes()).isEqualTo(1590);
        assertThat(fact.recognizedMinutesByDate()).isEqualTo(Map.of(date("2026-08-31"), 1590L));
    }

    @Test
    void timeOffDocumentUsesTheSameWorkDateAllocationAsLeave() {
        Instant start = at("2026-08-31", 13, 30);
        Instant end = at("2026-09-01", 12, 0);
        var document = new OaDocumentRow("time-off", "TIME_OFF", null, LeaveType.COMPENSATORY,
                NUMBER, start, end, "Asia/Shanghai", start, true, null, null, "oa", EMPLOYEE);
        documents(List.of(document), List.of(report(document, "APPROVED")));
        var fact = assemble().oaDocumentFacts().getFirst();
        assertThat(fact.recognizedMinutes()).isEqualTo(210);
        assertThat(fact.recognizedMinutesByDate()).isEqualTo(Map.of(
                date("2026-09-01"), 210L));
    }

    @Test
    void failedSeptemberPunchGateHasAnExplicitEmptyAllocation() {
        var overtime = overtime("no-punch", at("2026-09-30", 18, 30), at("2026-10-01", 2, 0));
        documents(List.of(overtime), List.of(report(overtime, "APPROVED")));
        var fact = assemble().oaDocumentFacts().getFirst();
        assertThat(fact.recognizedMinutes()).isZero();
        assertThat(fact.recognizedMinutesByDate()).isNotNull().isEmpty();
    }

    private PublishCommand assemble() {
        return engine.assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "audit", CUTOFF);
    }
    private void documents(List<OaDocumentRow> effective, List<OaReportFactRow> report) {
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF))).thenReturn(effective);
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF))).thenReturn(report);
    }
    private static OaDocumentRow leave(String key, LeaveType type, Instant start, Instant end) {
        return new OaDocumentRow(key, "LEAVE", null, type, NUMBER, start, end, "Asia/Shanghai", start,
                true, "serial-" + key, null, "oa", EMPLOYEE);
    }
    private static OaDocumentRow revocation(String key, OaDocumentRow leave, Instant start, Instant end) {
        return new OaDocumentRow(key, "LEAVE_REVOCATION", null, null, NUMBER, start, end, "Asia/Shanghai", start,
                true, null, leave.leaveSerial(), "oa", EMPLOYEE);
    }
    private static OaDocumentRow overtime(String key, Instant start, Instant end) {
        return new OaDocumentRow(key, "OVERTIME", OvertimeType.COMPENSATORY, null, NUMBER, start, end,
                "Asia/Shanghai", start, true, null, null, "oa", EMPLOYEE);
    }
    private static OaDocumentRow exemption() {
        return new OaDocumentRow("exemption", "EXEMPT_PUNCH", null, null, NUMBER, at("2026-08-30", 0, 0),
                at("2026-10-02", 0, 0), "Asia/Shanghai", CUTOFF, true, null, null, "oa", EMPLOYEE);
    }
    private static OaReportFactRow report(OaDocumentRow row, String status) {
        return new OaReportFactRow("doc-" + row.sourceBusinessKey(), row.sourceBusinessKey(), row.documentType(),
                row.leaveType(), EMPLOYEE, NUMBER, "assignment", row.startInstant(), row.endInstant(), status, "v1", row.overtimeType());
    }
    private static ShiftSegmentRow segment(LocalDate day, String key, int startHour, int startMinute, int endHour, int endMinute) {
        Instant start = day.atTime(startHour, startMinute).atZone(ZONE).toInstant();
        Instant end = day.atTime(endHour, endMinute).atZone(ZONE).toInstant();
        return new ShiftSegmentRow(EMPLOYEE, day, key + day, start, end,
                start.minusSeconds(3600), start.plusSeconds(3600), end.minusSeconds(3600), end.plusSeconds(3600));
    }
    @Test
    void sickLeaveRestDayZeroMinuteEntriesExcludePostTerminationDates() {
        // Employee terminates on 2026-09-11 (employmentTo = 2026-09-12 exclusive).
        // SICK leave 2026-09-09 (Mon) through 2026-09-15 (Sun) spans termination.
        // Expected in-tenure dates: 09-09 Mon, 09-10 Tue, 09-11 Wed (last active).
        // Expected post-termination: 09-12 Thu, 09-13 Fri, 09-14 Sat, 09-15 Sun - must NOT appear.
        // Weekend REST_DAY 0-minute entries: 09-13 Sat, 09-14 Sat/Sun (if in tenure) should get 0.
        // But 09-13, 09-14 are post-termination, so must be excluded from byDate entirely.

        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(EMPLOYEE, "employee-v1", NUMBER, "测试员工", "assignment",
                        "org", "org-v1", "技术支持中心/现场服务部/现场技术服务组",
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 12), // employmentTo exclusive
                        LocalDate.of(2026, 8, 1), null,
                        LocalDate.of(2026, 8, 1), null)));

        List<CalendarDayRow> calendar = new ArrayList<>();
        List<ShiftSegmentRow> shifts = new ArrayList<>();
        // 09-09 Mon through 09-15 Sun
        for (LocalDate day = date("2026-09-09"); !day.isAfter(date("2026-09-15")); day = day.plusDays(1)) {
            boolean weekend = day.getDayOfWeek().getValue() > 5; // Sat=6, Sun=7
            calendar.add(new CalendarDayRow(EMPLOYEE, day, weekend ? "WEEKEND" : "WORKDAY", 1, 1));
            if (!weekend) {
                shifts.add(segment(day, "am", 8, 30, 12, 0));
                shifts.add(segment(day, "pm", 13, 30, 18, 0));
            }
        }
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(calendar);
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(shifts);

        var sickLeave = leave("sick-cross-term", LeaveType.SICK,
                at("2026-09-09", 8, 30), at("2026-09-15", 18, 0));
        documents(List.of(sickLeave), List.of(report(sickLeave, "APPROVED")));

        var result = assemble();
        var fact = result.oaDocumentFacts().stream()
                .filter(f -> f.oaAttendanceDocumentId().equals("doc-sick-cross-term"))
                .findFirst().orElseThrow();

        // In-tenure workdays: 09-09 Mon (480), 09-10 Tue (480), 09-11 Wed (480) = 1440 total
        assertThat(fact.recognizedMinutes()).isEqualTo(1440L);
        assertThat(fact.recognizedMinutesByDate())
                .containsEntry(date("2026-09-09"), 480L)
                .containsEntry(date("2026-09-10"), 480L)
                .containsEntry(date("2026-09-11"), 480L);

        // In-tenure weekend REST_DAY gets 0-minute marker - but none in this range before termination
        // (09-13 Sat, 09-14 Sun are post-termination)

        // Post-termination dates must NOT appear in byDate at all
        assertThat(fact.recognizedMinutesByDate()).doesNotContainKeys(
                date("2026-09-12"), // Thu post-termination
                date("2026-09-13"), // Fri post-termination
                date("2026-09-14"), // Sat post-termination
                date("2026-09-15")  // Sun post-termination
        );

        // Total must equal sum of byDate entries
        long total = fact.recognizedMinutesByDate().values().stream().mapToLong(Long::longValue).sum();
        assertThat(total).isEqualTo(fact.recognizedMinutes());
    }

    @Test
    void sickLeaveRestDayZeroMinuteEntriesExcludeGapBetweenEmploymentPeriods() {
        // Employee has two employment periods with a gap:
        // Period 1: 2026-09-01 to 2026-09-10 (employmentTo = 2026-09-11 exclusive)
        // Gap: 2026-09-11 to 2026-09-19
        // Period 2: 2026-09-20 to ongoing (employmentTo = null)
        // SICK leave 2026-09-09 (Wed) through 2026-09-22 (Tue) spans gap and re-hire.
        // Expected valid dates: 09-09 Wed, 09-10 Thu (period 1), then 09-21 Mon, 09-22 Tue (period 2).
        // Gap dates 09-11 through 09-19 must NOT appear.
        // Weekend REST_DAY 0-minute: 09-12 Sat, 09-13 Sun (in gap - excluded), 09-20 Sun (period 2 - included as 0).

        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        new EmployeeIdentityIntervalRow(EMPLOYEE, "employee-v1", NUMBER, "测试员工", "assignment-1",
                                "org", "org-v1", "技术支持中心/现场服务部/现场技术服务组",
                                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11), // period 1
                                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11),
                                LocalDate.of(2026, 9, 1), null),
                        new EmployeeIdentityIntervalRow(EMPLOYEE, "employee-v2", NUMBER, "测试员工", "assignment-2",
                                "org", "org-v1", "技术支持中心/现场服务部/现场技术服务组",
                                LocalDate.of(2026, 9, 20), null, // period 2 - ongoing
                                LocalDate.of(2026, 9, 20), null,
                                LocalDate.of(2026, 9, 20), null)
                ));

        List<CalendarDayRow> calendar = new ArrayList<>();
        List<ShiftSegmentRow> shifts = new ArrayList<>();
        // 09-09 Mon through 09-22 Sun
        for (LocalDate day = date("2026-09-09"); !day.isAfter(date("2026-09-22")); day = day.plusDays(1)) {
            boolean weekend = day.getDayOfWeek().getValue() > 5; // Sat=6, Sun=7
            calendar.add(new CalendarDayRow(EMPLOYEE, day, weekend ? "WEEKEND" : "WEEKDAY", 1, 1));
            if (!weekend) {
                shifts.add(segment(day, "am", 8, 30, 12, 0));
                shifts.add(segment(day, "pm", 13, 30, 18, 0));
            }
        }
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(calendar);
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(shifts);

        var sickLeave = leave("sick-gap-rehire", LeaveType.SICK,
                at("2026-09-09", 8, 30), at("2026-09-22", 18, 0));
        documents(List.of(sickLeave), List.of(report(sickLeave, "APPROVED")));

        var result = assemble();
        var fact = result.oaDocumentFacts().stream()
                .filter(f -> f.oaAttendanceDocumentId().equals("doc-sick-gap-rehire"))
                .findFirst().orElseThrow();

        // Period 1 workdays: 09-09 Wed (480), 09-10 Thu (480)
        // Period 2 workdays: 09-21 Mon (480), 09-22 Tue (480) = 1920 total
        assertThat(fact.recognizedMinutes()).isEqualTo(1920L);
        assertThat(fact.recognizedMinutesByDate())
                .containsEntry(date("2026-09-09"), 480L)
                .containsEntry(date("2026-09-10"), 480L)
                .containsEntry(date("2026-09-21"), 480L)
                .containsEntry(date("2026-09-22"), 480L);

        // Period 2 weekend REST_DAY gets 0-minute marker: 09-20 Sun
        assertThat(fact.recognizedMinutesByDate())
                .containsEntry(date("2026-09-20"), 0L);

        // Gap dates must NOT appear in byDate at all
        assertThat(fact.recognizedMinutesByDate()).doesNotContainKeys(
                date("2026-09-11"), date("2026-09-12"), date("2026-09-13"),
                date("2026-09-14"), date("2026-09-15"), date("2026-09-16"),
                date("2026-09-17"), date("2026-09-18"), date("2026-09-19")
        );

        // Total must equal sum of byDate entries (1920 work + 0 = 1920)
        long total = fact.recognizedMinutesByDate().values().stream().mapToLong(Long::longValue).sum();
        assertThat(total).isEqualTo(fact.recognizedMinutes());
    }

    private static LocalDate date(String iso) { return LocalDate.parse(iso); }
    private static Instant at(String iso, int hour, int minute) { return date(iso).atTime(hour, minute).atZone(ZONE).toInstant(); }
}
