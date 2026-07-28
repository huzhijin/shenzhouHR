package com.szsemicon.hr.policy.application;

import com.szsemicon.hr.policy.domain.PolicyModels.FieldDefinition;
import com.szsemicon.hr.policy.domain.PolicyModels.ParameterValue;
import com.szsemicon.hr.policy.domain.PolicyModels.ScopeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class PolicyCommands {

    private PolicyCommands() {
    }

    public record RequestContext(String actorId, String requestId) {
    }

    public record CreateTemplate(
            String code,
            String name,
            String description,
            List<FieldDefinition> fieldDefinitions) {
    }

    public record CreateDraft(
            String basedOnVersionId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String changeReason) {
    }

    public record UpdateDraft(
            List<ParameterValue> parameters,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String changeReason,
            long expectedVersion) {
    }

    public record ScopeInput(
            ScopeType scopeType,
            String scopeResourceId,
            int priority,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    public record ReplaceScopes(List<ScopeInput> bindings, long expectedVersion) {
    }

    public record Simulate(String sampleName, Map<String, Object> inputs) {
    }

    public record Publish(String reason, long expectedVersion) {
    }

    public record Rollback(String targetVersionId, String reason, long expectedVersion) {
    }
}
