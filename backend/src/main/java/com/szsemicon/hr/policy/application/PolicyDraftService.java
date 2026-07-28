package com.szsemicon.hr.policy.application;

import com.szsemicon.hr.policy.application.PolicyCommands.CreateDraft;
import com.szsemicon.hr.policy.application.PolicyCommands.ReplaceScopes;
import com.szsemicon.hr.policy.application.PolicyCommands.RequestContext;
import com.szsemicon.hr.policy.application.PolicyCommands.ScopeInput;
import com.szsemicon.hr.policy.application.PolicyCommands.UpdateDraft;
import com.szsemicon.hr.policy.domain.PolicyModels.Page;
import com.szsemicon.hr.policy.domain.PolicyModels.ParameterValue;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyTemplate;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyVersion;
import com.szsemicon.hr.policy.domain.PolicyModels.ScopeBinding;
import com.szsemicon.hr.policy.domain.PolicyModels.TemplateStatus;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationIssue;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationResult;
import com.szsemicon.hr.policy.domain.PolicyModels.VersionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class PolicyDraftService {

    private final PolicyRepository repository;
    private final PolicyValidationService validationService;
    private final PolicyRecordSupport support;
    private final Clock clock;

    PolicyDraftService(
            PolicyRepository repository,
            PolicyValidationService validationService,
            PolicyRecordSupport support,
            Clock clock) {
        this.repository = repository;
        this.validationService = validationService;
        this.support = support;
        this.clock = clock;
    }

    Page<PolicyVersion> list(String templateId, int page, int size) {
        support.validatePage(page, size);
        support.requireTemplate(templateId);
        return repository.findVersions(templateId, page, size);
    }

    PolicyVersion get(String templateId, String versionId) {
        return support.requireVersion(templateId, versionId);
    }

    PolicyVersion create(
            String templateId,
            CreateDraft command,
            RequestContext context) {
        support.requireReason(command.changeReason());
        PolicyTemplate template = support.requireTemplate(templateId);
        if (template.status() != TemplateStatus.ACTIVE) {
            throw new PolicyExceptions.Conflict("POLICY_TEMPLATE_INACTIVE");
        }
        if (command.effectiveFrom() == null
                || (command.effectiveTo() != null
                && command.effectiveTo().isBefore(command.effectiveFrom()))) {
            throw support.invalid(
                    "INVALID_EFFECTIVE_PERIOD", "effectiveTo", "生效期间无效");
        }

        List<ParameterValue> parameters = List.of();
        List<ScopeBinding> sourceScopes = List.of();
        if (command.basedOnVersionId() != null) {
            PolicyVersion base =
                    support.requireVersion(templateId, command.basedOnVersionId());
            parameters = base.parameters();
            sourceScopes = base.scopeBindings();
        }

        Instant now = clock.instant();
        String versionId = UUID.randomUUID().toString();
        List<ScopeBinding> scopes = support.copyScopes(
                sourceScopes,
                versionId,
                command.effectiveFrom(),
                command.effectiveTo());
        PolicyVersion draft = new PolicyVersion(
                versionId,
                templateId,
                repository.nextVersionNumber(templateId),
                VersionStatus.DRAFT,
                parameters,
                command.effectiveFrom(),
                command.effectiveTo(),
                command.changeReason().trim(),
                new ValidationResult(false, List.of(), null),
                null,
                null,
                null,
                0,
                context.actorId(),
                now,
                null,
                context.actorId(),
                now,
                scopes);
        repository.insertVersion(draft);
        support.audit(
                context,
                now,
                "POLICY_DRAFT_CREATED",
                "POLICY_VERSION",
                versionId,
                command.changeReason(),
                Integer.toString(draft.versionNumber()),
                null,
                support.digestJson(draft));
        return support.requireVersion(templateId, versionId);
    }

    PolicyVersion update(
            String templateId,
            String versionId,
            UpdateDraft command,
            RequestContext context) {
        support.requireReason(command.changeReason());
        PolicyTemplate template = support.requireTemplate(templateId);
        PolicyVersion current = support.requireEditableVersion(templateId, versionId);
        Instant now = clock.instant();
        PolicyVersion candidate = new PolicyVersion(
                current.versionId(),
                current.templateId(),
                current.versionNumber(),
                VersionStatus.DRAFT,
                command.parameters(),
                command.effectiveFrom(),
                command.effectiveTo(),
                command.changeReason().trim(),
                new ValidationResult(false, List.of(), null),
                null,
                null,
                current.rollbackOfVersionId(),
                current.rowVersion(),
                current.createdBy(),
                current.createdAt(),
                null,
                context.actorId(),
                now,
                current.scopeBindings());
        ValidationResult validation =
                validationService.validateVersion(template, candidate, now);
        if (!validation.valid()) {
            throw new PolicyExceptions.ValidationFailed(validation.issues());
        }
        if (repository.updateDraft(
                        candidate,
                        command.expectedVersion(),
                        context.actorId(),
                        now)
                != 1) {
            throw new PolicyExceptions.StaleVersion();
        }
        support.audit(
                context,
                now,
                "POLICY_DRAFT_UPDATED",
                "POLICY_VERSION",
                versionId,
                command.changeReason(),
                Long.toString(current.rowVersion()),
                support.digestJson(current),
                support.digestJson(candidate));
        return support.requireVersion(templateId, versionId);
    }

    PolicyVersion replaceScopes(
            String templateId,
            String versionId,
            ReplaceScopes command,
            RequestContext context) {
        PolicyTemplate template = support.requireTemplate(templateId);
        PolicyVersion current = support.requireEditableVersion(templateId, versionId);
        List<ScopeBinding> scopes = new ArrayList<>();
        for (ScopeInput input : command.bindings()) {
            scopes.add(new ScopeBinding(
                    UUID.randomUUID().toString(),
                    versionId,
                    input.scopeType(),
                    input.scopeResourceId(),
                    input.priority(),
                    input.effectiveFrom(),
                    input.effectiveTo(),
                    0));
        }
        PolicyVersion candidate = support.withScopes(current, scopes);
        List<ValidationIssue> scopeIssues = validationService
                .validateVersion(template, candidate, clock.instant())
                .issues()
                .stream()
                .filter(issue -> issue.field().startsWith("scopeBindings")
                        || issue.field().startsWith("effective"))
                .toList();
        if (!scopeIssues.isEmpty()) {
            throw new PolicyExceptions.ValidationFailed(scopeIssues);
        }
        Instant now = clock.instant();
        if (repository.touchDraft(
                        templateId,
                        versionId,
                        command.expectedVersion(),
                        context.actorId(),
                        now)
                != 1) {
            throw new PolicyExceptions.StaleVersion();
        }
        repository.replaceScopes(versionId, scopes);
        support.audit(
                context,
                now,
                "POLICY_SCOPE_REPLACED",
                "POLICY_VERSION",
                versionId,
                "SCOPE_BINDINGS_UPDATED",
                Long.toString(current.rowVersion()),
                support.digestJson(current.scopeBindings()),
                support.digestJson(scopes));
        return support.requireVersion(templateId, versionId);
    }
}
