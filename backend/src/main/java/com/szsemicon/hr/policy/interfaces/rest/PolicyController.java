package com.szsemicon.hr.policy.interfaces.rest;

import com.szsemicon.hr.policy.application.PolicyApplicationService;
import com.szsemicon.hr.policy.application.PolicyCommands.CreateDraft;
import com.szsemicon.hr.policy.application.PolicyCommands.CreateTemplate;
import com.szsemicon.hr.policy.application.PolicyCommands.Publish;
import com.szsemicon.hr.policy.application.PolicyCommands.ReplaceScopes;
import com.szsemicon.hr.policy.application.PolicyCommands.RequestContext;
import com.szsemicon.hr.policy.application.PolicyCommands.Rollback;
import com.szsemicon.hr.policy.application.PolicyCommands.ScopeInput;
import com.szsemicon.hr.policy.application.PolicyCommands.Simulate;
import com.szsemicon.hr.policy.application.PolicyCommands.UpdateDraft;
import com.szsemicon.hr.policy.application.PolicyExceptions;
import com.szsemicon.hr.policy.domain.PolicyModels.FieldDefinition;
import com.szsemicon.hr.policy.domain.PolicyModels.ParameterValue;
import com.szsemicon.hr.policy.domain.PolicyModels.ScopeType;
import com.szsemicon.hr.policy.domain.PolicyModels.TemplateStatus;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationIssue;
import com.szsemicon.hr.policy.domain.PolicyModels.ValueType;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.ConflictResult;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.CreateDraftRequest;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.CreateTemplateRequest;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.ImpactPreview;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.PublishRequest;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.ReasonRequest;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.ReplaceScopesRequest;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.RollbackRequest;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.SimulationRequest;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.SimulationResult;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.TemplateDetail;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.TemplatePage;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.UpdateDraftRequest;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.ValidationResultDto;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.VersionDetail;
import com.szsemicon.hr.policy.interfaces.rest.PolicyDtos.VersionPage;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policy-templates")
public class PolicyController {

    private final PolicyApplicationService service;
    private final CurrentPrincipalProvider principalProvider;

