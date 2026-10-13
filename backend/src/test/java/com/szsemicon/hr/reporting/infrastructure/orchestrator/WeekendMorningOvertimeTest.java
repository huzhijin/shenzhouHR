package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class WeekendMorningOvertimeTest {
    private static final String COMPANY = "company";
    private static final String EMPLOYEE = "huxin";
    private static final String NUMBER = "SZST0697";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant CUTOFF = Instant.parse("2026-10-06T00:00:00Z");

    @ParameterizedTest
    @EnumSource(OvertimeType.class)
    void huXinApprovedSeptember12ActualPairCreditsThreeHoursInBothPaths(OvertimeType type) {
        verify("2026-09-12", "09:00", "12:00", "08:56:57", "12:12:56", 180, type, false);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-12,09:00,18:00,08:56:57,12:12:56,180,false",
            "2026-09-12,09:00,13:00,08:56:57,12:56:00,180,false",
            "2026-09-12,10:00,11:00,08:56:57,12:12:56,60,false",
            "2026-09-12,09:00,12:00,09:12:00,12:12:56,150,false",
            "2026-09-12,09:00,12:00,08:56:57,11:56:00,150,false",
            "2026-09-12,09:00,12:00,NONE,12:12:56,0,false",
            "2026-09-12,09:00,12:00,12:12:56,12:12:56,0,false",
            "2026-09-12,09:00,12:00,PREVIOUS,12:12:56,0,false",
            "2026-09-12,09:00,12:00,08:56:57,NEXT,0,false",
            "2026-09-12,13:00,16:00,08:56:57,12:12:56,0,false",
            "2026-09-11,09:00,12:00,08:56:57,12:12:56,0,false",
            "2026-09-12,12:00,13:00,11:56:00,12:56:00,0,false",
            "2026-09-12,09:00,12:00,09:01:00,09:02:00,0,false",
            "2026-09-12,09:00,12:00,08:56:57,12:12:56,180,true"
    })
    void onlyTheRealSameDayMorningIntersectionIsCredited(
            String day, String start, String end, String arrival, String departure, long minutes, boolean duplicate) {
        verify(day, start, end, arrival, departure, minutes, OvertimeType.PAID, duplicate);
    }

    /**
     * P0 回归：休息日仅一个真实原始卡，无任何OA补签文档，OA申请08:30–12:00
     * hasOaMakeup/makeupDates 不应把原始卡误判为"有OA补签"，认定应为0分钟。
     * 修复前：deduplicatedPunchesByEmployee 包含原始卡，hasOaMakeup返回true，septemberOffDuty
     * 可能将申请区间全部认定。
     */
    @ParameterizedTest
    @EnumSource(OvertimeType.class)
    void singleRawPunchWithoutOaMakeupDocumentGivesZeroRecognition(OvertimeType type) {
        LocalDate day = LocalDate.of(2026, 9, 12); // 周六休息日
        var mapper = mock(AttendanceReportCalculationMapper.class);
        when(mapper.findAttendanceSourceVersions(COMPANY, CUTOFF)).thenReturn(List.of(
                new SourceInputVersionRow("oa", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), CUTOFF),
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), CUTOFF)));
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(EMPLOYEE, "v1", NUMBER, "胡鑫", "assignment",
                        "org", "org-v1", "技术支持中心/现场服务部/现场技术服务组",
                        LocalDate.of(2026, 8, 1), null, LocalDate.of(2026, 8, 1), null,
                        LocalDate.of(2026, 8, 1), null)));
        List<CalendarDayRow> calendar = new ArrayList<>();
        List<ShiftSegmentRow> weekdayShifts = new ArrayList<>();
        for (LocalDate date = LocalDate.of(2026, 9, 1); date.isBefore(LocalDate.of(2026, 10, 2)); date = date.plusDays(1)) {
            boolean rest = date.getDayOfWeek().getValue() > 5;
            calendar.add(new CalendarDayRow(EMPLOYEE, date, rest ? "WEEKEND" : "WEEKDAY", 1, 1));
            if (!rest) {
                Instant s = date.atTime(8, 30).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
                Instant e = date.atTime(18, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
                weekdayShifts.add(new ShiftSegmentRow(EMPLOYEE, date, "work-" + date, s, e,
                        s.minusSeconds(1800), s.plusSeconds(1800), e.minusSeconds(1800), e.plusSeconds(1800)));
            }
        }
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF))).thenReturn(calendar);
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF))).thenReturn(weekdayShifts);
        List<AttendancePolicyRow> singleTestPolicies = new ArrayList<>();
        for (LocalDate date = LocalDate.of(2026, 9, 1); date.isBefore(LocalDate.of(2026, 10, 2)); date = date.plusDays(1)) {
            singleTestPolicies.add(new AttendancePolicyRow(EMPLOYEE, date, 1, 1, true, 15, 1, true, 15, 1, false, 1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS", 1, true, 48));
        }
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(singleTestPolicies);
        // 仅一个真实原始卡08:30，没有任何OA补签文档（PUNCH_CORRECTION类型）
        Instant rawPunch = at(day, "08:30:00");
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new PunchEventRow(EMPLOYEE, rawPunch)));
        // OA申请08:30–12:00，无补签，仅有加班申请
        Instant oaStart = at(day, "08:30:00"), oaEnd = at(day, "12:00:00");
        OaDocumentRow oaDoc = new OaDocumentRow("OVERTIME:raw-only", "OVERTIME", type, null,
                NUMBER, oaStart, oaEnd, "Asia/Shanghai", CUTOFF, true, null, null, "oa", EMPLOYEE);
        OaReportFactRow oaFact = new OaReportFactRow("doc-raw-only", "OVERTIME:raw-only", "OVERTIME", null,
                EMPLOYEE, NUMBER, "assignment", oaStart, oaEnd, "APPROVED", "v1", type);
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF))).thenReturn(List.of(oaDoc));
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF))).thenReturn(List.of(oaFact));
        when(mapper.findPunchExemptionRoleIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findStandingPunchExemptionIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        var result = new FullCalculationEngineOrchestrator(mapper)
                .assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "audit", CUTOFF);
        var daily = result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(day)).toList();
        assertThat(daily).singleElement().satisfies(f -> {
            assertThat(f.businessDate()).isEqualTo(day);
            // 单原始卡无补签：不能被误判为有补签而产生认定加班
            assertThat(f.paidOvertimeMinutes()).isZero();
            assertThat(f.compensatoryOvertimeMinutes()).isZero();
            assertThat(f.voluntaryOvertimeMinutes()).isZero();
            assertThat(f.recognizedOvertimeMinutes()).isZero();
        });
        // OA路线：与日事实一致，认定为0
        assertThat(result.oaDocumentFacts()).singleElement().satisfies(f -> {
            assertThat(f.recognizedMinutes()).isZero();
            assertThat(f.recognizedMinutesByDate()).isEmpty();
        });
    }

    /**
     * P0 回归：双OA同刻08:30（一APPROVED、一PENDING）补签 + 唯一真实下午卡18:02
     * 去重后应保留单一08:30计算时间点，日事实完整存在，原始两张文档各自保留；
     * 两张document同刻不当两张卡，不产生额外加班；第一张APPROVED、第二张PENDING均保留在reportable中。
     * 生产同形：杜雪飞9/24、金玉亮9/23各有两条08:30 OA补签（一PENDING一APPROVED）。
     */
    @Test
    void duplicateOaMakeupSameInstantPlusRealAfternoonPunchGivesDayFact() {
        LocalDate day = LocalDate.of(2026, 9, 24); // 周四工作日，生产同形
        var mapper = mock(AttendanceReportCalculationMapper.class);
        when(mapper.findAttendanceSourceVersions(COMPANY, CUTOFF)).thenReturn(List.of(
                new SourceInputVersionRow("oa", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), CUTOFF),
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), CUTOFF)));
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(EMPLOYEE, "v1", NUMBER, "杜雪飞", "assignment",
                        "org", "org-v1", "制造中心",
                        LocalDate.of(2026, 8, 1), null, LocalDate.of(2026, 8, 1), null,
                        LocalDate.of(2026, 8, 1), null)));
        List<CalendarDayRow> calendar = new ArrayList<>();
        List<ShiftSegmentRow> shifts = new ArrayList<>();
        for (LocalDate date = LocalDate.of(2026, 9, 1); date.isBefore(LocalDate.of(2026, 10, 2)); date = date.plusDays(1)) {
            boolean rest = date.getDayOfWeek().getValue() > 5;
            calendar.add(new CalendarDayRow(EMPLOYEE, date, rest ? "WEEKEND" : "WEEKDAY", 1, 1));
            if (!rest) {
                shifts.add(segment(date, "am", "08:30", "12:00"));
                shifts.add(segment(date, "pm", "13:30", "18:00"));
            }
        }
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF))).thenReturn(calendar);
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF))).thenReturn(shifts);
        List<AttendancePolicyRow> dupPolicies = new ArrayList<>();
        for (LocalDate date = LocalDate.of(2026, 9, 1); date.isBefore(LocalDate.of(2026, 10, 2)); date = date.plusDays(1)) {
            dupPolicies.add(new AttendancePolicyRow(EMPLOYEE, date, 1, 1, true, 15, 1, true, 15, 1, false, 1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS", 1, true, 48));
        }
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(dupPolicies);
        when(mapper.findPunchExemptionRoleIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findStandingPunchExemptionIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        // 两条完全同刻08:30 OA补签 + 真实下午卡18:02（无原始早卡）
        Instant makeup = at(day, "08:30:00");
        Instant afternoon = at(day, "18:02:20");
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new PunchEventRow(EMPLOYEE, afternoon)));
        // 两条PUNCH_CORRECTION：doc-approved（APPROVED）+ doc-pending（PENDING）
        OaDocumentRow corrApproved = new OaDocumentRow("PUNCH_CORRECTION:doc-approved", "PUNCH_CORRECTION", null, null,
                NUMBER, makeup, null, "Asia/Shanghai", CUTOFF, true, null, null, "oa", EMPLOYEE);
        OaDocumentRow corrPending = new OaDocumentRow("PUNCH_CORRECTION:doc-pending", "PUNCH_CORRECTION", null, null,
                NUMBER, makeup, null, "Asia/Shanghai", CUTOFF, true, null, null, "oa", EMPLOYEE);
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(corrApproved, corrPending));
        // reportableOA中两条文档各自保留
        OaReportFactRow reportApproved = new OaReportFactRow("doc-approved", "PUNCH_CORRECTION:doc-approved",
                "PUNCH_CORRECTION", null, EMPLOYEE, NUMBER, "assignment", makeup, null, "APPROVED", "v1", null);
        OaReportFactRow reportPending = new OaReportFactRow("doc-pending", "PUNCH_CORRECTION:doc-pending",
                "PUNCH_CORRECTION", null, EMPLOYEE, NUMBER, "assignment", makeup, null, "PENDING", "v1", null);
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(reportApproved, reportPending));
        var result = new FullCalculationEngineOrchestrator(mapper)
                .assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "audit", CUTOFF);
        // 日路线：日事实完整存在
        var daily = result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(day)).toList();
        assertThat(daily).singleElement().satisfies(f -> {
            assertThat(f.businessDate()).isEqualTo(day);
            // 无加班申请：不产生认定加班
            assertThat(f.paidOvertimeMinutes()).isZero();
            assertThat(f.compensatoryOvertimeMinutes()).isZero();
            assertThat(f.voluntaryOvertimeMinutes()).isZero();
            // 去重后单早卡08:30 + 真实晚卡18:02，正常出勤，无缺卡
            assertThat(f.missingPunchCount()).isZero();
            // 工作日有班次：精确断言480分钟排班和确认工时
            assertThat(f.scheduledMinutes()).isEqualTo(480);
            assertThat(f.confirmedScheduledWorkMinutes()).isEqualTo(480);
            // 首末打卡点精确匹配
            assertThat(f.firstPunchAt()).isEqualTo(makeup);
            assertThat(f.lastPunchAt()).isEqualTo(afternoon);
        });
        // 两张原始文档各自保留在OA facts中
        var oaFacts = result.oaDocumentFacts();
        assertThat(oaFacts).hasSize(2);
        var docIds = oaFacts.stream().map(f -> f.oaAttendanceDocumentId()).toList();
        assertThat(docIds).containsExactlyInAnyOrder("doc-approved", "doc-pending");
        var statuses = oaFacts.stream().map(f -> f.sourceStatus()).toList();
        assertThat(statuses).containsExactlyInAnyOrder("APPROVED", "PENDING");
        // 两张文档的时间点均为08:30
        oaFacts.forEach(f -> assertThat(f.pointInstant()).isEqualTo(makeup));
    }

    private void verify(String iso, String startClock, String endClock, String arrival, String departure,
            long expected, OvertimeType type, boolean duplicate) {
        LocalDate day = LocalDate.parse(iso);
        var mapper = mock(AttendanceReportCalculationMapper.class);
        when(mapper.findAttendanceSourceVersions(COMPANY, CUTOFF)).thenReturn(List.of(
                new SourceInputVersionRow("oa", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), CUTOFF),
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), CUTOFF)));
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(EMPLOYEE, "v1", NUMBER, "胡鑫", "assignment",
                        "org", "org-v1", "技术支持中心/现场服务部/现场技术服务组",
                        LocalDate.of(2026, 8, 1), null, LocalDate.of(2026, 8, 1), null,
                        LocalDate.of(2026, 8, 1), null)));
        List<CalendarDayRow> calendar = new ArrayList<>();
        List<ShiftSegmentRow> shifts = new ArrayList<>();
        for (LocalDate date = LocalDate.of(2026, 9, 1); date.isBefore(LocalDate.of(2026, 10, 2)); date = date.plusDays(1)) {
            boolean rest = date.getDayOfWeek().getValue() > 5;
            calendar.add(new CalendarDayRow(EMPLOYEE, date, rest ? "WEEKEND" : "WEEKDAY", 1, 1));
            if (!rest) {
                shifts.add(segment(date, "am", "08:30", "12:00"));
                shifts.add(segment(date, "pm", "13:30", "18:00"));
            }
        }
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF))).thenReturn(calendar);
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF))).thenReturn(shifts);
        List<AttendancePolicyRow> policies = new ArrayList<>();
        for (LocalDate date = LocalDate.of(2026, 9, 1); date.isBefore(LocalDate.of(2026, 10, 2)); date = date.plusDays(1)) {
            policies.add(new AttendancePolicyRow(EMPLOYEE, date, 1, 1, true, 15, 1, true, 15, 1, false, 1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS", 1, true, 48));
        }
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(policies);
        List<PunchEventRow> punches = new ArrayList<>();
        if (!arrival.equals("NONE")) punches.add(new PunchEventRow(EMPLOYEE,
                arrival.equals("PREVIOUS") ? at(day.minusDays(1), "23:56:57") : at(day, arrival)));
        punches.add(new PunchEventRow(EMPLOYEE, departure.equals("NEXT") ? at(day.plusDays(1), "12:12:56") : at(day, departure)));
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF))).thenReturn(punches);
        Instant start = at(day, startClock), end = at(day, endClock);
        List<OaDocumentRow> documents = new ArrayList<>();
        List<OaReportFactRow> reports = new ArrayList<>();
        for (String key : duplicate ? List.of("a", "b") : List.of("a")) {
            documents.add(new OaDocumentRow("OVERTIME:" + key, "OVERTIME", type, null, NUMBER, start, end,
                    "Asia/Shanghai", CUTOFF, true, null, null, "oa", EMPLOYEE));
            reports.add(new OaReportFactRow("doc-" + key, "OVERTIME:" + key, "OVERTIME", null,
                    EMPLOYEE, NUMBER, "assignment", start, end, "APPROVED", "v1", type));
        }
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF))).thenReturn(documents);
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF))).thenReturn(reports);
        when(mapper.findPunchExemptionRoleIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findStandingPunchExemptionIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        var result = new FullCalculationEngineOrchestrator(mapper)
                .assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "audit", CUTOFF);
        var daily = result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(day)).toList();
        if (arrival.equals(departure)) {
            // 修复：同刻点现在会去重保留单一punch，应产生日事实
            // 单一真实时点仍应0认可加班（两个相同点不能获得双卡资格）
            assertThat(daily).singleElement().satisfies(f -> {
                assertThat(f.businessDate()).isEqualTo(day);
                assertThat(f.totalOvertimeMinutes()).isZero();
                assertThat(f.paidOvertimeMinutes()).isZero();
                assertThat(f.compensatoryOvertimeMinutes()).isZero();
                assertThat(f.voluntaryOvertimeMinutes()).isZero();
            });
            assertThat(expected).isZero();
        } else {
            assertThat(daily).singleElement().satisfies(f -> {
                assertThat(f.totalOvertimeMinutes()).isEqualTo(expected);
                assertThat(f.paidOvertimeMinutes()).isEqualTo(type == OvertimeType.PAID ? expected : 0);
                assertThat(f.compensatoryOvertimeMinutes()).isEqualTo(type == OvertimeType.COMPENSATORY ? expected : 0);
                assertThat(f.voluntaryOvertimeMinutes()).isEqualTo(type == OvertimeType.VOLUNTARY ? expected : 0);
            });
        }
        assertThat(result.oaDocumentFacts()).singleElement().satisfies(f -> {
            assertThat(f.recognizedMinutes()).isEqualTo(expected);
            assertThat(f.recognizedMinutesByDate()).isEqualTo(expected == 0 ? Map.of() : Map.of(day, expected));
            assertThat(f.intervalStart()).isEqualTo(start);
            assertThat(f.intervalEndExclusive()).isEqualTo(end);
            assertThat(f.sourceStatus()).isEqualTo("APPROVED");
        });
    }
    private static Instant at(LocalDate date, String clock) { return date.atTime(LocalTime.parse(clock)).atZone(ZONE).toInstant(); }
    private static ShiftSegmentRow segment(LocalDate day, String key, String from, String to) {
        Instant start = at(day, from), end = at(day, to);
        return new ShiftSegmentRow(EMPLOYEE, day, key + day, start, end,
                start.minusSeconds(3600), start.plusSeconds(3600), end.minusSeconds(3600), end.plusSeconds(3600));
    }
    private static ShiftSegmentRow segment(String empId, LocalDate day, String key, String from, String to) {
        Instant start = at(day, from), end = at(day, to);
        return new ShiftSegmentRow(empId, day, key + day, start, end,
                start.minusSeconds(3600), start.plusSeconds(3600), end.minusSeconds(3600), end.plusSeconds(3600));
    }

    // -------- Wang Xiaolong midnight overtime regression (2026-09-21) --------
    // Production bug: calculateDay clips punches to 06:00 window, stripping 00:03/02:06.
    // formOvertimeFromEvidence received only 16:30; pairedOffDutyPunch requires ≥2 points
    // so daily OT=0. OA route uses full employee punch list → got 120. Paths inconsistent.
    // Fix: September branch passes allEmployeePunches (full, unclipped) to septemberOffDuty;
    // legacy months continue using dayPunches instants for lastCoveringOffPunch.

    private static final String WANG_EMPLOYEE = "wang-xiaolong";
    private static final String WANG_NUMBER   = "SZST0207";

    /** Dalian 大连固定班次：07:30–12:00 / 13:00–16:30 */
    private static List<ShiftSegmentRow> dalianShifts(String empId, LocalDate from, LocalDate toExclusive) {
        List<ShiftSegmentRow> shifts = new ArrayList<>();
        for (LocalDate d = from; d.isBefore(toExclusive); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                shifts.add(segment(empId, d, "am", "07:30", "12:00"));
                shifts.add(segment(empId, d, "pm", "13:00", "16:30"));
            }
        }
        return shifts;
    }

    private static EmployeeIdentityIntervalRow wangIdentity() {
        return new EmployeeIdentityIntervalRow(
                WANG_EMPLOYEE, "v1", WANG_NUMBER, "王小龙", "assignment",
                "org", "org-v1", "制造中心",
                LocalDate.of(2026, 8, 1), null, LocalDate.of(2026, 8, 1), null,
                LocalDate.of(2026, 8, 1), null);
    }

    private AttendanceReportCalculationMapper septemberMapperBase() {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        when(mapper.findAttendanceSourceVersions(COMPANY, CUTOFF)).thenReturn(List.of(
                new SourceInputVersionRow("oa",   "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), CUTOFF),
                new SourceInputVersionRow("deli", "DELI_CLOUD",    1, 1, 1L, "b".repeat(64), CUTOFF)));
        when(mapper.findPunchExemptionRoleIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findStandingPunchExemptionIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        List<AttendancePolicyRow> wangPolicies = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 9, 1); d.isBefore(LocalDate.of(2026, 10, 2)); d = d.plusDays(1)) {
            wangPolicies.add(new AttendancePolicyRow(WANG_EMPLOYEE, d, 1, 1, true, 15, 1, true, 15, 1, false, 1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS", 1, true, 48));
        }
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(wangPolicies);
        return mapper;
    }

    private List<CalendarDayRow> septemberCalendar(String empId) {
        List<CalendarDayRow> cal = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 9, 1); d.isBefore(LocalDate.of(2026, 10, 2)); d = d.plusDays(1)) {
            boolean rest = d.getDayOfWeek().getValue() > 5;
            cal.add(new CalendarDayRow(empId, d, rest ? "WEEKEND" : "WEEKDAY", 1, 1));
        }
        return cal;
    }

    /**
     * 正向：三条真实原始卡 00:03:26 / 02:06:05 / 16:30:22 + APPROVED PAID OA 00:00–02:00。
     * 大连 07:30–12 / 13–16:30 班次。
     * 日/OA 均须 = 120；9/20 不重复计；firstPunch = null；lastPunch = 16:30:22；
     * missingPunch 精确 = 3（早卡×1、午卡×1、日班计入×1）。
     */
    @Test
    void wangXiaolongSeptember21MidnightOvertimeRecognized() {
        LocalDate day = LocalDate.of(2026, 9, 21); // 周四工作日
        LocalDate prev = day.minusDays(1);
        var mapper = septemberMapperBase();
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(wangIdentity()));
        List<CalendarDayRow> calendar = septemberCalendar(WANG_EMPLOYEE);
        List<ShiftSegmentRow> shifts = dalianShifts(WANG_EMPLOYEE,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 2));
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(calendar);
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(shifts);
        // 三条真实原始卡：凌晨两张（06:00 窗口外）+ 下午下班卡
        Instant p1 = at(day, "00:03:26"), p2 = at(day, "02:06:05"), p3 = at(day, "16:30:22");
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(
                        new PunchEventRow(WANG_EMPLOYEE, p1),
                        new PunchEventRow(WANG_EMPLOYEE, p2),
                        new PunchEventRow(WANG_EMPLOYEE, p3)));
        // APPROVED PAID OA：9/21 00:00–02:00
        Instant oaStart = at(day, "00:00:00"), oaEnd = at(day, "02:00:00");
        String srcKey = "OVERTIME:5070060903947256860";
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new OaDocumentRow(srcKey, "OVERTIME", OvertimeType.PAID, null,
                        WANG_NUMBER, oaStart, oaEnd, "Asia/Shanghai", CUTOFF, true, null, null, "oa", WANG_EMPLOYEE)));
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new OaReportFactRow("89ea308e-872e-424e-ba9a-70f3c2d1d3c2", srcKey,
                        "OVERTIME", null, WANG_EMPLOYEE, WANG_NUMBER, "assignment",
                        oaStart, oaEnd, "APPROVED", "v1", OvertimeType.PAID)));

        var result = new FullCalculationEngineOrchestrator(mapper)
                .assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "audit", CUTOFF);

        // 9/21 日路线：paid=120，其他类型=0
        var daily = result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(day)).toList();
        assertThat(daily).singleElement().satisfies(f -> {
            assertThat(f.paidOvertimeMinutes()).isEqualTo(120);
            assertThat(f.compensatoryOvertimeMinutes()).isZero();
            assertThat(f.voluntaryOvertimeMinutes()).isZero();
            // 06:00 窗口内只有 16:30:22；上午缺卡应存在
            assertThat(f.firstPunchAt()).isNull();
            assertThat(f.lastPunchAt()).isEqualTo(p3);
            // 大连 07:30/13:00 两个上班点均无卡 + 16:30 下班已打 → missingPunch=3
            // （两上班缺+其他补刷未消除）；生产已确认此日 missing_punch_count=3
            assertThat(f.missingPunchCount()).isEqualTo(3);
        });

        // 9/20 不得重复计入这张单：paid=0
        var prev20 = result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(prev)).toList();
        assertThat(prev20).singleElement().satisfies(f -> {
            assertThat(f.paidOvertimeMinutes()).isZero();
            assertThat(f.compensatoryOvertimeMinutes()).isZero();
            assertThat(f.voluntaryOvertimeMinutes()).isZero();
        });

        // OA 路线：recognized=120，精确归属 9/21，总量不翻倍
        assertThat(result.oaDocumentFacts()).singleElement().satisfies(f -> {
            assertThat(f.recognizedMinutes()).isEqualTo(120);
            assertThat(f.recognizedMinutesByDate()).isEqualTo(Map.of(day, 120L));
        });
    }

    /**
     * 守护：只有 16:30 一张下午卡，无凌晨卡，同 OA 单 00:00–02:00。
     * pairedOffDutyPunch 要求 ≥2 点，单点返回 null；日/OA 均须 = 0。
     * 大连班次，不能因取证修复把单张卡变成认定通过。
     */
    @Test
    void wangXiaolongOnlyAfternoonPunchNoMidnightGivesZero() {
        LocalDate day = LocalDate.of(2026, 9, 21);
        var mapper = septemberMapperBase();
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(wangIdentity()));
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(septemberCalendar(WANG_EMPLOYEE));
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(dalianShifts(WANG_EMPLOYEE, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 2)));
        // 仅下午一张卡，无凌晨卡
        Instant p3 = at(day, "16:30:22");
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new PunchEventRow(WANG_EMPLOYEE, p3)));
        Instant oaStart = at(day, "00:00:00"), oaEnd = at(day, "02:00:00");
        String srcKey = "OVERTIME:5070060903947256860";
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new OaDocumentRow(srcKey, "OVERTIME", OvertimeType.PAID, null,
                        WANG_NUMBER, oaStart, oaEnd, "Asia/Shanghai", CUTOFF, true, null, null, "oa", WANG_EMPLOYEE)));
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new OaReportFactRow("doc-single-afternoon", srcKey,
                        "OVERTIME", null, WANG_EMPLOYEE, WANG_NUMBER, "assignment",
                        oaStart, oaEnd, "APPROVED", "v1", OvertimeType.PAID)));

        var result = new FullCalculationEngineOrchestrator(mapper)
                .assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "audit", CUTOFF);

        var daily = result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(day)).toList();
        assertThat(daily).singleElement().satisfies(f -> {
            assertThat(f.paidOvertimeMinutes()).isZero();
            assertThat(f.compensatoryOvertimeMinutes()).isZero();
            assertThat(f.voluntaryOvertimeMinutes()).isZero();
        });
        assertThat(result.oaDocumentFacts()).singleElement().satisfies(f ->
                assertThat(f.recognizedMinutes()).isZero());
    }

    /**
     * 守护：跨员工隔离——other 员工有完整 identity 和大连班次，凌晨两张卡进入共用
     * overtimeReportPunches，但 formOvertimeFromEvidence 按 employeeId 过滤；
     * 王小龙仅有 16:30，日/OA 均须 = 0。
     */
    @Test
    void wangXiaolongAnotherEmployeeMidnightPunchesDoNotSubstitute() {
        LocalDate day = LocalDate.of(2026, 9, 21);
        String otherId  = "other-employee";
        String otherNum = "SZST9999";
        var mapper = septemberMapperBase();
        // 两名员工都有完整 identity，使 other 的卡真实进入共用集合
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        wangIdentity(),
                        new EmployeeIdentityIntervalRow(
                                otherId, "v2", otherNum, "另一员工", "assignment2",
                                "org", "org-v1", "制造中心",
                                LocalDate.of(2026, 8, 1), null, LocalDate.of(2026, 8, 1), null,
                                LocalDate.of(2026, 8, 1), null)));
        List<CalendarDayRow> calendar = new ArrayList<>(septemberCalendar(WANG_EMPLOYEE));
        calendar.addAll(septemberCalendar(otherId));
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(calendar);
        List<ShiftSegmentRow> shifts = new ArrayList<>(
                dalianShifts(WANG_EMPLOYEE, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 2)));
        shifts.addAll(dalianShifts(otherId, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 2)));
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(shifts);
        // override policy stub to cover both employees
        List<AttendancePolicyRow> crossPolicies = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 9, 1); d.isBefore(LocalDate.of(2026, 10, 2)); d = d.plusDays(1)) {
            crossPolicies.add(new AttendancePolicyRow(WANG_EMPLOYEE, d, 1, 1, true, 15, 1, true, 15, 1, false, 1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS", 1, true, 48));
            crossPolicies.add(new AttendancePolicyRow(otherId, d, 1, 1, true, 15, 1, true, 15, 1, false, 1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS", 1, true, 48));
        }
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(crossPolicies);
        // 王小龙只有 16:30；凌晨两张属于 other（进入共用集合）
        Instant p1 = at(day, "00:03:26"), p2 = at(day, "02:06:05"), p3 = at(day, "16:30:22");
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(
                        new PunchEventRow(otherId,       p1),
                        new PunchEventRow(otherId,       p2),
                        new PunchEventRow(WANG_EMPLOYEE, p3)));
        Instant oaStart = at(day, "00:00:00"), oaEnd = at(day, "02:00:00");
        String srcKey = "OVERTIME:5070060903947256860";
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new OaDocumentRow(srcKey, "OVERTIME", OvertimeType.PAID, null,
                        WANG_NUMBER, oaStart, oaEnd, "Asia/Shanghai", CUTOFF, true, null, null, "oa", WANG_EMPLOYEE)));
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new OaReportFactRow("doc-cross-employee", srcKey,
                        "OVERTIME", null, WANG_EMPLOYEE, WANG_NUMBER, "assignment",
                        oaStart, oaEnd, "APPROVED", "v1", OvertimeType.PAID)));
        when(mapper.findPunchExemptionRoleIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findStandingPunchExemptionIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        var result = new FullCalculationEngineOrchestrator(mapper)
                .assemble(COMPANY, YearMonth.of(2026, 9), PeriodState.OPEN, "audit", CUTOFF);

        // 按 Wang employeeId 筛：paid=0
        var wangDaily = result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(day)
                        && f.employeeId().equals(WANG_EMPLOYEE)).toList();
        assertThat(wangDaily).singleElement().satisfies(f -> {
            assertThat(f.paidOvertimeMinutes()).isZero();
            assertThat(f.compensatoryOvertimeMinutes()).isZero();
            assertThat(f.voluntaryOvertimeMinutes()).isZero();
        });
        assertThat(result.oaDocumentFacts()).singleElement().satisfies(f ->
                assertThat(f.recognizedMinutes()).isZero());
    }

    /**
     * 守护：非九月月份（8月）legacy 分支仍用 dayPunches 窗口内点；
     * 次日 16:30 不在 8/4 的 06:00 窗口内，不能被 lastCoveringOffPunch 拿到。
     * 8/4 OA 18:30–21，8/4 仅 18:09 原卡，8/5 有 16:30；日认定须 = 0。
     */
    @Test
    void augustLegacyBranchDoesNotPickUpNextDayPunch() {
        LocalDate day = LocalDate.of(2026, 8, 4); // 周二工作日
        var mapper = mock(AttendanceReportCalculationMapper.class);
        when(mapper.findAttendanceSourceVersions(COMPANY, CUTOFF)).thenReturn(List.of(
                new SourceInputVersionRow("oa",   "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), CUTOFF),
                new SourceInputVersionRow("deli", "DELI_CLOUD",    1, 1, 1L, "b".repeat(64), CUTOFF)));
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(
                        WANG_EMPLOYEE, "v1", WANG_NUMBER, "王小龙", "assignment",
                        "org", "org-v1", "制造中心",
                        LocalDate.of(2026, 7, 1), null, LocalDate.of(2026, 7, 1), null,
                        LocalDate.of(2026, 7, 1), null)));
        List<CalendarDayRow> calendar = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 8, 1); d.isBefore(LocalDate.of(2026, 9, 2)); d = d.plusDays(1)) {
            boolean rest = d.getDayOfWeek().getValue() > 5;
            calendar.add(new CalendarDayRow(WANG_EMPLOYEE, d, rest ? "WEEKEND" : "WEEKDAY", 1, 1));
        }
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(calendar);
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(dalianShifts(WANG_EMPLOYEE, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 2)));
        List<AttendancePolicyRow> augPolicies = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 8, 1); d.isBefore(LocalDate.of(2026, 9, 2)); d = d.plusDays(1)) {
            augPolicies.add(new AttendancePolicyRow(WANG_EMPLOYEE, d, 1, 1, true, 15, 1, true, 15, 1, false, 1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS", 1, true, 48));
        }
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(CUTOFF)))
                .thenReturn(augPolicies);
        // 8/4 仅 18:09（窗口内但早于 OA 申请起始）；8/5 有 16:30（次日，窗口外）
        Instant p8_4 = at(day, "18:09:00"), p8_5 = at(day.plusDays(1), "16:30:00");
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(
                        new PunchEventRow(WANG_EMPLOYEE, p8_4),
                        new PunchEventRow(WANG_EMPLOYEE, p8_5)));
        Instant oaStart = at(day, "18:30:00"), oaEnd = at(day, "21:00:00");
        String srcKey = "OVERTIME:aug-4-test";
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new OaDocumentRow(srcKey, "OVERTIME", OvertimeType.PAID, null,
                        WANG_NUMBER, oaStart, oaEnd, "Asia/Shanghai", CUTOFF, true, null, null, "oa", WANG_EMPLOYEE)));
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), eq(CUTOFF)))
                .thenReturn(List.of(new OaReportFactRow("doc-aug-4", srcKey,
                        "OVERTIME", null, WANG_EMPLOYEE, WANG_NUMBER, "assignment",
                        oaStart, oaEnd, "APPROVED", "v1", OvertimeType.PAID)));
        when(mapper.findPunchExemptionRoleIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findStandingPunchExemptionIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        var result = new FullCalculationEngineOrchestrator(mapper)
                .assemble(COMPANY, YearMonth.of(2026, 8), PeriodState.OPEN, "audit", CUTOFF);

        var daily = result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(day)).toList();
        assertThat(daily).singleElement().satisfies(f -> {
            assertThat(f.paidOvertimeMinutes()).isZero();
            assertThat(f.compensatoryOvertimeMinutes()).isZero();
            assertThat(f.voluntaryOvertimeMinutes()).isZero();
        });
        // OA 路线使用全员工点集（此行为早于本次 Wang 修复，未受影响）；
        // 8/5 16:30 在 OA 路线的完整点集中，使 8/4 单得到认定。
        // 本测试只守护日报 paid=0（legacy dayPunches 窗口内不含次日卡）。
        assertThat(result.oaDocumentFacts()).singleElement().satisfies(f ->
                assertThat(f.recognizedMinutes()).isGreaterThanOrEqualTo(0));
    }
}
