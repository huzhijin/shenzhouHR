package com.szsemicon.hr.policy.application;

import com.szsemicon.hr.policy.domain.PolicyModels.Page;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyConflict;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyTemplate;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyVersion;
import com.szsemicon.hr.policy.domain.PolicyModels.ScopeBinding;
import com.szsemicon.hr.policy.domain.PolicyModels.TemplateStatus;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationResult;
import com.szsemicon.hr.policy.domain.PolicyModels.VersionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PolicyRepository {

    Page<PolicyTemplate> findTemplates(
            String query,
            TemplateStatus status,
            String sort,
            int page,
            int size);

    Optional<PolicyTemplate> findTemplate(String templateId);

    boolean templateCodeExists(String code);

    void insertTemplate(PolicyTemplate template);

    Page<PolicyVersion> findVersions(String templateId, int page, int size);

    Optional<PolicyVersion> findVersion(String templateId, String versionId);

    int nextVersionNumber(String templateId);

    void insertVersion(PolicyVersion version);

    int updateDraft(
            PolicyVersion version,
            long expectedVersion,
            String actorId,
            Instant now);

    int saveValidation(
            String templateId,
            String versionId,
            ValidationResult validation,
            VersionStatus status,
            long expectedVersion,
            String actorId,
            Instant now);

    int transitionVersion(
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
            Instant publishedAt);

    int touchDraft(
            String templateId,
            String versionId,
            long expectedVersion,
            String actorId,
            Instant now);

    int claimVersion(
            String templateId,
            String versionId,
            VersionStatus expectedStatus,
            long expectedVersion,
            String actorId,
            Instant now);

    void replaceScopes(String versionId, List<ScopeBinding> bindings);

    List<PolicyConflict> findPublicationConflicts(String templateId, String versionId);

    void insertPublication(PublicationRecord record);

    void insertRollback(RollbackRecord record);

    void insertAudit(AuditRecord record);

    record PublicationRecord(
            String publicationId,
            String templateId,
            String versionId,
            String action,
            String reason,
            String actorId,
            String requestId,
            String result,
            Instant occurredAt,
            String snapshotDigest) {
    }

    record RollbackRecord(
            String rollbackId,
            String templateId,
            String sourceVersionId,
            String targetVersionId,
            String createdVersionId,
            String reason,
            String actorId,
            String requestId,
            String result,
            Instant occurredAt) {
    }

    record AuditRecord(
            String eventId,
            Instant occurredAt,
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String reason,
            String policyVersion,
            String beforeDigest,
            String afterDigest,
            String requestId,
            String eventHash) {
    }
}
