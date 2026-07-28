package com.szsemicon.hr.policy.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;

final class PolicyRows {

    private PolicyRows() {
    }

    record TemplateRow(
            String templateId,
            String templateCode,
            String name,
            String description,
            String fieldDefinitionsJson,
            String status,
            int latestVersionNumber,
            long rowVersion,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {
    }

    record VersionRow(
            String versionId,
            String templateId,
            int versionNumber,
            String status,
            String parametersJson,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String changeReason,
            String validationJson,
            String snapshotJson,
            String snapshotDigest,
            String rollbackOfVersionId,
            long rowVersion,
            String createdBy,
            Instant createdAt,
            Instant publishedAt,
            String updatedBy,
            Instant updatedAt) {
    }

    record ScopeRow(
            String bindingId,
            String versionId,
            String scopeType,
            String scopeResourceId,
            int priority,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            long rowVersion) {
    }

    record ConflictRow(
            String conflictingVersionId,
            String scopeType,
            String scopeResourceId,
            int priority,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }
}
