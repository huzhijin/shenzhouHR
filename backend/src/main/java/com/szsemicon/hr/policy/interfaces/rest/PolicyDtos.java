package com.szsemicon.hr.policy.interfaces.rest;

import com.szsemicon.hr.policy.domain.PolicyModels.FieldDefinition;
import com.szsemicon.hr.policy.domain.PolicyModels.Page;
import com.szsemicon.hr.policy.domain.PolicyModels.ParameterValue;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyConflict;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyTemplate;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyVersion;
import com.szsemicon.hr.policy.domain.PolicyModels.ScopeBinding;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationIssue;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class PolicyDtos {

    private PolicyDtos() {
    }

    public record FieldDefinitionDto(
            String key,
            String label,
            String valueType,
            boolean required,
            List<String> enumValues,
            BigDecimal minimum,
            BigDecimal maximum) {
    }

    public record ParameterValueDto(String key, Object value) {
    }

    public record ScopeBindingDto(
            String bindingId,
            String scopeType,
            String scopeResourceId,
            int priority,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    public record TemplateSummary(
            String templateId,
            String code,
            String name,
            String status,
            int latestVersionNumber,
            long rowVersion) {
    }

    public record TemplateDetail(
            String templateId,
            String code,
            String name,
            String status,
            int latestVersionNumber,
            long rowVersion,
            String description,
            List<FieldDefinitionDto> fieldDefinitions) {
    }

    public record TemplatePage(
            List<TemplateSummary> items,
            long total,
            int page,
            int size) {
    }

    public record VersionSummary(
            String versionId,
            String templateId,
            int versionNumber,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String changeReason,
            String createdBy,
            Instant createdAt,
            Instant publishedAt,
            long rowVersion) {
    }

    public record VersionDetail(
            String versionId,
            String templateId,
            int versionNumber,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String changeReason,
            String createdBy,
            Instant createdAt,
            Instant publishedAt,
            long rowVersion,
            List<ParameterValueDto> parameters,
            List<ScopeBindingDto> scopeBindings,
            ValidationResultDto validation,
            String snapshotDigest,
            String rollbackOfVersionId) {
    }

    public record VersionPage(List<VersionSummary> items, long total, int page, int size) {
    }

    public record ValidationIssueDto(String code, String field, String message) {
    }

    public record ValidationResultDto(
            boolean valid,
            List<ValidationIssueDto> issues,
            Instant validatedAt) {
    }

    public record ConflictDto(
            String code,
            String conflictingVersionId,
            String scopeType,
            String scopeResourceId,
            int priority,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    public record ConflictResult(boolean hasConflicts, List<ConflictDto> conflicts) {
    }

    public record SimulationResult(
            boolean matched,
            List<ParameterValueDto> resolvedParameters,
            List<String> explanation) {
    }

    public record ImpactPreview(
            int scopeCount,
            int affectedObjectCount,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            boolean frozenPeriodProtected,
            List<String> warnings) {
    }

    public record CreateTemplateRequest(
            String code,
            String name,
            String description,
            List<FieldDefinitionDto> fieldDefinitions) {
    }

    public record CreateDraftRequest(
            String basedOnVersionId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String changeReason) {
    }

    public record UpdateDraftRequest(
            List<ParameterValueDto> parameters,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String changeReason,
            long expectedVersion) {
    }

    public record ScopeBindingRequest(
            String scopeType,
            String scopeResourceId,
            int priority,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    public record ReplaceScopesRequest(
            List<ScopeBindingRequest> bindings,
            long expectedVersion) {
    }

    public record SimulationRequest(String sampleName, Map<String, Object> inputs) {
    }

    public record PublishRequest(String reason, long expectedVersion) {
    }

    public record ReasonRequest(String reason) {
    }

    public record RollbackRequest(
            String targetVersionId,
            String reason,
            long expectedVersion) {
    }

    static TemplateDetail templateDetail(PolicyTemplate template) {
        return new TemplateDetail(
                template.templateId(),
                template.code(),
                template.name(),
                template.status().name(),
                template.latestVersionNumber(),
                template.rowVersion(),
                template.description(),
                template.fieldDefinitions().stream().map(PolicyDtos::field).toList());
    }

    static TemplatePage templatePage(Page<PolicyTemplate> page) {
        return new TemplatePage(
                page.items().stream().map(PolicyDtos::templateSummary).toList(),
                page.total(),
                page.page(),
                page.size());
    }

    static VersionDetail versionDetail(PolicyVersion version) {
        return new VersionDetail(
                version.versionId(),
                version.templateId(),
                version.versionNumber(),
                version.status().name(),
                version.effectiveFrom(),
                version.effectiveTo(),
                version.changeReason(),
                version.createdBy(),
                version.createdAt(),
                version.publishedAt(),
                version.rowVersion(),
                version.parameters().stream().map(PolicyDtos::parameter).toList(),
                version.scopeBindings().stream().map(PolicyDtos::scope).toList(),
                validation(version.validation()),
                version.snapshotDigest(),
                version.rollbackOfVersionId());
    }

    static VersionPage versionPage(Page<PolicyVersion> page) {
        return new VersionPage(
                page.items().stream().map(PolicyDtos::versionSummary).toList(),
                page.total(),
                page.page(),
                page.size());
    }

    static ValidationResultDto validation(ValidationResult result) {
        return new ValidationResultDto(
                result.valid(),
                result.issues().stream().map(PolicyDtos::issue).toList(),
                result.validatedAt());
    }

    static ConflictResult conflicts(List<PolicyConflict> conflicts) {
        List<ConflictDto> items = conflicts.stream()
                .map(conflict -> new ConflictDto(
                        conflict.code(),
                        conflict.conflictingVersionId(),
                        conflict.scopeType().name(),
                        conflict.scopeResourceId(),
                        conflict.priority(),
                        conflict.effectiveFrom(),
                        conflict.effectiveTo()))
                .toList();
        return new ConflictResult(!items.isEmpty(), items);
    }

    private static TemplateSummary templateSummary(PolicyTemplate template) {
        return new TemplateSummary(
                template.templateId(),
                template.code(),
                template.name(),
                template.status().name(),
                template.latestVersionNumber(),
                template.rowVersion());
    }

    private static VersionSummary versionSummary(PolicyVersion version) {
        return new VersionSummary(
                version.versionId(),
                version.templateId(),
                version.versionNumber(),
                version.status().name(),
                version.effectiveFrom(),
                version.effectiveTo(),
                version.changeReason(),
                version.createdBy(),
                version.createdAt(),
                version.publishedAt(),
                version.rowVersion());
    }

    private static FieldDefinitionDto field(FieldDefinition field) {
        return new FieldDefinitionDto(
                field.key(),
                field.label(),
                field.valueType().name(),
                field.required(),
                field.enumValues(),
                field.minimum(),
                field.maximum());
    }

    private static ParameterValueDto parameter(ParameterValue parameter) {
        return new ParameterValueDto(parameter.key(), parameter.value());
    }

    private static ScopeBindingDto scope(ScopeBinding scope) {
        return new ScopeBindingDto(
                scope.bindingId(),
                scope.scopeType().name(),
                scope.scopeResourceId(),
                scope.priority(),
                scope.effectiveFrom(),
                scope.effectiveTo());
    }

    private static ValidationIssueDto issue(ValidationIssue issue) {
        return new ValidationIssueDto(issue.code(), issue.field(), issue.message());
    }
}
