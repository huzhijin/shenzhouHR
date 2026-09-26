package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.DailyFactWrite;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.ExceptionFactWrite;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.OaDocumentFactWrite;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.ProjectionDraft;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.StoredProjection;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter.TimeAccountFactWrite;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class AttendanceReportProjectionWriteRows {

    private AttendanceReportProjectionWriteRows() {
    }

    record StoredProjectionRow(
            String projectionId,
            String companyId,
            LocalDate periodStart,
            LocalDate periodEndExclusive,
            String periodState,
            String projectionVersion,
            String formulaCatalogVersion,
            String sourceVersionsJson,
            String sourceSnapshotDigest,
            String projectionDigest,
            String status,
            Instant dataAsOf,
            Instant publishedAt) {

        StoredProjection toStoredProjection(List<String> sourceVersions) {
            return new StoredProjection(
                    projectionId,
                    companyId,
                    periodStart,
                    periodEndExclusive,
                    PeriodState.valueOf(periodState),
                    projectionVersion,
                    formulaCatalogVersion,
                    sourceVersions,
                    sourceSnapshotDigest,
                    projectionDigest,
                    status,
                    dataAsOf,
                    publishedAt);
        }
    }

    record ProjectionDraftRow(
            String projectionId,
            String companyId,
            LocalDate periodStart,
            LocalDate periodEndExclusive,
            String periodState,
            String projectionVersion,
            String formulaCatalogVersion,
            String sourceVersionsJson,
            String sourceSnapshotDigest,
            String projectionDigest,
            Instant dataAsOf,
            String createdBy,
            Instant createdAt) {

        static ProjectionDraftRow from(
                ProjectionDraft draft, String sourceVersionsJson) {
            return new ProjectionDraftRow(
                    draft.projectionId(),
                    draft.companyId(),
                    draft.periodStart(),
                    draft.periodEndExclusive(),
                    draft.periodState().name(),
                    draft.projectionVersion(),
                    draft.formulaCatalogVersion(),
                    sourceVersionsJson,
                    draft.sourceSnapshotDigest(),
                    draft.projectionDigest(),
                    draft.dataAsOf(),
                    draft.createdBy(),
                    draft.createdAt());
        }
    }

    record DailyFactRow(
            String rowId,
            String projectionId,
            String companyId,
            String employeeId,
            String employeeVersionId,
            String employmentAssignmentId,
            String organizationId,
            String organizationVersionId,
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
            String resultDigest,
            Instant createdAt) {

        static DailyFactRow from(DailyFactWrite value) {
            var fact = value.fact();
            return new DailyFactRow(
                    value.rowId(),
                    value.projectionId(),
                    fact.companyId(),
                    fact.employeeId(),
                    value.employeeVersionId(),
                    value.employmentAssignmentId(),
                    fact.organizationId(),
                    fact.organizationVersionId(),
                    fact.businessDate(),
                    fact.dayType().name(),
                    fact.shiftLabel(),
                    fact.scheduledMinutes(),
                    fact.confirmedScheduledWorkMinutes(),
                    fact.recognizedOvertimeMinutes(),
                    fact.paidOvertimeMinutes(),
                    fact.compensatoryOvertimeMinutes(),
                    fact.voluntaryOvertimeMinutes(),
                    fact.totalOvertimeMinutes(),
                    fact.leaveOrTimeOffMinutes(),
                    fact.leaveType() == null ? null : fact.leaveType().name(),
                    fact.absenceMinutes(),
                    fact.actualWorkMinutes(),
                    fact.scheduledAttendanceDays(),
                    fact.actualAttendanceDays(),
                    fact.lateMinutes(),
                    fact.penalizedLateMinutes(),
                    fact.earlyDepartureMinutes(),
                    fact.missingPunchCount(),
                    fact.firstPunchAt(),
                    fact.lastPunchAt(),
                    fact.calculationVersionId(),
                    fact.resultDigest(),
                    value.createdAt());
        }
    }

    record ExceptionFactRow(
            String rowId,
            String projectionId,
            String companyId,
            String employeeId,
            String employeeVersionId,
            String employmentAssignmentId,
            String organizationId,
            String organizationVersionId,
            LocalDate businessDate,
            String exceptionCaseId,
            String exceptionType,
            String severity,
            String state,
            long exceptionMinutes,
            String safeEvidenceSummary,
            String calculationVersionId,
            Instant createdAt) {

        static ExceptionFactRow from(ExceptionFactWrite value) {
            var fact = value.fact();
            return new ExceptionFactRow(
                    value.rowId(),
                    value.projectionId(),
                    value.companyId(),
                    fact.employeeId(),
                    value.employeeVersionId(),
                    value.employmentAssignmentId(),
                    fact.organizationId(),
                    value.organizationVersionId(),
                    fact.businessDate(),
                    fact.caseId(),
                    fact.exceptionType(),
                    fact.severity().name(),
                    fact.state().name(),
                    fact.minutes(),
                    fact.safeEvidenceSummary(),
                    fact.calculationVersionId(),
                    value.createdAt());
        }
    }

    record OaDocumentFactRow(
            String rowId,
            String projectionId,
            String companyId,
            String oaAttendanceDocumentId,
            String employeeId,
            String employeeVersionId,
            String employmentAssignmentId,
            String organizationId,
            String organizationVersionId,
            String documentType,
            String leaveTypeCode,
            String temporalShape,
            Instant pointInstant,
            Instant intervalStart,
            Instant intervalEndExclusive,
            long recognizedMinutes,
            String sourceStatus,
            String sourceVersion,
            Instant createdAt) {

        static OaDocumentFactRow from(OaDocumentFactWrite value) {
            return new OaDocumentFactRow(
                    value.rowId(),
                    value.projectionId(),
                    value.companyId(),
                    value.oaAttendanceDocumentId(),
                    value.employeeId(),
                    value.employeeVersionId(),
                    value.employmentAssignmentId(),
                    value.organizationId(),
                    value.organizationVersionId(),
                    value.documentType(),
                    value.leaveTypeCode(),
                    value.temporalShape().name(),
                    value.pointInstant(),
                    value.intervalStart(),
                    value.intervalEndExclusive(),
                    value.recognizedMinutes(),
                    value.sourceStatus(),
                    value.sourceVersion(),
                    value.createdAt());
        }
    }

    record TimeAccountFactRow(
            String rowId,
            String projectionId,
            String companyId,
            String accountId,
            String employeeId,
            String employeeVersionId,
            String organizationId,
            String organizationVersionId,
            String accountType,
            BigDecimal openingHours,
            BigDecimal grantedHours,
            BigDecimal overtimeCreditHours,
            BigDecimal manualIncreaseHours,
            BigDecimal usedHours,
            BigDecimal expiredHours,
            BigDecimal returnedHours,
            BigDecimal manualDeductionHours,
            String ledgerVersion,
            Instant createdAt) {

        static TimeAccountFactRow from(TimeAccountFactWrite value) {
            return new TimeAccountFactRow(
                    value.rowId(),
                    value.projectionId(),
                    value.companyId(),
                    value.accountId(),
                    value.employeeId(),
                    value.employeeVersionId(),
                    value.organizationId(),
                    value.organizationVersionId(),
                    value.accountType().name(),
                    value.openingHours(),
                    value.grantedHours(),
                    value.overtimeCreditHours(),
                    value.manualIncreaseHours(),
                    value.usedHours(),
                    value.expiredHours(),
                    value.returnedHours(),
                    value.manualDeductionHours(),
                    value.ledgerVersion(),
                    value.createdAt());
        }
    }
}
