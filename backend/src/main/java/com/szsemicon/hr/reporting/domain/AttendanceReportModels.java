package com.szsemicon.hr.reporting.domain;

import com.szsemicon.hr.attendance.domain.LeaveType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AttendanceReportModels {

    public static final String ATTENDANCE_RATE_FORMULA_VERSION =
            "ATTENDANCE_RATE_ACTUAL_DAYS_OVER_SCHEDULED_DAYS_V2";

    private AttendanceReportModels() {
    }

    public enum ReportType {
        ATTENDANCE_DETAIL,
        LEAVE,
        OVERTIME,
        WORK_HOURS,
        EXCEPTIONS,
        LATE,
        MISSED_PUNCH,
        ATTENDANCE_RATE,
        ANNUAL_LEAVE
    }

    public enum ScopeType {
        COMPANY,
        ORGANIZATION,
        SELF
    }

    public enum DayType {
        WEEKDAY,
        SATURDAY,
        SUNDAY,
        PUBLIC_HOLIDAY,
        ADJUSTED_WORKDAY
    }

    public enum ExceptionSeverity {
        INFO,
        WARNING,
        ERROR
    }

    public enum ExceptionState {
        OPEN,
        PENDING_EVIDENCE,
        PENDING_REVIEW,
        RESOLVED
    }

    public enum TimeAccountType {
        ANNUAL_LEAVE,
        COMP_TIME,
        RECOGNIZED_OVERTIME
    }

    public enum ReportField {
        BUSINESS_DATE("business-date", "考勤日期"),
        EMPLOYEE_NUMBER("employee-number", "工号"),
        EMPLOYEE_NAME("employee-name", "姓名"),
        ORGANIZATION("organization", "发生时组织"),
        SHIFT("shift", "班次"),
        SCHEDULED_HOURS("scheduled-hours", "应出勤工时"),
        SCHEDULED_ATTENDANCE_DAYS("scheduled-attendance-days", "应出勤天数"),
        ACTUAL_ATTENDANCE_DAYS("actual-attendance-days", "实际出勤天数"),
        CONFIRMED_HOURS("confirmed-hours", "排班内确认工作"),
        RECOGNIZED_OVERTIME_HOURS(
                "recognized-overtime-hours", "认可加班"),
        PAID_OVERTIME_HOURS("paid-overtime-hours", "计薪加班"),
        COMPENSATORY_OVERTIME_HOURS(
                "compensatory-overtime-hours", "转调休加班"),
        VOLUNTARY_OVERTIME_HOURS(
                "voluntary-overtime-hours", "义务加班"),
        TOTAL_OVERTIME_HOURS("total-overtime-hours", "汇总加班"),
        LEAVE_HOURS("leave-hours", "请假/调休"),
        SICK_LEAVE_DAYS("sick-leave-days", "病假天数"),
        ABSENCE_HOURS("absence-hours", "旷工"),
        ACTUAL_WORK_HOURS("actual-work-hours", "实际工作工时"),
        LATE_MINUTES("late-minutes", "迟到分钟"),
        PENALIZED_LATE_MINUTES(
                "penalized-late-minutes", "计罚迟到分钟"),
        EARLY_MINUTES("early-minutes", "早退分钟"),
        MISSING_PUNCH_COUNT("missing-punch-count", "缺卡次数"),
        FIRST_PUNCH("first-punch", "首次有效打卡"),
        LAST_PUNCH("last-punch", "末次有效打卡"),
        DOCUMENT_TYPE("document-type", "单据类型"),
        DOCUMENT_REFERENCE("document-reference", "单据引用"),
        DOCUMENT_START("document-start", "开始时间"),
        DOCUMENT_END("document-end", "结束时间"),
        APPROVAL_STATE("approval-state", "审批状态"),
        RECOGNIZED_HOURS("recognized-hours", "认定小时"),
        WEEKDAY_OVERTIME_HOURS(
                "weekday-overtime-hours", "工作日加班"),
        SATURDAY_OVERTIME_HOURS(
                "saturday-overtime-hours", "周六加班"),
        SUNDAY_OVERTIME_HOURS(
                "sunday-overtime-hours", "周日加班"),
        HOLIDAY_OVERTIME_HOURS(
                "holiday-overtime-hours", "法定节假日加班"),
        EXCEPTION_TYPE("exception-type", "异常类型"),
        EXCEPTION_SEVERITY("exception-severity", "异常级别"),
        EXCEPTION_STATE("exception-state", "处理状态"),
        EXCEPTION_MINUTES("exception-minutes", "异常分钟"),
        EVIDENCE_SUMMARY("evidence-summary", "证据摘要"),
        LATE_EVENT_COUNT("late-event-count", "迟到次数"),
        ATTENDANCE_RATE("attendance-rate", "出勤率（%）"),
        RATE_FORMULA_VERSION("rate-formula-version", "出勤率公式版本"),
        ACCOUNT_TYPE("account-type", "账户类型"),
        OPENING_HOURS("opening-hours", "期初"),
        GRANTED_HOURS("granted-hours", "系统发放"),
        OVERTIME_CREDIT_HOURS(
                "overtime-credit-hours", "加班转入"),
        MANUAL_INCREASE_HOURS(
                "manual-increase-hours", "人工增加"),
        USED_HOURS("used-hours", "请假/调休使用"),
        EXPIRED_HOURS("expired-hours", "到期失效"),
        RETURNED_HOURS("returned-hours", "销假/撤销返还"),
        MANUAL_DEDUCTION_HOURS(
                "manual-deduction-hours", "人工扣减"),
        BALANCE_HOURS("balance-hours", "当前余额"),
        EQUIVALENT_DAYS("equivalent-days", "8小时制等价天数");

        private final String key;
        private final String label;

        ReportField(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }
    }

    public record AuthorizedScope(
            ScopeType type,
            String reference,
            String label,
            String authorizationDigest) {

        public AuthorizedScope {
            Objects.requireNonNull(type, "type");
            reference = requireText(reference, "reference");
            label = requireText(label, "label");
            authorizationDigest = requireText(
                    authorizationDigest, "authorizationDigest");
        }
    }

    public record ReportFilter(
            YearMonth period,
            String companyId,
            String organizationId,
            String employeeId,
            String status) {

        public ReportFilter {
            Objects.requireNonNull(period, "period");
            companyId = optionalFilterReference(
                    companyId, "companyId", 36);
            organizationId = optionalFilterReference(
                    organizationId, "organizationId", 36);
            employeeId = optionalFilterReference(
                    employeeId, "employeeId", 36);
            status = optionalFilterReference(status, "status", 32);
            if (status != null
                    && !List.of(
                                    ExceptionState.OPEN.name(),
                                    ExceptionState.PENDING_EVIDENCE.name(),
                                    ExceptionState.PENDING_REVIEW.name(),
                                    ExceptionState.RESOLVED.name())
                            .contains(status)) {
                throw new IllegalArgumentException(
                        "status is not an allowed exception state");
            }
        }
    }

    public record DailyFact(
            String factId,
            String companyId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationVersionId,
            String organizationName,
            LocalDate businessDate,
            DayType dayType,
            String shiftLabel,
            long scheduledMinutes,
            long confirmedScheduledWorkMinutes,
            long recognizedOvertimeMinutes,
            long paidOvertimeMinutes,
            long compensatoryOvertimeMinutes,
            long voluntaryOvertimeMinutes,
            long totalOvertimeMinutes,
            long leaveOrTimeOffMinutes,
            long absenceMinutes,
            long actualWorkMinutes,
            int scheduledAttendanceDays,
            int actualAttendanceDays,
            long lateMinutes,
            long penalizedLateMinutes,
            long earlyDepartureMinutes,
            int missingPunchCount,
            Instant firstPunchAt,
            Instant lastPunchAt,
            String calculationVersionId,
            String resultDigest,
            LeaveType leaveType) {

        public DailyFact {
            factId = requireText(factId, "factId");
            companyId = requireText(companyId, "companyId");
            employeeId = requireText(employeeId, "employeeId");
            employeeNumber = requireText(employeeNumber, "employeeNumber");
            employeeName = requireText(employeeName, "employeeName");
            organizationId = requireText(organizationId, "organizationId");
            organizationVersionId = requireText(
                    organizationVersionId, "organizationVersionId");
            organizationName = requireText(
                    organizationName, "organizationName");
            Objects.requireNonNull(businessDate, "businessDate");
            Objects.requireNonNull(dayType, "dayType");
            shiftLabel = requireText(shiftLabel, "shiftLabel");
            requireNonNegative(
                    scheduledMinutes,
                    confirmedScheduledWorkMinutes,
                    recognizedOvertimeMinutes,
                    paidOvertimeMinutes,
                    compensatoryOvertimeMinutes,
                    voluntaryOvertimeMinutes,
                    totalOvertimeMinutes,
                    leaveOrTimeOffMinutes,
                    absenceMinutes,
                    actualWorkMinutes,
                    lateMinutes,
                    penalizedLateMinutes,
                    earlyDepartureMinutes,
                    missingPunchCount);
            if (scheduledAttendanceDays != 0 && scheduledAttendanceDays != 1) {
                throw new IllegalArgumentException(
                        "scheduledAttendanceDays must be 0 or 1");
            }
            if (actualAttendanceDays != 0 && actualAttendanceDays != 1) {
                throw new IllegalArgumentException(
                        "actualAttendanceDays must be 0 or 1");
            }
            if (actualWorkMinutes
                    != confirmedScheduledWorkMinutes
                            + recognizedOvertimeMinutes) {
                throw new IllegalArgumentException(
                        "actual work must equal confirmed scheduled work"
                                + " plus recognized overtime");
            }
            long classifiedOvertime = Math.addExact(
                    Math.addExact(
                            paidOvertimeMinutes,
                            compensatoryOvertimeMinutes),
                    voluntaryOvertimeMinutes);
            if (totalOvertimeMinutes != classifiedOvertime) {
                throw new IllegalArgumentException(
                        "total overtime must equal paid, compensatory,"
                                + " and voluntary overtime");
            }
            if (totalOvertimeMinutes > recognizedOvertimeMinutes) {
                throw new IllegalArgumentException(
                        "classified overtime cannot exceed recognized overtime");
            }
            if (penalizedLateMinutes > lateMinutes) {
                throw new IllegalArgumentException(
                        "penalized late minutes cannot exceed raw late minutes");
            }
            calculationVersionId = requireText(
                    calculationVersionId, "calculationVersionId");
            resultDigest = requireText(resultDigest, "resultDigest");
        }

        /**
         * Compatibility constructor for pre-classification callers. New
         * calculation and persistence paths use the canonical constructor and
         * explicitly supply all four classified overtime metrics.
         */
        public DailyFact(
                String factId,
                String companyId,
                String employeeId,
                String employeeNumber,
                String employeeName,
                String organizationId,
                String organizationVersionId,
                String organizationName,
                LocalDate businessDate,
                DayType dayType,
                String shiftLabel,
                long scheduledMinutes,
                long confirmedScheduledWorkMinutes,
                long recognizedOvertimeMinutes,
                long leaveOrTimeOffMinutes,
                long absenceMinutes,
                long actualWorkMinutes,
                int scheduledAttendanceDays,
                int actualAttendanceDays,
                long lateMinutes,
                long penalizedLateMinutes,
                long earlyDepartureMinutes,
                int missingPunchCount,
                Instant firstPunchAt,
                Instant lastPunchAt,
                String calculationVersionId,
                String resultDigest) {
            this(
                    factId,
                    companyId,
                    employeeId,
                    employeeNumber,
                    employeeName,
                    organizationId,
                    organizationVersionId,
                    organizationName,
                    businessDate,
                    dayType,
                    shiftLabel,
                    scheduledMinutes,
                    confirmedScheduledWorkMinutes,
                    recognizedOvertimeMinutes,
                    0,
                    0,
                    0,
                    0,
                    leaveOrTimeOffMinutes,
                    absenceMinutes,
                    actualWorkMinutes,
                    scheduledAttendanceDays,
                    actualAttendanceDays,
                    lateMinutes,
                    penalizedLateMinutes,
                    earlyDepartureMinutes,
                    missingPunchCount,
                    firstPunchAt,
                    lastPunchAt,
                    calculationVersionId,
                    resultDigest,
                    null);
        }

        /** Compatibility constructor for callers already supplying classified overtime. */
        public DailyFact(
                String factId,
                String companyId,
                String employeeId,
                String employeeNumber,
                String employeeName,
                String organizationId,
                String organizationVersionId,
                String organizationName,
                LocalDate businessDate,
                DayType dayType,
                String shiftLabel,
                long scheduledMinutes,
                long confirmedScheduledWorkMinutes,
                long recognizedOvertimeMinutes,
                long paidOvertimeMinutes,
                long compensatoryOvertimeMinutes,
                long voluntaryOvertimeMinutes,
                long totalOvertimeMinutes,
                long leaveOrTimeOffMinutes,
                long absenceMinutes,
                long actualWorkMinutes,
                int scheduledAttendanceDays,
                int actualAttendanceDays,
                long lateMinutes,
                long penalizedLateMinutes,
                long earlyDepartureMinutes,
                int missingPunchCount,
                Instant firstPunchAt,
                Instant lastPunchAt,
                String calculationVersionId,
                String resultDigest) {
            this(
                    factId,
                    companyId,
                    employeeId,
                    employeeNumber,
                    employeeName,
                    organizationId,
                    organizationVersionId,
                    organizationName,
                    businessDate,
                    dayType,
                    shiftLabel,
                    scheduledMinutes,
                    confirmedScheduledWorkMinutes,
                    recognizedOvertimeMinutes,
                    paidOvertimeMinutes,
                    compensatoryOvertimeMinutes,
                    voluntaryOvertimeMinutes,
                    totalOvertimeMinutes,
                    leaveOrTimeOffMinutes,
                    absenceMinutes,
                    actualWorkMinutes,
                    scheduledAttendanceDays,
                    actualAttendanceDays,
                    lateMinutes,
                    penalizedLateMinutes,
                    earlyDepartureMinutes,
                    missingPunchCount,
                    firstPunchAt,
                    lastPunchAt,
                    calculationVersionId,
                    resultDigest,
                    null);
        }
    }

    public record OaDocumentFact(
            String documentId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            String documentType,
            String leaveType,
            Instant start,
            Instant endExclusive,
            long recognizedMinutes,
            String sourceStatus,
            String sourceVersion) {

        public OaDocumentFact {
            documentId = requireText(documentId, "documentId");
            employeeId = requireText(employeeId, "employeeId");
            employeeNumber = requireText(employeeNumber, "employeeNumber");
            employeeName = requireText(employeeName, "employeeName");
            organizationId = requireText(organizationId, "organizationId");
            organizationName = requireText(
                    organizationName, "organizationName");
            documentType = requireText(documentType, "documentType");
            leaveType = optionalText(leaveType, "leaveType");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(endExclusive, "endExclusive");
            if (!start.isBefore(endExclusive)) {
                throw new IllegalArgumentException(
                        "OA report interval must be half-open");
            }
            requireNonNegative(recognizedMinutes);
            sourceStatus = requireText(sourceStatus, "sourceStatus");
            sourceVersion = requireText(sourceVersion, "sourceVersion");
        }
    }

    public record ExceptionFact(
            String caseId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            LocalDate businessDate,
            String exceptionType,
            ExceptionSeverity severity,
            ExceptionState state,
            long minutes,
            String safeEvidenceSummary,
            String calculationVersionId) {

        public ExceptionFact {
            caseId = requireText(caseId, "caseId");
            employeeId = requireText(employeeId, "employeeId");
            employeeNumber = requireText(employeeNumber, "employeeNumber");
            employeeName = requireText(employeeName, "employeeName");
            organizationId = requireText(organizationId, "organizationId");
            organizationName = requireText(
                    organizationName, "organizationName");
            Objects.requireNonNull(businessDate, "businessDate");
            exceptionType = requireText(exceptionType, "exceptionType");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(state, "state");
            requireNonNegative(minutes);
            safeEvidenceSummary = requireText(
                    safeEvidenceSummary, "safeEvidenceSummary");
            calculationVersionId = requireText(
                    calculationVersionId, "calculationVersionId");
        }
    }

    public record TimeAccountFact(
            String accountId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            TimeAccountType accountType,
            BigDecimal openingHours,
            BigDecimal grantedHours,
            BigDecimal overtimeCreditHours,
            BigDecimal manualIncreaseHours,
            BigDecimal usedHours,
            BigDecimal expiredHours,
            BigDecimal returnedHours,
            BigDecimal manualDeductionHours,
            String ledgerVersion) {

        public TimeAccountFact {
            accountId = requireText(accountId, "accountId");
            employeeId = requireText(employeeId, "employeeId");
            employeeNumber = requireText(employeeNumber, "employeeNumber");
            employeeName = requireText(employeeName, "employeeName");
            organizationId = requireText(organizationId, "organizationId");
            organizationName = requireText(
                    organizationName, "organizationName");
            Objects.requireNonNull(accountType, "accountType");
            openingHours = nonNegative(openingHours, "openingHours");
            grantedHours = nonNegative(grantedHours, "grantedHours");
            overtimeCreditHours = nonNegative(
                    overtimeCreditHours, "overtimeCreditHours");
            manualIncreaseHours = nonNegative(
                    manualIncreaseHours, "manualIncreaseHours");
            usedHours = nonNegative(usedHours, "usedHours");
            expiredHours = nonNegative(expiredHours, "expiredHours");
            returnedHours = nonNegative(returnedHours, "returnedHours");
            manualDeductionHours = nonNegative(
                    manualDeductionHours, "manualDeductionHours");
            ledgerVersion = requireText(ledgerVersion, "ledgerVersion");
        }

        public BigDecimal balanceHours() {
            return openingHours
                    .add(grantedHours)
                    .add(overtimeCreditHours)
                    .add(manualIncreaseHours)
                    .subtract(usedHours)
                    .subtract(expiredHours)
                    .add(returnedHours)
                    .subtract(manualDeductionHours)
                    .setScale(2, RoundingMode.HALF_UP);
        }
    }

    public record ReportSourceSnapshot(
            AuthorizedScope scope,
            ReportFilter filter,
            String projectionVersion,
            String periodState,
            Instant dataAsOf,
            List<String> sourceVersions,
            List<DailyFact> dailyFacts,
            List<OaDocumentFact> oaDocumentFacts,
            List<ExceptionFact> exceptionFacts,
            List<TimeAccountFact> timeAccountFacts) {

        public ReportSourceSnapshot {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(filter, "filter");
            projectionVersion = requireText(
                    projectionVersion, "projectionVersion");
            periodState = requireText(periodState, "periodState");
            Objects.requireNonNull(dataAsOf, "dataAsOf");
            sourceVersions = immutableSorted(sourceVersions);
            dailyFacts = List.copyOf(Objects.requireNonNull(
                    dailyFacts, "dailyFacts"));
            oaDocumentFacts = List.copyOf(Objects.requireNonNull(
                    oaDocumentFacts, "oaDocumentFacts"));
            exceptionFacts = List.copyOf(Objects.requireNonNull(
                    exceptionFacts, "exceptionFacts"));
            timeAccountFacts = List.copyOf(Objects.requireNonNull(
                    timeAccountFacts, "timeAccountFacts"));
        }
    }

    public record ReportColumn(ReportField field) {

        public ReportColumn {
            Objects.requireNonNull(field, "field");
        }
    }

    public record ReportRow(
            String rowReference,
            Map<ReportField, String> values,
            String drillDownReference) {

        public ReportRow {
            rowReference = requireText(rowReference, "rowReference");
            values = Map.copyOf(Objects.requireNonNull(values, "values"));
            if (values.isEmpty()) {
                throw new IllegalArgumentException(
                        "report row values must not be empty");
            }
            drillDownReference = optionalText(
                    drillDownReference, "drillDownReference");
        }
    }

    public record ReportDataSet(
            ReportType type,
            String title,
            List<ReportColumn> columns,
            List<ReportField> exportAllowlist,
            List<ReportRow> rows,
            String calculationFormulaVersion) {

        public ReportDataSet {
            Objects.requireNonNull(type, "type");
            title = requireText(title, "title");
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
            exportAllowlist = List.copyOf(Objects.requireNonNull(
                    exportAllowlist, "exportAllowlist"));
            rows = Objects.requireNonNull(rows, "rows").stream()
                    .sorted(Comparator.comparing(ReportRow::rowReference))
                    .toList();
            calculationFormulaVersion = requireText(
                    calculationFormulaVersion, "calculationFormulaVersion");
            var columnFields = columns.stream()
                    .map(ReportColumn::field)
                    .toList();
            if (!columnFields.containsAll(exportAllowlist)) {
                throw new IllegalArgumentException(
                        "export allowlist must be a subset of visible columns");
            }
            for (ReportRow row : rows) {
                if (!columnFields.containsAll(row.values().keySet())) {
                    throw new IllegalArgumentException(
                            "report row contains a non-allowlisted field");
                }
            }
        }
    }

    static String hours(long minutes) {
        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    static String decimal(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static String optionalText(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must be null or non-blank");
        }
        return value;
    }

    private static String optionalFilterReference(
            String value, String field, int maximumLength) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()
                || value.length() > maximumLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    field + " is not a safe report filter");
        }
        return value;
    }

    private static BigDecimal nonNegative(
            BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    field + " must be non-negative");
        }
        return value;
    }

    private static List<String> immutableSorted(List<String> values) {
        return Objects.requireNonNull(values, "values").stream()
                .map(value -> requireText(value, "sourceVersion"))
                .sorted()
                .distinct()
                .toList();
    }

    private static void requireNonNegative(long... values) {
        for (long value : values) {
            if (value < 0) {
                throw new IllegalArgumentException(
                        "report numeric values must be non-negative");
            }
        }
    }
}
