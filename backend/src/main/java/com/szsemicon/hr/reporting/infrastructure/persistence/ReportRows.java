package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.LegalEntityOption;
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

    record LegalEntityRow(String legalEntityId, String name) {

        LegalEntityOption toDomain() {
            return new LegalEntityOption(legalEntityId, name);
        }
    }

    record ProjectionRow(
            String projectionId,
            String legalEntityId,
            String projectionVersion,
            String periodState,
            String sourceVersionsJson,
            Instant dataAsOf) {
    }

    record ScopeRow(
            String scopeId,
            String scopeType,
            String legalEntityId,
            String organizationId,
            boolean includeDescendants,
            String principalEmployeeId) {
    }

    record DailyRow(
            String factId,
            String legalEntityId,
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
            long leaveOrTimeOffMinutes,
            long absenceMinutes,
            long actualWorkMinutes,
            long lateMinutes,
            long penalizedLateMinutes,
            long earlyDepartureMinutes,
            int missingPunchCount,
            Instant firstPunchAt,
            Instant lastPunchAt,
            String calculationVersionId,
            String resultDigest) {

        DailyFact toDomain() {
            return new DailyFact(
                    factId,
                    legalEntityId,
                    employeeId,
                    employeeNumber,
                    employeeName,
                    organizationId,
                    organizationVersionId,
                    organizationName,
                    businessDate,
                    DayType.valueOf(dayType),
                    shiftLabel,
                    scheduledMinutes,
                    confirmedScheduledWorkMinutes,
                    recognizedOvertimeMinutes,
                    leaveOrTimeOffMinutes,
                    absenceMinutes,
                    actualWorkMinutes,
                    lateMinutes,
                    penalizedLateMinutes,
                    earlyDepartureMinutes,
                    missingPunchCount,
                    firstPunchAt,
                    lastPunchAt,
                    calculationVersionId,
                    resultDigest);
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
            String sourceVersion) {

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
                    sourceVersion);
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
                    ExceptionSeverity.valueOf(severity),
                    ExceptionState.valueOf(state),
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
                    TimeAccountType.valueOf(accountType),
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
}
