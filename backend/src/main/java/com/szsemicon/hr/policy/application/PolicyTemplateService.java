package com.szsemicon.hr.policy.application;

import com.szsemicon.hr.policy.application.PolicyCommands.CreateTemplate;
import com.szsemicon.hr.policy.application.PolicyCommands.RequestContext;
import com.szsemicon.hr.policy.domain.PolicyModels.Page;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyTemplate;
import com.szsemicon.hr.policy.domain.PolicyModels.TemplateStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class PolicyTemplateService {

    private static final Set<String> TEMPLATE_SORTS =
            Set.of("createdAt", "updatedAt", "templateCode");

    private final PolicyRepository repository;
    private final PolicyValidationService validationService;
    private final PolicyRecordSupport support;
    private final Clock clock;

    PolicyTemplateService(
            PolicyRepository repository,
            PolicyValidationService validationService,
            PolicyRecordSupport support,
            Clock clock) {
        this.repository = repository;
        this.validationService = validationService;
        this.support = support;
        this.clock = clock;
    }

    Page<PolicyTemplate> list(
            String query,
            TemplateStatus status,
            String sort,
            int page,
            int size) {
        support.validatePage(page, size);
        if (!TEMPLATE_SORTS.contains(sort)) {
            throw support.invalid("INVALID_SORT", "sort", "不支持的排序字段");
        }
        String normalizedQuery = query == null || query.isBlank() ? null : query.trim();
        return repository.findTemplates(normalizedQuery, status, sort, page, size);
    }

    PolicyTemplate get(String templateId) {
        return support.requireTemplate(templateId);
    }

    PolicyTemplate create(CreateTemplate command, RequestContext context) {
        var issues = validationService.validateTemplate(
                command.code(),
                command.name(),
                command.description(),
                command.fieldDefinitions());
        if (!issues.isEmpty()) {
            throw new PolicyExceptions.ValidationFailed(issues);
        }
        if (repository.templateCodeExists(command.code())) {
            throw new PolicyExceptions.Conflict("POLICY_TEMPLATE_CODE_CONFLICT");
        }

        Instant now = clock.instant();
        String templateId = UUID.randomUUID().toString();
        PolicyTemplate template = new PolicyTemplate(
                templateId,
                command.code(),
                command.name().trim(),
                command.description(),
                command.fieldDefinitions(),
                TemplateStatus.ACTIVE,
                0,
                0,
                context.actorId(),
                now,
                context.actorId(),
                now);
        repository.insertTemplate(template);
        support.audit(
                context,
                now,
                "POLICY_TEMPLATE_CREATED",
                "POLICY_TEMPLATE",
                templateId,
                "CREATED",
                null,
                null,
                support.digestJson(template));
        return support.requireTemplate(templateId);
    }
}
