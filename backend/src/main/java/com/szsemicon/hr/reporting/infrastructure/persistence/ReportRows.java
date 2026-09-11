package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.CompanyOption;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.DepartmentAttendanceRate;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.EmployeeSickLeaveDays;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.EmployeeDepartmentAttendancePeriod;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionSeverity;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

final class ReportRows {

    private ReportRows() {
    }

    record SourceCutoffRow(String sourceType, Instant committedAt) {
    }

    record CompanyRow(String companyId, String companyName) {

        CompanyOption toDomain() {
            return new CompanyOption(companyId, companyName);
        }
    }

    record PrincipalHomeRow(
            String employeeId,
            String employeeNumber,
            String companyId,
            String companyName,
            String organizationId,
            String organizationName) {
    }

    record ProjectionRow(
            String projectionId,
            String companyId,
            String projectionVersion,
            String periodState,
            String sourceVersionsJson,
            Instant dataAsOf) {
    }

    record ScopeRow(
            String scopeId,
            String scopeType,
            String companyId,
            String organizationId,
            boolean includeDescendants,
            String principalEmployeeId) {
    }

    record DepartmentAttendanceRateRow(
            String companyId,
            String organizationId,
            long actualAttendanceDays,
            long scheduledAttendanceDays,
            long sickLeaveDays,
            BigDecimal attendanceRate) {

        DepartmentAttendanceRate toDomain() {
            return new DepartmentAttendanceRate(
                    companyId,
                    organizationId,
                    actualAttendanceDays,
                    scheduledAttendanceDays,
                    sickLeaveDays,
                    attendanceRate);
        }

        DepartmentAttendanceRateRow(
                String companyId,
                String organizationId,
                long actualAttendanceDays,
                long scheduledAttendanceDays,
                BigDecimal attendanceRate) {
            this(
                    companyId,
                    organizationId,
                    actualAttendanceDays,
                    scheduledAttendanceDays,
                    0,
                    attendanceRate);
        }
    }

    record EmployeeDepartmentAttendancePeriodRow(
            String companyId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            LocalDate periodStart,
            LocalDate periodEnd,
            long actualAttendanceDays,
            long scheduledAttendanceDays,
            BigDecimal attendanceRate) {

        EmployeeDepartmentAttendancePeriod toDomain() {
            return new EmployeeDepartmentAttendancePeriod(
                    companyId,
                    employeeId,
                    employeeNumber,
                    employeeName,
                    organizationId,
                    organizationName,
                    periodStart,
                    periodEnd,
                    actualAttendanceDays,
                    scheduledAttendanceDays,
                    attendanceRate);
        }
    }

    record EmployeeSickLeaveDaysRow(
            String companyId,
            String employeeId,
            String organizationId,
            long sickLeaveDays) {

        EmployeeSickLeaveDays toDomain() {
            return new EmployeeSickLeaveDays(
                    companyId, employeeId, organizationId, sickLeaveDays);
        }
    }

    record MatrixEmployeeRow(
            String employeeId,
            String employeeNumber,
            String employeeName) {
    }

    record DailyRow(
            String factId,
            String companyId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationVersionId,
            String organizationName,
            LocalDate businessDate,
            String dayType,
            String shiftLabel,
            long scheduledMinutes,
            long confirmedScheduledWorkMinutes,
            long recognizedOvertimeMinutes,
            long paidOvertimeMinutes,
            long compensatoryOvertimeMinutes,
            long voluntaryOvertimeMinutes,
            long totalOvertimeMinutes,
            long leaveOrTimeOffMinutes,
            String leaveType,
            long absenceMinutes,
            long actualWorkMinutes,
            int scheduledAttendanceDays,
            double actualAttendanceDays,
            long lateMinutes,
            long penalizedLateMinutes,
            long earlyDepartureMinutes,
            int missingPunchCount,
            Instant firstPunchAt,
            Instant lastPunchAt,
            String calculationVersionId,
            String resultDigest) {

        DailyFact toDomain() {
            DayType parsedDay = DayType.WEEKDAY;
            if (dayType != null && !dayType.isBlank()) {
                try {
                    parsedDay = DayType.valueOf(dayType.trim());
                } catch (IllegalArgumentException ignored) {
                    parsedDay = DayType.WEEKDAY;
                }
            }
            long paid = Math.max(0, paidOvertimeMinutes);
            long compensatory = Math.max(0, compensatoryOvertimeMinutes);
            long voluntary = Math.max(0, voluntaryOvertimeMinutes);
            long classified = paid + compensatory + voluntary;
            long recognized = Math.max(recognizedOvertimeMinutes, classified);
            long confirmed = Math.max(0, confirmedScheduledWorkMinutes);
            long actual = confirmed + recognized;
            long late = Math.max(0, lateMinutes);
            long penalized = Math.min(Math.max(0, penalizedLateMinutes), late);
            String shift = (shiftLabel == null || shiftLabel.isBlank())
                    ? "—"
                    : shiftLabel;
            String orgName = (organizationName == null
                    || organizationName.isBlank())
                    ? "—"
                    : organizationName;
            return new DailyFact(
                    factId,
                    companyId,
                    employeeId,
                    employeeNumber,
                    employeeName,
                    organizationId,
                    organizationVersionId,
                    orgName,
                    businessDate,
                    parsedDay,
                    shift,
                    Math.max(0, scheduledMinutes),
                    confirmed,
                    recognized,
                    paid,
                    compensatory,
                    voluntary,
                    classified,
                    Math.max(0, leaveOrTimeOffMinutes),
                    Math.max(0, absenceMinutes),
                    actual,
                    scheduledAttendanceDays == 1 ? 1 : 0,
                    snapAttendanceDays(actualAttendanceDays),
                    late,
                    penalized,
                    Math.max(0, earlyDepartureMinutes),
                    Math.max(0, missingPunchCount),
                    firstPunchAt,
                    lastPunchAt,
                    calculationVersionId,
                    resultDigest,
                    LeaveType.fromLeaveCode(leaveType));
        }

        private static double snapAttendanceDays(double days) {
            if (Math.abs(days - 1.0d) < 0.000_001d) {
                return 1.0d;
            }
            if (Math.abs(days - 0.5d) < 0.000_001d) {
                return 0.5d;
            }
            if (days >= 0.75d) {
                return 1.0d;
            }
            if (days >= 0.25d) {
                return 0.5d;
            }
            return 0.0d;
        }
    }

