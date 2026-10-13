package com.szsemicon.hr.reporting.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

/**
 * H2 HTTP integration test for OaOvertimeRecognitionController (V75).
 * Uses real Spring Security filter chain, real MyBatis, real service.
 *
 * Auth: SecurityMockMvcRequestPostProcessors.user() sets a pre-authenticated principal.
 * SessionAuthenticationFilter hydrates capabilities from DB when no session cookie is present
 * and SecurityContext already holds an authenticated token, so capability tests must manipulate
 * the DB, not just the MockMvc authority list.
 *
 * CSRF: SessionCsrfFilter — when no session cookie is present and the principal IS authenticated,
 * a non-blank X-CSRF-TOKEN header satisfies the check. Omitting the header returns 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OaOvertimeRecognitionControllerH2Test {

    @TestConfiguration
    static class TestClockConfiguration {
        @Bean
        @Primary
        public Clock testClock() {
            return Clock.fixed(
                Instant.parse("2026-10-08T08:00:00.123456789Z"),
                ZoneOffset.UTC
            );
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String SHARED_SOURCE_ID    = "e05866a7-97b0-11f1-8924-00155d09fd0e";
    private static final String COMPANY_ID          = "c0000097-0000-0000-0000-000000000097";
    private static final String EMPLOYEE_ID         = "e0000097-0000-0000-0000-000000000097";
    private static final String ACTOR_PRINCIPAL_ID  = "p0000097-0000-0000-0000-000000000097";
    private static final String RECOGNITION_ID_1    = UUID.randomUUID().toString();
    private static final String OA_DOC_ID_1         = UUID.randomUUID().toString();
    private static final String ROLE_ID             = "r0000097-0000-0000-0000-000000000097";
    private static final String CAPABILITY_ID       = "cap00097-0000-0000-0000-000000000097";
    private static final String SCOPE_ID            = "s0000097-0000-0000-0000-000000000097";
    private static final String EMPLOYEE_VERSION_ID = "ev000097-0000-0000-0000-000000000097";
    private static final String RAW_ID_1            = UUID.randomUUID().toString();
    private static final String NORMALIZED_ID_1     = UUID.randomUUID().toString();

    // 78-char source version: "v20260907-" (10) + "a"*31 (31) + ":obs=" (5) + "b"*32 (32) = 78
    private static final String LONG_SOURCE_VERSION =
        "v20260907-" + "a".repeat(31) + ":obs=" + "b".repeat(32);
    private static final String LONG_RAW_ID         = UUID.randomUUID().toString();
    private static final String LONG_NORMALIZED_ID  = UUID.randomUUID().toString();
    private static final String LONG_OA_DOC_ID      = UUID.randomUUID().toString();
    private static final String LONG_RECOGNITION_ID = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM attendance_report_oa_overtime_recognition WHERE recognition_id IN (?, ?)",
            RECOGNITION_ID_1, LONG_RECOGNITION_ID);
        jdbc.update("DELETE FROM audit_event WHERE resource_type = 'attendance_report_oa_overtime_recognition' " +
            "AND resource_id_ref IN (?, ?)", RECOGNITION_ID_1, LONG_RECOGNITION_ID);
        jdbc.update("DELETE FROM oa_attendance_document_context WHERE oa_attendance_document_id IN (?, ?)",
            OA_DOC_ID_1, LONG_OA_DOC_ID);
        jdbc.update("DELETE FROM employee_match_decision WHERE normalized_attendance_record_id IN (?, ?)",
            NORMALIZED_ID_1, LONG_NORMALIZED_ID);
        jdbc.update("DELETE FROM oa_attendance_document WHERE oa_attendance_document_id IN (?, ?)",
            OA_DOC_ID_1, LONG_OA_DOC_ID);
        jdbc.update("DELETE FROM normalized_attendance_record WHERE normalized_attendance_record_id IN (?, ?)",
            NORMALIZED_ID_1, LONG_NORMALIZED_ID);
        jdbc.update("DELETE FROM raw_attendance_fact WHERE raw_attendance_fact_id IN (?, ?)",
            RAW_ID_1, LONG_RAW_ID);
        jdbc.update("DELETE FROM auth_principal_role_assignment WHERE principal_id = ?", ACTOR_PRINCIPAL_ID);
        jdbc.update("DELETE FROM auth_role_capability WHERE role_id = ?", ROLE_ID);
        jdbc.update("DELETE FROM auth_capability WHERE capability_id = ?", CAPABILITY_ID);
        jdbc.update("DELETE FROM auth_role WHERE role_id = ?", ROLE_ID);
        jdbc.update("DELETE FROM auth_data_scope WHERE scope_id = ?", SCOPE_ID);
        jdbc.update("DELETE FROM employee_current_projection WHERE employee_id = ?", EMPLOYEE_ID);
        jdbc.update("DELETE FROM employee_version WHERE employee_version_id = ?", EMPLOYEE_VERSION_ID);
        jdbc.update("DELETE FROM employee WHERE employee_id = ?", EMPLOYEE_ID);
        jdbc.update("DELETE FROM auth_principal WHERE principal_id = ?", ACTOR_PRINCIPAL_ID);
        jdbc.update("DELETE FROM attendance_source WHERE attendance_source_id = ?", SHARED_SOURCE_ID);
        jdbc.update("DELETE FROM company WHERE company_id = ?", COMPANY_ID);

        jdbc.update("INSERT INTO company (company_id, code, name, status, created_at) VALUES (?, ?, ?, 'ACTIVE', ?)",
            COMPANY_ID, "TEST-COMP-097", "HTTP测试公司", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        jdbc.update("INSERT INTO attendance_source (attendance_source_id, company_id, source_code, source_type, " +
            "display_name, status, created_by, created_at) VALUES (?, ?, ?, 'OA_ATTENDANCE', ?, 'ACTIVE', ?, ?)",
            SHARED_SOURCE_ID, COMPANY_ID, "OA-TEST-097", "测试OA源", ACTOR_PRINCIPAL_ID,
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        jdbc.update("INSERT INTO employee (employee_id, company_id, display_name, employment_status) " +
            "VALUES (?, ?, ?, 'ACTIVE')", EMPLOYEE_ID, COMPANY_ID, "HTTP测试员工");
        jdbc.update("INSERT INTO employee_version (employee_version_id, employee_id, employee_number, " +
            "display_name, status, effective_from, effective_to, source_authority, created_at, created_by) " +
            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL, 'TEST', ?, 'SYSTEM')",
            EMPLOYEE_VERSION_ID, EMPLOYEE_ID, "EMP-097", "HTTP测试员工",
            java.sql.Date.valueOf("2026-01-01"), Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        jdbc.update("INSERT INTO employee_current_projection (employee_id, current_version_id, projected_at) " +
            "VALUES (?, ?, ?)", EMPLOYEE_ID, EMPLOYEE_VERSION_ID, Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO auth_principal (principal_id, employee_id, status, created_at, row_version) " +
            "VALUES (?, ?, 'ACTIVE', ?, 0)", ACTOR_PRINCIPAL_ID, EMPLOYEE_ID, Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO auth_capability (capability_id, capability_code, permission_domain, action_code) " +
            "VALUES (?, 'ATTENDANCE_REPORT:REFRESH', 'ATTENDANCE_REPORT', 'REFRESH')", CAPABILITY_ID);
        jdbc.update("INSERT INTO auth_role (role_id, role_code, role_name, permission_domain) " +
            "VALUES (?, 'HR_ADMIN_TEST_097', 'HR管理员(HTTP测试)', 'ATTENDANCE_REPORT')", ROLE_ID);
        jdbc.update("INSERT INTO auth_role_capability (role_id, capability_id) VALUES (?, ?)",
            ROLE_ID, CAPABILITY_ID);
        jdbc.update("INSERT INTO auth_data_scope (scope_id, scope_type, company_id, organization_id, " +
            "include_descendants, valid_from, valid_to) VALUES (?, 'COMPANY', ?, NULL, TRUE, ?, NULL)",
            SCOPE_ID, COMPANY_ID, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        jdbc.update("INSERT INTO auth_principal_role_assignment (assignment_id, principal_id, role_id, " +
            "data_scope_id, valid_from, valid_to, assigned_by, reason, row_version) " +
            "VALUES (?, ?, ?, ?, ?, NULL, 'SYSTEM', 'V75 HTTP TEST', 0)",
            UUID.randomUUID().toString(), ACTOR_PRINCIPAL_ID, ROLE_ID, SCOPE_ID,
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));

        // Short-version OA chain only. The long-version chain is inserted inside
        // recognizePost_longSourceVersion_200_persistedExact after the short chain is removed,
        // so the two consumable APPROVED/ACTIVATED COMPENSATORY documents never coexist and
        // hasOtherConsumableOaOverlap is never triggered by fixture setup.
        insertOaChain(RAW_ID_1, NORMALIZED_ID_1, OA_DOC_ID_1,
            "施昆卫:OVERTIME:20260907:097", "v20260907-097",
            Instant.parse("2026-09-07T10:00:00Z"), Instant.parse("2026-09-07T12:30:00Z"),
            Instant.parse("2026-09-08T02:00:00Z"));
    }

    private void insertOaChain(String rawId, String normId, String docId,
            String businessKey, String sourceVersion,
            Instant intervalStart, Instant intervalEnd, Instant receivedAt) {
        jdbc.update("INSERT INTO raw_attendance_fact (raw_attendance_fact_id, attendance_source_id, " +
            "company_id, source_business_key, source_version, canonical_payload_digest, received_at, " +
            "fact_kind, interval_start, interval_end) VALUES (?, ?, ?, ?, ?, ?, ?, 'OA_DOCUMENT', ?, ?)",
            rawId, SHARED_SOURCE_ID, COMPANY_ID, businessKey, sourceVersion,
            computeSha256("raw-" + rawId), Timestamp.from(receivedAt),
            Timestamp.from(intervalStart), Timestamp.from(intervalEnd));
        jdbc.update("INSERT INTO normalized_attendance_record (normalized_attendance_record_id, " +
            "raw_attendance_fact_id, normalization_revision, validation_status, " +
            "interval_start, interval_end, canonical_digest, created_at, schema_version, record_kind) " +
            "VALUES (?, ?, 1, 'VALID', ?, ?, ?, ?, 'V1', 'OA_INTERVAL')",
            normId, rawId, Timestamp.from(intervalStart), Timestamp.from(intervalEnd),
            computeSha256("norm-" + normId), Timestamp.from(receivedAt.plusSeconds(300)));
        jdbc.update("INSERT INTO employee_match_decision (normalized_attendance_record_id, employee_id, " +
            "employment_period_id, resolver_snapshot_digest, match_status, created_at) " +
            "VALUES (?, ?, ?, ?, 'MATCHED', ?)",
            normId, EMPLOYEE_ID, UUID.randomUUID().toString(),
            "resolver-097", Timestamp.from(receivedAt.plusSeconds(360)));
        jdbc.update("INSERT INTO oa_attendance_document (oa_attendance_document_id, " +
            "normalized_attendance_record_id, attendance_source_id, source_business_key, " +
            "source_version, knowledge_rank, document_type, source_status, " +
            "created_at, leave_type, leave_serial, original_leave_serial) " +
            "VALUES (?, ?, ?, ?, ?, 1, 'OVERTIME', 'APPROVED', ?, NULL, NULL, NULL)",
            docId, normId, SHARED_SOURCE_ID, businessKey, sourceVersion,
            Timestamp.from(receivedAt.plusSeconds(600)));
        jdbc.update("INSERT INTO oa_attendance_document_context (oa_attendance_document_id, " +
            "activation_decision, overtime_type, created_at) VALUES (?, 'ACTIVATED', 'COMPENSATORY', ?)",
            docId, Timestamp.from(receivedAt.plusSeconds(660)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ helpers

    private String confirmText() {
        return "施昆卫 2026年9月7日加班150分钟，已确认审批单据准确(HTTP)";
    }

    private String recognizeBody(String requestId, String recognitionId, String docId,
            String businessKey, String sourceVersion) {
        String ct = confirmText();
        return """
            {
              "requestId": "%s",
              "recognitionId": "%s",
              "companyId": "%s",
              "employeeId": "%s",
              "sourceBusinessKey": "%s",
              "sourceVersion": "%s",
              "oaAttendanceDocumentId": "%s",
              "businessDate": "2026-09-07",
              "expectedIntervalStart": "2026-09-07T10:00:00Z",
              "expectedIntervalEnd": "2026-09-07T12:30:00Z",
              "recognizedMinutes": 150,
              "reason": "施昆卫加班150分钟",
              "userConfirmationText": "%s",
              "userConfirmationSha256": "%s",
              "evidenceSha256": "%s"
            }
            """.formatted(requestId, recognitionId, COMPANY_ID, EMPLOYEE_ID,
                businessKey, sourceVersion, docId,
                ct, computeSha256(ct), computeSha256("evidence-http-097"));
    }

    /** Convenience overload using the short-version fixture. */
    private String recognizeBody(String requestId) {
        return recognizeBody(requestId, RECOGNITION_ID_1, OA_DOC_ID_1,
            "施昆卫:OVERTIME:20260907:097", "v20260907-097");
    }

    private String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void recognizePost_200_noStore_fiveFieldResponse() throws Exception {
        mockMvc.perform(post("/api/v1/attendance/oa-overtime-recognition")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(recognizeBody(UUID.randomUUID().toString())))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.recognitionId").value(RECOGNITION_ID_1))
            .andExpect(jsonPath("$.knowledgeAt").exists())
            .andExpect(jsonPath("$.recognitionDigest").isString())
            .andExpect(jsonPath("$.reversedAt").doesNotExist())
            .andExpect(jsonPath("$.reversedBy").doesNotExist());

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_report_oa_overtime_recognition WHERE recognition_id = ?",
            Long.class, RECOGNITION_ID_1)).isEqualTo(1L);
    }

    @Test
    void recognizePost_replay_returnsSameBody() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String body = recognizeBody(requestId);

        String firstResponse = mockMvc.perform(post("/api/v1/attendance/oa-overtime-recognition")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String replayResponse = mockMvc.perform(post("/api/v1/attendance/oa-overtime-recognition")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(replayResponse).isEqualTo(firstResponse);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_report_oa_overtime_recognition WHERE recognition_id = ?",
            Long.class, RECOGNITION_ID_1)).isEqualTo(1L);
    }

    @Test
    void recognizePost_missingAuthority_returns403_noDbRow() throws Exception {
        // SessionAuthenticationFilter re-hydrates capabilities from DB, so the DB edge must be
        // removed — altering only the MockMvc authority list has no effect.
        try {
            jdbc.update("DELETE FROM auth_role_capability WHERE role_id = ? AND capability_id = ?",
                ROLE_ID, CAPABILITY_ID);

            mockMvc.perform(post("/api/v1/attendance/oa-overtime-recognition")
                    .with(user(ACTOR_PRINCIPAL_ID).authorities(
                        new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                    .header("X-CSRF-TOKEN", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(recognizeBody(UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());

            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_overtime_recognition WHERE recognition_id = ?",
                Long.class, RECOGNITION_ID_1)).isEqualTo(0L);
        } finally {
            // Restore the capability edge so subsequent tests see a clean fixture
            jdbc.update("INSERT INTO auth_role_capability (role_id, capability_id) VALUES (?, ?)",
                ROLE_ID, CAPABILITY_ID);
        }
    }

    @Test
    void recognizePost_employeeOutOfScope_returns404_noDbRow() throws Exception {
        // Temporarily redirect SCOPE_ID to a different company so EMPLOYEE_ID is out of range.
        // ResourceNotAvailableAccessDeniedException -> ApiExceptionHandler -> 404 RESOURCE_NOT_AVAILABLE.
        String outsideCompanyId = "c0000097-0000-0000-ffff-000000000097";
        try {
            jdbc.update("INSERT INTO company (company_id, code, name, status, created_at) " +
                "VALUES (?, ?, ?, 'ACTIVE', ?)",
                outsideCompanyId, "OUTSIDE-097", "范围外公司",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
            // Point the scope at the outside company — employee is in COMPANY_ID, not outsideCompanyId
            jdbc.update("UPDATE auth_data_scope SET company_id = ? WHERE scope_id = ?",
                outsideCompanyId, SCOPE_ID);

            mockMvc.perform(post("/api/v1/attendance/oa-overtime-recognition")
                    .with(user(ACTOR_PRINCIPAL_ID).authorities(
                        new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                    .header("X-CSRF-TOKEN", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(recognizeBody(UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());

            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_overtime_recognition WHERE recognition_id = ?",
                Long.class, RECOGNITION_ID_1)).isEqualTo(0L);
        } finally {
            jdbc.update("UPDATE auth_data_scope SET company_id = ? WHERE scope_id = ?",
                COMPANY_ID, SCOPE_ID);
            jdbc.update("DELETE FROM company WHERE company_id = ?", outsideCompanyId);
        }
    }

    @Test
    void recognizePost_longSourceVersion_200_persistedExact() throws Exception {
        assertThat(LONG_SOURCE_VERSION.length()).isEqualTo(78);

        // Remove the short-version OA chain so only the long-version chain is consumable.
        // Two overlapping APPROVED/ACTIVATED/COMPENSATORY documents for the same interval
        // trigger hasOtherConsumableOaOverlap and would block both requests.
        jdbc.update("DELETE FROM oa_attendance_document_context WHERE oa_attendance_document_id = ?", OA_DOC_ID_1);
        jdbc.update("DELETE FROM employee_match_decision WHERE normalized_attendance_record_id = ?", NORMALIZED_ID_1);
        jdbc.update("DELETE FROM oa_attendance_document WHERE oa_attendance_document_id = ?", OA_DOC_ID_1);
        jdbc.update("DELETE FROM normalized_attendance_record WHERE normalized_attendance_record_id = ?", NORMALIZED_ID_1);
        jdbc.update("DELETE FROM raw_attendance_fact WHERE raw_attendance_fact_id = ?", RAW_ID_1);

        // Insert the long-version chain as the sole consumable OA document for this test.
        insertOaChain(LONG_RAW_ID, LONG_NORMALIZED_ID, LONG_OA_DOC_ID,
            "施昆卫:OVERTIME:20260907:097L", LONG_SOURCE_VERSION,
            Instant.parse("2026-09-07T10:00:00Z"), Instant.parse("2026-09-07T12:30:00Z"),
            Instant.parse("2026-09-08T03:00:00Z"));

        String requestId = UUID.randomUUID().toString();
        String body = recognizeBody(requestId, LONG_RECOGNITION_ID, LONG_OA_DOC_ID,
            "施昆卫:OVERTIME:20260907:097L", LONG_SOURCE_VERSION);

        mockMvc.perform(post("/api/v1/attendance/oa-overtime-recognition")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.recognitionId").value(LONG_RECOGNITION_ID));

        String persistedVersion = jdbc.queryForObject(
            "SELECT source_version FROM attendance_report_oa_overtime_recognition " +
            "WHERE recognition_id = ?", String.class, LONG_RECOGNITION_ID);
        assertThat(persistedVersion).isEqualTo(LONG_SOURCE_VERSION);
        assertThat(persistedVersion.length()).isEqualTo(78);

        // Exact idempotent replay returns same body
        String firstBody = mockMvc.perform(post("/api/v1/attendance/oa-overtime-recognition")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String replayBody = mockMvc.perform(post("/api/v1/attendance/oa-overtime-recognition")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(replayBody).isEqualTo(firstBody);
    }

    @Test
    void recognizePost_missingCsrfToken_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/attendance/oa-overtime-recognition")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                // no X-CSRF-TOKEN header — SessionCsrfFilter rejects
                .contentType(MediaType.APPLICATION_JSON)
                .content(recognizeBody(UUID.randomUUID().toString())))
            .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_report_oa_overtime_recognition WHERE recognition_id = ?",
            Long.class, RECOGNITION_ID_1)).isEqualTo(0L);
    }
}
