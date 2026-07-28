package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class Wave1PolicyLifecycleIntegrationTest extends Wave1IntegrationTestSupport {

    @Test
    void draftRejectsUnknownFieldsAndArbitraryScriptPayloads() throws Exception {
        String originalParameters = jdbc.queryForObject(
                "SELECT parameters_json FROM policy_version WHERE version_id = ?",
                String.class,
                POLICY_DRAFT_VERSION);

        mockMvc.perform(patch(
                        "/api/v1/policy-templates/{templateId}/versions/{versionId}",
                        POLICY_TEMPLATE,
                        POLICY_DRAFT_VERSION)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "parameters":[{
                                     "key":"script",
                                     "value":"return runtime.execute(input)"
                                   }],
                                   "effectiveFrom":"2027-01-01",
                                   "effectiveTo":null,
                                   "changeReason":"WAVE-1 合成非法脚本",
                                   "expectedVersion":0
                                 }
                                 """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());

        assertThat(jdbc.queryForObject(
                "SELECT parameters_json FROM policy_version WHERE version_id = ?",
                String.class,
                POLICY_DRAFT_VERSION)).isEqualTo(originalParameters);
    }

    @Test
    void overlappingScopePeriodAndPriorityBlockPublication() throws Exception {
        long publicationBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_publication_record",
                Long.class);

        mockMvc.perform(post(
                        "/api/v1/policy-templates/{templateId}/versions/{versionId}/conflicts",
                        POLICY_TEMPLATE,
                        POLICY_CONFLICT_VERSION)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasConflicts").value(true))
                .andExpect(jsonPath("$.conflicts[0].conflictingVersionId")
                        .value(POLICY_PUBLISHED_VERSION));

        mockMvc.perform(post(
                        "/api/v1/policy-templates/{templateId}/versions/{versionId}/publish",
                        POLICY_TEMPLATE,
                        POLICY_CONFLICT_VERSION)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"reason":"WAVE-1 合成冲突发布","expectedVersion":0}
                                 """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").isNotEmpty());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM policy_version WHERE version_id = ?",
                String.class,
                POLICY_CONFLICT_VERSION)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_publication_record",
                Long.class)).isEqualTo(publicationBefore);
    }

    @Test
    void validationSimulationImpactPublishDeactivateAndRollbackAppendHistory() throws Exception {
        long versionCountBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_version WHERE template_id = ?",
                Long.class,
                POLICY_TEMPLATE);
        long auditBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event",
                Long.class);

        mockMvc.perform(post(
                        "/api/v1/policy-templates/{templateId}/versions/{versionId}/validate",
                        POLICY_TEMPLATE,
                        POLICY_DRAFT_VERSION)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.issues").isEmpty());

        mockMvc.perform(post(
                        "/api/v1/policy-templates/{templateId}/versions/{versionId}/simulate",
                        POLICY_TEMPLATE,
                        POLICY_DRAFT_VERSION)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "sampleName":"WAVE-1 合成审批样例",
                                   "inputs":{"approvalMode":"MANUAL"}
                                 }
                                 """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.resolvedParameters").isArray())
                .andExpect(jsonPath("$.explanation").isArray());

        mockMvc.perform(post(
                        "/api/v1/policy-templates/{templateId}/versions/{versionId}/impact-preview",
                        POLICY_TEMPLATE,
                        POLICY_DRAFT_VERSION)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeCount").value(1))
                .andExpect(jsonPath("$.frozenPeriodProtected").value(true));

        long versionAfterValidation = jdbc.queryForObject(
                "SELECT row_version FROM policy_version WHERE version_id = ?",
                Long.class,
                POLICY_DRAFT_VERSION);
        MvcResult published = mockMvc.perform(post(
                        "/api/v1/policy-templates/{templateId}/versions/{versionId}/publish",
                        POLICY_TEMPLATE,
                        POLICY_DRAFT_VERSION)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "reason":"WAVE-1 合成规则发布",
                                   "expectedVersion":%d
                                 }
                                 """.formatted(versionAfterValidation)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.snapshotDigest").isNotEmpty())
                .andReturn();

        MvcResult inactive = mockMvc.perform(post(
                        "/api/v1/policy-templates/{templateId}/versions/{versionId}/deactivate",
                        POLICY_TEMPLATE,
                        POLICY_DRAFT_VERSION)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"WAVE-1 合成规则停用\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andReturn();

        long inactiveRowVersion = JsonPath.read(
                inactive.getResponse().getContentAsString(),
                "$.rowVersion");
        MvcResult rollback = mockMvc.perform(post(
                        "/api/v1/policy-templates/{templateId}/versions/{versionId}/rollback",
                        POLICY_TEMPLATE,
                        POLICY_DRAFT_VERSION)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "targetVersionId":"%s",
                                   "reason":"WAVE-1 合成回滚",
                                   "expectedVersion":%d
                                 }
                                 """.formatted(
                                POLICY_PUBLISHED_VERSION,
                                inactiveRowVersion)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.rollbackOfVersionId")
                        .value(POLICY_PUBLISHED_VERSION))
                .andReturn();

        String rollbackVersionId = JsonPath.read(
                rollback.getResponse().getContentAsString(),
                "$.versionId");
        assertThat(rollbackVersionId).isNotEqualTo(POLICY_PUBLISHED_VERSION);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_version WHERE template_id = ?",
                Long.class,
                POLICY_TEMPLATE)).isEqualTo(versionCountBefore + 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_publication_record WHERE template_id = ?",
                Long.class,
                POLICY_TEMPLATE)).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_rollback_record WHERE template_id = ?",
                Long.class,
                POLICY_TEMPLATE)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event",
                Long.class)).isGreaterThanOrEqualTo(auditBefore + 4);

        assertThat(JsonPath.<String>read(
                published.getResponse().getContentAsString(),
                "$.snapshotDigest")).hasSize(64);
    }

    @Test
    void staleExpectedVersionReturnsConflictWithoutChangingTheDraft() throws Exception {
        String before = jdbc.queryForObject(
                "SELECT parameters_json FROM policy_version WHERE version_id = ?",
                String.class,
                POLICY_DRAFT_VERSION);

        mockMvc.perform(patch(
                        "/api/v1/policy-templates/{templateId}/versions/{versionId}",
                        POLICY_TEMPLATE,
                        POLICY_DRAFT_VERSION)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "parameters":[{
                                     "key":"approvalMode",
                                     "value":"AUTOMATIC"
                                   }],
                                   "effectiveFrom":"2027-01-01",
                                   "effectiveTo":null,
                                   "changeReason":"WAVE-1 合成陈旧写入",
                                   "expectedVersion":999999
                                 }
                                 """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        assertThat(jdbc.queryForObject(
                "SELECT parameters_json FROM policy_version WHERE version_id = ?",
                String.class,
                POLICY_DRAFT_VERSION)).isEqualTo(before);
    }

    @Test
    void frozenPeriodGuardDefaultsToDenyWhenNoDecisionProviderCanConfirmSafety()
            throws Exception {
        jdbc.update(
                """
                UPDATE policy_scope_binding
                SET scope_resource_id = '30000000-0000-0000-0000-000000000099'
                WHERE version_id = ?
                """,
                POLICY_CONFLICT_VERSION);

        mockMvc.perform(post(
                        "/api/v1/policy-templates/{templateId}/versions/{versionId}/publish",
                        POLICY_TEMPLATE,
                        POLICY_CONFLICT_VERSION)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "reason":"WAVE-1 合成冻结保护检查",
                                   "expectedVersion":0
                                 }
                                 """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("FROZEN_PERIOD_PROTECTION_UNAVAILABLE"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM policy_version WHERE version_id = ?",
                String.class,
                POLICY_CONFLICT_VERSION)).isEqualTo("DRAFT");
    }

    @Test
    void policyWriteRequiresCapabilityBeforeAnyMutationOrAuditSuccess() throws Exception {
        long publications = jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_publication_record",
                Long.class);

        mockMvc.perform(post(
                        "/api/v1/policy-templates/{templateId}/versions/{versionId}/publish",
                        POLICY_TEMPLATE,
                        POLICY_DRAFT_VERSION)
                        .with(user(LIMITED_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"reason":"WAVE-1 合成越权发布","expectedVersion":0}
                                 """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_publication_record",
                Long.class)).isEqualTo(publications);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM policy_version WHERE version_id = ?",
                String.class,
                POLICY_DRAFT_VERSION)).isEqualTo("DRAFT");
    }
}
