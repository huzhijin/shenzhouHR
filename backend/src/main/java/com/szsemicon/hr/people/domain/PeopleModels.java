package com.szsemicon.hr.people.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class PeopleModels {

    public static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private PeopleModels() {
    }

    public enum TemplateType {
        ORGANIZATION,
        EMPLOYEE,
        EMPLOYMENT,
        PRIOR_SERVICE
    }

    public enum BatchStatus {
        DRAFT,
        VALIDATING,
        VALIDATION_FAILED,
        AWAITING_CONFIRMATION,
        PUBLISHING,
        PUBLISHED,
        PUBLISH_FAILED,
        VOIDED
    }

    public enum DiffCategory {
        ADDED,
        UPDATED,
        UNCHANGED,
        CONFLICT,
        ERROR
    }

    public enum IssueSeverity {
        WARNING,
        BLOCKING
    }

    public record TemplateField(
            String key,
            String label,
            boolean required,
            String valueType,
            boolean matchKey,
            String description,
            List<String> enumValues) {
    }

    public record TemplateVersion(
            TemplateType templateType,
            String templateVersion,
            String fileName,
            String sha256,
            Instant publishedAt,
            List<TemplateField> fields) {
    }

    public record ImportFile(
            String fileId,
            String batchId,
            String originalFileName,
            String mediaType,
            long sizeBytes,
            String sha256,
            byte[] content,
            String uploadedBy,
            Instant uploadedAt) {
    }

    public record MappingEntry(String sourceColumn, String targetField) {
    }

    public record PrecheckSummary(
            int added,
            int updated,
            int unchanged,
            int conflict,
            int error,
            int blockingIssueCount) {
    }

    public record ImportBatch(
            String batchId,
            String legalEntityId,
            TemplateType templateType,
            String templateVersion,
            BatchStatus status,
            String reason,
            String fileSha256,
            List<MappingEntry> mapping,
            PrecheckSummary summary,
            Long precheckVersion,
            long rowVersion,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt,
            Instant publishedAt,
            Instant voidedAt,
            String duplicateOfPublicationId,
            ImportFile file) {
    }

    public record ImportDiff(
            String diffId,
            String batchId,
            int rowNumber,
            TemplateType entityType,
            DiffCategory category,
            String matchedResourceId,
            Map<String, Object> sourceValues,
            Map<String, Object> currentValues,
            Map<String, Object> proposedValues) {
    }

    public record ImportIssue(
            String issueId,
            String batchId,
            int rowNumber,
            String field,
            String code,
            String message,
            IssueSeverity severity,
            List<String> candidateEmployeeIds) {
    }

    public record Publication(
            String publicationId,
            String batchId,
            String legalEntityId,
            TemplateType templateType,
            String templateVersion,
            String fileSha256,
            String snapshotDigest,
            List<String> localVersionIds,
            boolean deduplicated,
            String duplicateOfPublicationId,
            String publishedBy,
            Instant publishedAt,
            String snapshotJson) {
    }

    public record Rollback(
            String rollbackId,
            String batchId,
            String sourcePublicationId,
            String restoredSnapshotDigest,
            List<String> createdVersionIds,
            String rolledBackBy,
            Instant rolledBackAt) {
    }

    public record OrganizationVersion(
            String organizationVersionId,
            String organizationId,
            String legalEntityId,
            String parentOrganizationId,
            String code,
            String name,
            String organizationType,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String sourceAuthority,
            String sourceBatchId,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            int childCount) {
    }

    public record EmployeeVersion(
            String employeeVersionId,
            String employeeId,
            String legalEntityId,
            String employeeNumber,
            String displayName,
            String status,
            String externalEmployeeId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String sourceAuthority,
            String sourceBatchId,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt) {
    }

    public record EmploymentPeriod(
            String employmentPeriodId,
            String assignmentVersionId,
            String employeeId,
            String organizationId,
            String positionId,
            LocalDate startDate,
            LocalDate terminationDate,
            LocalDate endExclusive,
            String recordStatus,
            String sourceBatchId,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt) {
    }

    public record PriorServiceRecord(
            String priorServiceRecordId,
            String employeeId,
            String recordType,
            int amountDays,
            String reason,
            LocalDate businessDate,
            String sourceBatchId,
            String reversalOfRecordId,
            int resultingTotalDays,
            String actorId,
            Instant occurredAt,
            String requestId,
            long rowVersion) {
    }

    public record PriorServiceReplay(
            String employeeId,
            int totalDays,
            int recordCount,
            String replayDigest,
            Instant recalculatedAt) {
    }

    public record IdempotencyRecord(
            String actorId,
            String actionCode,
            String idempotencyKey,
            String requestDigest,
            String resourceId,
            String resultJson) {
    }
}
