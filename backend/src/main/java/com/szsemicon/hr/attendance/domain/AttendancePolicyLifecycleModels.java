package com.szsemicon.hr.attendance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class AttendancePolicyLifecycleModels {

    private AttendancePolicyLifecycleModels() {
    }

    public enum ScopedVersionStatus {
        DRAFT,
        VALIDATED,
        PUBLISHED
    }

    public record ParameterValue(String key, Object value) {
    }

    public record ValidationIssue(String code, String field, String message) {
    }

    public record ValidationResult(
            boolean valid,
            List<ValidationIssue> issues,
            Instant validatedAt) {

        public ValidationResult {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    public record ScopedPolicyVersion(
            String scopedVersionId,
            String scopeId,
            String templateId,
            String companyId,
            AttendancePolicyModels.PolicyKind policyKind,
            int versionNumber,
            ScopedVersionStatus status,
            List<ParameterValue> parameters,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String changeReason,
            ValidationResult validation,
            String snapshotJson,
            String snapshotDigest,
            String rollbackOfScopedVersionId,
            long rowVersion,
            String createdBy,
            Instant createdAt,
            Instant publishedAt,
            String updatedBy,
            Instant updatedAt,
            LocalDate deactivationEffectiveFrom) {

        public ScopedPolicyVersion {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
            validation = validation == null
                    ? new ValidationResult(false, List.of(), null)
                    : validation;
        }
    }

    public record Page<T>(List<T> items, long total, int page, int size) {

        public Page {
            items = List.copyOf(items);
        }
    }
}
