package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.DeliveryMode;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportJob;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportStatus;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

final class AttendanceReportExportRows {

    private AttendanceReportExportRows() {
    }

    record ExportWriteRow(
            String exportId,
            String principalId,
            String reportType,
            LocalDate periodStart,
            String legalEntityId,
            String organizationId,
            String employeeId,
            String filterStatus,
            String purpose,
            String projectionVersion,
            String authorizationDigest,
            String queryFingerprint,
            String visibleContentDigest,
            String formulaVersion,
            String exportFieldsJson,
            long rowCount,
            String deliveryMode,
            String status,
            String contentType,
            String fileExtension,
            String contentSha256,
            long contentLength,
            String failureCode,
            Instant expiresAt,
            Instant createdAt,
            Instant completedAt) {

        static ExportWriteRow from(
                ExportJob job, String exportFieldsJson) {
            return new ExportWriteRow(
                    job.exportId(),
                    job.principalId(),
                    job.reportType().name(),
                    job.filter().period().atDay(1),
                    job.filter().legalEntityId(),
                    job.filter().organizationId(),
                    job.filter().employeeId(),
                    job.filter().status(),
                    job.purpose(),
                    job.projectionVersion(),
                    job.authorizationDigest(),
                    job.queryFingerprint(),
                    job.visibleContentDigest(),
                    job.formulaVersion(),
                    exportFieldsJson,
                    job.rowCount(),
                    job.deliveryMode().name(),
                    job.status().name(),
                    job.contentType(),
                    job.fileExtension(),
                    job.contentSha256(),
                    job.contentLength(),
                    job.failureCode(),
                    job.expiresAt(),
                    job.createdAt(),
                    job.completedAt());
        }
    }

    record ExportReadRow(
            String exportId,
            String principalId,
            String reportType,
            LocalDate periodStart,
            String legalEntityId,
            String organizationId,
            String employeeId,
            String filterStatus,
            String purpose,
            String projectionVersion,
            String authorizationDigest,
            String queryFingerprint,
            String visibleContentDigest,
            String formulaVersion,
            String exportFieldsJson,
            long rowCount,
            String deliveryMode,
            String status,
            String contentType,
            String fileExtension,
            String contentSha256,
            long contentLength,
            String failureCode,
            Instant expiresAt,
            Instant createdAt,
            Instant completedAt,
            byte[] content) {

        ExportJob toJob(
                List<ReportField> exportFields,
                ExportStatus statusOverride) {
            return new ExportJob(
                    exportId,
                    principalId,
                    ReportType.valueOf(reportType),
                    new ReportFilter(
                            YearMonth.from(periodStart),
                            legalEntityId,
                            organizationId,
                            employeeId,
                            filterStatus),
                    purpose,
                    projectionVersion,
                    authorizationDigest,
                    queryFingerprint,
                    visibleContentDigest,
                    formulaVersion,
                    exportFields,
                    rowCount,
                    DeliveryMode.valueOf(deliveryMode),
                    statusOverride == null
                            ? ExportStatus.valueOf(status)
                            : statusOverride,
                    contentType,
                    fileExtension,
                    contentSha256,
                    contentLength,
                    failureCode,
                    expiresAt,
                    createdAt,
                    completedAt);
        }
    }
}