    public PolicyController(
            PolicyApplicationService service,
            CurrentPrincipalProvider principalProvider) {
        this.service = service;
        this.principalProvider = principalProvider;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('POLICY:READ')")
    public ResponseEntity<TemplatePage> listTemplates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) TemplateStatus status) {
        return ok(PolicyDtos.templatePage(
                service.listTemplates(query, status, sort, page, size)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POLICY:CREATE')")
    public ResponseEntity<TemplateDetail> createTemplate(
            @RequestBody CreateTemplateRequest request,
            HttpServletRequest servletRequest) {
        List<FieldDefinition> fields = safe(request.fieldDefinitions()).stream()
                .map(field -> new FieldDefinition(
                        field.key(),
                        field.label(),
                        valueType(field.valueType()),
                        field.required(),
                        field.enumValues(),
                        field.minimum(),
                        field.maximum()))
                .toList();
        TemplateDetail response = PolicyDtos.templateDetail(service.createTemplate(
                new CreateTemplate(
                        request.code(),
                        request.name(),
                        request.description(),
                        fields),
                context(servletRequest)));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping("/{templateId}")
    @PreAuthorize("hasAuthority('POLICY:READ')")
    public ResponseEntity<TemplateDetail> getTemplate(@PathVariable String templateId) {
        return ok(PolicyDtos.templateDetail(service.getTemplate(templateId)));
    }

    @GetMapping("/{templateId}/versions")
    @PreAuthorize("hasAuthority('POLICY:READ')")
    public ResponseEntity<VersionPage> listVersions(
            @PathVariable String templateId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(PolicyDtos.versionPage(service.listVersions(templateId, page, size)));
    }

    @PostMapping("/{templateId}/versions")
    @PreAuthorize("hasAuthority('POLICY:CREATE')")
    public ResponseEntity<VersionDetail> createDraft(
            @PathVariable String templateId,
            @RequestBody CreateDraftRequest request,
            HttpServletRequest servletRequest) {
        VersionDetail response = PolicyDtos.versionDetail(service.createDraft(
                templateId,
                new CreateDraft(
                        request.basedOnVersionId(),
                        request.effectiveFrom(),
                        request.effectiveTo(),
                        request.changeReason()),
                context(servletRequest)));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping("/{templateId}/versions/{versionId}")
    @PreAuthorize("hasAuthority('POLICY:READ')")
    public ResponseEntity<VersionDetail> getVersion(
            @PathVariable String templateId,
            @PathVariable String versionId) {
        return ok(PolicyDtos.versionDetail(service.getVersion(templateId, versionId)));
    }

    @PatchMapping("/{templateId}/versions/{versionId}")
    @PreAuthorize("hasAuthority('POLICY:EDIT')")
    public ResponseEntity<VersionDetail> updateDraft(
            @PathVariable String templateId,
            @PathVariable String versionId,
            @RequestBody UpdateDraftRequest request,
            HttpServletRequest servletRequest) {
        List<ParameterValue> parameters = safe(request.parameters()).stream()
                .map(parameter -> new ParameterValue(parameter.key(), parameter.value()))
                .toList();
        return ok(PolicyDtos.versionDetail(service.updateDraft(
                templateId,
                versionId,
                new UpdateDraft(
                        parameters,
                        request.effectiveFrom(),
                        request.effectiveTo(),
                        request.changeReason(),
                        request.expectedVersion()),
                context(servletRequest))));
    }

    @PutMapping("/{templateId}/versions/{versionId}/scope-bindings")
    @PreAuthorize("hasAuthority('POLICY:EDIT')")
    public ResponseEntity<VersionDetail> replaceScopes(
            @PathVariable String templateId,
            @PathVariable String versionId,
            @RequestBody ReplaceScopesRequest request,
            HttpServletRequest servletRequest) {
        List<ScopeInput> bindings = safe(request.bindings()).stream()
                .map(binding -> new ScopeInput(
                        scopeType(binding.scopeType()),
                        binding.scopeResourceId(),
                        binding.priority(),
                        binding.effectiveFrom(),
                        binding.effectiveTo()))
                .toList();
        return ok(PolicyDtos.versionDetail(service.replaceScopes(
                templateId,
                versionId,
                new ReplaceScopes(bindings, request.expectedVersion()),
                context(servletRequest))));
    }

    @PostMapping("/{templateId}/versions/{versionId}/validate")
    @PreAuthorize("hasAuthority('POLICY:VALIDATE')")
    public ResponseEntity<ValidationResultDto> validateVersion(
            @PathVariable String templateId,
            @PathVariable String versionId,
            HttpServletRequest servletRequest) {
        return ok(PolicyDtos.validation(
                service.validateVersion(templateId, versionId, context(servletRequest))));
    }

    @PostMapping("/{templateId}/versions/{versionId}/conflicts")
    @PreAuthorize("hasAuthority('POLICY:VALIDATE')")
    public ResponseEntity<ConflictResult> conflicts(
            @PathVariable String templateId,
            @PathVariable String versionId,
            HttpServletRequest servletRequest) {
        return ok(PolicyDtos.conflicts(
                service.findConflicts(templateId, versionId, context(servletRequest))));
    }

    @PostMapping("/{templateId}/versions/{versionId}/simulate")
    @PreAuthorize("hasAuthority('POLICY:SIMULATE')")
    public ResponseEntity<SimulationResult> simulate(
            @PathVariable String templateId,
            @PathVariable String versionId,
            @RequestBody SimulationRequest request,
            HttpServletRequest servletRequest) {
        var result = service.simulate(
                templateId,
                versionId,
                new Simulate(request.sampleName(), request.inputs()),
                context(servletRequest));
        return ok(new SimulationResult(
                result.matched(),
                result.resolvedParameters().stream()
                        .map(parameter -> new PolicyDtos.ParameterValueDto(
                                parameter.key(), parameter.value()))
                        .toList(),
                result.explanation()));
    }

    @PostMapping("/{templateId}/versions/{versionId}/impact-preview")
    @PreAuthorize("hasAuthority('POLICY:SIMULATE')")
    public ResponseEntity<ImpactPreview> impactPreview(
            @PathVariable String templateId,
            @PathVariable String versionId,
            HttpServletRequest servletRequest) {
        var result =
                service.previewImpact(templateId, versionId, context(servletRequest));
        return ok(new ImpactPreview(
                result.scopeCount(),
                result.affectedObjectCount(),
                result.effectiveFrom(),
                result.effectiveTo(),
                result.frozenPeriodProtected(),
                result.warnings()));
    }

    @PostMapping("/{templateId}/versions/{versionId}/publish")
    @PreAuthorize("hasAuthority('POLICY:PUBLISH')")
    public ResponseEntity<VersionDetail> publish(
            @PathVariable String templateId,
            @PathVariable String versionId,
            @RequestBody PublishRequest request,
            HttpServletRequest servletRequest) {
        return ok(PolicyDtos.versionDetail(service.publish(
                templateId,
                versionId,
                new Publish(request.reason(), request.expectedVersion()),
                context(servletRequest))));
    }

    @PostMapping("/{templateId}/versions/{versionId}/deactivate")
    @PreAuthorize("hasAuthority('POLICY:DEACTIVATE')")
    public ResponseEntity<VersionDetail> deactivate(
            @PathVariable String templateId,
            @PathVariable String versionId,
            @RequestBody ReasonRequest request,
            HttpServletRequest servletRequest) {
        return ok(PolicyDtos.versionDetail(service.deactivate(
                templateId,
                versionId,
                request.reason(),
                context(servletRequest))));
    }

    @PostMapping("/{templateId}/versions/{versionId}/rollback")
    @PreAuthorize("hasAuthority('POLICY:ROLLBACK')")
    public ResponseEntity<VersionDetail> rollback(
            @PathVariable String templateId,
            @PathVariable String versionId,
            @RequestBody RollbackRequest request,
            HttpServletRequest servletRequest) {
        VersionDetail response = PolicyDtos.versionDetail(service.rollback(
                templateId,
                versionId,
                new Rollback(
                        request.targetVersionId(),
                        request.reason(),
                        request.expectedVersion()),
                context(servletRequest)));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(response);
    }

    private RequestContext context(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        String requestId = value == null ? "unavailable" : value.toString();
        return new RequestContext(principalProvider.currentPrincipalId(), requestId);
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private ValueType valueType(String value) {
        try {
            return ValueType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalidEnum("fieldDefinitions.valueType");
        }
    }

    private ScopeType scopeType(String value) {
        try {
            return ScopeType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalidEnum("bindings.scopeType");
        }
    }

    private PolicyExceptions.ValidationFailed invalidEnum(String field) {
        return new PolicyExceptions.ValidationFailed(List.of(
                new ValidationIssue("INVALID_ENUM_VALUE", field, "枚举值不在允许范围内")));
    }
}
