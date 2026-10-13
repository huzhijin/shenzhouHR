package com.szsemicon.hr.attendance.interfaces.rest;

import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.Page;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ParameterValue;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ScopedPolicyVersion;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ValidationIssue;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ValidationResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class AttendancePolicyLifecycleDtos {

    private AttendancePolicyLifecycleDtos() {
    }

    record ParameterView(String key, Object value) {
    }

    record ValidationIssueView(String code, String field, String message) {
    }

    record ValidationView(boolean valid, List<ValidationIssueView> issues, Instant validatedAt) {
    }

    record VersionView(
            String scopedVersionId,
            String scopeId,
            String templateId,
            String companyId,
            String policyKind,
            int versionNumber,
            String status,
            List<ParameterView> parameters,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String changeReason,
            ValidationView validation,
            String snapshotDigest,
            String rollbackOfScopedVersionId,
            long rowVersion,
            String createdBy,
            Instant createdAt,
            Instant publishedAt,
            String updatedBy,
            Instant updatedAt,
            LocalDate deactivationEffectiveFrom) {
    }

    record VersionPage(List<VersionView> items, long total, int page, int size) {
    }

    static VersionPage page(Page<ScopedPolicyVersion> value) {
        return new VersionPage(
                value.items().stream().map(AttendancePolicyLifecycleDtos::version).toList(),
                value.total(),
                value.page(),
                value.size());
    }

    static VersionView version(ScopedPolicyVersion value) {
        return new VersionView(
                value.scopedVersionId(),
                value.scopeId(),
                value.templateId(),
                value.companyId(),
                value.policyKind().name(),
                value.versionNumber(),
                value.status().name(),
                value.parameters().stream()
                        .map(AttendancePolicyLifecycleDtos::parameter)
                        .toList(),
                value.effectiveFrom(),
                value.effectiveTo(),
                value.changeReason(),
                validation(value.validation()),
                value.snapshotDigest(),
                value.rollbackOfScopedVersionId(),
                value.rowVersion(),
                value.createdBy(),
                value.createdAt(),
                value.publishedAt(),
                value.updatedBy(),
                value.updatedAt(),
                value.deactivationEffectiveFrom());
    }

    static ParameterView parameter(ParameterValue value) {
        return new ParameterView(value.key(), value.value());
    }

    static ValidationView validation(ValidationResult value) {
        return new ValidationView(
                value.valid(),
                value.issues().stream()
                        .map(AttendancePolicyLifecycleDtos::issue)
                        .toList(),
                value.validatedAt());
    }

    private static ValidationIssueView issue(ValidationIssue value) {
        return new ValidationIssueView(value.code(), value.field(), value.message());
    }
}
