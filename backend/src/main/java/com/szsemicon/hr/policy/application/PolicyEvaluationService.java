package com.szsemicon.hr.policy.application;

import com.szsemicon.hr.policy.application.PolicyCommands.RequestContext;
import com.szsemicon.hr.policy.application.PolicyCommands.Simulate;
import com.szsemicon.hr.policy.domain.PolicyModels.ParameterValue;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyConflict;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyTemplate;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyVersion;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationResult;
import com.szsemicon.hr.policy.domain.PolicyModels.VersionStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
class PolicyEvaluationService {

    private final PolicyRepository repository;
    private final PolicyValidationService validationService;
    private final PolicyFrozenPeriodProtection frozenPeriodProtection;
    private final PolicyRecordSupport support;
    private final Clock clock;

    PolicyEvaluationService(
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

    ValidationResult validate(
            String templateId,
            String versionId,
            RequestContext context) {
        PolicyTemplate template = support.requireTemplate(templateId);
        PolicyVersion current = support.requireEditableVersion(templateId, versionId);
        var now = clock.instant();
        ValidationResult result = validationService.validateVersion(template, current, now);
        VersionStatus resultingStatus =
                result.valid() ? VersionStatus.VALIDATED : VersionStatus.DRAFT;
        if (repository.saveValidation(
                        templateId,
                        versionId,
                        result,
                        resultingStatus,
                        current.rowVersion(),
                        context.actorId(),
                        now)
                != 1) {
            throw new PolicyExceptions.StaleVersion();
        }
        support.audit(
                context,
                now,
                "POLICY_VALIDATED",
                "POLICY_VERSION",
                versionId,
                result.valid() ? "VALID" : "INVALID",
                Integer.toString(current.versionNumber()),
                null,
                support.digestJson(result));
        return result;
    }

    List<PolicyConflict> findConflicts(
            String templateId,
            String versionId,
            RequestContext context) {
        support.requireVersion(templateId, versionId);
        List<PolicyConflict> conflicts =
                repository.findPublicationConflicts(templateId, versionId);
        support.audit(
                context,
                clock.instant(),
                "POLICY_CONFLICT_CHECKED",
                "POLICY_VERSION",
                versionId,
                conflicts.isEmpty() ? "NO_CONFLICT" : "CONFLICT",
                null,
                null,
                support.digestJson(conflicts));
        return conflicts;
    }

    SimulationResult simulate(
            String templateId,
            String versionId,
            Simulate command,
            RequestContext context) {
        validationService.validateSimulationInput(command.sampleName(), command.inputs());
        PolicyTemplate template = support.requireTemplate(templateId);
        PolicyVersion version = support.requireVersion(templateId, versionId);
        ValidationResult validation =
                validationService.validateVersion(template, version, clock.instant());
        if (!validation.valid()) {
            throw new PolicyExceptions.ValidationFailed(validation.issues());
        }
        boolean matched = !version.scopeBindings().isEmpty();
        List<String> explanation = List.of(
                "样例仅解析受控字段和枚举，不执行脚本或表达式。",
                matched ? "样例命中已声明作用范围。" : "样例未命中作用范围。");
        support.audit(
                context,
                clock.instant(),
                "POLICY_SIMULATED",
                "POLICY_VERSION",
                versionId,
                command.sampleName(),
                Integer.toString(version.versionNumber()),
                null,
                support.digestJson(command.inputs()));
        return new SimulationResult(matched, version.parameters(), explanation);
    }

    ImpactPreview previewImpact(
            String templateId,
            String versionId,
            RequestContext context) {
        PolicyVersion version = support.requireVersion(templateId, versionId);
        PolicyFrozenPeriodProtection.Decision decision =
                frozenPeriodProtection.assessPublication(version);
        List<String> warnings = decision.publicationAllowed()
                ? List.of("WAVE-1 仅提供影响范围底座；人员影响数量将在后续领域接入后计算。")
                : List.of("生效期间可能已冻结；未接入期间判定器时发布默认拒绝。");
        ImpactPreview preview = new ImpactPreview(
                version.scopeBindings().size(),
                0,
                version.effectiveFrom(),
                version.effectiveTo(),
                decision.protectionActive(),
                warnings);
        support.audit(
                context,
                clock.instant(),
                "POLICY_IMPACT_PREVIEWED",
                "POLICY_VERSION",
                versionId,
                "IMPACT_PREVIEW",
                Integer.toString(version.versionNumber()),
                null,
                support.digestJson(preview));
        return preview;
    }

    record SimulationResult(
            boolean matched,
            List<ParameterValue> resolvedParameters,
            List<String> explanation) {

        SimulationResult {
            resolvedParameters = List.copyOf(resolvedParameters);
            explanation = List.copyOf(explanation);
        }
    }

    record ImpactPreview(
            int scopeCount,
            int affectedObjectCount,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            boolean frozenPeriodProtected,
            List<String> warnings) {

        ImpactPreview {
            warnings = List.copyOf(warnings);
        }
    }
}
