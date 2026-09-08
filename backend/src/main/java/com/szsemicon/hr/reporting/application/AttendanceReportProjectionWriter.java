package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.OaTemporalShape;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for immutable formal attendance report projections.
 * Implementations must join facts to their referenced identity versions and
 * reject a write unless exactly one trusted reference set matches.
 */
public interface AttendanceReportProjectionWriter {

    boolean lockCompany(String companyId);

    Optional<StoredProjection> findByDigest(
            String companyId,
            LocalDate periodStart,
            String projectionDigest);

    Optional<StoredProjection> findLatestPublished(
            String companyId, LocalDate periodStart);

    void createDraft(ProjectionDraft draft);

    void appendDailyFact(DailyFactWrite fact);

    void appendExceptionFact(ExceptionFactWrite fact);

    void appendOaDocumentFact(OaDocumentFactWrite fact);

    void appendTimeAccountFact(TimeAccountFactWrite fact);

    void markPublished(String projectionId, Instant publishedAt);

    default void copyFactsOutsideRange(
            String sourceProjectionId,
            String targetProjectionId,
            LocalDate windowStart,
            LocalDate windowEndExclusive,
            Instant createdAt) {
        copyFactsOutsideRange(
                sourceProjectionId,
                targetProjectionId,
                windowStart,
                windowEndExclusive,
                createdAt,
                null);
    }

    default void copyFactsOutsideRange(
            String sourceProjectionId,
            String targetProjectionId,
            LocalDate windowStart,
            LocalDate windowEndExclusive,
            Instant createdAt,
            String employeeId) {
    }

    default void copyFactsOutsideRange(
            String sourceProjectionId,
            String targetProjectionId,
            LocalDate windowStart,
            LocalDate windowEndExclusive,
            Instant createdAt,
            String employeeId,
            java.util.Collection<String> employeeIds) {
        String single = employeeId;
        if ((single == null || single.isBlank())
                && employeeIds != null
                && employeeIds.size() == 1) {
            single = employeeIds.iterator().next();
        }
        copyFactsOutsideRange(
                sourceProjectionId,
                targetProjectionId,
                windowStart,
                windowEndExclusive,
                createdAt,
                single);
    }

    default void copyOaDocumentFactsExceptEmployeeWindow(
            String sourceProjectionId,
            String targetProjectionId,
            Instant windowStart,
            Instant windowEndExclusive,
            Instant createdAt,
            String employeeId) {
    }

    default void copyOaDocumentFactsExceptEmployeeWindow(
            String sourceProjectionId,
            String targetProjectionId,
            Instant windowStart,
            Instant windowEndExclusive,
            Instant createdAt,
            String employeeId,
            java.util.Collection<String> employeeIds) {
        String single = employeeId;
        if ((single == null || single.isBlank())
                && employeeIds != null
                && employeeIds.size() == 1) {
            single = employeeIds.iterator().next();
        }
        copyOaDocumentFactsExceptEmployeeWindow(
                sourceProjectionId,
                targetProjectionId,
                windowStart,
                windowEndExclusive,
                createdAt,
                single);
    }

    default void copyTimeAccountFactsExceptEmployee(
            String sourceProjectionId,
            String targetProjectionId,
            Instant createdAt,
            String employeeId) {
    }

    default void copyTimeAccountFactsExceptEmployee(
            String sourceProjectionId,
            String targetProjectionId,
            Instant createdAt,
            String employeeId,
            java.util.Collection<String> employeeIds) {
        String single = employeeId;
        if ((single == null || single.isBlank())
                && employeeIds != null
                && employeeIds.size() == 1) {
            single = employeeIds.iterator().next();
        }
        copyTimeAccountFactsExceptEmployee(
                sourceProjectionId,
                targetProjectionId,
                createdAt,
                single);
    }

    record StoredProjection(
            String projectionId,
            String companyId,
            LocalDate periodStart,
            LocalDate periodEndExclusive,
            PeriodState periodState,
            String projectionVersion,
            String formulaCatalogVersion,
            List<String> sourceVersions,
            String sourceSnapshotDigest,
            String projectionDigest,
            String status,
            Instant dataAsOf,
            Instant publishedAt) {
    }

    record ProjectionDraft(
            String projectionId,
            String companyId,
            LocalDate periodStart,
            LocalDate periodEndExclusive,
            PeriodState periodState,
            String projectionVersion,
            String formulaCatalogVersion,
            List<String> sourceVersions,
            String sourceSnapshotDigest,
            String projectionDigest,
            Instant dataAsOf,
            String createdBy,
            Instant createdAt) {
    }

    record DailyFactWrite(
            String rowId,
            String projectionId,
            String employeeVersionId,
            String employmentAssignmentId,
            DailyFact fact,
            Instant createdAt) {
    }

    record ExceptionFactWrite(
            String rowId,
            String projectionId,
            String companyId,
            String employeeVersionId,
            String employmentAssignmentId,
            String organizationVersionId,
            ExceptionFact fact,
            Instant createdAt) {
    }

    record OaDocumentFactWrite(
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
            OaTemporalShape temporalShape,
            Instant pointInstant,
            Instant intervalStart,
            Instant intervalEndExclusive,
            long recognizedMinutes,
            String sourceStatus,
            String sourceVersion,
            Instant createdAt) {
    }

    record TimeAccountFactWrite(
            String rowId,
            String projectionId,
            String companyId,
            String accountId,
            String employeeId,
            String employeeVersionId,
            String organizationId,
            String organizationVersionId,
            TimeAccountType accountType,
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
    }
}
