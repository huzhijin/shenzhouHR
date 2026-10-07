package com.szsemicon.hr.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.domain.LeaveType;
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
            assertThat(report.calculationFormulaVersion())
                    .isEqualTo(AttendanceReportCalculator.formulaVersion(type));
        }
    }

    @Test
    void lateReportCountsExactStartLateFromException() {
        var base = snapshot(null, "employee-a");
        DailyFact original = base.dailyFacts().getFirst();
        DailyFact zeroLate = new DailyFact(
                original.factId(),
                original.companyId(),
                original.employeeId(),
                original.employeeNumber(),
                original.employeeName(),
                original.organizationId(),
                original.organizationVersionId(),
                original.organizationName(),
                original.businessDate(),
                original.dayType(),
                original.shiftLabel(),
                original.scheduledMinutes(),
                original.confirmedScheduledWorkMinutes(),
                original.recognizedOvertimeMinutes(),
                original.leaveOrTimeOffMinutes(),
                original.absenceMinutes(),
                original.actualWorkMinutes(),
                original.scheduledAttendanceDays(),
                original.actualAttendanceDays(),
                0,
                0,
                original.earlyDepartureMinutes(),
                original.missingPunchCount(),
                original.firstPunchAt(),
                original.lastPunchAt(),
                original.calculationVersionId(),
                original.resultDigest());
        var snapshot = new ReportSourceSnapshot(
                base.scope(),
                base.filter(),
                base.projectionVersion(),
                base.periodState(),
                base.dataAsOf(),
                base.sourceVersions(),
                List.of(zeroLate),
                base.oaDocumentFacts(),
                List.of(new ExceptionFact(
                        "late-exact-start",
                        original.employeeId(),
                        original.employeeNumber(),
                        original.employeeName(),
                        original.organizationId(),
                        original.organizationName(),
                        original.businessDate(),
                        "LATE",
                        ExceptionSeverity.WARNING,
                        ExceptionState.OPEN,
                        0,
                        "到达时刻等于班次开始",
                        original.calculationVersionId())),
                base.timeAccountFacts());

        var report = calculator.calculate(ReportType.LATE, snapshot);

        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().getFirst().values())
                .containsEntry(ReportField.LATE_EVENT_COUNT, "1")
                .containsEntry(ReportField.LATE_MINUTES, "0");
    }

    @Test
    void actualWorkDoesNotDoubleCountOvertimeConvertedToTimeOff() {
        var report = calculator.calculate(
                ReportType.WORK_HOURS, snapshot(null, "employee-a"));
        var row = report.rows().getFirst();

        assertThat(row.values())
                .containsEntry(ReportField.SCHEDULED_HOURS, "8.00")
                .containsEntry(ReportField.PAID_OVERTIME_HOURS, "0.00")
                .containsEntry(ReportField.ANNUAL_LEAVE_HOURS, "1.00")
                .containsEntry(ReportField.LEAVE_HOURS, "0.00")
                .containsEntry(ReportField.COMPENSATORY_OVERTIME_HOURS, "0.00")
                .containsEntry(ReportField.TIME_OFF_HOURS, "0.00")
                .containsEntry(ReportField.ACTUAL_WORK_HOURS, "7.00");
    }

    @Test
    void monthlyWorkHoursUsesPaidOvertimeAndTimeOffFormula() {
        DailyFact day = new DailyFact(
                "day-formula",
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
                176 * 60,
                176 * 60,
                12 * 60,
                8 * 60,
                4 * 60,
                0,
                12 * 60,
                0,
                0,
                188 * 60,
                1,
                1,
                0,
                0,
                0,
                0,
                Instant.parse("2026-07-01T00:30:00Z"),
                Instant.parse("2026-07-01T10:00:00Z"),
                "calculation-formula",
                "digest-formula",
                null);
        OaDocumentFact otherLeave = new OaDocumentFact(
                "oa-other",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "制造中心",
                "LEAVE",
                "SICK",
                Instant.parse("2026-07-06T00:30:00Z"),
                Instant.parse("2026-07-06T08:30:00Z"),
                8 * 60,
                "APPROVED",
                "oa-v");
        OaDocumentFact annual = new OaDocumentFact(
                "oa-annual",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "制造中心",
                "LEAVE",
                "ANNUAL",
                Instant.parse("2026-07-07T00:30:00Z"),
                Instant.parse("2026-07-07T08:30:00Z"),
                8 * 60,
                "APPROVED",
                "oa-v");
        OaDocumentFact usedTimeOff = new OaDocumentFact(
                "oa-off",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "制造中心",
                "TIME_OFF",
                "COMPENSATORY",
                Instant.parse("2026-07-08T00:30:00Z"),
                Instant.parse("2026-07-08T08:30:00Z"),
                8 * 60,
                "APPROVED",
                "oa-v");
        ReportSourceSnapshot snapshot = snapshot(null, "employee-a");
        snapshot = new ReportSourceSnapshot(
                snapshot.scope(),
                snapshot.filter(),
                snapshot.projectionVersion(),
                snapshot.periodState(),
                snapshot.dataAsOf(),
                snapshot.sourceVersions(),
                List.of(day),
                List.of(otherLeave, annual, usedTimeOff),
                snapshot.exceptionFacts(),
                snapshot.timeAccountFacts());
        var row = calculator.calculate(ReportType.WORK_HOURS, snapshot)
                .rows()
                .getFirst();
        assertThat(row.values())
                .containsEntry(ReportField.SCHEDULED_HOURS, "176.00")
                .containsEntry(ReportField.PAID_OVERTIME_HOURS, "8.00")
                .containsEntry(ReportField.LEAVE_HOURS, "8.00")
                .containsEntry(ReportField.ANNUAL_LEAVE_HOURS, "8.00")
                .containsEntry(ReportField.COMPENSATORY_OVERTIME_HOURS, "4.00")
                .containsEntry(ReportField.TIME_OFF_HOURS, "8.00")
                .containsEntry(ReportField.ACTUAL_WORK_HOURS, "164.00");
    }

    @Test
    void exceptionOverviewSplitsOffDutyMissingPunchAndWritesDetails() {
        var report = calculator.calculate(
                ReportType.EXCEPTIONS, snapshot(null, null));
        var row = report.rows().getFirst();

        assertThat(row.values())
                .containsEntry(ReportField.EXCEPTION_TYPE, "MISSING_OFF_DUTY")
                .containsEntry(
                        ReportField.EXCEPTION_DETAILS, "上班 08:30，无下班卡")
                .containsEntry(ReportField.BUSINESS_DATE, "2026-07-01");
        assertThat(report.columns().stream()
                .map(AttendanceReportModels.ReportColumn::field))
                .contains(ReportField.EXCEPTION_DETAILS);
    }

    @Test
    void exceptionOverviewWritesLateDetailsWithoutRelabelingAbsence() {
        ReportSourceSnapshot snapshot = snapshot(null, "employee-a");
        var lateSnapshot = new ReportSourceSnapshot(
                snapshot.scope(),
                snapshot.filter(),
                snapshot.projectionVersion(),
                snapshot.periodState(),
                snapshot.dataAsOf(),
                snapshot.sourceVersions(),
                snapshot.dailyFacts(),
                snapshot.oaDocumentFacts(),
                List.of(
                        new ExceptionFact(
                                "late-a-1",
                                "employee-a",
                                "0007",
                                "陈思远",
                                "organization-a",
                                "制造中心",
                                LocalDate.of(2026, 7, 1),
                                "LATE",
                                ExceptionSeverity.WARNING,
                                ExceptionState.OPEN,
                                29,
                                "首次有效打卡晚于班次开始",
                                "calculation-a-1"),
                        new ExceptionFact(
                                "absence-a-2",
                                "employee-a",
                                "0007",
                                "陈思远",
                                "organization-a",
                                "制造中心",
                                LocalDate.of(2026, 7, 2),
                                "ABSENCE",
                                ExceptionSeverity.ERROR,
                                ExceptionState.OPEN,
                                480,
                                "无打卡",
                                "calculation-a-2")),
                snapshot.timeAccountFacts());

        var report = calculator.calculate(ReportType.EXCEPTIONS, lateSnapshot);
        assertThat(report.rows()).anySatisfy(row -> assertThat(row.values())
                .containsEntry(ReportField.EXCEPTION_TYPE, "LATE")
                .containsEntry(
                        ReportField.EXCEPTION_DETAILS,
                        "上班 08:35，计罚 29 分钟"));
        assertThat(report.rows()).anySatisfy(row -> assertThat(row.values())
                .containsEntry(ReportField.EXCEPTION_TYPE, "ABSENCE")
                .containsEntry(
                        ReportField.EXCEPTION_DETAILS, "应出勤，无打卡无单据")
                .doesNotContainEntry(
                        ReportField.EXCEPTION_TYPE, "MISSING_PUNCH_OVERDUE"));
    }

    @Test
    void displayRangeExcludesDaysOutsideTheWindowFromTotals() {
        ReportSourceSnapshot full = snapshot(null, "employee-a");
        ReportFilter ranged = new ReportFilter(
                YearMonth.of(2026, 7),
                "legal-a",
                null,
                "employee-a",
                null,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 2));
        ReportSourceSnapshot sliced = new ReportSourceSnapshot(
                full.scope(),
                ranged,
                full.projectionVersion(),
                full.periodState(),
                full.dataAsOf(),
                full.sourceVersions(),
                full.dailyFacts(),
                full.oaDocumentFacts(),
                full.exceptionFacts(),
                full.timeAccountFacts());
        var rangedDetail = calculator.calculate(
                ReportType.ATTENDANCE_DETAIL, sliced);
        var allMonth = calculator.calculate(
                ReportType.ATTENDANCE_DETAIL, full);
        assertThat(rangedDetail.rows().size())
                .isLessThan(allMonth.rows().size());
    }

    @Test
    void overtimeListsOneDocumentPerRowIncludingPaper() {
        var report = calculator.calculate(
                ReportType.OVERTIME, overtimeDocuments());
        assertThat(report.rows()).hasSize(4);
        assertThat(report.rows())
                .extracting(row -> row.values().get(ReportField.SOURCE_ORIGIN))
                .containsExactly("OA", "OA", "OA", "PAPER");
        assertThat(report.rows())
                .extracting(row -> row.values().get(ReportField.DOCUMENT_TYPE))
                .containsExactly("PAID", "COMPENSATORY", "VOLUNTARY", "PAID");
        assertThat(report.columns())
                .extracting(AttendanceReportModels.ReportColumn::field)
                .contains(
                        ReportField.DOCUMENT_START,
                        ReportField.RECOGNIZED_HOURS,
                        ReportField.SOURCE_ORIGIN)
                .doesNotContain(ReportField.WEEKDAY_OVERTIME_HOURS);
    }



    @Test
    void attendanceRateUsesDayFieldsAndRoundsTwentyOfTwentyTwoDays() {
        var report = calculator.calculate(
                ReportType.ATTENDANCE_RATE,
                attendanceSnapshot(20, 0, 2));
        var row = report.rows().getFirst();

        assertThat(row.values())
                .containsEntry(ReportField.SCHEDULED_ATTENDANCE_DAYS, "22")
                .containsEntry(ReportField.ACTUAL_ATTENDANCE_DAYS, "20")
                .containsEntry(ReportField.ATTENDANCE_RATE, "90.91")
                .doesNotContainKey(ReportField.RATE_FORMULA_VERSION);
        assertThat(report.calculationFormulaVersion())
                .isEqualTo(
                        AttendanceReportModels.ATTENDANCE_RATE_FORMULA_VERSION);
    }

    @Test
    void paidAnnualLeaveKeepsTwentyPunchDaysAtFullAttendance() {
        var report = calculator.calculate(
                ReportType.ATTENDANCE_RATE,
                attendanceSnapshot(20, 2, 0));

        assertThat(report.rows().getFirst().values())
                .containsEntry(ReportField.SCHEDULED_ATTENDANCE_DAYS, "22")
                .containsEntry(ReportField.ACTUAL_ATTENDANCE_DAYS, "22")
                .containsEntry(ReportField.ATTENDANCE_RATE, "100.00");
    }

    @Test
    void sickLeaveCountsAsAttendanceButPersonalLeaveDoesNotAndSickDaysDisplay() {
        DailyFact firstSickDay = withLeaveType(
                attendanceDay(1, false, true), LeaveType.SICK);
        DailyFact secondSickDay = withLeaveType(
                attendanceDay(2, false, true), LeaveType.SICK);
        DailyFact personalLeaveDay = withLeaveType(
                attendanceDay(3, false, false), LeaveType.PERSONAL);
        var report = calculator.calculate(
                ReportType.ATTENDANCE_RATE,
                snapshotWithDailyFacts(List.of(
                        firstSickDay, secondSickDay, personalLeaveDay)));

        assertThat(report.rows()).singleElement().satisfies(row ->
                assertThat(row.values())
                        .containsEntry(
                                ReportField.SCHEDULED_ATTENDANCE_DAYS, "3")
                        .containsEntry(
                                ReportField.ACTUAL_ATTENDANCE_DAYS, "2")
                        .containsEntry(ReportField.SICK_LEAVE_DAYS, "2")
                        .containsEntry(ReportField.ATTENDANCE_RATE, "66.67"));
    }

    @Test
    void lateConvertedToAbsenceReducesMonthlyAttendanceRate() {
        var report = calculator.calculate(
                ReportType.ATTENDANCE_RATE,
                attendanceSnapshot(21, 0, 1));

        assertThat(report.rows().getFirst().values())
                .containsEntry(ReportField.SCHEDULED_ATTENDANCE_DAYS, "22")
                .containsEntry(ReportField.ACTUAL_ATTENDANCE_DAYS, "21")
                .containsEntry(ReportField.ATTENDANCE_RATE, "95.45");
    }

    @Test
    void leaveReportFailsClosedForNonEffectiveOaStatus() {
        var report = calculator.calculate(
                ReportType.LEAVE, snapshot(null, null));

        assertThat(report.rows())
                .extracting(AttendanceReportModels.ReportRow::rowReference)
                .containsExactly("leave:oa-approved");
    }

    @Test
    void leaveReportDropsCoveringUnknownHeaderThatUnionsTypedIntervals() {
        var base = snapshot(null, null);
        var oaFacts = new java.util.ArrayList<>(base.oaDocumentFacts());
        oaFacts.add(new OaDocumentFact(
                "oa-time-off",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "制造中心",
                "LEAVE",
                "TIME_OFF",
                Instant.parse("2026-07-10T00:30:00Z"),
                Instant.parse("2026-07-10T03:00:00Z"),
                150,
                "APPROVED",
                "oa-version-time-off"));
        oaFacts.add(new OaDocumentFact(
                "oa-personal",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "制造中心",
                "LEAVE",
                "PERSONAL",
                Instant.parse("2026-07-10T03:00:00Z"),
                Instant.parse("2026-07-10T04:00:00Z"),
                60,
                "APPROVED",
                "oa-version-personal"));
        oaFacts.add(new OaDocumentFact(
                "oa-covering",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "制造中心",
                "LEAVE",
                "PERSONAL",
                Instant.parse("2026-07-10T00:30:00Z"),
                Instant.parse("2026-07-10T04:00:00Z"),
                210,
                "UNKNOWN",
                "oa-version-covering"));
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
                .contains("leave:oa-time-off", "leave:oa-personal")
                .doesNotContain("leave:oa-covering");
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
                original.scheduledAttendanceDays(),
                original.actualAttendanceDays(),
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

    private ReportSourceSnapshot attendanceSnapshot(
            int punchedDays, int paidLeaveDays, int absentDays) {
        var base = snapshot(null, "employee-a");
        var facts = new java.util.ArrayList<DailyFact>();
        int dayOfMonth = 1;
        for (int index = 0; index < punchedDays; index++) {
            facts.add(attendanceDay(dayOfMonth++, true, false));
        }
        for (int index = 0; index < paidLeaveDays; index++) {
            facts.add(attendanceDay(dayOfMonth++, false, true));
        }
        for (int index = 0; index < absentDays; index++) {
            facts.add(attendanceDay(dayOfMonth++, false, false));
        }
        return new ReportSourceSnapshot(
                base.scope(),
                base.filter(),
                base.projectionVersion(),
                base.periodState(),
                base.dataAsOf(),
                base.sourceVersions(),
                facts,
                base.oaDocumentFacts(),
                base.exceptionFacts(),
                base.timeAccountFacts());
    }

    private DailyFact attendanceDay(
            int dayOfMonth, boolean punched, boolean paidLeave) {
        long confirmedMinutes = punched ? 480 : 0;
        long leaveMinutes = paidLeave ? 480 : 0;
        boolean attended = punched || paidLeave;
        return new DailyFact(
                "attendance-day-" + dayOfMonth,
                "legal-a",
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "organization-version-a",
                "制造中心",
                LocalDate.of(2026, 7, dayOfMonth),
                DayType.WEEKDAY,
                "总部夏令班",
                480,
                confirmedMinutes,
                0,
                leaveMinutes,
                attended ? 0 : 480,
                confirmedMinutes,
                1,
                attended ? 1 : 0,
                0,
                0,
                0,
                attended ? 0 : 2,
                null,
                null,
                "calculation-day-" + dayOfMonth,
                "digest-day-" + dayOfMonth);
    }

    private DailyFact withLeaveType(DailyFact base, LeaveType leaveType) {
        return new DailyFact(
                base.factId(),
                base.companyId(),
                base.employeeId(),
                base.employeeNumber(),
                base.employeeName(),
                base.organizationId(),
                base.organizationVersionId(),
                base.organizationName(),
                base.businessDate(),
                base.dayType(),
                base.shiftLabel(),
                base.scheduledMinutes(),
                base.confirmedScheduledWorkMinutes(),
                base.recognizedOvertimeMinutes(),
                base.paidOvertimeMinutes(),
                base.compensatoryOvertimeMinutes(),
                base.voluntaryOvertimeMinutes(),
                base.totalOvertimeMinutes(),
                base.leaveOrTimeOffMinutes(),
                base.absenceMinutes(),
                base.actualWorkMinutes(),
                base.scheduledAttendanceDays(),
                base.actualAttendanceDays(),
                base.lateMinutes(),
                base.penalizedLateMinutes(),
                base.earlyDepartureMinutes(),
                base.missingPunchCount(),
                base.firstPunchAt(),
                base.lastPunchAt(),
                base.calculationVersionId(),
                base.resultDigest(),
                leaveType);
    }

    private ReportSourceSnapshot overtimeDocuments() {
        var base = snapshot(null, "employee-a");
        return new ReportSourceSnapshot(
                base.scope(),
                base.filter(),
                base.projectionVersion(),
                base.periodState(),
                base.dataAsOf(),
                base.sourceVersions(),
                base.dailyFacts(),
                List.of(
                        overtimeFact("ot-1", "2026-07-18T09:40:00Z", "2026-07-18T14:00:00Z",
                                "PAID", "OA"),
                        overtimeFact("ot-2", "2026-07-20T09:40:00Z", "2026-07-20T14:00:00Z",
                                "COMPENSATORY", "OA"),
                        overtimeFact("ot-3", "2026-07-23T09:40:00Z", "2026-07-23T14:00:00Z",
                                "VOLUNTARY", "OA"),
                        overtimeFact("ot-4", "2026-07-25T09:40:00Z", "2026-07-25T14:00:00Z",
                                "PAID", "PAPER")),
                base.exceptionFacts(),
                base.timeAccountFacts());
    }

    private OaDocumentFact overtimeFact(
            String id,
            String start,
            String end,
            String type,
            String origin) {
        return new OaDocumentFact(
                id,
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                "制造中心",
                "OVERTIME",
                type,
                Instant.parse(start),
                Instant.parse(end),
                150,
                "APPROVED",
                "oa-v",
                origin);
    }

    private ReportSourceSnapshot snapshotWithDailyFacts(List<DailyFact> facts) {
        var base = snapshot(null, "employee-a");
        return new ReportSourceSnapshot(
                base.scope(),
                base.filter(),
                base.projectionVersion(),
                base.periodState(),
                base.dataAsOf(),
                base.sourceVersions(),
                facts,
                base.oaDocumentFacts(),
                base.exceptionFacts(),
                base.timeAccountFacts());
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
                                1,  // scheduledAttendanceDays
                                1,  // actualAttendanceDays
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
                                0,  // scheduledAttendanceDays (休息日)
                                1,  // actualAttendanceDays (有实际工作)
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
                                1,  // scheduledAttendanceDays
                                1,  // actualAttendanceDays (有部分实际工作)
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
                                "oa-version-2"),
                        new OaDocumentFact(
                                "oa-overtime",
                                "employee-a",
                                "0007",
                                "陈思远",
                                "organization-a",
                                "制造中心",
                                "OVERTIME",
                                "PAID",
                                Instant.parse("2026-07-01T09:40:00Z"),
                                Instant.parse("2026-07-01T14:00:00Z"),
                                150,
                                "APPROVED",
                                "oa-version-3")),
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

    @Test
    void undeclaredOvertimeAndLongPunchSpanAreNotActionable() {
        assertThat(AttendanceReportCalculator.actionableException(
                exception(
                        "undeclared",
                        LocalDate.of(2026, 7, 15),
                        "制造中心",
                        "OVERTIME_DOCUMENT_MISSING_OR_LATE")))
                .isFalse();
        assertThat(AttendanceReportCalculator.actionableException(
                exception(
                        "span",
                        LocalDate.of(2026, 7, 15),
                        "制造中心",
                        "LONG_PUNCH_SPAN_REVIEW")))
                .isFalse();
        assertThat(AttendanceReportCalculator.actionableException(
                exception(
                        "fake",
                        LocalDate.of(2026, 7, 15),
                        "制造中心",
                        "FAKE_OVERTIME")))
                .isTrue();
    }

    @Test
    void wuhanDalianAugustExceptionsAreMutedOnlyInAugust2026() {
        ExceptionFact wuhanAugust = exception(
                "wh-aug",
                LocalDate.of(2026, 8, 12),
                "技术支持中心-现场服务部-武汉产品服务组",
                "LATE");
        ExceptionFact dalianAugust = exception(
                "dl-aug",
                LocalDate.of(2026, 8, 5),
                "客户现场服务部-大连办事处",
                "ABSENCE");
        ExceptionFact wuhanJuly = exception(
                "wh-jul",
                LocalDate.of(2026, 7, 15),
                "技术支持中心-现场服务部-武汉产品服务组",
                "LATE");
        assertThat(AttendanceReportCalculator.mutedWuhanDalianAugust(wuhanAugust))
                .isTrue();
        assertThat(AttendanceReportCalculator.mutedWuhanDalianAugust(dalianAugust))
                .isTrue();
        assertThat(AttendanceReportCalculator.mutedWuhanDalianAugust(wuhanJuly))
                .isFalse();
    }

    @Test
    void exceptionOverviewOmitsUndeclaredOvertimeAndWuhanAugust() {
        ReportSourceSnapshot base = snapshot(null, "employee-a");
        ReportSourceSnapshot withNoise = new ReportSourceSnapshot(
                base.scope(),
                base.filter(),
                base.projectionVersion(),
                base.periodState(),
                base.dataAsOf(),
                base.sourceVersions(),
                base.dailyFacts(),
                base.oaDocumentFacts(),
                List.of(
                        exception(
                                "fake-1",
                                LocalDate.of(2026, 7, 1),
                                "制造中心",
                                "FAKE_OVERTIME"),
                        exception(
                                "undeclared-1",
                                LocalDate.of(2026, 7, 1),
                                "制造中心",
                                "OVERTIME_DOCUMENT_MISSING_OR_LATE"),
                        exception(
                                "wuhan-aug",
                                LocalDate.of(2026, 8, 12),
                                "武汉产品服务组",
                                "LATE")),
                base.timeAccountFacts());
        var report = calculator.calculate(ReportType.EXCEPTIONS, withNoise);
        assertThat(report.rows())
                .extracting(row -> row.values().get(ReportField.EXCEPTION_TYPE))
                .containsExactly("FAKE_OVERTIME");
    }

    private static ExceptionFact exception(
            String caseId,
            LocalDate date,
            String organizationName,
            String type) {
        return new ExceptionFact(
                caseId,
                "employee-a",
                "0007",
                "陈思远",
                "organization-a",
                organizationName,
                date,
                type,
                ExceptionSeverity.WARNING,
                ExceptionState.OPEN,
                30,
                "reason",
                "calculation-a-1");
    }
}
