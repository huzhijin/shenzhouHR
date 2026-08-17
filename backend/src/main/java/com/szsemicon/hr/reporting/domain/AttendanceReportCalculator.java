package com.szsemicon.hr.reporting.domain;

import static com.szsemicon.hr.reporting.domain.AttendanceReportModels.ATTENDANCE_RATE_FORMULA_VERSION;
import static com.szsemicon.hr.reporting.domain.AttendanceReportModels.decimal;
import static com.szsemicon.hr.reporting.domain.AttendanceReportModels.hours;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportColumn;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportDataSet;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportRow;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AttendanceReportCalculator {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> EFFECTIVE_OA_STATUSES = Set.of(
            "APPROVED", "MODIFIED", "SUPPLEMENTED");

    public ReportDataSet calculate(
            ReportType type, ReportSourceSnapshot snapshot) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(snapshot, "snapshot");
        return switch (type) {
            case ATTENDANCE_DETAIL -> attendanceDetail(snapshot);
            case LEAVE -> leave(snapshot);
            case OVERTIME -> overtime(snapshot);
            case WORK_HOURS -> workHours(snapshot);
            case EXCEPTIONS -> exceptions(snapshot);
            case LATE -> late(snapshot);
            case MISSED_PUNCH -> missedPunch(snapshot);
            case ATTENDANCE_RATE -> attendanceRate(snapshot);
            case ANNUAL_LEAVE -> annualLeave(snapshot);
        };
    }

    private ReportDataSet attendanceDetail(ReportSourceSnapshot snapshot) {
        List<ReportField> fields = List.of(
                ReportField.BUSINESS_DATE,
                ReportField.EMPLOYEE_NUMBER,
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.SHIFT,
                ReportField.SCHEDULED_HOURS,
                ReportField.CONFIRMED_HOURS,
                ReportField.RECOGNIZED_OVERTIME_HOURS,
                ReportField.PAID_OVERTIME_HOURS,
                ReportField.COMPENSATORY_OVERTIME_HOURS,
                ReportField.VOLUNTARY_OVERTIME_HOURS,
                ReportField.TOTAL_OVERTIME_HOURS,
                ReportField.LEAVE_HOURS,
                ReportField.ABSENCE_HOURS,
                ReportField.ACTUAL_WORK_HOURS,
                ReportField.LATE_MINUTES,
                ReportField.PENALIZED_LATE_MINUTES,
                ReportField.EARLY_MINUTES,
                ReportField.MISSING_PUNCH_COUNT,
                ReportField.FIRST_PUNCH,
                ReportField.LAST_PUNCH);
        List<ReportRow> rows = visibleDaily(snapshot).stream()
                .map(fact -> row(
                        "attendance:" + fact.factId(),
                        Map.ofEntries(
                                entry(ReportField.BUSINESS_DATE,
                                        fact.businessDate().toString()),
                                entry(ReportField.EMPLOYEE_NUMBER,
                                        fact.employeeNumber()),
                                entry(ReportField.EMPLOYEE_NAME,
                                        fact.employeeName()),
                                entry(ReportField.ORGANIZATION,
                                        fact.organizationName()),
                                entry(ReportField.SHIFT, fact.shiftLabel()),
                                entry(ReportField.SCHEDULED_HOURS,
                                        hours(fact.scheduledMinutes())),
                                entry(ReportField.CONFIRMED_HOURS,
                                        hours(fact.confirmedScheduledWorkMinutes())),
                                entry(ReportField.RECOGNIZED_OVERTIME_HOURS,
                                        hours(fact.recognizedOvertimeMinutes())),
                                entry(ReportField.PAID_OVERTIME_HOURS,
                                        hours(fact.paidOvertimeMinutes())),
                                entry(ReportField.COMPENSATORY_OVERTIME_HOURS,
                                        hours(fact.compensatoryOvertimeMinutes())),
                                entry(ReportField.VOLUNTARY_OVERTIME_HOURS,
                                        hours(fact.voluntaryOvertimeMinutes())),
                                entry(ReportField.TOTAL_OVERTIME_HOURS,
                                        hours(fact.totalOvertimeMinutes())),
                                entry(ReportField.LEAVE_HOURS,
                                        hours(fact.leaveOrTimeOffMinutes())),
                                entry(ReportField.ABSENCE_HOURS,
                                        hours(fact.absenceMinutes())),
                                entry(ReportField.ACTUAL_WORK_HOURS,
                                        hours(fact.actualWorkMinutes())),
                                entry(ReportField.LATE_MINUTES,
                                        Long.toString(fact.lateMinutes())),
                                entry(ReportField.PENALIZED_LATE_MINUTES,
                                        Long.toString(
                                                fact.penalizedLateMinutes())),
                                entry(ReportField.EARLY_MINUTES,
                                        Long.toString(
                                                fact.earlyDepartureMinutes())),
                                entry(ReportField.MISSING_PUNCH_COUNT,
                                        Integer.toString(
                                                fact.missingPunchCount())),
                                entry(ReportField.FIRST_PUNCH,
                                        instant(fact.firstPunchAt())),
                                entry(ReportField.LAST_PUNCH,
                                        instant(fact.lastPunchAt()))),
                        "calculation:" + fact.calculationVersionId()))
                .toList();
        return dataSet(
                ReportType.ATTENDANCE_DETAIL,
                "月度考勤明细",
                fields,
                rows,
                "PRD_V1_9_DAILY_SEGMENT_METRICS_V2");
    }

    private ReportDataSet leave(ReportSourceSnapshot snapshot) {
        List<ReportField> fields = List.of(
                ReportField.EMPLOYEE_NUMBER,
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.DOCUMENT_TYPE,
                ReportField.DOCUMENT_START,
                ReportField.DOCUMENT_END,
                ReportField.RECOGNIZED_HOURS,
                ReportField.APPROVAL_STATE);
        List<ReportRow> rows = visibleOa(snapshot).stream()
                .filter(value -> Set.of("LEAVE", "TIME_OFF")
                        .contains(value.documentType().toUpperCase(Locale.ROOT)))
                .filter(value -> EFFECTIVE_OA_STATUSES.contains(
                        value.sourceStatus().toUpperCase(Locale.ROOT)))
                .map(fact -> row(
                        "leave:" + fact.documentId(),
                        Map.ofEntries(
                                entry(ReportField.EMPLOYEE_NUMBER,
                                        fact.employeeNumber()),
                                entry(ReportField.EMPLOYEE_NAME,
                                        fact.employeeName()),
                                entry(ReportField.ORGANIZATION,
                                        fact.organizationName()),
                                entry(ReportField.DOCUMENT_TYPE,
                                        fact.leaveType() == null
                                                ? fact.documentType()
                                                : fact.leaveType()),
                                entry(ReportField.DOCUMENT_START,
                                        fact.start().toString()),
                                entry(ReportField.DOCUMENT_END,
                                        fact.endExclusive().toString()),
                                entry(ReportField.RECOGNIZED_HOURS,
                                        hours(fact.recognizedMinutes())),
                                entry(ReportField.APPROVAL_STATE,
                                        fact.sourceStatus())),
                        "oa-document:" + fact.documentId()))
                .toList();
        return dataSet(
                ReportType.LEAVE,
                "请假与调休统计",
                fields,
                rows,
                "OA_EFFECTIVE_INTERVAL_INTERSECT_SCHEDULE_V1");
    }

    private ReportDataSet overtime(ReportSourceSnapshot snapshot) {
        List<ReportField> fields = List.of(
                ReportField.EMPLOYEE_NUMBER,
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.WEEKDAY_OVERTIME_HOURS,
                ReportField.SATURDAY_OVERTIME_HOURS,
                ReportField.SUNDAY_OVERTIME_HOURS,
                ReportField.HOLIDAY_OVERTIME_HOURS,
                ReportField.PAID_OVERTIME_HOURS,
                ReportField.COMPENSATORY_OVERTIME_HOURS,
                ReportField.VOLUNTARY_OVERTIME_HOURS,
                ReportField.TOTAL_OVERTIME_HOURS,
                ReportField.RECOGNIZED_OVERTIME_HOURS);
        List<ReportRow> rows = aggregateDaily(visibleDaily(snapshot)).values()
                .stream()
                .map(value -> row(
                        "overtime:" + value.reference(),
                        Map.ofEntries(
                                entry(ReportField.EMPLOYEE_NUMBER,
                                        value.employeeNumber),
                                entry(ReportField.EMPLOYEE_NAME,
                                        value.employeeName),
                                entry(ReportField.ORGANIZATION,
                                        value.organizationName),
                                entry(ReportField.WEEKDAY_OVERTIME_HOURS,
                                        hours(value.weekdayOvertime)),
                                entry(ReportField.SATURDAY_OVERTIME_HOURS,
                                        hours(value.saturdayOvertime)),
                                entry(ReportField.SUNDAY_OVERTIME_HOURS,
                                        hours(value.sundayOvertime)),
                                entry(ReportField.HOLIDAY_OVERTIME_HOURS,
                                        hours(value.holidayOvertime)),
                                entry(ReportField.PAID_OVERTIME_HOURS,
                                        hours(value.paidOvertime)),
                                entry(ReportField.COMPENSATORY_OVERTIME_HOURS,
                                        hours(value.compensatoryOvertime)),
                                entry(ReportField.VOLUNTARY_OVERTIME_HOURS,
                                        hours(value.voluntaryOvertime)),
                                entry(ReportField.TOTAL_OVERTIME_HOURS,
                                        hours(value.totalOvertime)),
                                entry(ReportField.RECOGNIZED_OVERTIME_HOURS,
                                        hours(value.recognizedOvertime))),
                        "employee:" + value.reference()))
                .toList();
        return dataSet(
                ReportType.OVERTIME,
                "认可加班汇总",
                fields,
                rows,
                "OVERTIME_CLASSIFICATION_PAID_COMPENSATORY_VOLUNTARY_V2");
    }

    private ReportDataSet workHours(ReportSourceSnapshot snapshot) {
        List<ReportField> fields = List.of(
                ReportField.EMPLOYEE_NUMBER,
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.SCHEDULED_HOURS,
                ReportField.CONFIRMED_HOURS,
                ReportField.RECOGNIZED_OVERTIME_HOURS,
                ReportField.LEAVE_HOURS,
                ReportField.ABSENCE_HOURS,
                ReportField.ACTUAL_WORK_HOURS);
        List<ReportRow> rows = aggregateDaily(visibleDaily(snapshot)).values()
                .stream()
                .map(value -> row(
                        "work-hours:" + value.reference(),
                        Map.ofEntries(
                                entry(ReportField.EMPLOYEE_NUMBER,
                                        value.employeeNumber),
                                entry(ReportField.EMPLOYEE_NAME,
                                        value.employeeName),
                                entry(ReportField.ORGANIZATION,
                                        value.organizationName),
                                entry(ReportField.SCHEDULED_HOURS,
                                        hours(value.scheduled)),
                                entry(ReportField.CONFIRMED_HOURS,
                                        hours(value.confirmed)),
                                entry(ReportField.RECOGNIZED_OVERTIME_HOURS,
                                        hours(value.recognizedOvertime)),
                                entry(ReportField.LEAVE_HOURS,
                                        hours(value.leave)),
                                entry(ReportField.ABSENCE_HOURS,
                                        hours(value.absence)),
                                entry(ReportField.ACTUAL_WORK_HOURS,
                                        hours(value.actualWork))),
                        "employee:" + value.reference()))
                .toList();
        return dataSet(
                ReportType.WORK_HOURS,
                "个人月度工时",
                fields,
                rows,
                "PRD_V1_9_ACTUAL_WORK_EQUALS_CONFIRMED_PLUS_OVERTIME_V1");
    }

    private ReportDataSet exceptions(ReportSourceSnapshot snapshot) {
        List<ReportField> fields = List.of(
                ReportField.BUSINESS_DATE,
                ReportField.EMPLOYEE_NUMBER,
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.EXCEPTION_TYPE,
                ReportField.EXCEPTION_SEVERITY,
                ReportField.EXCEPTION_STATE,
                ReportField.EXCEPTION_MINUTES,
                ReportField.EVIDENCE_SUMMARY);
        List<ReportRow> rows = visibleExceptions(snapshot).stream()
                .map(fact -> row(
                        "exception:" + fact.caseId(),
                        Map.ofEntries(
                                entry(ReportField.BUSINESS_DATE,
                                        fact.businessDate().toString()),
                                entry(ReportField.EMPLOYEE_NUMBER,
                                        fact.employeeNumber()),
                                entry(ReportField.EMPLOYEE_NAME,
                                        fact.employeeName()),
                                entry(ReportField.ORGANIZATION,
                                        fact.organizationName()),
                                entry(ReportField.EXCEPTION_TYPE,
                                        fact.exceptionType()),
                                entry(ReportField.EXCEPTION_SEVERITY,
                                        fact.severity().name()),
                                entry(ReportField.EXCEPTION_STATE,
                                        fact.state().name()),
                                entry(ReportField.EXCEPTION_MINUTES,
                                        Long.toString(fact.minutes())),
                                entry(ReportField.EVIDENCE_SUMMARY,
                                        fact.safeEvidenceSummary())),
                        "calculation:" + fact.calculationVersionId()))
                .toList();
        return dataSet(
                ReportType.EXCEPTIONS,
                "考勤异常总览",
                fields,
                rows,
                "PRD_V1_9_EXCEPTION_CASE_CURRENT_STATE_V1");
    }

    private ReportDataSet late(ReportSourceSnapshot snapshot) {
        List<ReportField> fields = List.of(
                ReportField.EMPLOYEE_NUMBER,
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.LATE_EVENT_COUNT,
                ReportField.LATE_MINUTES,
                ReportField.PENALIZED_LATE_MINUTES);
        List<ReportRow> rows = aggregateDaily(visibleDaily(snapshot)).values()
                .stream()
                .filter(value -> value.lateMinutes > 0)
                .map(value -> row(
                        "late:" + value.reference(),
                        Map.ofEntries(
                                entry(ReportField.EMPLOYEE_NUMBER,
                                        value.employeeNumber),
                                entry(ReportField.EMPLOYEE_NAME,
                                        value.employeeName),
                                entry(ReportField.ORGANIZATION,
                                        value.organizationName),
                                entry(ReportField.LATE_EVENT_COUNT,
                                        Integer.toString(value.lateEvents)),
                                entry(ReportField.LATE_MINUTES,
                                        Long.toString(value.lateMinutes)),
                                entry(ReportField.PENALIZED_LATE_MINUTES,
                                        Long.toString(
                                                value.penalizedLateMinutes))),
                        "employee:" + value.reference()))
                .toList();
        return dataSet(
                ReportType.LATE,
                "迟到统计",
                fields,
                rows,
                "PRD_V1_9_MONTHLY_LATE_GRACE_PRESERVE_RAW_V1");
    }

    private ReportDataSet missedPunch(ReportSourceSnapshot snapshot) {
        List<ReportField> fields = List.of(
                ReportField.EMPLOYEE_NUMBER,
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.MISSING_PUNCH_COUNT);
        List<ReportRow> rows = aggregateDaily(visibleDaily(snapshot)).values()
                .stream()
                .filter(value -> value.missingPunches > 0)
                .map(value -> row(
                        "missing-punch:" + value.reference(),
                        Map.ofEntries(
                                entry(ReportField.EMPLOYEE_NUMBER,
                                        value.employeeNumber),
                                entry(ReportField.EMPLOYEE_NAME,
                                        value.employeeName),
                                entry(ReportField.ORGANIZATION,
                                        value.organizationName),
                                entry(ReportField.MISSING_PUNCH_COUNT,
                                        Integer.toString(
                                                value.missingPunches))),
                        "employee:" + value.reference()))
                .toList();
        return dataSet(
                ReportType.MISSED_PUNCH,
                "缺卡统计",
                fields,
                rows,
                "PRD_V1_9_SINGLE_SIDED_MISSING_PUNCH_V1");
    }

    private ReportDataSet attendanceRate(ReportSourceSnapshot snapshot) {
        List<ReportField> fields = List.of(
                ReportField.EMPLOYEE_NUMBER,
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.SCHEDULED_ATTENDANCE_DAYS,
                ReportField.ACTUAL_ATTENDANCE_DAYS,
                ReportField.SICK_LEAVE_DAYS,
                ReportField.ATTENDANCE_RATE);
        List<ReportRow> rows = aggregateDaily(visibleDaily(snapshot)).values()
                .stream()
                .map(value -> row(
                        "attendance-rate:" + value.reference(),
                        Map.ofEntries(
                                entry(ReportField.EMPLOYEE_NUMBER,
                                        value.employeeNumber),
                                entry(ReportField.EMPLOYEE_NAME,
                                        value.employeeName),
                                entry(ReportField.ORGANIZATION,
                                        value.organizationName),
                                entry(ReportField.SCHEDULED_ATTENDANCE_DAYS,
                                        Integer.toString(value.scheduledAttendanceDays)),
                                entry(ReportField.ACTUAL_ATTENDANCE_DAYS,
                                        Integer.toString(value.actualAttendanceDays)),
                                entry(ReportField.SICK_LEAVE_DAYS,
                                        Integer.toString(value.sickLeaveDays)),
                                entry(ReportField.ATTENDANCE_RATE,
                                        rateByDays(value.actualAttendanceDays, value.scheduledAttendanceDays))),
                        "employee:" + value.reference()))
                .toList();
        return dataSet(
                ReportType.ATTENDANCE_RATE,
                "出勤率统计",
                fields,
                rows,
                ATTENDANCE_RATE_FORMULA_VERSION);
    }

    private ReportDataSet annualLeave(ReportSourceSnapshot snapshot) {
        List<ReportField> fields = List.of(
                ReportField.EMPLOYEE_NUMBER,
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.ACCOUNT_TYPE,
                ReportField.OPENING_HOURS,
                ReportField.GRANTED_HOURS,
                ReportField.OVERTIME_CREDIT_HOURS,
                ReportField.MANUAL_INCREASE_HOURS,
                ReportField.USED_HOURS,
                ReportField.EXPIRED_HOURS,
                ReportField.RETURNED_HOURS,
                ReportField.MANUAL_DEDUCTION_HOURS,
                ReportField.BALANCE_HOURS,
                ReportField.EQUIVALENT_DAYS);
        List<ReportRow> rows = visibleTimeAccounts(snapshot).stream()
                .filter(value ->
                        value.accountType() == TimeAccountType.ANNUAL_LEAVE)
                .map(fact -> row(
                        "annual-leave:" + fact.accountId(),
                        Map.ofEntries(
                                entry(ReportField.EMPLOYEE_NUMBER,
                                        fact.employeeNumber()),
                                entry(ReportField.EMPLOYEE_NAME,
                                        fact.employeeName()),
                                entry(ReportField.ORGANIZATION,
                                        fact.organizationName()),
                                entry(ReportField.ACCOUNT_TYPE,
                                        fact.accountType().name()),
                                entry(ReportField.OPENING_HOURS,
                                        decimal(fact.openingHours())),
                                entry(ReportField.GRANTED_HOURS,
                                        decimal(fact.grantedHours())),
                                entry(ReportField.OVERTIME_CREDIT_HOURS,
                                        decimal(
                                                fact.overtimeCreditHours())),
                                entry(ReportField.MANUAL_INCREASE_HOURS,
                                        decimal(
                                                fact.manualIncreaseHours())),
                                entry(ReportField.USED_HOURS,
                                        decimal(fact.usedHours())),
                                entry(ReportField.EXPIRED_HOURS,
                                        decimal(fact.expiredHours())),
                                entry(ReportField.RETURNED_HOURS,
                                        decimal(fact.returnedHours())),
                                entry(ReportField.MANUAL_DEDUCTION_HOURS,
                                        decimal(
                                                fact.manualDeductionHours())),
                                entry(ReportField.BALANCE_HOURS,
                                        decimal(fact.balanceHours())),
                                entry(ReportField.EQUIVALENT_DAYS,
                                        decimal(fact.balanceHours().divide(
                                                BigDecimal.valueOf(8),
                                                2,
                                                RoundingMode.HALF_UP)))),
                        "ledger:" + fact.ledgerVersion()))
                .toList();
        return dataSet(
                ReportType.ANNUAL_LEAVE,
                "年休假余额汇总",
                fields,
                rows,
                "PRD_V1_9_IMMUTABLE_TIME_ACCOUNT_LEDGER_BALANCE_V1");
    }

    private List<DailyFact> visibleDaily(ReportSourceSnapshot snapshot) {
        return snapshot.dailyFacts().stream()
                .filter(value -> snapshot.filter().period()
                        .equals(java.time.YearMonth.from(
                                value.businessDate())))
                .filter(value -> matches(
                        value.organizationId(),
                        value.employeeId(),
                        snapshot))
                .toList();
    }

    private List<OaDocumentFact> visibleOa(ReportSourceSnapshot snapshot) {
        var periodStart = snapshot.filter()
                .period()
                .atDay(1)
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();
        var periodEnd = snapshot.filter()
                .period()
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();
        return snapshot.oaDocumentFacts().stream()
                .filter(value -> value.start().isBefore(periodEnd)
                        && value.endExclusive().isAfter(periodStart))
                .filter(value -> matches(
                        value.organizationId(),
                        value.employeeId(),
                        snapshot))
                .toList();
    }

    private List<ExceptionFact> visibleExceptions(
            ReportSourceSnapshot snapshot) {
        return snapshot.exceptionFacts().stream()
                .filter(value -> snapshot.filter().period()
                        .equals(java.time.YearMonth.from(
                                value.businessDate())))
                .filter(value -> matches(
                        value.organizationId(),
                        value.employeeId(),
                        snapshot))
                .filter(value -> snapshot.filter().status() == null
                        || value.state().name().equals(
                                snapshot.filter().status()))
                .toList();
    }

    private List<TimeAccountFact> visibleTimeAccounts(
            ReportSourceSnapshot snapshot) {
        return snapshot.timeAccountFacts().stream()
                .filter(value -> matches(
                        value.organizationId(),
                        value.employeeId(),
                        snapshot))
                .toList();
    }

    private boolean matches(
            String organizationId,
            String employeeId,
            ReportSourceSnapshot snapshot) {
        return (snapshot.filter().organizationId() == null
                        || snapshot.filter().organizationId()
                                .equals(organizationId))
                && (snapshot.filter().employeeId() == null
                        || snapshot.filter().employeeId().equals(employeeId));
    }

    private Map<String, EmployeeAggregate> aggregateDaily(
            List<DailyFact> facts) {
        Map<String, EmployeeAggregate> result = new LinkedHashMap<>();
        facts.stream()
                .sorted(java.util.Comparator.comparing(
                                DailyFact::employeeNumber)
                        .thenComparing(DailyFact::businessDate)
                        .thenComparing(DailyFact::factId))
                .forEach(fact -> result
                        .computeIfAbsent(
                                fact.employeeId()
                                        + "\u001f"
                                        + fact.organizationId()
                                        + "\u001f"
                                        + fact.organizationVersionId(),
                                ignored -> new EmployeeAggregate(fact))
                        .add(fact));
        return result;
    }

    private ReportDataSet dataSet(
            ReportType type,
            String title,
            List<ReportField> fields,
            List<ReportRow> rows,
            String formulaVersion) {
        List<ReportColumn> columns =
                fields.stream().map(ReportColumn::new).toList();
        return new ReportDataSet(
                type, title, columns, fields, rows, formulaVersion);
    }

    private ReportRow row(
            String reference,
            Map<ReportField, String> values,
            String drillDownReference) {
        return new ReportRow(
                reference,
                new EnumMap<>(values),
                drillDownReference);
    }

    private static Map.Entry<ReportField, String> entry(
            ReportField field, String value) {
        return Map.entry(field, value == null ? "—" : value);
    }

    private static String instant(java.time.Instant value) {
        return value == null ? "—" : value.toString();
    }

    private static String rateByDays(int actualDays, int scheduledDays) {
        if (scheduledDays == 0) {
            return "N/A";
        }
        return BigDecimal.valueOf(actualDays)
                .multiply(BigDecimal.valueOf(100))
                .divide(
                        BigDecimal.valueOf(scheduledDays),
                        2,
                        RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static final class EmployeeAggregate {

        private final String employeeId;
        private final String employeeNumber;
        private final String employeeName;
        private final String organizationId;
        private final String organizationVersionId;
        private final String organizationName;
        private long scheduled;
        private long confirmed;
        private long recognizedOvertime;
        private long paidOvertime;
        private long compensatoryOvertime;
        private long voluntaryOvertime;
        private long totalOvertime;
        private long leave;
        private long absence;
        private long actualWork;
        private int scheduledAttendanceDays;
        private int actualAttendanceDays;
        private int sickLeaveDays;
        private long weekdayOvertime;
        private long saturdayOvertime;
        private long sundayOvertime;
        private long holidayOvertime;
        private int lateEvents;
        private long lateMinutes;
        private long penalizedLateMinutes;
        private int missingPunches;

        private EmployeeAggregate(DailyFact fact) {
            employeeId = fact.employeeId();
            employeeNumber = fact.employeeNumber();
            employeeName = fact.employeeName();
            organizationId = fact.organizationId();
            organizationVersionId = fact.organizationVersionId();
            organizationName = fact.organizationName();
        }

        private void add(DailyFact fact) {
            if (!employeeNumber.equals(fact.employeeNumber())
                    || !employeeName.equals(fact.employeeName())
                    || !organizationId.equals(fact.organizationId())
                    || !organizationVersionId.equals(
                            fact.organizationVersionId())
                    || !organizationName.equals(fact.organizationName())) {
                throw new IllegalArgumentException(
                        "employee or occurrence-time organization changed"
                                + " within one report aggregate");
            }
            scheduled += fact.scheduledMinutes();
            confirmed += fact.confirmedScheduledWorkMinutes();
            recognizedOvertime += fact.recognizedOvertimeMinutes();
            paidOvertime += fact.paidOvertimeMinutes();
            compensatoryOvertime += fact.compensatoryOvertimeMinutes();
            voluntaryOvertime += fact.voluntaryOvertimeMinutes();
            totalOvertime += fact.totalOvertimeMinutes();
            leave += fact.leaveOrTimeOffMinutes();
            absence += fact.absenceMinutes();
            actualWork += fact.actualWorkMinutes();
            scheduledAttendanceDays += fact.scheduledAttendanceDays();
            actualAttendanceDays += fact.actualAttendanceDays();
            if (fact.leaveType()
                    == com.szsemicon.hr.attendance.domain.LeaveType.SICK) {
                sickLeaveDays += fact.scheduledAttendanceDays();
            }
            if (fact.lateMinutes() > 0) {
                lateEvents++;
            }
            lateMinutes += fact.lateMinutes();
            penalizedLateMinutes += fact.penalizedLateMinutes();
            missingPunches += fact.missingPunchCount();
            switch (fact.dayType()) {
                case WEEKDAY, ADJUSTED_WORKDAY ->
                        weekdayOvertime += fact.recognizedOvertimeMinutes();
                case SATURDAY ->
                        saturdayOvertime += fact.recognizedOvertimeMinutes();
                case SUNDAY ->
                        sundayOvertime += fact.recognizedOvertimeMinutes();
                case PUBLIC_HOLIDAY ->
                        holidayOvertime += fact.recognizedOvertimeMinutes();
            }
        }

        private String reference() {
            return employeeId
                    + ":"
                    + organizationId
                    + ":"
                    + organizationVersionId;
        }
    }
}
