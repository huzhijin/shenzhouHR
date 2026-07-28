package com.szsemicon.hr.policy.infrastructure.persistence;

import com.szsemicon.hr.policy.application.PolicyRepository;
import com.szsemicon.hr.policy.domain.PolicyModels.FieldDefinition;
import com.szsemicon.hr.policy.domain.PolicyModels.Page;
import com.szsemicon.hr.policy.domain.PolicyModels.ParameterValue;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyConflict;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyTemplate;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyVersion;
import com.szsemicon.hr.policy.domain.PolicyModels.ScopeBinding;
import com.szsemicon.hr.policy.domain.PolicyModels.ScopeType;
import com.szsemicon.hr.policy.domain.PolicyModels.TemplateStatus;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationResult;
import com.szsemicon.hr.policy.domain.PolicyModels.VersionStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyBatisPolicyRepository implements PolicyRepository {

    private final PolicyMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisPolicyRepository(PolicyMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Page<PolicyTemplate> findTemplates(
            String query,
            TemplateStatus status,
            String sort,
            int page,
            int size) {
        String statusValue = status == null ? null : status.name();
        long total = mapper.countTemplates(query, statusValue);
        long offset = Math.multiplyExact((long) page, size);
        List<PolicyTemplate> items = mapper.findTemplates(
                        query,
                        statusValue,
                        sort,
                        size,
                        offset)
                .stream()
                .map(this::toTemplate)
                .toList();
        return new Page<>(items, total, page, size);
    }

    @Override
    public Optional<PolicyTemplate> findTemplate(String templateId) {
        return Optional.ofNullable(mapper.findTemplate(templateId)).map(this::toTemplate);
    }

    @Override
    public boolean templateCodeExists(String code) {
        return mapper.countTemplateCode(code) > 0;
    }

    @Override
    public void insertTemplate(PolicyTemplate template) {
        mapper.insertTemplate(toRow(template));
    }

    @Override
    public Page<PolicyVersion> findVersions(String templateId, int page, int size) {
        long total = mapper.countVersions(templateId);
        long offset = Math.multiplyExact((long) page, size);
        List<PolicyVersion> items = mapper.findVersions(templateId, size, offset).stream()
                .map(this::toVersion)
                .toList();
        return new Page<>(items, total, page, size);
    }

    @Override
    public Optional<PolicyVersion> findVersion(String templateId, String versionId) {
        return Optional.ofNullable(mapper.findVersion(templateId, versionId)).map(this::toVersion);
    }

    @Override
    public int nextVersionNumber(String templateId) {
        return Math.addExact(mapper.maxVersionNumber(templateId), 1);
    }

    @Override
    public void insertVersion(PolicyVersion version) {
        mapper.insertVersion(toRow(version));
        for (ScopeBinding scope : version.scopeBindings()) {
            mapper.insertScope(toRow(scope));
        }
    }

    @Override
    public int updateDraft(
            PolicyVersion version,
            long expectedVersion,
            String actorId,
            Instant now) {
        return mapper.updateDraft(
                toRow(version),
                expectedVersion,
                nextOpaqueVersion(expectedVersion, now),
                actorId,
                now);
    }

    @Override
    public int saveValidation(
            String templateId,
            String versionId,
            ValidationResult validation,
            VersionStatus status,
            long expectedVersion,
            String actorId,
            Instant now) {
        return mapper.saveValidation(
                templateId,
                versionId,
                writeJson(validation),
                status.name(),
                expectedVersion,
                nextOpaqueVersion(expectedVersion, now),
                actorId,
                now);
    }

    @Override
    public int transitionVersion(
            String templateId,
            String versionId,
            VersionStatus expectedStatus,
            VersionStatus status,
            String changeReason,
            String snapshotJson,
            String snapshotDigest,
            long expectedVersion,
            String actorId,
            Instant now,
            Instant publishedAt) {
        return mapper.transitionVersion(
                templateId,
                versionId,
                expectedStatus.name(),
                status.name(),
                changeReason,
                snapshotJson,
                snapshotDigest,
                expectedVersion,
                nextOpaqueVersion(expectedVersion, now),
                actorId,
                now,
                publishedAt);
    }

    @Override
    public int touchDraft(
            String templateId,
            String versionId,
            long expectedVersion,
            String actorId,
            Instant now) {
        return mapper.touchDraft(
                templateId,
                versionId,
                expectedVersion,
                nextOpaqueVersion(expectedVersion, now),
                actorId,
                now);
    }

    @Override
    public int claimVersion(
            String templateId,
            String versionId,
            VersionStatus expectedStatus,
            long expectedVersion,
            String actorId,
            Instant now) {
        return mapper.claimVersion(
                templateId,
                versionId,
                expectedStatus.name(),
                expectedVersion,
                nextOpaqueVersion(expectedVersion, now),
                actorId,
                now);
    }

    @Override
    public void replaceScopes(String versionId, List<ScopeBinding> bindings) {
        mapper.deleteScopes(versionId);
        bindings.stream().map(this::toRow).forEach(mapper::insertScope);
    }

    @Override
    public List<PolicyConflict> findPublicationConflicts(
            String templateId,
            String versionId) {
        return mapper.findPublicationConflicts(templateId, versionId).stream()
                .map(row -> new PolicyConflict(
                        "POLICY_SCOPE_CONFLICT",
                        row.conflictingVersionId(),
                        ScopeType.valueOf(row.scopeType()),
                        row.scopeResourceId(),
                        row.priority(),
                        row.effectiveFrom(),
                        row.effectiveTo()))
                .toList();
    }

    @Override
    public void insertPublication(PublicationRecord record) {
        mapper.insertPublication(record);
    }

    @Override
    public void insertRollback(RollbackRecord record) {
        mapper.insertRollback(record);
    }

    @Override
    public void insertAudit(AuditRecord record) {
        mapper.insertAudit(record);
    }

    private long nextOpaqueVersion(long expectedVersion, Instant now) {
        return Math.max(Math.addExact(expectedVersion, 1), now.toEpochMilli());
    }

    private PolicyTemplate toTemplate(PolicyRows.TemplateRow row) {
        return new PolicyTemplate(
                row.templateId(),
                row.templateCode(),
                row.name(),
                row.description(),
                readArray(row.fieldDefinitionsJson(), FieldDefinition[].class),
                TemplateStatus.valueOf(row.status()),
                row.latestVersionNumber(),
                row.rowVersion(),
                row.createdBy(),
                row.createdAt(),
                row.updatedBy(),
                row.updatedAt());
    }

    private PolicyVersion toVersion(PolicyRows.VersionRow row) {
        ValidationResult validation = row.validationJson() == null
                ? new ValidationResult(false, List.of(), null)
                : readObject(row.validationJson(), ValidationResult.class);
        return new PolicyVersion(
                row.versionId(),
                row.templateId(),
                row.versionNumber(),
                VersionStatus.valueOf(row.status()),
                readArray(row.parametersJson(), ParameterValue[].class),
                row.effectiveFrom(),
                row.effectiveTo(),
                row.changeReason(),
                validation,
                row.snapshotJson(),
                row.snapshotDigest(),
                row.rollbackOfVersionId(),
                row.rowVersion(),
                row.createdBy(),
                row.createdAt(),
                row.publishedAt(),
                row.updatedBy(),
                row.updatedAt(),
                mapper.findScopes(row.versionId()).stream().map(this::toScope).toList());
    }

    private ScopeBinding toScope(PolicyRows.ScopeRow row) {
        return new ScopeBinding(
                row.bindingId(),
                row.versionId(),
                ScopeType.valueOf(row.scopeType()),
                row.scopeResourceId(),
                row.priority(),
                row.effectiveFrom(),
                row.effectiveTo(),
                row.rowVersion());
    }

    private PolicyRows.TemplateRow toRow(PolicyTemplate template) {
        return new PolicyRows.TemplateRow(
                template.templateId(),
                template.code(),
                template.name(),
                template.description(),
                writeJson(template.fieldDefinitions()),
                template.status().name(),
                template.latestVersionNumber(),
                template.rowVersion(),
                template.createdBy(),
                template.createdAt(),
                template.updatedBy(),
                template.updatedAt());
    }

    private PolicyRows.VersionRow toRow(PolicyVersion version) {
        return new PolicyRows.VersionRow(
                version.versionId(),
                version.templateId(),
                version.versionNumber(),
                version.status().name(),
                writeJson(version.parameters()),
                version.effectiveFrom(),
                version.effectiveTo(),
                version.changeReason(),
                writeJson(version.validation()),
                version.snapshotJson(),
                version.snapshotDigest(),
                version.rollbackOfVersionId(),
                version.rowVersion(),
                version.createdBy(),
                version.createdAt(),
                version.publishedAt(),
                version.updatedBy(),
                version.updatedAt());
    }

    private PolicyRows.ScopeRow toRow(ScopeBinding scope) {
        return new PolicyRows.ScopeRow(
                scope.bindingId(),
                scope.versionId(),
                scope.scopeType().name(),
                scope.scopeResourceId(),
                scope.priority(),
                scope.effectiveFrom(),
                scope.effectiveTo(),
                scope.rowVersion());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("policy value must be JSON serializable", exception);
        }
    }

    private <T> T readObject(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("stored policy JSON is invalid", exception);
        }
    }

    private <T> List<T> readArray(String json, Class<T[]> type) {
        return Arrays.asList(readObject(json, type));
    }
}
