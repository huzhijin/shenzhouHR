package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class Wave2ApiBehaviorContractIntegrationTest extends Wave1IntegrationTestSupport {

    private static final String EMPLOYEE_ID = "b0000000-0000-0000-0000-000000000001";
    private static final String BATCH_ID = "91000000-0000-0000-0000-000000000001";
    private static final String PUBLICATION_ID = "93000000-0000-0000-0000-000000000009";
    private static final String FILE_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void compatibility_lists_expose_the_required_wave2_projection_fields() throws Exception {
        mockMvc.perform(get("/api/v1/organization-units")
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("MASTER_DATA:READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].organizationVersionId").isString())
                .andExpect(jsonPath("$[0].sourceAuthority").value("LOCAL"))
                .andExpect(jsonPath("$[0].rowVersion").isNumber());

        mockMvc.perform(get("/api/v1/employees")
                        .queryParam("query", "LEGACY-b0000000")
                        .queryParam("status", "ACTIVE")
                        .queryParam("sort", "employeeNumber")
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("MASTER_DATA:READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].employeeVersionId").isString())
                .andExpect(jsonPath("$.items[0].employeeNumber").isString())
                .andExpect(jsonPath("$.items[0].sourceAuthority").value("LOCAL"))
                .andExpect(jsonPath("$.items[0].rowVersion").isNumber());
    }

    @Test
    void create_employee_response_matches_the_locked_detail_contract() throws Exception {
        mockMvc.perform(post("/api/v1/employees")
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("EMPLOYEE:CREATE")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-create-detail-contract")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyId":"30000000-0000-0000-0000-000000000001",
                                  "employeeNumber":"W2-LOCAL-DETAIL-001",
                                  "displayName":"WAVE-2 合成员工详情",
                                  "externalEmployeeId":"W2-LOCAL-EXT-001",
                                  "effectiveFrom":"2026-07-25",
                                  "reason":"WAVE-2 合同响应验证"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.employmentPeriods").isArray())
                .andExpect(jsonPath("$.priorService.totalDays").value(0))
                .andExpect(jsonPath("$.auditResourceId").isString());
    }

    @Test
    void legacyCompanyRequestFieldIsRejectedWithoutCreatingAnEmployee()
            throws Exception {
        String employeeNumber = "W2-LEGACY-COMPANY-FIELD";

        mockMvc.perform(post("/api/v1/employees")
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("EMPLOYEE:CREATE")))
                        .with(csrf())
                        .header(
                                "Idempotency-Key",
                                "wave2-reject-legacy-company-field")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalEntityId":"30000000-0000-0000-0000-000000000001",
                                  "employeeNumber":"%s",
                                  "displayName":"旧字段必须拒绝",
                                  "externalEmployeeId":"W2-LEGACY-COMPANY-EXT",
                                  "effectiveFrom":"2026-07-25",
                                  "reason":"旧字段失败关闭验证"
                                }
                                """.formatted(employeeNumber)))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee WHERE employee_number = ?",
                Long.class,
                employeeNumber)).isZero();
    }

    @Test
    void corruptedCrossCompanyEmploymentCannotLeakListCountOrDetail()
            throws Exception {
        String organizationPrincipal =
                "80000000-0000-0000-0000-000000000003";
        String organizationRole =
                "10000000-0000-0000-0000-000000000003";
        String outsideEmployee =
                "b0000000-0000-0000-0000-000000000004";
        jdbc.update(
                """
                UPDATE employment_assignment
                SET organization_id =
                    '40000000-0000-0000-0000-000000000003'
                WHERE employee_id = ?
                """,
                outsideEmployee);
        jdbc.update(
                """
                INSERT INTO auth_role_capability (role_id, capability_id)
                VALUES
                    (?, '23000000-0000-0000-0000-000000000014'),
                    (?, '23000000-0000-0000-0000-000000000017')
                """,
                organizationRole,
                organizationRole);

        mockMvc.perform(get("/api/v1/employees")
                        .queryParam("query", "Mallory")
                        .with(user(organizationPrincipal).authorities(
                                authority("MASTER_DATA:READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/v1/employees/{employeeId}", outsideEmployee)
                        .with(user(organizationPrincipal).authorities(
                                authority("EMPLOYEE:READ"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        mockMvc.perform(get(
                        "/api/v1/employees/{employeeId}/employment-periods",
                        outsideEmployee)
                        .with(user(organizationPrincipal).authorities(
                                authority("EMPLOYMENT:READ"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));
    }

    @Test
    void historicalAsOfCannotRestoreFormerDepartmentAccessAfterTransfer()
            throws Exception {
        String employeeId =
                "b0000000-0000-0000-0000-000000000002";
        String formerDepartmentPrincipal =
                "80000000-0000-0000-0000-000000000003";
        String currentDepartmentPrincipal =
                "80000000-0000-0000-0000-000000000099";
        String departmentRole =
                "10000000-0000-0000-0000-000000000003";
        String currentDepartmentScope =
                "90000000-0000-0000-0000-000000000099";

        jdbc.update(
                """
                INSERT INTO auth_role_capability (role_id, capability_id)
                VALUES (?, '23000000-0000-0000-0000-000000000014')
                """,
                departmentRole);
        jdbc.update(
                """
                UPDATE employment_assignment
                SET effective_to = TIMESTAMP '2026-07-01 00:00:00'
                WHERE assignment_id =
                    'c0000000-0000-0000-0000-000000000002'
                """);
        jdbc.update(
                """
                INSERT INTO employment_assignment (
                    assignment_id, employment_period_id, employee_id,
                    organization_id, effective_from, change_reason
                ) VALUES (
                    'c0000000-0000-0000-0000-000000000099',
                    'c0000000-0000-0000-0000-000000000099',
                    ?, '40000000-0000-0000-0000-000000000001',
                    TIMESTAMP '2026-07-01 00:00:00',
                    'WAVE-2 transfer authorization test'
                )
                """,
                employeeId);
        jdbc.update(
                """
                INSERT INTO auth_principal (
                    principal_id, status, created_at, row_version
                ) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)
                """,
                currentDepartmentPrincipal);
        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, company_id, organization_id,
                    include_descendants, valid_from, valid_to
                ) VALUES (
                    ?, 'ORGANIZATION', NULL,
                    '40000000-0000-0000-0000-000000000001',
                    TRUE, TIMESTAMP '2020-01-01 00:00:00', NULL
                )
                """,
                currentDepartmentScope);
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to
                ) VALUES (
                    'a0000000-0000-0000-0000-000000000099',
                    ?, ?, ?, TIMESTAMP '2020-01-01 00:00:00', NULL
                )
                """,
                currentDepartmentPrincipal,
                departmentRole,
                currentDepartmentScope);

        mockMvc.perform(get("/api/v1/employees/{employeeId}", employeeId)
                        .queryParam("asOf", "2026-06-01")
                        .with(user(formerDepartmentPrincipal).authorities(
                                authority("EMPLOYEE:READ"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        mockMvc.perform(get("/api/v1/employees/{employeeId}", employeeId)
                        .queryParam("asOf", "2026-06-01")
                        .with(user(currentDepartmentPrincipal).authorities(
                                authority("EMPLOYEE:READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(employeeId));
    }

    @Test
    void employee_aggregate_etag_advances_and_rejects_a_stale_followup() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/employees/{employeeId}/employment-periods",
                        EMPLOYEE_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("EMPLOYMENT:CREATE")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-aggregate-employment")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationId":"40000000-0000-0000-0000-000000000002",
                                  "startDate":"2026-08-01",
                                  "reason":"WAVE-2 聚合版本验证"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/employees/{employeeId}", EMPLOYEE_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("EMPLOYEE:READ"))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.rowVersion").value(1));

        mockMvc.perform(post(
                        "/api/v1/employees/{employeeId}/prior-service-adjustments",
                        EMPLOYEE_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("PRIOR_SERVICE:ADJUST")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-stale-aggregate-write")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amountDays":30,
                                  "businessDate":"2026-07-25",
                                  "reason":"WAVE-2 陈旧版本拒绝"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        mockMvc.perform(post(
                        "/api/v1/employees/{employeeId}/prior-service-adjustments",
                        EMPLOYEE_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(authority("PRIOR_SERVICE:ADJUST")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-current-aggregate-write")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amountDays":30,
                                  "businessDate":"2026-07-25",
                                  "reason":"WAVE-2 当前版本写入"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""));
    }

    @Test
    void published_batch_detail_contains_its_immutable_publication() throws Exception {
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
                    publication_id, batch_id, company_id, template_type,
                    template_version, file_sha256, idempotency_key, snapshot_digest,
                    snapshot_json, local_version_ids_json, published_by, published_at
                ) VALUES (?, ?, ?, 'EMPLOYEE', '1.0', ?, ?, ?, '{}', '[]', ?,
                    CURRENT_TIMESTAMP)
                """,
                PUBLICATION_ID,
                BATCH_ID,
                "30000000-0000-0000-0000-000000000001",
                FILE_SHA256,
                "wave2-detail-publication",
                FILE_SHA256,
                ADMIN_PRINCIPAL);

        mockMvc.perform(get("/api/v1/people-imports/{batchId}", BATCH_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("PEOPLE_IMPORT:READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publication.publicationId").value(PUBLICATION_ID))
                .andExpect(jsonPath("$.publication.snapshotDigest").value(FILE_SHA256));
    }

    @Test
    void published_batch_has_no_physical_delete_route() throws Exception {
        mockMvc.perform(delete("/api/v1/people-imports/{batchId}", BATCH_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("PEOPLE_IMPORT:READ")))
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    private static SimpleGrantedAuthority authority(String value) {
        return new SimpleGrantedAuthority(value);
    }
}
