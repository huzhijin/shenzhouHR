package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AttendanceReportExportStore {

    void insert(ExportJob job, byte[] content);

    Optional<ExportJob> findOwnedJob(
            String exportId, String principalId);

    Optional<ExportArtifact> findOwnedReady(
            String exportId,
            String principalId,
            String expectedQueryFingerprint,
            String expectedVisibleContentDigest,
            Instant readAt);

    Optional<ExportJob> claimNextQueued(Instant claimedAt);

    void markReady(
            String exportId,
            byte[] content,
            String contentType,
            String fileExtension,
            String contentSha256,
            String expectedVisibleContentDigest,
            Instant completedAt);

    void markFailed(
            String exportId, String failureCode, Instant completedAt);

    int purgeExpired(Instant expiredAt, int limit);

    enum DeliveryMode {
        SYNC,
        ASYNC
    }

    enum ExportStatus {
        QUEUED,
        BUILDING,
        READY,
        FAILED
    }

    record ExportJob(
            String exportId,
            String principalId,
            ReportType reportType,
            ReportFilter filter,
            String purpose,
            String projectionVersion,
            String authorizationDigest,
            String queryFingerprint,
            String visibleContentDigest,
            String formulaVersion,
            List<ReportField> exportFields,
            long rowCount,
            DeliveryMode deliveryMode,
            ExportStatus status,
            String contentType,
            String fileExtension,
            String contentSha256,
            long contentLength,
            String failureCode,
            Instant expiresAt,
            Instant createdAt,
            Instant completedAt) {

        public ExportJob {
            requireText(exportId, "exportId");
            requireText(principalId, "principalId");
            if (reportType == null || filter == null) {
                throw new IllegalArgumentException(
                        "export report type and filter are required");
            }
            if (filter.companyId() == null) {
                throw new IllegalArgumentException(
                        "export must be bound to one company");
            }
            purpose = normalizePurpose(purpose);
            requireText(projectionVersion, "projectionVersion");
            requireDigest(authorizationDigest, "authorizationDigest");
            requireDigest(queryFingerprint, "queryFingerprint");
            requireDigest(
                    visibleContentDigest, "visibleContentDigest");
            requireText(formulaVersion, "formulaVersion");
            exportFields = List.copyOf(exportFields);
            if (exportFields.isEmpty() || rowCount < 0) {
                throw new IllegalArgumentException(
                        "export fields and row count are invalid");
            }
            if (deliveryMode == null || status == null) {
                throw new IllegalArgumentException(
                        "export delivery mode and status are required");
            }
            if (contentLength < 0) {
                throw new IllegalArgumentException(
                        "export content length is invalid");
            }
            if (contentSha256 != null) {
                requireDigest(contentSha256, "contentSha256");
            }
            if (contentType != null) {
                requireText(contentType, "contentType");
            }
            if (fileExtension != null
                    && !fileExtension.matches("[a-z0-9]{1,8}")) {
                throw new IllegalArgumentException(
                        "export file extension is invalid");
            }
            if (failureCode != null) {
                requireText(failureCode, "failureCode");
            }
            if (expiresAt == null
                    || createdAt == null
                    || !expiresAt.isAfter(createdAt)) {
                throw new IllegalArgumentException(
                        "export timestamps are invalid");
            }
            if (status == ExportStatus.READY
                    && (contentType == null
                            || fileExtension == null
                            || contentSha256 == null
                            || contentLength == 0
                            || completedAt == null)) {
                throw new IllegalArgumentException(
                        "ready export metadata is incomplete");
            }
            if (status == ExportStatus.FAILED
                    && (failureCode == null || completedAt == null)) {
                throw new IllegalArgumentException(
                        "failed export metadata is incomplete");
            }
            if ((status == ExportStatus.QUEUED
                            || status == ExportStatus.BUILDING)
                    && (deliveryMode != DeliveryMode.ASYNC
                            || contentType != null
                            || fileExtension != null
                            || contentSha256 != null
                            || contentLength != 0
                            || failureCode != null
                            || completedAt != null)) {
                throw new IllegalArgumentException(
                        "pending export metadata is inconsistent");
            }
            if (status == ExportStatus.FAILED
                    && (deliveryMode != DeliveryMode.ASYNC
                            || contentType != null
                            || fileExtension != null
                            || contentSha256 != null
                            || contentLength != 0)) {
                throw new IllegalArgumentException(
                        "failed export metadata is inconsistent");
            }
            if (deliveryMode == DeliveryMode.SYNC
                    && status != ExportStatus.READY) {
                throw new IllegalArgumentException(
                        "synchronous exports must be ready");
            }
            if (completedAt != null && completedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException(
                        "export completion cannot precede creation");
            }
        }

        public static String normalizePurpose(String value) {
            String normalized = value == null ? "" : value.strip();
            if (normalized.length() < 2
                    || normalized.length() > 200
                    || normalized.chars().anyMatch(
                            Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "export purpose must contain 2 to 200 safe characters");
            }
            return normalized;
        }
    }

    record ExportArtifact(ExportJob job, byte[] content) {

        public ExportArtifact {
            if (job == null) {
                throw new IllegalArgumentException(
                        "export job is required");
            }
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    field + " must be a lowercase SHA-256 digest");
        }
    }

}
