package com.szsemicon.hr.policy.application;

import com.szsemicon.hr.policy.application.PolicyCommands.Publish;
import com.szsemicon.hr.policy.application.PolicyCommands.RequestContext;
import com.szsemicon.hr.policy.application.PolicyCommands.Rollback;
import com.szsemicon.hr.policy.application.PolicyRepository.PublicationRecord;
import com.szsemicon.hr.policy.application.PolicyRepository.RollbackRecord;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyConflict;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyTemplate;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyVersion;
import com.szsemicon.hr.policy.domain.PolicyModels.ScopeBinding;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationResult;
import com.szsemicon.hr.policy.domain.PolicyModels.VersionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class PolicyLifecycleService {

    private final PolicyRepository repository;
    private final PolicyValidationService validationService;
    private final PolicyFrozenPeriodProtection frozenPeriodProtection;
    private final PolicyRecordSupport support;
    private final Clock clock;

    PolicyLifecycleService(
            PolicyRepository repository,
            PolicyValidationService validationService,
            PolicyFrozenPeriodProtection frozenPeriodProtection,
            PolicyRecordSupport support,
            Clock clock) {
        this.repository = repository;
        this.validationService = validationService;
        this.frozenPeriodProtection = frozenPeriodProtection;
        this.support = support;
        this.clock = clock;
    }

    PolicyVersion publish(
            String templateId,
            String versionId,
            Publish command,
            RequestContext context) {
        support.requireReason(command.reason());
        PolicyTemplate template = support.requireTemplate(templateId);
        PolicyVersion current = support.requireEditableVersion(templateId, versionId);
        if (current.rowVersion() != command.expectedVersion()) {
            throw new PolicyExceptions.StaleVersion();
        }
        ValidationResult validation =
                validationService.validateVersion(template, current, clock.instant());
        if (!validation.valid()) {
            throw new PolicyExceptions.ValidationFailed(validation.issues());
        }
        List<PolicyConflict> conflicts =
                repository.findPublicationConflicts(templateId, versionId);
        if (!conflicts.isEmpty()) {
            throw new PolicyExceptions.Conflict("POLICY_SCOPE_CONFLICT");
        }
        PolicyFrozenPeriodProtection.Decision decision =
                frozenPeriodProtection.assessPublication(current);
        if (!decision.publicationAllowed()) {
            throw new PolicyExceptions.Conflict(decision.reasonCode());
        }

        Instant now = clock.instant();
        String snapshotJson = support.snapshot(template, current);
        String snapshotDigest = support.sha256(snapshotJson);
        if (repository.transitionVersion(
                        templateId,
                        versionId,
                        current.status(),
                        VersionStatus.PUBLISHED,
                        command.reason().trim(),
                        snapshotJson,
                        snapshotDigest,
                        command.expectedVersion(),
                        context.actorId(),
                        now,
                        now)
                != 1) {
            throw new PolicyExceptions.StaleVersion();
        }
        repository.insertPublication(new PublicationRecord(
                UUID.randomUUID().toString(),
                templateId,
                versionId,
                "PUBLISH",
                command.reason().trim(),
                context.actorId(),
                context.requestId(),
                "SUCCESS",
                now,
                snapshotDigest));
        support.audit(
                context,
                now,
                "POLICY_PUBLISHED",
                "POLICY_VERSION",
                versionId,
                command.reason(),
                Integer.toString(current.versionNumber()),
                current.snapshotDigest(),
                snapshotDigest);
        return support.requireVersion(templateId, versionId);
    }

    PolicyVersion deactivate(
            String templateId,
            String versionId,
            String reason,
            RequestContext context) {
        support.requireReason(reason);
        PolicyVersion current = support.requireVersion(templateId, versionId);
        if (current.status() != VersionStatus.PUBLISHED) {
            throw new PolicyExceptions.Conflict("POLICY_VERSION_NOT_PUBLISHED");
        }
        Instant now = clock.instant();
        if (repository.transitionVersion(
                        templateId,
                        versionId,
                        VersionStatus.PUBLISHED,
                        VersionStatus.INACTIVE,
                        reason.trim(),
                        current.snapshotJson(),
                        current.snapshotDigest(),
                        current.rowVersion(),
                        context.actorId(),
                        now,
                        current.publishedAt())
                != 1) {
            throw new PolicyExceptions.StaleVersion();
        }
        repository.insertPublication(new PublicationRecord(
                UUID.randomUUID().toString(),
                templateId,
                versionId,
                "DEACTIVATE",
                reason.trim(),
                context.actorId(),
                context.requestId(),
                "SUCCESS",
                now,
                current.snapshotDigest()));
        support.audit(
                context,
                now,
                "POLICY_DEACTIVATED",
                "POLICY_VERSION",
                versionId,
                reason,
                Integer.toString(current.versionNumber()),
                current.snapshotDigest(),
                current.snapshotDigest());
        return support.requireVersion(templateId, versionId);
    }

    PolicyVersion rollback(
            String templateId,
            String sourceVersionId,
            Rollback command,
            RequestContext context) {
        support.requireReason(command.reason());
        PolicyTemplate template = support.requireTemplate(templateId);
        PolicyVersion source = support.requireVersion(templateId, sourceVersionId);
        PolicyVersion target =
                support.requireVersion(templateId, command.targetVersionId());
        if (source.status() != VersionStatus.INACTIVE
                && source.status() != VersionStatus.PUBLISHED) {
            throw new PolicyExceptions.Conflict("POLICY_ROLLBACK_SOURCE_INVALID");
        }
        if (source.rowVersion() != command.expectedVersion()) {
            throw new PolicyExceptions.StaleVersion();
        }
        Instant now = clock.instant();
        if (repository.claimVersion(
                        templateId,
                        sourceVersionId,
                        source.status(),
                        command.expectedVersion(),
                        context.actorId(),
                        now)
                != 1) {
            throw new PolicyExceptions.StaleVersion();
        }

        String createdVersionId = UUID.randomUUID().toString();
        List<ScopeBinding> createdScopes = support.copyScopes(
                source.scopeBindings(),
                createdVersionId,
                source.effectiveFrom(),
                source.effectiveTo());
        PolicyVersion draft = new PolicyVersion(
                createdVersionId,
                templateId,
                repository.nextVersionNumber(templateId),
                VersionStatus.DRAFT,
                target.parameters(),
                source.effectiveFrom(),
                source.effectiveTo(),
                command.reason().trim(),
                new ValidationResult(true, List.of(), now),
                null,
                null,
                target.versionId(),
                0,
                context.actorId(),
                now,
                null,
                context.actorId(),
                now,
                createdScopes);
        ValidationResult validation =
                validationService.validateVersion(template, draft, now);
        if (!validation.valid()) {
            throw new PolicyExceptions.ValidationFailed(validation.issues());
        }
        repository.insertVersion(draft);
        if (!repository.findPublicationConflicts(templateId, createdVersionId).isEmpty()) {
            throw new PolicyExceptions.Conflict("POLICY_SCOPE_CONFLICT");
        }
        PolicyFrozenPeriodProtection.Decision decision =
                frozenPeriodProtection.assessPublication(draft);
        if (!decision.publicationAllowed()) {
            throw new PolicyExceptions.Conflict(decision.reasonCode());
        }
        String snapshotJson = support.snapshot(template, draft);
        String snapshotDigest = support.sha256(snapshotJson);
        if (repository.transitionVersion(
                        templateId,
                        createdVersionId,
                        VersionStatus.DRAFT,
                        VersionStatus.PUBLISHED,
                        command.reason().trim(),
                        snapshotJson,
                        snapshotDigest,
                        0,
                        context.actorId(),
                        now,
                        now)
                != 1) {
            throw new PolicyExceptions.StaleVersion();
        }
        repository.insertPublication(new PublicationRecord(
                UUID.randomUUID().toString(),
                templateId,
                createdVersionId,
                "ROLLBACK",
                command.reason().trim(),
                context.actorId(),
                context.requestId(),
                "SUCCESS",
                now,
                snapshotDigest));
        repository.insertRollback(new RollbackRecord(
                UUID.randomUUID().toString(),
                templateId,
                sourceVersionId,
                target.versionId(),
                createdVersionId,
                command.reason().trim(),
                context.actorId(),
                context.requestId(),
                "SUCCESS",
                now));
        support.audit(
                context,
                now,
                "POLICY_ROLLED_BACK",
                "POLICY_VERSION",
                createdVersionId,
                command.reason(),
                Integer.toString(draft.versionNumber()),
                target.snapshotDigest(),
                snapshotDigest);
        return support.requireVersion(templateId, createdVersionId);
    }
}
