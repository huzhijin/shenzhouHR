package com.szsemicon.hr.policy.application;

import com.szsemicon.hr.policy.application.PolicyCommands.RequestContext;
import com.szsemicon.hr.policy.application.PolicyRepository.AuditRecord;
import com.szsemicon.hr.policy.domain.PolicyModels.FieldDefinition;
import com.szsemicon.hr.policy.domain.PolicyModels.ParameterValue;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyTemplate;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyVersion;
import com.szsemicon.hr.policy.domain.PolicyModels.ScopeBinding;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationIssue;
import com.szsemicon.hr.policy.domain.PolicyModels.VersionStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Maintains the persistence-facing invariants shared by policy use cases.
 */
@Service
class PolicyRecordSupport {

    private static final int MAX_PAGE_SIZE = 100;

    private final PolicyRepository repository;
    private final ObjectMapper objectMapper;

    PolicyRecordSupport(PolicyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    PolicyTemplate requireTemplate(String templateId) {
        return repository.findTemplate(templateId).orElseThrow(PolicyExceptions.NotFound::new);
    }

    PolicyVersion requireVersion(String templateId, String versionId) {
        return repository.findVersion(templateId, versionId)
                .orElseThrow(PolicyExceptions.NotFound::new);
    }

    PolicyVersion requireEditableVersion(String templateId, String versionId) {
        PolicyVersion version = requireVersion(templateId, versionId);
        if (version.status() != VersionStatus.DRAFT
                && version.status() != VersionStatus.VALIDATED) {
            throw new PolicyExceptions.Conflict("PUBLISHED_POLICY_IMMUTABLE");
        }
        return version;
    }

    void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("INVALID_PAGE", "page", "分页参数超出允许范围");
        }
    }

    void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 2 || reason.length() > 500) {
            throw invalid("INVALID_REASON", "reason", "原因长度必须在 2 到 500 字符");
        }
    }

    PolicyExceptions.ValidationFailed invalid(String code, String field, String message) {
        return new PolicyExceptions.ValidationFailed(
                List.of(new ValidationIssue(code, field, message)));
    }

    List<ScopeBinding> copyScopes(
            List<ScopeBinding> source,
            String versionId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        return source.stream()
                .map(scope -> new ScopeBinding(
                        UUID.randomUUID().toString(),
                        versionId,
                        scope.scopeType(),
                        scope.scopeResourceId(),
                        scope.priority(),
                        effectiveFrom,
                        effectiveTo,
                        0))
                .toList();
    }

    PolicyVersion withScopes(PolicyVersion current, List<ScopeBinding> scopes) {
        return new PolicyVersion(
                current.versionId(),
                current.templateId(),
                current.versionNumber(),
                current.status(),
                current.parameters(),
                current.effectiveFrom(),
                current.effectiveTo(),
                current.changeReason(),
                current.validation(),
                current.snapshotJson(),
                current.snapshotDigest(),
                current.rollbackOfVersionId(),
                current.rowVersion(),
                current.createdBy(),
                current.createdAt(),
                current.publishedAt(),
                current.updatedBy(),
                current.updatedAt(),
                scopes);
    }

    String snapshot(PolicyTemplate template, PolicyVersion version) {
        return writeJson(new PolicySnapshot(
                template.templateId(),
                template.code(),
                template.fieldDefinitions(),
                version.versionId(),
                version.versionNumber(),
                version.parameters(),
                version.scopeBindings(),
                version.effectiveFrom(),
                version.effectiveTo(),
                version.changeReason(),
                version.rollbackOfVersionId()));
    }

    String digestJson(Object value) {
        return sha256(writeJson(value));
    }

    String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    void audit(
            RequestContext context,
            Instant now,
            String action,
            String resourceType,
            String resourceId,
            String reason,
            String policyVersion,
            String beforeDigest,
            String afterDigest) {
        String eventId = UUID.randomUUID().toString();
        String requestId = context.requestId() == null || context.requestId().isBlank()
                ? eventId
                : context.requestId();
        repository.insertAudit(new AuditRecord(
                eventId,
                now,
                context.actorId(),
                action,
                resourceType,
                resourceId,
                "SUCCESS",
                reason,
                policyVersion,
                beforeDigest,
                afterDigest,
                requestId,
                sha256(eventId + "|" + requestId + "|" + action + "|" + resourceId)));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("policy snapshot must be JSON serializable", exception);
        }
    }

    private record PolicySnapshot(
            String templateId,
            String templateCode,
            List<FieldDefinition> fieldDefinitions,
            String versionId,
            int versionNumber,
            List<ParameterValue> parameters,
            List<ScopeBinding> scopeBindings,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String changeReason,
            String rollbackOfVersionId) {
    }
}