    record OaDocumentRow(
            String documentId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            String documentType,
            String leaveType,
            Instant startAt,
            Instant endExclusive,
            long recognizedMinutes,
            String sourceStatus,
            String sourceVersion,
            String sourceOrigin) {

        OaDocumentFact toDomain() {
            return new OaDocumentFact(
                    documentId,
                    employeeId,
                    employeeNumber,
                    employeeName,
                    organizationId,
                    organizationName,
                    documentType,
                    leaveType,
                    startAt,
                    endExclusive,
                    recognizedMinutes,
                    sourceStatus,
                    sourceVersion,
                    sourceOrigin);
        }
    }

    record ExceptionRow(
            String caseId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            LocalDate businessDate,
            String exceptionType,
            String severity,
            String state,
            long minutes,
            String safeEvidenceSummary,
            String calculationVersionId) {

        ExceptionFact toDomain() {
            return new ExceptionFact(
                    caseId,
                    employeeId,
                    employeeNumber,
                    employeeName,
                    organizationId,
                    organizationName,
                    businessDate,
                    exceptionType,
                    parseSeverity(severity),
                    parseState(state),
                    minutes,
                    safeEvidenceSummary,
                    calculationVersionId);
        }
    }

    record TimeAccountRow(
            String accountId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            String accountType,
            BigDecimal openingHours,
            BigDecimal grantedHours,
            BigDecimal overtimeCreditHours,
            BigDecimal manualIncreaseHours,
            BigDecimal usedHours,
            BigDecimal expiredHours,
            BigDecimal returnedHours,
            BigDecimal manualDeductionHours,
            String ledgerVersion) {

        TimeAccountFact toDomain() {
            return new TimeAccountFact(
                    accountId,
                    employeeId,
                    employeeNumber,
                    employeeName,
                    organizationId,
                    organizationName,
                    parseAccountType(accountType),
                    openingHours,
                    grantedHours,
                    overtimeCreditHours,
                    manualIncreaseHours,
                    usedHours,
                    expiredHours,
                    returnedHours,
                    manualDeductionHours,
                    ledgerVersion);
        }
    }

    private static ExceptionSeverity parseSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            return ExceptionSeverity.INFO;
        }
        try {
            return ExceptionSeverity.valueOf(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return switch (raw.trim()) {
                case "WARN", "WARNING", "警告" -> ExceptionSeverity.WARNING;
                case "ERROR", "错误" -> ExceptionSeverity.ERROR;
                default -> ExceptionSeverity.INFO;
            };
        }
    }

    private static ExceptionState parseState(String raw) {
        if (raw == null || raw.isBlank()) {
            return ExceptionState.OPEN;
        }
        try {
            return ExceptionState.valueOf(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return ExceptionState.OPEN;
        }
    }

    private static TimeAccountType parseAccountType(String raw) {
        if (raw == null || raw.isBlank()) {
            return TimeAccountType.ANNUAL_LEAVE;
        }
        try {
            return TimeAccountType.valueOf(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return switch (raw.trim()) {
                case "TIME_OFF", "COMPENSATORY" -> TimeAccountType.COMP_TIME;
                case "ANNUAL", "ANNUAL_LEAVE" -> TimeAccountType.ANNUAL_LEAVE;
                case "WORK_HOURS", "OVERTIME", "RECOGNIZED_OVERTIME"
                        -> TimeAccountType.RECOGNIZED_OVERTIME;
                default -> TimeAccountType.ANNUAL_LEAVE;
            };
        }
    }

    record OrganizationGraphRow(
            String organizationId,
            String parentOrganizationId,
            String name,
            String orgType) {
    }

    record OrganizationAncestorRow(
            String organizationId,
            String ancestorName,
            String ancestorOrgType,
            int depth) {
    }
}
