package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.application.QueryPageRows.EmployeeDailyAggregateRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.OaRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttendanceReportQueryPageWorkHoursTest {

    @Test
    void annualLeaveIsNotCountedTwiceAndActualHoursUseOfficialFormula() {
        EmployeeDailyAggregateRow row = new EmployeeDailyAggregateRow(
                "emp-1",
                "SZST0048",
                "张珍珍",
                "org-1",
                "采购部",
                0,
                0,
                0,
                0,
                128 * 60,
                90,
                0,
                0,
                270,
                270,
                0,
                0,
                16,
                BigDecimal.ONE,
                "无班次");

        Map<String, Object> mapped = AttendanceReportQueryPageService.workHoursRow(
                row,
                List.of(oa("LEAVE", "ANNUAL", 270)));

        assertThat(mapped.get("leaveHours")).isEqualTo(0.0);
        assertThat(mapped.get("annualLeaveHours")).isEqualTo(4.5);
        assertThat(mapped.get("compensatoryOvertimeHours")).isEqualTo(0.0);
        assertThat(mapped.get("timeOffHours")).isEqualTo(0.0);
        assertThat(mapped.get("actualHours")).isEqualTo(128.0 + 1.5 - 4.5);
        assertThat(mapped.get("note")).isNull();
    }

    @Test
    void timeOffLeaveIsNotRolledIntoOtherLeaveAndExchangedOvertimeIsShown() {
        EmployeeDailyAggregateRow row = new EmployeeDailyAggregateRow(
                "emp-1",
                "SZST0036",
                "颜倩",
                "org-1",
                "人事部",
                0,
                0,
                0,
                0,
                168 * 60,
                45 * 60,
                8 * 60,
                0,
                4 * 60 + 270,
                0,
                270,
                0,
                22,
                BigDecimal.ONE,
                null);

        Map<String, Object> mapped = AttendanceReportQueryPageService.workHoursRow(
                row,
                List.of(
                        oa("LEAVE", "PERSONAL", 4 * 60),
                        oa("TIME_OFF", "TIME_OFF", 270)));

        assertThat(mapped.get("leaveHours")).isEqualTo(4.0);
        assertThat(mapped.get("annualLeaveHours")).isEqualTo(0.0);
        assertThat(mapped.get("compensatoryOvertimeHours")).isEqualTo(8.0);
        assertThat(mapped.get("timeOffHours")).isEqualTo(4.5);
        assertThat(mapped.get("actualHours")).isEqualTo(168.0 + 45.0 + 8.0 - 4.0 - 4.5);
    }

    @Test
    void queryHoursMatchReportCenterWhenOaLeaveIsApplied() {
        EmployeeDailyAggregateRow row = new EmployeeDailyAggregateRow(
                "emp-1",
                "SZST0000",
                "陈觉晓",
                "org-1",
                "董事长",
                0, 0, 0, 0,
                176 * 60,
                0, 0, 0, 0, 0, 0, 0,
                22,
                BigDecimal.ONE,
                null);
        Map<String, Object> mapped = AttendanceReportQueryPageService.workHoursRow(
                row, List.of());
        assertThat(mapped.get("scheduledHours")).isEqualTo(176.0);
        assertThat(mapped.get("actualHours")).isEqualTo(176.0);
    }

    @Test
    void voluntaryOvertimeCountsAsActualHoursButNotPaidOvertime() {
        EmployeeDailyAggregateRow row = new EmployeeDailyAggregateRow(
                "emp-1",
                "SZST0487",
                "赵俊杰",
                "org-1",
                "DC部-软件设计组",
                0, 0, 0, 0,
                168 * 60,
                4 * 60 + 30,
                0,
                3 * 60 + 30,
                0, 0, 0, 0,
                21,
                BigDecimal.ONE,
                null);
        Map<String, Object> mapped = AttendanceReportQueryPageService.workHoursRow(
                row, List.of());
        assertThat(mapped.get("paidOvertimeHours")).isEqualTo(4.5);
        assertThat(mapped.get("voluntaryOvertimeHours")).isEqualTo(3.5);
        assertThat(mapped.get("actualHours")).isEqualTo(168.0 + 4.5 + 3.5);
    }

    private static OaRow oa(String type, String leaveType, long minutes) {
        // Aug 3 interval, projection covers Aug; byDate has the same single day.
        // This gives legacyMinutesInWindowOrThrow a valid single-month path and
        // lets recognizedMinutesInWindow return the full minute value.
        String byDate = minutes > 0
                ? "{\"2026-08-03\":" + minutes + "}"
                : null;
        return new OaRow(
                "doc-" + type + "-" + leaveType,
                "emp-1",
                "SZST0001",
                "张三",
                "org-1",
                "采购部",
                type,
                leaveType,
                Instant.parse("2026-08-03T00:30:00Z"),
                Instant.parse("2026-08-03T09:30:00Z"),
                minutes,
                "APPROVED",
                "OA",
                java.time.LocalDate.of(2026, 8, 1),
                java.time.LocalDate.of(2026, 9, 1),
                byDate);
    }

    @Test
    void zeroMinutesNullJsonCrossMonthCancellationCountsZeroWithoutThrow() {
        // A fully-cancelled leave (recognizedMinutes=0, byDate=null) spanning
        // two months must return 0, not throw 503 REQUIRED.
        var aggregate = new EmployeeDailyAggregateRow(
                "emp-1", "SZST0026", "唐勇", "org-1", "测试部",
                0, 0, 0, 0, 11040L, 0, 0, 0, 0, 0, 0, 0, 23,
                java.math.BigDecimal.ZERO, null);
        var doc = new OaRow(
                "cancelled-cross", "emp-1", "SZST0026", "唐勇", "org-1", "测试部",
                "LEAVE", "SICK",
                Instant.parse("2026-09-16T16:00:00Z"),
                Instant.parse("2026-10-16T16:00:00Z"),
                0L, "APPROVED", "OA",
                java.time.LocalDate.of(2026, 9, 1),
                java.time.LocalDate.of(2026, 10, 1),
                null); // cancelled: total=0, no byDate

        var row = AttendanceReportQueryPageService.workHoursRow(aggregate, List.of(doc),
                java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 10, 1));

        assertThat(row.get("leaveHours")).isEqualTo(0.0d);
    }

    @Test
    void zeroMinutesInvalidJsonThrows503() {
        // Even when recognizedMinutes=0, a malformed byDate JSON must be rejected.
        var aggregate = new EmployeeDailyAggregateRow(
                "emp-1", "SZST0001", "张三", "org-1", "测试部",
                0, 0, 0, 0, 11040L, 0, 0, 0, 0, 0, 0, 0, 23,
                java.math.BigDecimal.ZERO, null);
        var doc = new OaRow(
                "bad-json-zero", "emp-1", "SZST0001", "张三", "org-1", "测试部",
                "LEAVE", "SICK",
                Instant.parse("2026-09-01T16:00:00Z"),
                Instant.parse("2026-09-02T16:00:00Z"),
                0L, "APPROVED", "OA",
                java.time.LocalDate.of(2026, 9, 1),
                java.time.LocalDate.of(2026, 10, 1),
                "{not-valid}"); // invalid JSON, total=0

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                AttendanceReportQueryPageService.workHoursRow(aggregate, List.of(doc),
                        java.time.LocalDate.of(2026, 9, 1),
                        java.time.LocalDate.of(2026, 10, 1)))
                .isInstanceOf(com.szsemicon.hr.shared.web.ApiProblemException.class)
                .hasMessageContaining("校验失败");
    }
}
