package com.szsemicon.hr.policy.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PolicyModels {

    private PolicyModels() {
    }

    public enum TemplateStatus {
        ACTIVE,
        INACTIVE
    }

    public enum VersionStatus {
        DRAFT,
        VALIDATED,
        PUBLISHED,
        INACTIVE
    }

    public enum ValueType {
        ENUM,
        BOOLEAN,
        INTEGER,
        DECIMAL,
        DURATION,
        TIME_WINDOW,
        DATE,
        TEXT
    }

    public enum ScopeType {
        COMPANY,
        LOCATION,
        ATTENDANCE_GROUP,
        POLICY_GROUP
    }

    public record FieldDefinition(
            String key,
            String label,
            ValueType valueType,
            boolean required,
            List<String> enumValues,
            BigDecimal minimum,
            BigDecimal maximum) {

        public FieldDefinition {
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }
    }

    public record ParameterValue(String key, Object value) {
    }

    public record ScopeBinding(
            String bindingId,
            String versionId,
            ScopeType scopeType,
            String scopeResourceId,
            int priority,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            long rowVersion) {
    }

    public record ValidationIssue(String code, String field, String message) {
    }

    public record ValidationResult(
            boolean valid,
            List<ValidationIssue> issues,
            Instant validatedAt) {

        public ValidationResult {
            issues = List.copyOf(issues);
        }
    }

    public record PolicyTemplate(
            String templateId,
            String code,
            String name,
            String description,
            List<FieldDefinition> fieldDefinitions,
            TemplateStatus status,
            int latestVersionNumber,
            long rowVersion,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {

        public PolicyTemplate {
            fieldDefinitions = List.copyOf(fieldDefinitions);
        }
    }

    public record PolicyVersion(
            String versionId,
            String templateId,
            int versionNumber,
            VersionStatus status,
            List<ParameterValue> parameters,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String changeReason,
            ValidationResult validation,
            String snapshotJson,
            String snapshotDigest,
            String rollbackOfVersionId,
            long rowVersion,
            String createdBy,
            Instant createdAt,
            Instant publishedAt,
            String updatedBy,
            Instant updatedAt,
            List<ScopeBinding> scopeBindings) {

        public PolicyVersion {
            parameters = List.copyOf(parameters);
            scopeBindings = List.copyOf(scopeBindings);
        }
    }

    public record PolicyConflict(
            String code,
            String conflictingVersionId,
            ScopeType scopeType,
            String scopeResourceId,
            int priority,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    public record Page<T>(List<T> items, long total, int page, int size) {

        public Page {
            items = List.copyOf(items);
        }
    }
}
