package com.szsemicon.hr.reporting.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class QueryPageRows {

    private QueryPageRows() {
    }

    public record PinRow(
            String projectionId,
            String projectionVersion,
            String periodState,
            Instant dataAsOf,
            String sourceVersionsJson) {
    }

    public record DirectoryEmployeeRow(
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName) {
    }

    public record ExceptionRow(
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
            long exceptionMinutes,
            String details,
            Instant firstPunchAt,
            Instant lastPunchAt) {
    }

    public record OaRow(
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
            String sourceOrigin,
            LocalDate projectionStart,
            LocalDate projectionEndExclusive,
            String recognizedMinutesByDateJson,
            Instant projectionDataAsOf) {
        // 13-arg compat: predates projection window + byDate + dataAsOf fields
        public OaRow(
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
                String sourceOrigin) {
            this(documentId, employeeId, employeeNumber, employeeName,
                    organizationId, organizationName, documentType, leaveType,
                    startAt, endExclusive, recognizedMinutes, sourceStatus,
                    sourceOrigin, null, null, null, null);
        }
        // 16-arg compat: predates projectionDataAsOf field
        public OaRow(
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
                String sourceOrigin,
                LocalDate projectionStart,
                LocalDate projectionEndExclusive,
                String recognizedMinutesByDateJson) {
            this(documentId, employeeId, employeeNumber, employeeName,
                    organizationId, organizationName, documentType, leaveType,
                    startAt, endExclusive, recognizedMinutes, sourceStatus,
                    sourceOrigin, projectionStart, projectionEndExclusive,
                    recognizedMinutesByDateJson, null);
        }
    }

    public record EmployeeDailyAggregateRow(
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            long lateEvents,
            long lateMinutes,
            long penalizedLateMinutes,
            long missingPunches,
            long scheduledMinutes,
            long paidOvertimeMinutes,
            long compensatoryOvertimeMinutes,
            long voluntaryOvertimeMinutes,
            long leaveMinutes,
            long annualLeaveMinutes,
            long timeOffMinutes,
            long actualWorkMinutes,
            long scheduledAttendanceDays,
            BigDecimal actualAttendanceDays,
            String employmentNote) {
    }

    public record TimeAccountRow(
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            String accountType,
            BigDecimal openingHours,
            BigDecimal grantedHours,
            BigDecimal overtimeCreditHours,
            BigDecimal usedHours,
            BigDecimal remainingHours) {
    }

    public record LeaveStatAccountRow(
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            String accountType,
            BigDecimal openingHours,
            BigDecimal grantedHours,
            BigDecimal overtimeCreditHours,
            BigDecimal usedHours,
            BigDecimal remainingHours,
            LocalDate hireDate,
            Integer priorServiceDays) {
    }

    public record OaUsageDocumentRow(
            String documentId,
            String employeeId,
            LocalDate projectionStart,
            LocalDate projectionEndExclusive,
            Instant startAt,
            Instant endExclusive,
            long recognizedMinutes,
            String kind,
            String recognizedMinutesByDateJson) {
    }

    /** Same immutable pin, including all effective competing leave forms for the day. */
    public record LegacyLeaveDayEvidenceRow(
            String documentId,
            String employeeId,
            LocalDate projectionStart,
            LocalDate businessDate,
            Long leaveMinutes,
            String leaveType,
            long effectiveDocumentCount) {
    }

    public record MonthlyLeaveUsageRow(
            String employeeId,
            Integer usageMonth,
            long recognizedMinutes,
            String kind) {
        public MonthlyLeaveUsageRow(
                String employeeId, Integer usageMonth, long recognizedMinutes) {
            this(employeeId, usageMonth, recognizedMinutes, "USED");
        }
    }

    public record LeaveSummaryRow(
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            String leaveType,
            long recognizedMinutes,
            long documentCount) {
    }

    public record DailyJournalRow(
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            LocalDate businessDate,
            String dayType,
            String shiftLabel,
            String leaveType,
            Instant firstPunchAt,
            Instant lastPunchAt,
            long lateMinutes,
            long earlyDepartureMinutes,
            long absenceMinutes,
            long leaveOrTimeOffMinutes,
            long recognizedOvertimeMinutes,
            long scheduledMinutes,
            int missingPunchCount,
            long paidOvertimeMinutes,
            long compensatoryOvertimeMinutes,
            long voluntaryOvertimeMinutes) {

        public DailyJournalRow(
                String employeeId,
                String employeeNumber,
                String employeeName,
                String organizationId,
                String organizationName,
                LocalDate businessDate,
                String dayType,
                String shiftLabel,
                String leaveType,
                Instant firstPunchAt,
                Instant lastPunchAt,
                long lateMinutes,
                long earlyDepartureMinutes,
                long absenceMinutes,
                long leaveOrTimeOffMinutes,
                long recognizedOvertimeMinutes,
                long scheduledMinutes,
                int missingPunchCount) {
            this(
                    employeeId,
                    employeeNumber,
                    employeeName,
                    organizationId,
                    organizationName,
                    businessDate,
                    dayType,
                    shiftLabel,
                    leaveType,
                    firstPunchAt,
                    lastPunchAt,
                    lateMinutes,
                    earlyDepartureMinutes,
                    absenceMinutes,
                    leaveOrTimeOffMinutes,
                    recognizedOvertimeMinutes,
                    scheduledMinutes,
                    missingPunchCount,
                    0,
                    0,
                    0);
        }

        public DailyJournalRow(
                String employeeId,
                String employeeNumber,
                String employeeName,
                String organizationId,
                String organizationName,
                LocalDate businessDate,
                String dayType,
                String shiftLabel,
                String leaveType,
                Instant firstPunchAt,
                Instant lastPunchAt,
                long lateMinutes,
                long earlyDepartureMinutes,
                long absenceMinutes,
                long leaveOrTimeOffMinutes,
                long recognizedOvertimeMinutes,
                long scheduledMinutes,
                int missingPunchCount,
                long paidOvertimeMinutes,
                long compensatoryOvertimeMinutes) {
            this(
                    employeeId,
                    employeeNumber,
                    employeeName,
                    organizationId,
                    organizationName,
                    businessDate,
                    dayType,
                    shiftLabel,
                    leaveType,
                    firstPunchAt,
                    lastPunchAt,
                    lateMinutes,
                    earlyDepartureMinutes,
                    absenceMinutes,
                    leaveOrTimeOffMinutes,
                    recognizedOvertimeMinutes,
                    scheduledMinutes,
                    missingPunchCount,
                    paidOvertimeMinutes,
                    compensatoryOvertimeMinutes,
                    0);
        }
    }

    public record DailyCellRow(
            String employeeId,
            LocalDate businessDate,
            String dayType,
            String leaveType,
            long lateMinutes,
            long earlyDepartureMinutes,
            int missingPunchCount,
            Instant firstPunchAt,
            Instant lastPunchAt,
            long paidOvertimeMinutes,
            long compensatoryOvertimeMinutes) {
    }

    public record FinanceOvertimeCellRow(
            String employeeId,
            LocalDate businessDate,
            String dayType,
            long recognizedOvertimeMinutes,
            long paidOvertimeMinutes,
            long compensatoryOvertimeMinutes,
            long voluntaryOvertimeMinutes) {

        public FinanceOvertimeCellRow(
                String employeeId,
                LocalDate businessDate,
                String dayType,
                long recognizedOvertimeMinutes) {
            this(
                    employeeId,
                    businessDate,
                    dayType,
                    recognizedOvertimeMinutes,
                    0,
                    0,
                    0);
        }
    }

    public record DailyMetricCellRow(
            String employeeId,
            LocalDate businessDate,
            String dayType,
            long minutes) {
    }

    public record FactQuery(
            List<String> projectionIds,
            String companyId,
            LocalDate fromDate,
            LocalDate toDateExclusive,
            List<String> organizationIds,
            List<String> employeeIds,
            String employeeNumber,
            String employeeId,
            String exceptionType,
            String severity,
            String state,
            String documentType,
            String leaveType,
            String sourceStatus,
            String overtimeTreatment,
            Integer occurrenceDay,
            String lateCountBand,
            String lateMinuteBand,
            String punchSide,
            String employmentStatus,
            String attendanceType,
            BigDecimal rateBelow,
            String annualBalanceBand,
            String annualLevelOne,
            String annualLevelTwo,
            String accountType,
            int offset,
            int limit,
            String sheet) {
        public FactQuery(
                List<String> projectionIds,
                String companyId,
                LocalDate fromDate,
                LocalDate toDateExclusive,
                List<String> organizationIds,
                List<String> employeeIds,
                String employeeNumber,
                String employeeId,
                String exceptionType,
                String severity,
                String state,
                String documentType,
                String leaveType,
                String sourceStatus,
                String overtimeTreatment,
                Integer occurrenceDay,
                String lateCountBand,
                String lateMinuteBand,
                String punchSide,
                String employmentStatus,
                String attendanceType,
                BigDecimal rateBelow,
                String annualBalanceBand,
                String annualLevelOne,
                String annualLevelTwo,
                String accountType,
                int offset,
                int limit) {
            this(
                    projectionIds,
                    companyId,
                    fromDate,
                    toDateExclusive,
                    organizationIds,
                    employeeIds,
                    employeeNumber,
                    employeeId,
                    exceptionType,
                    severity,
                    state,
                    documentType,
                    leaveType,
                    sourceStatus,
                    overtimeTreatment,
                    occurrenceDay,
                    lateCountBand,
                    lateMinuteBand,
                    punchSide,
                    employmentStatus,
                    attendanceType,
                    rateBelow,
                    annualBalanceBand,
                    annualLevelOne,
                    annualLevelTwo,
                    accountType,
                    offset,
                    limit,
                    null);
        }
    }
}
