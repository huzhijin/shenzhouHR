package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class Wave2PeopleExtendedAcceptanceIntegrationTest extends Wave1IntegrationTestSupport {

    private static final String BATCH_ID = "91000000-0000-0000-0000-000000000001";
    private static final String EMPLOYEE_ID = "b0000000-0000-0000-0000-000000000001";
    private static final String ORGANIZATION_ID = "40000000-0000-0000-0000-000000000001";
    private static final String FILE_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void versioned_template_download_is_a_real_xlsx_with_field_instructions() throws Exception {
        byte[] content = mockMvc.perform(get(
                        "/api/v1/people-imports/templates/{type}/versions/{version}",
                        "EMPLOYEE",
                        "1.0.0")
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("PEOPLE_IMPORT:TEMPLATE_DOWNLOAD"))))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse()
                        .getHeader(HttpHeaders.CONTENT_DISPOSITION))
                        .contains("initial-import-1.0.0.xlsx"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(content).startsWith((byte) 'P', (byte) 'K');
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheet("Data")).isNotNull();
            assertThat(workbook.getSheet("Field Instructions")).isNotNull();
            assertThat(workbook.getSheet("Field Instructions").getLastRowNum())
                    .isGreaterThanOrEqualTo(4);
        }
    }

    @Test
    void people_import_error_report_is_a_real_xlsx() throws Exception {
        byte[] content = mockMvc.perform(get(
                        "/api/v1/people-imports/{batchId}/error-report",
                        BATCH_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("PEOPLE_IMPORT:ERROR_REPORT_DOWNLOAD"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(content).startsWith((byte) 'P', (byte) 'K');
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            assertThat(workbook.getSheet("Errors")).isNotNull();
            assertThat(workbook.getSheet("Errors").getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheet("Errors").getRow(1).getCell(2).getStringCellValue())
                    .isEqualTo("EMPLOYEE_MATCH_AMBIGUOUS");
        }
    }

    @Test
    void blocking_people_import_cannot_publish() throws Exception {
        jdbc.update(
                """
                UPDATE people_import_batch
                SET status = 'AWAITING_CONFIRMATION', blocking_issue_count = 1,
                    row_version = 1, precheck_version = 1
                WHERE batch_id = ?
                """,
                BATCH_ID);

        mockMvc.perform(post("/api/v1/people-imports/{batchId}/publish", BATCH_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("PEOPLE_IMPORT:PUBLISH")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-blocking-publish")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEOPLE_IMPORT_BLOCKING_ERRORS"));
    }

    @Test
    void repeated_publish_does_not_duplicate_employee_or_version() throws Exception {
        jdbc.update(
                """
                INSERT INTO people_import_diff (
                    diff_id, batch_id, row_number, entity_type, category,
                    matched_resource_id, source_values_json, current_values_json,
                    proposed_values_json
                ) VALUES (
                    '94000000-0000-0000-0000-000000000001', ?, 3, 'EMPLOYEE',
                    'ADDED', NULL, ?, NULL, ?
                )
                """,
                BATCH_ID,
                """
                {"employeeNumber":"W2-IMP-001","externalEmployeeId":"W2-EXT-001",
                 "displayName":"WAVE-2 合成员工","effectiveFrom":"2026-07-25"}
                """,
                """
                {"employeeNumber":"W2-IMP-001","externalEmployeeId":"W2-EXT-001",
                 "displayName":"WAVE-2 合成员工","effectiveFrom":"2026-07-25"}
                """);
        long employeeBefore = count("employee");
        long versionBefore = count("employee_version");

        var first = mockMvc.perform(publishRequest("wave2-entity-idempotency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduplicated").value(false))
                .andExpect(jsonPath("$.localVersionIds[0]").isString())
                .andReturn();
        var second = mockMvc.perform(publishRequest("wave2-entity-idempotency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduplicated").value(true))
                .andReturn();

        assertThat(count("employee")).isEqualTo(employeeBefore + 1);
        assertThat(count("employee_version")).isEqualTo(versionBefore + 1);
        assertThat(JsonPath.<String>read(
                first.getResponse().getContentAsString(), "$.publicationId"))
                .isEqualTo(JsonPath.<String>read(
                        second.getResponse().getContentAsString(), "$.publicationId"));
    }

    @Test
    void voiding_draft_preserves_source_file_and_physical_delete_is_absent() throws Exception {
        jdbc.update(
                """
                UPDATE people_import_batch
                SET status = 'DRAFT', row_version = 0, precheck_version = NULL
                WHERE batch_id = ?
                """,
                BATCH_ID);
        jdbc.update(
                """
                INSERT INTO people_import_file (
                    file_id, batch_id, original_file_name, media_type, size_bytes,
                    sha256, content, uploaded_by, uploaded_at
                ) VALUES (
                    '95000000-0000-0000-0000-000000000001', ?,
                    'wave2-synthetic.xlsx',
                    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
                    2, ?, X'504B', ?, CURRENT_TIMESTAMP
                )
                """,
                BATCH_ID,
                FILE_SHA256,
                ADMIN_PRINCIPAL);

        mockMvc.perform(post("/api/v1/people-imports/{batchId}/void", BATCH_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("PEOPLE_IMPORT:VOID")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-void-preserves-file")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"WAVE-2 合成草稿作废"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM people_import_file WHERE batch_id = ?",
                Long.class,
                BATCH_ID)).isEqualTo(1L);

        mockMvc.perform(delete("/api/v1/people-imports/{batchId}", BATCH_ID)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM people_import_batch WHERE batch_id = ?",
                Long.class,
                BATCH_ID)).isEqualTo(1L);
    }

    @Test
    void local_employee_change_creates_a_new_version_and_audit() throws Exception {
        long versionBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee_version WHERE employee_id = ?",
                Long.class,
                EMPLOYEE_ID);
        long auditBefore = auditCountForResource(EMPLOYEE_ID);

        mockMvc.perform(patch("/api/v1/employees/{employeeId}", EMPLOYEE_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("EMPLOYEE:EDIT")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-employee-new-version")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "employeeNumber":"LEGACY-b0000000-0000-0000-0000-000000000001",
                                  "displayName":"WAVE-2 合成员工新版本",
                                  "status":"ACTIVE",
                                  "effectiveFrom":"2026-08-01",
                                  "effectiveTo":null,
                                  "reason":"WAVE-2 合成本地维护"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(1))
                .andExpect(jsonPath("$.sourceAuthority").value("LOCAL"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee_version WHERE employee_id = ?",
                Long.class,
                EMPLOYEE_ID)).isEqualTo(versionBefore + 1);
        assertThat(auditCountForResource(EMPLOYEE_ID)).isEqualTo(auditBefore + 1);

        mockMvc.perform(get("/api/v1/employees/{employeeId}/versions", EMPLOYEE_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("EMPLOYEE:READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].rowVersion").value(1))
                .andExpect(jsonPath("$.items[1].rowVersion").value(0));
    }

    @Test
    void local_organization_change_creates_a_new_version_and_audit() throws Exception {
        long versionBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM organization_version WHERE organization_id = ?",
                Long.class,
                ORGANIZATION_ID);
        long auditBefore = auditCountForResource(ORGANIZATION_ID);

        mockMvc.perform(patch(
                        "/api/v1/organization-units/{organizationId}",
                        ORGANIZATION_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("ORGANIZATION:EDIT")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-organization-new-version")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "parentOrganizationId":null,
                                  "code":"10",
                                  "name":"神州总部 WAVE-2 本地版本",
                                  "organizationType":"COMPANY",
                                  "status":"ACTIVE",
                                  "effectiveFrom":"2026-08-01",
                                  "effectiveTo":null,
                                  "reason":"WAVE-2 合成组织本地维护"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(1))
                .andExpect(jsonPath("$.sourceAuthority").value("LOCAL"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM organization_version WHERE organization_id = ?",
                Long.class,
                ORGANIZATION_ID)).isEqualTo(versionBefore + 1);
        assertThat(auditCountForResource(ORGANIZATION_ID)).isEqualTo(auditBefore + 1);
    }

    @Test
    void prior_service_recalculation_is_reproducible() throws Exception {
        var adjustment = mockMvc.perform(post(
                        "/api/v1/employees/{employeeId}/prior-service-adjustments",
                        EMPLOYEE_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("PRIOR_SERVICE:ADJUST")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-prior-replay-adjust")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amountDays":365,
                                  "businessDate":"2026-07-25",
                                  "reason":"WAVE-2 合成工龄重放"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultingTotalDays").value(365))
                .andReturn();
        String currentEtag = adjustment.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(currentEtag).isNotBlank();

        var replay = mockMvc.perform(post(
                        "/api/v1/employees/{employeeId}/prior-service/recalculate",
                        EMPLOYEE_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("PRIOR_SERVICE:ADJUST")))
                        .with(csrf())
                        .header("Idempotency-Key", "wave2-prior-replay-calculate")
                        .header("If-Match", currentEtag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"WAVE-2 合成工龄确定性重算"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDays").value(365))
                .andExpect(jsonPath("$.recordCount").value(1))
                .andReturn();
        String replayDigest = JsonPath.read(
                replay.getResponse().getContentAsString(), "$.replayDigest");

        mockMvc.perform(get(
                        "/api/v1/employees/{employeeId}/prior-service-records",
                        EMPLOYEE_ID)
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("PRIOR_SERVICE:READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDays").value(365))
                .andExpect(jsonPath("$.replayDigest").value(replayDigest))
                .andExpect(jsonPath("$.items[0].reason")
                        .value("WAVE-2 合成工龄重放"));
    }

    @Test
    void day_after_termination_has_no_employment_assignment() throws Exception {
        mockMvc.perform(get(
                        "/api/v1/employees/{employeeId}/employment-periods",
                        EMPLOYEE_ID)
                        .queryParam("asOf", "2026-08-01")
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                authority("EMPLOYMENT:READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            publishRequest(String key) {
        return post("/api/v1/people-imports/{batchId}/publish", BATCH_ID)
                .with(user(ADMIN_PRINCIPAL).authorities(
                        authority("PEOPLE_IMPORT:PUBLISH")))
                .with(csrf())
                .header("Idempotency-Key", key)
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishBody());
    }

    private static String publishBody() {
        return """
                {
                  "reason":"WAVE-2 合成发布验证",
                  "confirmedFileSha256":"%s",
                  "confirmedPrecheckVersion":1
                }
                """.formatted(FILE_SHA256);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static SimpleGrantedAuthority authority(String value) {
        return new SimpleGrantedAuthority(value);
    }
}
