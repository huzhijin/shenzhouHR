package com.szsemicon.hr.people.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;

final class PeopleRows {

    private PeopleRows() {
    }

    record BatchRow(
            String batchId,
            String legalEntityId,
            String templateType,
            String templateVersion,
            String status,
            String reason,
            String fileSha256,
            String mappingJson,
            int addedCount,
            int updatedCount,
            int unchangedCount,
            int conflictCount,
            int errorCount,
            int blockingIssueCount,
            Long precheckVersion,
            long rowVersion,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt,
            Instant publishedAt,
            Instant voidedAt,
            String duplicateOfPublicationId,
            String fileId,
            String originalFileName,
            String mediaType,
            Long sizeBytes,
            String storedFileSha256,
            byte[] content,
            String uploadedBy,
            Instant uploadedAt) {
    }

    record DiffRow(
            String diffId,
            String batchId,
            int rowNumber,
            String entityType,
            String category,
            String matchedResourceId,
            String sourceValuesJson,
            String currentValuesJson,
            String proposedValuesJson) {
    }

    record IssueRow(
            String issueId,
            String batchId,
            int rowNumber,
            String fieldName,
            String issueCode,
            String message,
            String severity,
            String candidateEmployeeIdsJson) {
    }

    record PublicationRow(
            String publicationId,
            String batchId,
            String legalEntityId,
            String templateType,
            String templateVersion,
            String fileSha256,
            String snapshotDigest,
            String snapshotJson,
            String localVersionIdsJson,
            String publishedBy,
            Instant publishedAt) {
    }

    record RollbackRow(
            String rollbackId,
            String batchId,
            String sourcePublicationId,
            String restoredSnapshotDigest,
            String createdVersionIdsJson,
            String rolledBackBy,
            Instant rolledBackAt) {
    }

    record IdempotencyRow(
            String actorId,
            String actionCode,
            String idempotencyKey,
            String requestDigest,
            String resourceId,
            String resultJson) {
    }

    record OrganizationRow(
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
            String sourceImportBatchId,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            int childCount) {
    }

    record EmployeeRow(
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
            String sourceImportBatchId,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt) {
    }

    record EmploymentRow(
            String employmentPeriodId,
            String assignmentId,
            String employeeId,
            String organizationId,
            String positionId,
            LocalDate startDate,
            LocalDate terminationDate,
            LocalDate endExclusive,
            String recordStatus,
            String sourceImportBatchId,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt) {
    }

    record PriorServiceRow(
            String priorServiceRecordId,
            String employeeId,
            String recordType,
            int amountDays,
            String reason,
            LocalDate businessDate,
            String sourceImportBatchId,
            String reversalOfRecordId,
            int resultingTotalDays,
            String actorId,
            Instant occurredAt,
            String requestId,
            long rowVersion) {
    }
}
