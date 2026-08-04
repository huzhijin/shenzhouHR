package com.szsemicon.hr.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionSeverity;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceReportCalculatorTest {

    private final AttendanceReportCalculator calculator =
            new AttendanceReportCalculator();

    @Test
    void producesAllNineFormalReportsFromAuthorizedSourceFacts() {
        ReportSourceSnapshot snapshot = snapshot(null, null);

        for (ReportType type : ReportType.values()) {
            var report = calculator.calculate(type, snapshot);

            assertThat(report.type()).isEqualTo(type);
            assertThat(report.title()).isNotBlank();
            assertThat(report.columns()).isNotEmpty();
            assertThat(report.exportAllowlist()).containsExactlyElementsOf(
                    report.columns().stream()
                            .map(AttendanceReportModels.ReportColumn::field)
                            .toList());
            assertThat(report.rows()).isNotEmpty();
        }
    }

    @Test
    void actualWorkDoesNotDoubleCountOvertimeConvertedToTimeOff() {
        var report = calculator.calculate(
                ReportType.WORK_HOURS, snapshot(null, "employee-a"));
        var row = report.rows().getFirst();

        assertThat(row.values())
                .containsEntry(ReportField.CONFIRMED_HOURS, "7.00")
                .containsEntry(
                        ReportField.RECOGNIZED_OVERTIME_HOURS, "3.50")
                .containsEntry(ReportField.ACTUAL_WORK_HOURS, "10.50");
    }

    @Test
    void overtimeSeparatesWeekdaySaturdaySundayAndHoliday() {
        var report = calculator.calculate(
                ReportType.OVERTIME, snapshot(null, "employee-a"));
        var row = report.rows().getFirst();

        assertThat(row.values())
                .containsEntry(
                        ReportField.WEEKDAY_OVERTIME_HOURS, "1.50")
                .containsEntry(
                        ReportField.SATURDAY_OVERTIME_HOURS, "2.00")
                .containsEntry(
                        ReportField.SUNDAY_OVERTIME_HOURS, "0.00")
                .containsEntry(
                        ReportField.HOLIDAY_OVERTIME_HOURS, "0.00")
                .containsEntry(
                        ReportField.RECOGNIZED_OVERTIME_HOURS, "3.50");
    }

    @Test
    void attendanceRateUsesVersionedFormulaWithoutExposingItsInternalName() {
        var report = calculator.calculate(
                ReportType.ATTENDANCE_RATE,
                snapshot(null, "employee-a"));
        var row = report.rows().getFirst();

        assertThat(row.values())
                .containsEntry(ReportField.ATTENDANCE_RATE, "87.50")
                .doesNotContainKey(ReportField.RATE_FORMULA_VERSION);
        assertThat(report.calculationFormulaVersion())
                .isEqualTo(
                        AttendanceReportModels.ATTENDANCE_RATE_FORMULA_VERSION);
    }

    @Test
    void leaveReportFailsClosedForNonEffectiveOaStatus() {
        var report = calculator.calculate(
                ReportType.LEAVE, snapshot(null, null));

        assertThat(report.rows())
                .extracting(AttendanceReportModels.ReportRow::rowReference)
                .containsExactly("leave:oa-approved");
        assertThat(report.columns())
                .extracting(AttendanceReportModels.ReportColumn::field)
                .doesNotContain(ReportField.DOCUMENT_REFERENCE);
    }

    @Test
    void annualLeaveReplaysThePrdLedgerBalanceFormula() {
        var report = calculator.calculate(
                ReportType.ANNUAL_LEAVE,
                snapshot(null, "employee-a"));
        var row = report.rows().getFirst();

        assertThat(row.values())
                .containsEntry(ReportField.BALANCE_HOURS, "36.00")
                .containsEntry(ReportField.EQUIVALENT_DAYS, "4.50");
    }

    @Test
    void requestFiltersCanOnlyNarrowTheAlreadyAuthorizedSnapshot() {
        var report = calculator.calculate(
                ReportType.ATTENDANCE_DETAIL,
                snapshot("organization-a", "employee-a"));

        assertThat(report.rows()).hasSize(2);
        assertThat(report.rows())
                .allSatisfy(row -> assertThat(row.values())
                        .containsEntry(ReportField.EMPLOYEE_NUMBER, "0007")
                        .containsEntry(ReportField.ORGANIZATION, "制造中心"));
    }

    @Test
    void includesOaIntervalsThatStartBeforeAndOverlapTheRequestedMonth() {
        var base = snapshot(null, "employee-a");
        var oaFacts = new java.util.ArrayList<>(base.oaDocumentFacts());
        oaFacts.add(new OaDocumentFact(
                "oa-cross-month",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "制造中心",
                "LEAVE",
                "年假",
                Instant.parse("2026-06-30T15:00:00Z"),
                Instant.parse("2026-06-30T18:00:00Z"),
                120,
                "APPROVED",
                "oa-version-cross-month"));
        var snapshot = new ReportSourceSnapshot(
                base.scope(),
                base.filter(),
                base.projectionVersion(),
                base.periodState(),
                base.dataAsOf(),
                base.sourceVersions(),
                base.dailyFacts(),
                oaFacts,
                base.exceptionFacts(),
                base.timeAccountFacts());

        var report = calculator.calculate(ReportType.LEAVE, snapshot);

        assertThat(report.rows())
                .extracting(AttendanceReportModels.ReportRow::rowReference)
                .contains("leave:oa-cross-month");
    }

    @Test
    void aggregatesAnEmployeeSeparatelyByOccurrenceTimeOrganizationVersion() {
        var base = snapshot(null, "employee-a");
        var original = base.dailyFacts().getFirst();
        var moved = new DailyFact(
                "day-a-after-transfer",
                original.companyId(),
                original.employeeId(),
                original.employeeNumber(),
                original.employeeName(),
                "organization-c",
                "organization-version-c",
                "供应链中心",
                LocalDate.of(2026, 7, 20),
                original.dayType(),
                original.shiftLabel(),
                original.scheduledMinutes(),
                original.confirmedScheduledWorkMinutes(),
                original.recognizedOvertimeMinutes(),
                original.leaveOrTimeOffMinutes(),
                original.absenceMinutes(),
                original.actualWorkMinutes(),
                original.lateMinutes(),
                original.penalizedLateMinutes(),
                original.earlyDepartureMinutes(),
                original.missingPunchCount(),
                original.firstPunchAt(),
                original.lastPunchAt(),
                "calculation-a-after-transfer",
                "digest-a-after-transfer");
        var dailyFacts = new java.util.ArrayList<>(base.dailyFacts());
        dailyFacts.add(moved);
        var snapshot = new ReportSourceSnapshot(
                base.scope(),
                base.filter(),
                base.projectionVersion(),
                base.periodState(),
                base.dataAsOf(),
                base.sourceVersions(),
                dailyFacts,
                base.oaDocumentFacts(),
                base.exceptionFacts(),
                base.timeAccountFacts());

        var report = calculator.calculate(ReportType.WORK_HOURS, snapshot);

        assertThat(report.rows()).hasSize(2);
        assertThat(report.rows())
                .extracting(row ->
                        row.values().get(ReportField.ORGANIZATION))
                .containsExactlyInAnyOrder("制造中心", "供应链中心");
    }

    private ReportSourceSnapshot snapshot(
            String organizationId, String employeeId) {
        return new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope:company-a",
                        "神州公司",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 7),
                        "legal-a",
                        organizationId,
                        employeeId,
                        null),
                "projection-v1",
                "OPEN",
                Instant.parse("2026-07-29T00:00:00Z"),
                List.of("deli:cursor-20", "oa:batch-8", "rules:v3"),
                List.of(
                        new DailyFact(
                                "day-a-1",
                                "legal-a",
                                "employee-a",
                                "0007",
                                "陈思远",
                                "organization-a",
                                "organization-version-a",
                                "制造中心",
                                LocalDate.of(2026, 7, 1),
                                DayType.WEEKDAY,
                                "总部夏令班",
                                480,
                                420,
                                90,
                                60,
                                0,
                                510,
                                10,
                                0,
                                0,
                                0,
                                Instant.parse("2026-07-01T00:35:00Z"),
                                Instant.parse("2026-07-01T12:00:00Z"),
                                "calculation-a-1",
                                "digest-a-1"),
                        new DailyFact(
                                "day-a-2",
                                "legal-a",
                                "employee-a",
                                "0007",
                                "陈思远",
                                "organization-a",
                                "organization-version-a",
                                "制造中心",
                                LocalDate.of(2026, 7, 4),
                                DayType.SATURDAY,
                                "休息日",
                                0,
                                0,
                                120,
                                0,
                                0,
                                120,
                                0,
                                0,
                                0,
                                0,
                                Instant.parse("2026-07-04T01:00:00Z"),
                                Instant.parse("2026-07-04T03:00:00Z"),
                                "calculation-a-2",
                                "digest-a-2"),
                        new DailyFact(
                                "day-b-1",
                                "legal-a",
                                "employee-b",
                                "0012",
                                "张伟",
                                "organization-b",
                                "organization-version-b",
                                "研发中心",
                                LocalDate.of(2026, 7, 1),
                                DayType.WEEKDAY,
                                "总部夏令班",
                                480,
                                240,
                                0,
                                0,
                                240,
                                240,
                                0,
                                0,
                                0,
                                1,
                                Instant.parse("2026-07-01T00:30:00Z"),
                                null,
                                "calculation-b-1",
                                "digest-b-1")),
                List.of(
                        new OaDocumentFact(
                                "oa-approved",
                                "employee-a",
                                "0007",
                                "陈思远",
                                "organization-a",
                                "制造中心",
                                "LEAVE",
                                "年假",
                                Instant.parse("2026-07-01T05:00:00Z"),
                                Instant.parse("2026-07-01T06:00:00Z"),
                                60,
                                "APPROVED",
                                "oa-version-1"),
                        new OaDocumentFact(
                                "oa-draft",
                                "employee-a",
                                "0007",
                                "陈思远",
                                "organization-a",
                                "制造中心",
                                "LEAVE",
                                "事假",
                                Instant.parse("2026-07-02T01:00:00Z"),
                                Instant.parse("2026-07-02T02:00:00Z"),
                                0,
                                "DRAFT",
                                "oa-version-2")),
                List.of(new ExceptionFact(
                        "exception-b-1",
                        "employee-b",
                        "0012",
                        "张伟",
                        "organization-b",
                        "研发中心",
                        LocalDate.of(2026, 7, 1),
                        "MISSING_PUNCH_OVERDUE",
                        ExceptionSeverity.ERROR,
                        ExceptionState.OPEN,
                        240,
                        "班次与有效打卡数量核验",
                        "calculation-b-1")),
                List.of(new TimeAccountFact(
                        "annual-a",
                        "employee-a",
                        "0007",
                        "陈思远",
                        "organization-a",
                        "制造中心",
                        TimeAccountType.ANNUAL_LEAVE,
                        new BigDecimal("8"),
                        new BigDecimal("40"),
                        BigDecimal.ZERO,
                        new BigDecimal("2"),
                        new BigDecimal("16"),
                        new BigDecimal("1"),
                        new BigDecimal("4"),
                        new BigDecimal("1"),
                        "ledger-v9")));
    }
}
