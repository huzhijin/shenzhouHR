package com.szsemicon.hr.policy.application;

import com.szsemicon.hr.policy.application.PolicyCommands.CreateDraft;
import com.szsemicon.hr.policy.application.PolicyCommands.CreateTemplate;
import com.szsemicon.hr.policy.application.PolicyCommands.Publish;
import com.szsemicon.hr.policy.application.PolicyCommands.ReplaceScopes;
import com.szsemicon.hr.policy.application.PolicyCommands.RequestContext;
import com.szsemicon.hr.policy.application.PolicyCommands.Rollback;
import com.szsemicon.hr.policy.application.PolicyCommands.Simulate;
import com.szsemicon.hr.policy.application.PolicyCommands.UpdateDraft;
import com.szsemicon.hr.policy.domain.PolicyModels.Page;
import com.szsemicon.hr.policy.domain.PolicyModels.ParameterValue;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyConflict;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyTemplate;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyVersion;
import com.szsemicon.hr.policy.domain.PolicyModels.TemplateStatus;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationResult;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundary and stable API for the policy REST adapter.
 *
 * <p>Use-case behavior is intentionally delegated to narrowly scoped services so template
 * catalog, draft editing, evaluation, and lifecycle transitions remain independently
 * reviewable.
 */
@Service
public class PolicyApplicationService {

    private final PolicyTemplateService templateService;
    private final PolicyDraftService draftService;
    private final PolicyEvaluationService evaluationService;
    private final PolicyLifecycleService lifecycleService;

    public PolicyApplicationService(
            PolicyTemplateService templateService,
            PolicyDraftService draftService,
            PolicyEvaluationService evaluationService,
            PolicyLifecycleService lifecycleService) {
        this.templateService = templateService;
        this.draftService = draftService;
        this.evaluationService = evaluationService;
        this.lifecycleService = lifecycleService;
    }

    @Transactional(readOnly = true)
    public Page<PolicyTemplate> listTemplates(
            String query,
            TemplateStatus status,
            String sort,
            int page,
            int size) {
        return templateService.list(query, status, sort, page, size);
    }

    @Transactional(readOnly = true)
    public PolicyTemplate getTemplate(String templateId) {
        return templateService.get(templateId);
    }

    @Transactional
    public PolicyTemplate createTemplate(CreateTemplate command, RequestContext context) {
        return templateService.create(command, context);
    }

    @Transactional(readOnly = true)
    public Page<PolicyVersion> listVersions(String templateId, int page, int size) {
        return draftService.list(templateId, page, size);
    }

    @Transactional(readOnly = true)
    public PolicyVersion getVersion(String templateId, String versionId) {
        return draftService.get(templateId, versionId);
    }

    @Transactional
    public PolicyVersion createDraft(
            String templateId,
            CreateDraft command,
            RequestContext context) {
        return draftService.create(templateId, command, context);
    }

    @Transactional
    public PolicyVersion updateDraft(
            String templateId,
            String versionId,
            UpdateDraft command,
            RequestContext context) {
        return draftService.update(templateId, versionId, command, context);
    }

    @Transactional
    public PolicyVersion replaceScopes(
            String templateId,
            String versionId,
            ReplaceScopes command,
            RequestContext context) {
        return draftService.replaceScopes(templateId, versionId, command, context);
    }

    @Transactional
    public ValidationResult validateVersion(
            String templateId,
            String versionId,
            RequestContext context) {
        return evaluationService.validate(templateId, versionId, context);
    }

    @Transactional
    public List<PolicyConflict> findConflicts(
            String templateId,
            String versionId,
            RequestContext context) {
        return evaluationService.findConflicts(templateId, versionId, context);
    }

    @Transactional
    public SimulationResult simulate(
            String templateId,
            String versionId,
            Simulate command,
            RequestContext context) {
        var result = evaluationService.simulate(templateId, versionId, command, context);
        return new SimulationResult(
                result.matched(), result.resolvedParameters(), result.explanation());
    }

    @Transactional
    public ImpactPreview previewImpact(
            String templateId,
            String versionId,
            RequestContext context) {
        var result = evaluationService.previewImpact(templateId, versionId, context);
        return new ImpactPreview(
                result.scopeCount(),
                result.affectedObjectCount(),
                result.effectiveFrom(),
                result.effectiveTo(),
                result.frozenPeriodProtected(),
                result.warnings());
    }

    @Transactional
    public PolicyVersion publish(
            String templateId,
            String versionId,
            Publish command,
            RequestContext context) {
        return lifecycleService.publish(templateId, versionId, command, context);
    }

    @Transactional
    public PolicyVersion deactivate(
            String templateId,
            String versionId,
            String reason,
            RequestContext context) {
        return lifecycleService.deactivate(templateId, versionId, reason, context);
    }

    @Transactional
    public PolicyVersion rollback(
            String templateId,
            String sourceVersionId,
            Rollback command,
            RequestContext context) {
        return lifecycleService.rollback(templateId, sourceVersionId, command, context);
    }

    public record SimulationResult(
            boolean matched,
            List<ParameterValue> resolvedParameters,
            List<String> explanation) {

        public SimulationResult {
            resolvedParameters = List.copyOf(resolvedParameters);
            explanation = List.copyOf(explanation);
        }
    }

    public record ImpactPreview(
            int scopeCount,
            int affectedObjectCount,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            boolean frozenPeriodProtected,
            List<String> warnings) {

        public ImpactPreview {
            warnings = List.copyOf(warnings);
        }
    }
}
