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
