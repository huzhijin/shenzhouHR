package com.szsemicon.hr.reporting.domain;

import static com.szsemicon.hr.reporting.domain.AttendanceReportModels.ATTENDANCE_RATE_FORMULA_VERSION;
import static com.szsemicon.hr.reporting.domain.AttendanceReportModels.decimal;
import static com.szsemicon.hr.reporting.domain.AttendanceReportModels.hours;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportColumn;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportDataSet;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportRow;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AttendanceReportCalculator {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(BUSINESS_ZONE);
    private static final Set<String> EFFECTIVE_OA_STATUSES = Set.of(
            "APPROVED", "MODIFIED", "SUPPLEMENTED", "UNKNOWN");

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

    public static String formulaVersion(ReportType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case ATTENDANCE_DETAIL -> "PRD_V1_9_DAILY_SEGMENT_METRICS_V2";
            case LEAVE ->
                    "OA_EFFECTIVE_INTERVAL_INTERSECT_SCHEDULE_OR_STANDARD_WINDOWS_V2";
            case OVERTIME ->
                    "OVERTIME_DOCUMENT_DETAIL_OA_PAPER_V1";
            case WORK_HOURS -> "MONTHLY_WORK_HOURS_PAID_OT_FORMULA_V1";
            case EXCEPTIONS -> "PRD_V1_9_EXCEPTION_CASE_CURRENT_STATE_V2";
            case LATE -> "PRD_V1_9_MONTHLY_LATE_GRACE_PRESERVE_RAW_V1";
            case MISSED_PUNCH -> "PRD_V1_9_SINGLE_SIDED_MISSING_PUNCH_V1";
            case ATTENDANCE_RATE -> ATTENDANCE_RATE_FORMULA_VERSION;
            case ANNUAL_LEAVE ->
                    "PRD_V1_9_IMMUTABLE_TIME_ACCOUNT_LEDGER_BALANCE_V1";
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
                formulaVersion(ReportType.ATTENDANCE_DETAIL));
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
                .filter(value -> value.documentType() != null
                        && Set.of("LEAVE", "TIME_OFF")
                        .contains(value.documentType().toUpperCase(Locale.ROOT)))
                .filter(value -> value.sourceStatus() != null
                        && EFFECTIVE_OA_STATUSES.contains(
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
                formulaVersion(ReportType.LEAVE));
    }

    private ReportDataSet overtime(ReportSourceSnapshot snapshot) {
        List<ReportField> fields = List.of(
                ReportField.EMPLOYEE_NUMBER,
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.DOCUMENT_TYPE,
                ReportField.DOCUMENT_START,
                ReportField.DOCUMENT_END,
                ReportField.RECOGNIZED_HOURS,
                ReportField.APPROVAL_STATE,
                ReportField.SOURCE_ORIGIN);
        List<ReportRow> rows = visibleOa(snapshot).stream()
                .filter(value -> "OVERTIME".equalsIgnoreCase(
                        value.documentType()))
                .filter(value -> EFFECTIVE_OA_STATUSES.contains(
                        value.sourceStatus().toUpperCase(Locale.ROOT)))
                .map(fact -> row(
                        "overtime:" + fact.documentId(),
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
                                        fact.sourceStatus()),
                                entry(ReportField.SOURCE_ORIGIN,
                                        sourceOrigin(fact.sourceOrigin()))),
                        "oa-document:" + fact.documentId()))
                .toList();
        return dataSet(
                ReportType.OVERTIME,
                "加班单据明细",
                fields,
                rows,
                formulaVersion(ReportType.OVERTIME));
    }

    private ReportDataSet workHours(ReportSourceSnapshot snapshot) {
        List<ReportField> fields = List.of(
                ReportField.EMPLOYEE_NUMBER,
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.SCHEDULED_HOURS,
                ReportField.PAID_OVERTIME_HOURS,
                ReportField.LEAVE_HOURS,
                ReportField.ANNUAL_LEAVE_HOURS,
                ReportField.COMPENSATORY_OVERTIME_HOURS,
                ReportField.TIME_OFF_HOURS,
                ReportField.ACTUAL_WORK_HOURS);
        Map<String, EmployeeAggregate> aggregates =
                aggregateDaily(visibleDaily(snapshot));
        applyLeaveBreakdown(aggregates, visibleOa(snapshot));
        List<ReportRow> rows = aggregates.values()
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
                                entry(ReportField.PAID_OVERTIME_HOURS,
                                        hours(value.paidOvertime)),
                                entry(ReportField.LEAVE_HOURS,
                                        hours(value.otherLeaveHours())),
                                entry(ReportField.ANNUAL_LEAVE_HOURS,
                                        hours(value.annualLeave)),
                                entry(ReportField.COMPENSATORY_OVERTIME_HOURS,
                                        hours(value.compensatoryOvertime)),
                                entry(ReportField.TIME_OFF_HOURS,
                                        hours(value.timeOff)),
                                entry(ReportField.ACTUAL_WORK_HOURS,
                                        hours(value.personalActualMinutes()))),
                        "employee:" + value.reference()))
                .toList();
        return dataSet(
                ReportType.WORK_HOURS,
                "个人月度工时",
                fields,
                rows,
                formulaVersion(ReportType.WORK_HOURS));
    }

    private ReportDataSet exceptions(ReportSourceSnapshot snapshot) {
        Map<String, DailyFact> dailyByEmployeeDate = new LinkedHashMap<>();
        for (DailyFact daily : visibleDaily(snapshot)) {
            dailyByEmployeeDate.put(
                    daily.employeeId() + ":" + daily.businessDate(), daily);
        }
        List<ReportField> fields = List.of(
                ReportField.BUSINESS_DATE,
                ReportField.EMPLOYEE_NUMBER,
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.EXCEPTION_TYPE,
                ReportField.EXCEPTION_SEVERITY,
                ReportField.EXCEPTION_STATE,
                ReportField.EXCEPTION_MINUTES,
                ReportField.EXCEPTION_DETAILS,
                ReportField.EVIDENCE_SUMMARY);
        List<ReportRow> rows = visibleExceptions(snapshot).stream()
                .filter(AttendanceReportCalculator::actionableException)
                .filter(fact -> !mutedWuhanDalianAugust(fact))
                .map(fact -> {
                    DailyFact daily = dailyByEmployeeDate.get(
                            fact.employeeId() + ":" + fact.businessDate());
                    String reportType = reportExceptionType(fact, daily);
                    return row(
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
                                            reportType),
                                    entry(ReportField.EXCEPTION_SEVERITY,
                                            fact.severity().name()),
                                    entry(ReportField.EXCEPTION_STATE,
                                            fact.state().name()),
                                    entry(ReportField.EXCEPTION_MINUTES,
                                            Long.toString(fact.minutes())),
                                    entry(ReportField.EXCEPTION_DETAILS,
                                            exceptionDetails(
                                                    reportType, fact, daily)),
                                    entry(ReportField.EVIDENCE_SUMMARY,
                                            fact.safeEvidenceSummary())),
                            "calculation:" + fact.calculationVersionId());
                })
                .toList();
        return dataSet(
                ReportType.EXCEPTIONS,
                "考勤异常总览",
                fields,
                rows,
                formulaVersion(ReportType.EXCEPTIONS));
    }

    private ReportDataSet late(ReportSourceSnapshot snapshot) {
        List<ReportField> fields = List.of(
                ReportField.EMPLOYEE_NUMBER,
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.LATE_EVENT_COUNT,
                ReportField.LATE_MINUTES,
                ReportField.PENALIZED_LATE_MINUTES);
        Map<String, EmployeeAggregate> lateAggregates =
                aggregateDaily(visibleDaily(snapshot));
        addLateEventsFromExceptions(lateAggregates, snapshot);
        List<ReportRow> rows = lateAggregates.values()
                .stream()
                .filter(value -> value.lateEvents > 0)
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
                formulaVersion(ReportType.LATE));
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
                formulaVersion(ReportType.MISSED_PUNCH));
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
                                        formatDays(value.actualAttendanceDays)),
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
                formulaVersion(ReportType.ATTENDANCE_RATE));
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
                formulaVersion(ReportType.ANNUAL_LEAVE));
    }

    private List<DailyFact> visibleDaily(ReportSourceSnapshot snapshot) {
        return snapshot.dailyFacts().stream()
                .filter(value -> inDisplayWindow(
                        value.businessDate(), snapshot.filter()))
                .filter(value -> matches(
                        value.organizationId(),
                        value.employeeId(),
                        snapshot))
                .toList();
    }

    private List<OaDocumentFact> visibleOa(ReportSourceSnapshot snapshot) {
        var windowStart = displayStart(snapshot.filter())
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();
        var windowEnd = displayEnd(snapshot.filter())
                .plusDays(1)
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();
        return CoveringLeaveDocuments.dropCoveringFacts(
                snapshot.oaDocumentFacts().stream()
                        .filter(value -> value.start().isBefore(windowEnd)
                                && value.endExclusive().isAfter(windowStart))
                        .filter(value -> matches(
                                value.organizationId(),
                                value.employeeId(),
                                snapshot))
                        .toList());
    }

    public static boolean inDisplayWindow(LocalDate date, ReportFilter filter) {
        return !date.isBefore(displayStart(filter))
                && !date.isAfter(displayEnd(filter));
    }

    public static LocalDate displayStart(ReportFilter filter) {
        return filter.fromDate() != null
                ? filter.fromDate()
                : filter.period().atDay(1);
    }

    public static LocalDate displayEnd(ReportFilter filter) {
        return filter.toDate() != null
                ? filter.toDate()
                : filter.period().atEndOfMonth();
    }

    private static String sourceOrigin(String origin) {
        return "PAPER".equalsIgnoreCase(origin) ? "PAPER" : "OA";
    }

    private static String reportExceptionType(
            ExceptionFact fact, DailyFact daily) {
        String type = fact.exceptionType() == null
                ? ""
                : fact.exceptionType().toUpperCase(Locale.ROOT);
        if (type.startsWith("MISSING_PUNCH")) {
            Instant morning = morningPunch(daily);
            Instant afternoon = afternoonPunch(daily);
            if (morning == null && afternoon != null) {
                return "MISSING_ON_DUTY";
            }
            if (morning != null && afternoon == null) {
                return "MISSING_OFF_DUTY";
            }
        }
        return type.isEmpty() ? fact.exceptionType() : type;
    }

    private static String exceptionDetails(
            String reportType, ExceptionFact fact, DailyFact daily) {
        Instant morning = morningPunch(daily);
        Instant afternoon = afternoonPunch(daily);
        return switch (reportType) {
            case "LATE" -> morning == null
                    ? "计罚 " + fact.minutes() + " 分钟"
                    : "上班 " + CLOCK.format(morning)
                            + "，计罚 " + fact.minutes() + " 分钟";
            case "EARLY_DEPARTURE" -> afternoon == null
                    ? "早退 " + fact.minutes() + " 分钟"
                    : "下班 " + CLOCK.format(afternoon)
                            + "，早退 " + fact.minutes() + " 分钟";
            case "MISSING_ON_DUTY" -> afternoon == null
                    ? "无上班卡"
                    : "无上班卡，下班 " + CLOCK.format(afternoon);
            case "MISSING_OFF_DUTY" -> morning == null
                    ? "无下班卡"
                    : "上班 " + CLOCK.format(morning) + "，无下班卡";
            case "ABSENCE" -> "应出勤，无打卡无单据";
            case "FAKE_OVERTIME" -> "加班时段盖住未请假的上班时段";
            case "OVERTIME_DOCUMENT_MISSING_OR_LATE" ->
                    fact.safeEvidenceSummary() == null
                            || fact.safeEvidenceSummary().startsWith("原因码=")
                    ? "未报加班"
                    : fact.safeEvidenceSummary();
            case "OVERTIME_FORM_BEYOND_LAST_PUNCH" ->
                    fact.safeEvidenceSummary() == null
                            || fact.safeEvidenceSummary().startsWith("原因码=")
                    ? "加班结束晚于打卡"
                    : fact.safeEvidenceSummary();
            case "LONG_PUNCH_SPAN_REVIEW" ->
                    fact.safeEvidenceSummary() == null
                            || fact.safeEvidenceSummary().startsWith("原因码=")
                    ? "长时在岗待审"
                    : fact.safeEvidenceSummary();
            case "NEGATIVE_LEAVE_BALANCE",
                    "NEGATIVE_ANNUAL_LEAVE_BALANCE",
                    "NEGATIVE_TIME_OFF_BALANCE" -> fact.safeEvidenceSummary();
            default -> morning == null && afternoon == null
                    ? "无打卡"
                    : fact.safeEvidenceSummary();
        };
    }

    private static Instant morningPunch(DailyFact daily) {
        if (daily == null) {
            return null;
        }
        Instant first = daily.firstPunchAt();
        Instant last = daily.lastPunchAt();
        if (first == null && last == null) {
            return null;
        }
        if (last == null || last.equals(first)) {
            Instant only = first != null ? first : last;
            return morningInstant(only) ? only : null;
        }
        return first;
    }

    private static Instant afternoonPunch(DailyFact daily) {
        if (daily == null) {
            return null;
        }
        Instant first = daily.firstPunchAt();
        Instant last = daily.lastPunchAt();
        if (first == null && last == null) {
            return null;
        }
        if (last == null || last.equals(first)) {
            Instant only = first != null ? first : last;
            return morningInstant(only) ? null : only;
        }
        return last;
    }

    private static boolean morningInstant(Instant instant) {
        return !instant.atZone(BUSINESS_ZONE)
                .toLocalTime()
                .isAfter(LocalTime.NOON);
    }

    public static boolean actionableException(ExceptionFact fact) {
        String type = fact.exceptionType() == null
                ? ""
                : fact.exceptionType().toUpperCase(Locale.ROOT);
        if ("LATE".equals(type) && fact.minutes() <= 0) {
            return false;
        }
        return switch (type) {
            case "LATE", "EARLY_DEPARTURE",
                    "MISSING_PUNCH_PENDING", "MISSING_PUNCH_OVERDUE",
                    "MISSING_PUNCH", "MISSING_ON_DUTY", "MISSING_OFF_DUTY",
                    "ABSENCE", "FAKE_OVERTIME",
                    "OVERTIME_FORM_BEYOND_LAST_PUNCH",
                    "NEGATIVE_LEAVE_BALANCE",
                    "NEGATIVE_ANNUAL_LEAVE_BALANCE",
                    "NEGATIVE_TIME_OFF_BALANCE" -> true;
            default -> false;
        };
    }

    public static boolean mutedWuhanDalianAugust(ExceptionFact fact) {
        if (fact == null || fact.businessDate() == null) {
            return false;
        }
        if (fact.businessDate().getYear() != 2026
                || fact.businessDate().getMonthValue() != 8) {
            return false;
        }
        String path = fact.organizationName() == null
                ? ""
                : fact.organizationName();
        return path.contains("武汉") || path.contains("大连");
    }

    private List<ExceptionFact> visibleExceptions(
            ReportSourceSnapshot snapshot) {
        return snapshot.exceptionFacts().stream()
                .filter(value -> inDisplayWindow(
                        value.businessDate(), snapshot.filter()))
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

    private void applyLeaveBreakdown(
            Map<String, EmployeeAggregate> aggregates,
            List<OaDocumentFact> documents) {
        for (OaDocumentFact document : documents) {
            String documentType = document.documentType()
                    .toUpperCase(Locale.ROOT);
            if (!Set.of("LEAVE", "TIME_OFF").contains(documentType)) {
                continue;
            }
            if (!EFFECTIVE_OA_STATUSES.contains(
                    document.sourceStatus().toUpperCase(Locale.ROOT))) {
                continue;
            }
            for (EmployeeAggregate aggregate : aggregates.values()) {
                if (!aggregate.employeeId.equals(document.employeeId())
                        || !aggregate.organizationId.equals(
                                document.organizationId())) {
                    continue;
                }
                aggregate.addOaLeave(documentType, document.leaveType(),
                        document.recognizedMinutes());
            }
        }
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

    private static void addLateEventsFromExceptions(
            Map<String, EmployeeAggregate> aggregates,
            ReportSourceSnapshot snapshot) {
        for (ExceptionFact fact : snapshot.exceptionFacts()) {
            if (!"LATE".equalsIgnoreCase(fact.exceptionType())
                    || fact.state() == ExceptionState.RESOLVED) {
                continue;
            }
            for (EmployeeAggregate aggregate : aggregates.values()) {
                if (aggregate.employeeId.equals(fact.employeeId())) {
                    aggregate.lateDates.add(fact.businessDate());
                    aggregate.lateEvents = aggregate.lateDates.size();
                }
            }
        }
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

    private static String rateByDays(double actualDays, int scheduledDays) {
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

    private static String formatDays(double days) {
        return BigDecimal.valueOf(days)
                .setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
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
        private long annualLeave;
        private long timeOff;
        private long otherLeave;
        private boolean oaLeaveApplied;
        private long absence;
        private long actualWork;
        private int scheduledAttendanceDays;
        private double actualAttendanceDays;
        private int sickLeaveDays;
        private long weekdayOvertime;
        private long saturdayOvertime;
        private long sundayOvertime;
        private long holidayOvertime;
        private int lateEvents;
        private final Set<java.time.LocalDate> lateDates = new HashSet<>();
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
                lateDates.add(fact.businessDate());
                lateEvents = lateDates.size();
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

        private void addOaLeave(
                String documentType, String leaveType, long minutes) {
            oaLeaveApplied = true;
            String classified = leaveType == null
                    ? ""
                    : leaveType.toUpperCase(Locale.ROOT);
            if ("TIME_OFF".equals(documentType)
                    || classified.equals("COMPENSATORY")
                    || classified.equals("TIME_OFF")
                    || classified.contains("调休")) {
                timeOff += minutes;
                return;
            }
            if (classified.equals("ANNUAL")
                    || classified.equals("ANNUAL_LEAVE")
                    || classified.contains("年假")) {
                annualLeave += minutes;
                return;
            }
            otherLeave += minutes;
        }

        private long otherLeaveHours() {
            return oaLeaveApplied ? otherLeave : leave;
        }

        private long personalActualMinutes() {
            return scheduled
                    + paidOvertime
                    + voluntaryOvertime
                    - otherLeaveHours()
                    - annualLeave
                    + compensatoryOvertime
                    - timeOff;
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
