package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class Wave2PeopleAcceptanceIntegrationTest extends Wave1IntegrationTestSupport {

    private static final String BATCH_ID = "91000000-0000-0000-0000-000000000001";
    private static final String PUBLICATION_ID = "93000000-0000-0000-0000-000000000001";
    private static final String EMPLOYEE_ID = "b0000000-0000-0000-0000-000000000001";
    private static final String FILE_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void people_import_requires_precheck() throws Exception {
        jdbc.update(
                """
                UPDATE people_import_batch
                SET status = 'DRAFT', precheck_version = NULL, row_version = 0
                WHERE batch_id = ?
                """,
                BATCH_ID);
        mockMvc.perform(post("/api/v1/people-imports/{batchId}/publish", BATCH_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("PEOPLE_IMPORT:PUBLISH")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-precheck-required")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason":"WAVE-2 合成发布检查",
                                  "confirmedFileSha256":"%s",
                                  "confirmedPrecheckVersion":1
                                }
                                """.formatted(FILE_SHA256)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEOPLE_IMPORT_PRECHECK_REQUIRED"));
    }

    @Test
    void people_import_publish_is_idempotent() throws Exception {
        mockMvc.perform(idempotentPublishRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduplicated").value(false));
        mockMvc.perform(idempotentPublishRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduplicated").value(true));
    }

    @Test
    void published_people_import_with_references_rejects_rollback() throws Exception {
        jdbc.update(
                """
                UPDATE people_import_batch
                SET status = 'PUBLISHED', row_version = 2, published_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                """,
                BATCH_ID);
        jdbc.update(
                """
                INSERT INTO people_import_publication (
                    publication_id, batch_id, file_sha256, idempotency_key,
                    snapshot_digest, snapshot_json, local_version_ids_json,
                    published_by, published_at
                ) VALUES (?, ?, ?, 'wave2-published-fixture', ?, '{}', '[]', ?,
                    CURRENT_TIMESTAMP)
                """,
                PUBLICATION_ID,
                BATCH_ID,
                FILE_SHA256,
                FILE_SHA256,
                ADMIN_PRINCIPAL);
        jdbc.update(
                """
                UPDATE employee_version
                SET source_import_batch_id = ?
                WHERE employee_id = ?
                """,
                BATCH_ID,
                EMPLOYEE_ID);
        mockMvc.perform(post("/api/v1/people-imports/{batchId}/rollback", BATCH_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("PEOPLE_IMPORT:ROLLBACK")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-referenced-rollback")
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason":"WAVE-2 合成引用冲突",
                                  "confirmedPublicationId":"%s"
                                }
                                """.formatted(PUBLICATION_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("PEOPLE_IMPORT_ROLLBACK_HAS_REFERENCES"));
    }

    @Test
    void organization_sync_route_is_absent() throws Exception {
        mockMvc.perform(post("/api/v1/organization-units/sync")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rehire_creates_new_employment_period() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/employees/{employeeId}/employment-periods",
                        EMPLOYEE_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("EMPLOYMENT:CREATE")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-rehire-001")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationId":"40000000-0000-0000-0000-000000000002",
                                  "startDate":"2026-08-01",
                                  "reason":"WAVE-2 合成二次入职"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startDate").value("2026-08-01"));
    }

    @Test
    void termination_day_is_inside_half_open_employment_period() throws Exception {
        mockMvc.perform(get(
                        "/api/v1/employees/{employeeId}/employment-periods",
                        EMPLOYEE_ID)
                        .queryParam("asOf", "2026-07-31")
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("EMPLOYMENT:READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].endExclusive").value("2026-08-01"));
    }

    @Test
    void overlapping_employment_period_is_rejected() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/employees/{employeeId}/employment-periods",
                        EMPLOYEE_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("EMPLOYMENT:CREATE")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-overlap-001")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationId":"40000000-0000-0000-0000-000000000001",
                                  "startDate":"2020-06-01",
                                  "reason":"WAVE-2 合成重叠检查"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPLOYMENT_PERIOD_OVERLAP"));
    }

    @Test
    void prior_service_change_is_audited() throws Exception {
        long before = auditCountForResource(EMPLOYEE_ID);
        mockMvc.perform(post(
                        "/api/v1/employees/{employeeId}/prior-service-adjustments",
                        EMPLOYEE_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("PRIOR_SERVICE:ADJUST")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-prior-service")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amountDays":540,
                                  "businessDate":"2026-07-25",
                                  "reason":"WAVE-2 合成入职前工龄调整"
                                }
                                """))
                .andExpect(status().isCreated());
        assertThat(auditCountForResource(EMPLOYEE_ID)).isEqualTo(before + 1);
    }

    @Test
    void name_and_department_are_not_unique_match_keys() throws Exception {
        mockMvc.perform(get("/api/v1/people-imports/{batchId}/errors", BATCH_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("PEOPLE_IMPORT:READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("EMPLOYEE_MATCH_AMBIGUOUS"))
                .andExpect(jsonPath("$.items[0].candidateEmployeeIds").isArray());
    }

    private static SimpleGrantedAuthority authority(String value) {
        return new SimpleGrantedAuthority(value);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            idempotentPublishRequest() {
        return post("/api/v1/people-imports/{batchId}/publish", BATCH_ID)
                .with(user(ADMIN_PRINCIPAL).authorities(authority("PEOPLE_IMPORT:PUBLISH")))
                .with(csrf())
                .header("Idempotency-Key", "wave2-idempotent-publish")
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "reason":"WAVE-2 合成幂等发布",
                          "confirmedFileSha256":"%s",
                          "confirmedPrecheckVersion":1
                        }
                        """.formatted(FILE_SHA256));
    }
}
