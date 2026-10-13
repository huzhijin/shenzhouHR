package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.szsemicon.hr.attendance.application.AttendancePolicyLifecycleRepository;
import com.szsemicon.hr.attendance.application.AttendancePolicyRepository;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Import(Wave3PolicyLifecycleSemanticIntegrationTest.FixedClockConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Wave3PolicyLifecycleSemanticIntegrationTest
        extends Wave1IntegrationTestSupport {

    private static final String COMPANY =
            "30000000-0000-0000-0000-000000000001";
    private static final String TEMPLATE_ID =
            "a6000000-0000-0000-0000-000000000001";
    private static final String SCOPE_ID =
            "a6100000-0000-0000-0000-000000000001";
    private static final String BASELINE_VERSION_ID =
            "a6200000-0000-0000-0000-000000000001";
    private static final String BASELINE_EVENT_ID =
            "a6300000-0000-0000-0000-000000000001";
    private static final String BASELINE_DIGEST =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    private static final String OUTSIDE_SCOPE_ID =
            "a6100000-0000-0000-0000-000000000002";
    private static final String OUTSIDE_VERSION_ID =
            "a6200000-0000-0000-0000-000000000002";
    private static final String OUTSIDE_EVENT_ID =
            "a6300000-0000-0000-0000-000000000002";

    @Autowired
    private MutableClock testClock;

    @Autowired
    private AttendancePolicyLifecycleRepository lifecycleRepository;

    @Autowired
    private AttendancePolicyRepository policyRepository;

    @BeforeEach
    void seedAttendancePolicyLifecycle() {
        testClock.setInstant(Instant.parse("2026-07-27T00:00:00Z"));
        Timestamp recordedAt =
                Timestamp.from(Instant.parse("2026-07-20T00:00:00Z"));
        jdbc.update(
                """
                INSERT INTO attendance_policy_template (
                    policy_template_id, template_code, name, description,
                    field_definitions_json, created_by, created_at
                ) VALUES (
                    ?, 'LATE_GRACE', '生命周期迟到宽限',
                    'WAVE-3 lifecycle semantic fixture', '[]', ?, ?
                )
                """,
                TEMPLATE_ID, ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO attendance_policy_scope (
                    scope_id, policy_template_id, company_id,
                    row_version, created_by, created_at
                ) VALUES (?, ?, ?, 0, ?, ?)
                """,
                SCOPE_ID, TEMPLATE_ID, COMPANY,
                ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO attendance_policy_scoped_version (
                    scoped_version_id, scope_id, version_number,
                    parameters_json, effective_from, effective_to,
                    validation_json, snapshot_json, snapshot_digest,
                    rollback_of_scoped_version_id, row_version,
                    change_reason, created_by, created_at
                ) VALUES (
                    ?, ?, 1, '{"enabled":true,"graceMinutes":15}',
                    DATE '1970-01-01', NULL,
                    '{"valid":true,"issues":[]}', '{}', ?, NULL, 0,
                    'baseline publication', ?, ?
                )
                """,
                BASELINE_VERSION_ID, SCOPE_ID, BASELINE_DIGEST,
                ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO attendance_policy_lifecycle_event (
                    lifecycle_event_id, scope_id, scoped_version_id,
                    event_sequence, predecessor_event_id, action,
                    business_effective_from, reason, actor_id,
                    request_id, recorded_at
                ) VALUES (
                    ?, ?, ?, 1, NULL, 'PUBLISHED', DATE '1970-01-01',
                    'baseline publication', ?, 'baseline-publication', ?
                )
                """,
                BASELINE_EVENT_ID, SCOPE_ID, BASELINE_VERSION_ID,
                ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO attendance_policy_scope (
                    scope_id, policy_template_id, company_id,
                    row_version, created_by, created_at
                ) VALUES (
                    ?, ?, '30000000-0000-0000-0000-000000000002',
                    0, ?, ?
                )
                """,
                OUTSIDE_SCOPE_ID, TEMPLATE_ID, ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO attendance_policy_scoped_version (
                    scoped_version_id, scope_id, version_number,
                    parameters_json, effective_from, effective_to,
                    validation_json, snapshot_json, snapshot_digest,
                    rollback_of_scoped_version_id, row_version,
                    change_reason, created_by, created_at
                ) VALUES (
                    ?, ?, 1, '{"enabled":true,"graceMinutes":15}',
                    DATE '1970-01-01', NULL,
                    '{"valid":true,"issues":[]}', '{}', ?, NULL, 0,
                    'outside-scope publication', ?, ?
                )
                """,
                OUTSIDE_VERSION_ID, OUTSIDE_SCOPE_ID, BASELINE_DIGEST,
                ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO attendance_policy_lifecycle_event (
                    lifecycle_event_id, scope_id, scoped_version_id,
                    event_sequence, predecessor_event_id, action,
                    business_effective_from, reason, actor_id,
                    request_id, recorded_at
                ) VALUES (
                    ?, ?, ?, 1, NULL, 'PUBLISHED', DATE '1970-01-01',
                    'outside-scope publication', ?,
                    'outside-scope-publication', ?
                )
                """,
                OUTSIDE_EVENT_ID, OUTSIDE_SCOPE_ID, OUTSIDE_VERSION_ID,
                ADMIN_PRINCIPAL, recordedAt);
    }

    @Test
    void lifecycle_is_append_only_locked_replayable_and_one_winner()
            throws Exception {
        mockMvc.perform(post("/api/v1/attendance-setup/policy-simulations")
                        .with(user(LIMITED_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "employeeId":"b0000000-0000-0000-0000-000000000001",
                                  "businessDate":"2026-07-27",
                                  "correctionAsOf":"2026-07-27T00:00:00Z",
                                  "punches":[]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get(
                        "/api/v1/attendance-setup/policy-lifecycle/versions/{versionId}/context",
                        BASELINE_VERSION_ID)
                        .with(user(LIMITED_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.scopedVersionId")
                        .value(BASELINE_VERSION_ID))
                .andExpect(jsonPath("$.templateId").value(TEMPLATE_ID))
                .andExpect(jsonPath("$.companyId").value(COMPANY))
                .andExpect(jsonPath("$.policyKind").value("LATE_GRACE"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        mockMvc.perform(get(
                        "/api/v1/attendance-setup/policy-lifecycle/versions/{versionId}/context",
                        OUTSIDE_VERSION_ID)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));
        mockMvc.perform(get(
                        "/api/v1/attendance-setup/policy-lifecycle/versions/{versionId}/context",
                        "00000000-0000-0000-0000-000000000000")
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        String draftReason = "policy draft create";
        String draftBody = """
                {
                  "basedOnVersionId":"%s",
                  "effectiveFrom":"2026-07-28",
                  "reason":"%s"
                }
                """.formatted(BASELINE_VERSION_ID, draftReason);
        MvcResult draft = mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions",
                        TEMPLATE_ID)
                        .queryParam("companyId", COMPANY),
                "policy-draft-exact-replay",
                draftReason,
                draftBody)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string(
                        "Idempotency-Key", "policy-draft-exact-replay"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        assertThat(idempotencyState("policy-draft-exact-replay"))
                .isEqualTo("COMPLETED_SUCCESS");
        assertThat(idempotencyHeaders("policy-draft-exact-replay"))
                .contains(
                        "\"ETag\":\"\\\"0\\\"\"",
                        "\"Idempotency-Key\":\"policy-draft-exact-replay\"");
        String originalDraftId = value(draft, "$.scopedVersionId");
        MvcResult draftReplay = mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions",
                        TEMPLATE_ID)
                        .queryParam("companyId", COMPANY),
                "policy-draft-exact-replay",
                draftReason,
                draftBody)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().string(
                        "Idempotency-Key", "policy-draft-exact-replay"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andReturn();
        assertExactResponse(draft, draftReplay);

        String changedReason = "policy draft changed reason";
        mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions",
                        TEMPLATE_ID)
                        .queryParam("companyId", COMPANY),
                "policy-draft-exact-replay",
                changedReason,
                draftBody.replace(draftReason, changedReason))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(versionCount()).isEqualTo(2);
        assertThat(successAuditCount("ATTENDANCE_POLICY_DRAFT_CREATED"))
                .isEqualTo(1);

        String updateReason = "policy immutable draft edit";
        String updateBody = """
                {
                  "parameters":[
                    {"key":"enabled","value":true},
                    {"key":"graceMinutes","value":15}
                  ],
                  "effectiveFrom":"2026-07-28",
                  "reason":"%s"
                }
                """.formatted(updateReason);
        MvcResult updated = mutate(
                patch(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}",
                        TEMPLATE_ID,
                        originalDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"0\""),
                "policy-draft-edit-winner",
                updateReason,
                updateBody)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andReturn();
        String editedDraftId = value(updated, "$.scopedVersionId");
        assertThat(editedDraftId).isNotEqualTo(originalDraftId);
        MvcResult updatedReplay = mutate(
                patch(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}",
                        TEMPLATE_ID,
                        originalDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"0\""),
                "policy-draft-edit-winner",
                updateReason,
                updateBody)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andReturn();
        assertExactResponse(updated, updatedReplay);
        mutate(
                patch(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}",
                        TEMPLATE_ID,
                        originalDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"0\""),
                "policy-draft-edit-loser",
                updateReason,
                updateBody)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(versionCount()).isEqualTo(3);

        String validateReason = "policy validate";
        MvcResult validated = mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/validate",
                        TEMPLATE_ID,
                        editedDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"0\""),
                "policy-validate-exact-replay",
                validateReason,
                null)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.valid").value(true))
                .andReturn();
        MvcResult validatedReplay = mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/validate",
                        TEMPLATE_ID,
                        editedDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"0\""),
                "policy-validate-exact-replay",
                validateReason,
                null)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        assertExactResponse(validated, validatedReplay);

        String publishReason = "policy future publication";
        String publishBody = """
                {"reason":"%s"}
                """.formatted(publishReason);
        mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/publish",
                        TEMPLATE_ID,
                        editedDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"0\""),
                "policy-publish-stale",
                publishReason,
                publishBody)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        MvcResult published = mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/publish",
                        TEMPLATE_ID,
                        editedDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"1\""),
                "policy-publish-exact-replay",
                publishReason,
                publishBody)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn();
        MvcResult publishedReplay = mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/publish",
                        TEMPLATE_ID,
                        editedDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"1\""),
                "policy-publish-exact-replay",
                publishReason,
                publishBody)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        assertExactResponse(published, publishedReplay);
        assertThat(lifecycleRepository.findPublishedAt(
                SCOPE_ID, LocalDate.parse("2026-07-28")))
                .containsExactly(editedDraftId);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_lifecycle_event
                WHERE scoped_version_id = ?
                  AND action = 'DEACTIVATE_SCHEDULED'
                  AND business_effective_from = DATE '2026-07-28'
                """,
                Long.class,
                BASELINE_VERSION_ID)).isEqualTo(1);

        long versionsBeforeHistoricalSourceRollback = versionCount();
        long timelineBeforeHistoricalSourceRollback = lifecycleCount();
        long rollbackAuditsBeforeHistoricalSource =
                successAuditCount("ATTENDANCE_POLICY_ROLLED_BACK");
        long historicalSourceFailuresBefore =
                failureAuditCount("POLICY_ROLLBACK_SOURCE_NOT_CURRENT");
        String historicalSourceReason =
                "policy historical source rollback rejected";
        mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/rollback",
                        TEMPLATE_ID,
                        BASELINE_VERSION_ID)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"1\""),
                "policy-historical-source-rollback",
                historicalSourceReason,
                """
                {
                  "targetVersionId":"%s",
                  "effectiveFrom":"2026-07-29",
                  "reason":"%s"
                }
                """.formatted(editedDraftId, historicalSourceReason))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("POLICY_ROLLBACK_SOURCE_NOT_CURRENT"));
        assertThat(versionCount())
                .isEqualTo(versionsBeforeHistoricalSourceRollback);
        assertThat(lifecycleCount())
                .isEqualTo(timelineBeforeHistoricalSourceRollback);
        assertThat(successAuditCount("ATTENDANCE_POLICY_ROLLED_BACK"))
                .isEqualTo(rollbackAuditsBeforeHistoricalSource);
        assertThat(idempotencyCount("policy-historical-source-rollback"))
                .isZero();
        assertThat(failureAuditCount("POLICY_ROLLBACK_SOURCE_NOT_CURRENT"))
                .isEqualTo(historicalSourceFailuresBefore + 1);

        mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/validate",
                        TEMPLATE_ID,
                        editedDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"1\""),
                "policy-validate-published-stale",
                validateReason,
                null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        VersionContent publishedContent = content(editedDraftId);
        long lifecycleBeforeFrozenFailure = lifecycleCount();
        testClock.setInstant(Instant.parse("2026-07-29T00:00:00Z"));
        String deactivateReason = "policy frozen retry";
        String deactivateBody = """
                {
                  "effectiveFrom":"2026-07-29",
                  "reason":"%s"
                }
                """.formatted(deactivateReason);
        mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/deactivate",
                        TEMPLATE_ID,
                        editedDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"2\""),
                "policy-deactivate-failed-then-retry",
                deactivateReason,
                deactivateBody)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("FROZEN_PERIOD_PROTECTION_UNAVAILABLE"));
        assertThat(lifecycleCount()).isEqualTo(lifecycleBeforeFrozenFailure);
        assertThat(idempotencyCount("policy-deactivate-failed-then-retry"))
                .isZero();
        assertThat(failureAuditCount("FROZEN_PERIOD_PROTECTION_UNAVAILABLE"))
                .isEqualTo(1);

        testClock.setInstant(Instant.parse("2026-07-27T00:00:00Z"));
        MvcResult deactivated = mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/deactivate",
                        TEMPLATE_ID,
                        editedDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"2\""),
                "policy-deactivate-failed-then-retry",
                deactivateReason,
                deactivateBody)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
                .andExpect(jsonPath("$.deactivationEffectiveFrom")
                        .value("2026-07-29"))
                .andReturn();
        assertThat(idempotencyCount("policy-deactivate-failed-then-retry"))
                .isEqualTo(1);
        MvcResult deactivatedReplay = mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/deactivate",
                        TEMPLATE_ID,
                        editedDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"2\""),
                "policy-deactivate-failed-then-retry",
                deactivateReason,
                deactivateBody)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
                .andReturn();
        assertExactResponse(deactivated, deactivatedReplay);
        assertThat(content(editedDraftId)).isEqualTo(publishedContent);
        LocalDate policyPeriodStart = LocalDate.parse("2026-07-28");
        LocalDate lifecycleExclusiveEnd = LocalDate.parse("2026-07-29");
        LocalDate beyondLifecycleEnd = LocalDate.parse("2026-07-30");
        assertThat(policyRepository.publishedVersionMatchesKind(
                COMPANY,
                editedDraftId,
                PolicyKind.LATE_GRACE,
                policyPeriodStart,
                lifecycleExclusiveEnd)).isTrue();
        assertThat(policyRepository.findPublishedVersionIdsByKind(
                COMPANY,
                PolicyKind.LATE_GRACE,
                policyPeriodStart,
                lifecycleExclusiveEnd)).containsExactly(editedDraftId);
        assertThat(policyRepository.publishedVersionMatchesKind(
                COMPANY,
                editedDraftId,
                PolicyKind.LATE_GRACE,
                policyPeriodStart,
                beyondLifecycleEnd)).isFalse();
        assertThat(policyRepository.findPublishedVersionIdsByKind(
                COMPANY,
                PolicyKind.LATE_GRACE,
                policyPeriodStart,
                beyondLifecycleEnd)).isEmpty();
        assertThat(policyRepository.publishedVersionMatchesKind(
                COMPANY,
                editedDraftId,
                PolicyKind.LATE_GRACE,
                policyPeriodStart,
                null)).isFalse();
        assertThat(policyRepository.findPublishedVersionIdsByKind(
                COMPANY,
                PolicyKind.LATE_GRACE,
                policyPeriodStart,
                null)).isEmpty();

        String rollbackReason = "policy rollback publication";
        String rollbackBody = """
                {
                  "targetVersionId":"%s",
                  "effectiveFrom":"2026-07-30",
                  "reason":"%s"
                }
                """.formatted(BASELINE_VERSION_ID, rollbackReason);
        MvcResult rollback = mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/rollback",
                        TEMPLATE_ID,
                        editedDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"3\""),
                "policy-rollback-exact-replay",
                rollbackReason,
                rollbackBody)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.rollbackOfScopedVersionId")
                        .value(BASELINE_VERSION_ID))
                .andReturn();
        String rollbackVersionId = value(rollback, "$.scopedVersionId");
        MvcResult rollbackReplay = mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/rollback",
                        TEMPLATE_ID,
                        editedDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"3\""),
                "policy-rollback-exact-replay",
                rollbackReason,
                rollbackBody)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andReturn();
        assertExactResponse(rollback, rollbackReplay);
        assertThat(content(editedDraftId)).isEqualTo(publishedContent);
        assertThat(lifecycleRepository.findPublishedAt(
                SCOPE_ID, LocalDate.parse("2026-07-30")))
                .containsExactly(rollbackVersionId);
        assertThat(policyRepository.publishedVersionMatchesKind(
                COMPANY,
                rollbackVersionId,
                PolicyKind.LATE_GRACE,
                LocalDate.parse("2026-07-30"),
                null)).isTrue();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_lifecycle_event
                WHERE scoped_version_id = ? AND action = 'ROLLED_BACK'
                """,
                Long.class,
                editedDraftId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_lifecycle_event
                WHERE scoped_version_id = ? AND action = 'PUBLISHED'
                """,
                Long.class,
                rollbackVersionId)).isEqualTo(1);

        long versionsBeforeRace = versionCount();
        String concurrentReason = "policy rollback race";
        String concurrentBody = """
                {
                  "targetVersionId":"%s",
                  "effectiveFrom":"2026-07-31",
                  "reason":"%s"
                }
                """.formatted(BASELINE_VERSION_ID, concurrentReason);
        List<MvcResult> race = concurrent(
                () -> rollback(
                        rollbackVersionId,
                        0,
                        "policy-rollback-race-first",
                        concurrentReason,
                        concurrentBody),
                () -> rollback(
                        rollbackVersionId,
                        0,
                        "policy-rollback-race-second",
                        concurrentReason,
                        concurrentBody));
        assertThat(race.stream()
                .map(result -> result.getResponse().getStatus())
                .sorted()
                .toList()).containsExactly(201, 409);
        assertThat(versionCount()).isEqualTo(versionsBeforeRace + 1);
        MvcResult raceWinner = race.stream()
                .filter(result -> result.getResponse().getStatus() == 201)
                .findFirst()
                .orElseThrow();
        String raceWinnerVersionId = value(raceWinner, "$.scopedVersionId");
        assertThat(lifecycleRepository.findPublishedAt(
                SCOPE_ID, LocalDate.parse("2026-07-31")))
                .containsExactly(raceWinnerVersionId);
        assertThat(policyRepository.publishedVersionMatchesKind(
                COMPANY,
                raceWinnerVersionId,
                PolicyKind.LATE_GRACE,
                LocalDate.parse("2026-07-31"),
                null)).isTrue();
        assertThat(failureAuditCount("VERSION_CONFLICT"))
                .isGreaterThanOrEqualTo(3);
        assertThat(successAuditCount("ATTENDANCE_POLICY_ROLLED_BACK"))
                .isEqualTo(2);

        String backfillDraftReason = "policy backfill draft";
        MvcResult backfillDraft = mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions",
                        TEMPLATE_ID)
                        .queryParam("companyId", COMPANY),
                "policy-backfill-draft",
                backfillDraftReason,
                """
                {
                  "basedOnVersionId":"%s",
                  "effectiveFrom":"2026-07-29",
                  "reason":"%s"
                }
                """.formatted(BASELINE_VERSION_ID, backfillDraftReason))
                .andExpect(status().isCreated())
                .andReturn();
        String backfillDraftId = value(
                backfillDraft, "$.scopedVersionId");
        String backfillValidationReason = "policy backfill validation";
        mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/validate",
                        TEMPLATE_ID,
                        backfillDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"0\""),
                "policy-backfill-validate",
                backfillValidationReason,
                null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        long timelineBeforeBackfillPublish = lifecycleCount();
        long publishAuditsBeforeBackfill =
                successAuditCount("ATTENDANCE_POLICY_PUBLISHED");
        long backfillFailuresBefore = failureAuditCount(
                "POLICY_PUBLICATION_BACKFILL_CONFLICT");
        String backfillPublishReason = "policy backfill publish rejected";
        mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/publish",
                        TEMPLATE_ID,
                        backfillDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"1\""),
                "policy-backfill-publish",
                backfillPublishReason,
                """
                {"reason":"%s"}
                """.formatted(backfillPublishReason))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("POLICY_PUBLICATION_BACKFILL_CONFLICT"));
        assertThat(lifecycleCount()).isEqualTo(timelineBeforeBackfillPublish);
        assertThat(successAuditCount("ATTENDANCE_POLICY_PUBLISHED"))
                .isEqualTo(publishAuditsBeforeBackfill);
        assertThat(idempotencyCount("policy-backfill-publish")).isZero();
        assertThat(failureAuditCount(
                "POLICY_PUBLICATION_BACKFILL_CONFLICT"))
                .isEqualTo(backfillFailuresBefore + 1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_lifecycle_event
                WHERE scoped_version_id = ? AND action = 'PUBLISHED'
                """,
                Long.class,
                backfillDraftId)).isZero();
        assertThat(lifecycleRepository.findPublishedAt(
                SCOPE_ID, LocalDate.parse("2026-07-31")))
                .containsExactly(raceWinnerVersionId);

        String duplicateDraftReason = "policy duplicate parameter draft";
        MvcResult duplicateDraft = mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions",
                        TEMPLATE_ID)
                        .queryParam("companyId", COMPANY),
                "policy-duplicate-draft",
                duplicateDraftReason,
                """
                {
                  "basedOnVersionId":"%s",
                  "effectiveFrom":"2026-08-01",
                  "reason":"%s"
                }
                """.formatted(BASELINE_VERSION_ID, duplicateDraftReason))
                .andExpect(status().isCreated())
                .andReturn();
        String duplicateDraftId = value(
                duplicateDraft, "$.scopedVersionId");
        long versionsBeforeDuplicate = versionCount();
        long timelineBeforeDuplicate = lifecycleCount();
        long updateAuditsBeforeDuplicate =
                successAuditCount("ATTENDANCE_POLICY_DRAFT_UPDATED");
        long duplicateFailuresBefore =
                failureAuditCount("PATCH", "VALIDATION_ERROR");
        String duplicateReason = "policy duplicate parameter rejected";
        mutate(
                patch(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}",
                        TEMPLATE_ID,
                        duplicateDraftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"0\""),
                "policy-duplicate-parameter",
                duplicateReason,
                """
                {
                  "parameters":[
                    {"key":"enabled","value":true},
                    {"key":"graceMinutes","value":15},
                    {"key":"graceMinutes","value":15}
                  ],
                  "effectiveFrom":"2026-08-01",
                  "reason":"%s"
                }
                """.formatted(duplicateReason))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(versionCount()).isEqualTo(versionsBeforeDuplicate);
        assertThat(lifecycleCount()).isEqualTo(timelineBeforeDuplicate);
        assertThat(successAuditCount("ATTENDANCE_POLICY_DRAFT_UPDATED"))
                .isEqualTo(updateAuditsBeforeDuplicate);
        assertThat(idempotencyCount("policy-duplicate-parameter")).isZero();
        assertThat(failureAuditCount("PATCH", "VALIDATION_ERROR"))
                .isEqualTo(duplicateFailuresBefore + 1);
    }

    private org.springframework.test.web.servlet.ResultActions mutate(
            MockHttpServletRequestBuilder request,
            String idempotencyKey,
            String reason,
            String body) throws Exception {
        request.with(user(ADMIN_PRINCIPAL))
                .with(csrf())
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Change-Reason", reason);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private MvcResult rollback(
            String sourceVersionId,
            long expectedVersion,
            String idempotencyKey,
            String reason,
            String body) throws Exception {
        return mutate(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/rollback",
                        TEMPLATE_ID,
                        sourceVersionId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH,
                                "\"" + expectedVersion + "\""),
                idempotencyKey,
                reason,
                body).andReturn();
    }

    private List<MvcResult> concurrent(
            Callable<MvcResult> first,
            Callable<MvcResult> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<MvcResult> readyFirst = ready(first, ready, start);
        Callable<MvcResult> readySecond = ready(second, ready, start);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(readyFirst));
            futures.add(executor.submit(readySecond));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            Collections.shuffle(results);
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Callable<MvcResult> ready(
            Callable<MvcResult> delegate,
            CountDownLatch ready,
            CountDownLatch start) {
        return () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return delegate.call();
        };
    }

    private void assertExactResponse(
            MvcResult first, MvcResult replay) throws Exception {
        assertThat(replay.getResponse().getStatus())
                .isEqualTo(first.getResponse().getStatus());
        assertThat(replay.getResponse().getHeader(HttpHeaders.ETAG))
                .isEqualTo(first.getResponse().getHeader(HttpHeaders.ETAG));
        assertThat(replay.getResponse().getHeader("Idempotency-Key"))
                .isEqualTo(first.getResponse().getHeader("Idempotency-Key"));
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    private long versionCount() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_scoped_version
                WHERE scope_id = ?
                """,
                Long.class,
                SCOPE_ID);
    }

    private long lifecycleCount() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_lifecycle_event
                WHERE scope_id = ?
                """,
                Long.class,
                SCOPE_ID);
    }

    private long successAuditCount(String action) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = ? AND result_code = 'SUCCESS'
                """,
                Long.class,
                action);
    }

    private long failureAuditCount(String reason) {
        return failureAuditCount("POST", reason);
    }

    private long failureAuditCount(String method, String reason) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = ?
                  AND result_code = 'FAILURE'
                  AND reason_code = ?
                """,
                Long.class,
                "ATTENDANCE_SETUP_" + method + "_FAILURE",
                reason);
    }

    private long idempotencyCount(String key) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_setup_idempotency
                WHERE idempotency_key = ?
                """,
                Long.class,
                key);
    }

    private String idempotencyState(String key) {
        return jdbc.queryForObject(
                """
                SELECT state
                FROM attendance_setup_idempotency
                WHERE idempotency_key = ?
                """,
                String.class,
                key);
    }

    private String idempotencyHeaders(String key) {
        return jdbc.queryForObject(
                """
                SELECT CAST(response_headers_json AS VARCHAR)
                FROM attendance_setup_idempotency
                WHERE idempotency_key = ?
                """,
                String.class,
                key);
    }

    private VersionContent content(String scopedVersionId) {
        return jdbc.queryForObject(
                """
                SELECT CAST(parameters_json AS VARCHAR),
                       CAST(snapshot_json AS VARCHAR), snapshot_digest
                FROM attendance_policy_scoped_version
                WHERE scoped_version_id = ?
                """,
                (resultSet, ignored) -> new VersionContent(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getString(3)),
                scopedVersionId);
    }

    private String value(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private record VersionContent(
            String parametersJson,
            String snapshotJson,
            String snapshotDigest) {
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        MutableClock wave3PolicyLifecycleClock() {
            return new MutableClock(
                    Instant.parse("2026-07-27T00:00:00Z"));
        }
    }

    static final class MutableClock extends Clock {

        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
