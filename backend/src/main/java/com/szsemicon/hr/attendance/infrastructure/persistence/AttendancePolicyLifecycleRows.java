package com.szsemicon.hr.attendance.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;

final class AttendancePolicyLifecycleRows {

    private AttendancePolicyLifecycleRows() {
    }

    record ScopeRow(
            String scopeId,
            String templateId,
            String legalEntityId,
            String policyKind,
            long rowVersion) {
    }

    record VersionRow(
            String scopedVersionId,
            String scopeId,
            String templateId,
            String legalEntityId,
            String policyKind,
            int versionNumber,
            String status,
            String parametersJson,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String changeReason,
            String validationJson,
            String snapshotJson,
            String snapshotDigest,
            String rollbackOfScopedVersionId,
            long rowVersion,
            String createdBy,
            Instant createdAt,
            Instant validatedAt,
            Instant publishedAt,
            LocalDate deactivationEffectiveFrom) {
    }

    record LifecycleHeadRow(
            String lifecycleEventId,
            int eventSequence) {
    }
}
