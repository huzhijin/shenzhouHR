package com.szsemicon.hr.reporting.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OvertimeDocumentPresence;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * H2 HTTP integration test for OaEvidenceExclusionController (V74).
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OaEvidenceExclusionControllerH2Test {

    @TestConfiguration
    static class TestClockConfiguration {
        @Bean
        @Primary
        public Clock testClock() {
            return Clock.fixed(
                Instant.parse("2026-10-08T09:00:00.987654321Z"),
                ZoneOffset.UTC
            );
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private OaAttendanceDocumentSourcePort syntheticOaAttendanceDocumentAdapter;

    private static final String SHARED_SOURCE_ID    = "e05866a7-97b0-11f1-8924-00155d09fd0e";
    private static final String COMPANY_ID          = "c0000096-0000-0000-0000-000000000096";
    private static final String EMPLOYEE_ID         = "e0000096-0000-0000-0000-000000000096";
    private static final String ACTOR_PRINCIPAL_ID  = "p0000096-0000-0000-0000-000000000096";
    private static final String EXCLUSION_ID_1      = UUID.randomUUID().toString();
    private static final String OA_DOC_ID_1         = UUID.randomUUID().toString();
    private static final String ROLE_ID             = "r0000096-0000-0000-0000-000000000096";
    private static final String CAPABILITY_ID       = "cap00096-0000-0000-0000-000000000096";
    private static final String SCOPE_ID            = "s0000096-0000-0000-0000-000000000096";
    private static final String EMPLOYEE_VERSION_ID = "ev000096-0000-0000-0000-000000000096";
    private static final String RAW_ID_1            = UUID.randomUUID().toString();
    private static final String NORMALIZED_ID_1     = UUID.randomUUID().toString();

    // 78-char source version: "v20260907-" (10) + "a"*31 (31) + ":obs=" (5) + "b"*32 (32) = 78
    private static final String LONG_SOURCE_VERSION =
        "v20260907-" + "a".repeat(31) + ":obs=" + "b".repeat(32);
    private static final String LONG_RAW_ID         = UUID.randomUUID().toString();
    private static final String LONG_NORMALIZED_ID  = UUID.randomUUID().toString();
    private static final String LONG_OA_DOC_ID      = UUID.randomUUID().toString();
    private static final String LONG_EXCLUSION_ID   = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        // Clean in correct order - children first, then parents
        // Delete all exclusions for this employee first (has FKs to employee and auth_principal)
        jdbc.update("DELETE FROM attendance_report_oa_evidence_exclusion WHERE employee_id = ?", EMPLOYEE_ID);
        jdbc.update("DELETE FROM audit_event WHERE resource_type = 'attendance_report_oa_evidence_exclusion'");
        jdbc.update("DELETE FROM oa_attendance_document_context");
        jdbc.update("DELETE FROM oa_attendance_document");
        jdbc.update("DELETE FROM employee_match_decision WHERE employee_id = ?", EMPLOYEE_ID);
        jdbc.update("DELETE FROM normalized_attendance_record");
        jdbc.update("DELETE FROM raw_attendance_fact WHERE attendance_source_id = ?", SHARED_SOURCE_ID);
        // Auth cleanup
        jdbc.update("DELETE FROM auth_principal_role_assignment WHERE principal_id = ?", ACTOR_PRINCIPAL_ID);
        jdbc.update("DELETE FROM auth_role_capability WHERE role_id = ?", ROLE_ID);
        jdbc.update("DELETE FROM auth_role WHERE role_id = ?", ROLE_ID);
        jdbc.update("DELETE FROM auth_capability WHERE capability_id = ?", CAPABILITY_ID);
        jdbc.update("DELETE FROM auth_data_scope WHERE scope_id = ?", SCOPE_ID);
        // Employee cleanup
        jdbc.update("DELETE FROM employee_current_projection WHERE employee_id = ?", EMPLOYEE_ID);
        jdbc.update("DELETE FROM employee_version WHERE employee_version_id = ?", EMPLOYEE_VERSION_ID);
        jdbc.update("DELETE FROM employee WHERE employee_id = ?", EMPLOYEE_ID);
        jdbc.update("DELETE FROM attendance_source WHERE attendance_source_id = ?", SHARED_SOURCE_ID);
        jdbc.update("DELETE FROM auth_principal WHERE principal_id = ?", ACTOR_PRINCIPAL_ID);
        jdbc.update("DELETE FROM company WHERE company_id = ?", COMPANY_ID);

        jdbc.update("INSERT INTO company (company_id, code, name, status, created_at) VALUES (?, ?, ?, 'ACTIVE', ?)",
            COMPANY_ID, "TEST-COMP-096", "HTTP排除测试公司", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        jdbc.update("INSERT INTO attendance_source (attendance_source_id, company_id, source_code, source_type, " +
            "display_name, status, created_by, created_at) VALUES (?, ?, ?, 'OA_ATTENDANCE', ?, 'ACTIVE', ?, ?)",
            SHARED_SOURCE_ID, COMPANY_ID, "OA-TEST-096", "测试OA源", ACTOR_PRINCIPAL_ID,
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        jdbc.update("INSERT INTO employee (employee_id, company_id, display_name, employment_status) " +
            "VALUES (?, ?, ?, 'ACTIVE')", EMPLOYEE_ID, COMPANY_ID, "HTTP排除测试员工");
        jdbc.update("INSERT INTO employee_version (employee_version_id, employee_id, employee_number, " +
            "display_name, status, effective_from, effective_to, source_authority, created_at, created_by) " +
            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL, 'TEST', ?, 'SYSTEM')",
            EMPLOYEE_VERSION_ID, EMPLOYEE_ID, "EMP-096", "HTTP排除测试员工",
            java.sql.Date.valueOf("2026-01-01"), Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        jdbc.update("INSERT INTO employee_current_projection (employee_id, current_version_id, projected_at) " +
            "VALUES (?, ?, ?)", EMPLOYEE_ID, EMPLOYEE_VERSION_ID, Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO auth_principal (principal_id, employee_id, status, created_at, row_version) " +
            "VALUES (?, ?, 'ACTIVE', ?, 0)", ACTOR_PRINCIPAL_ID, EMPLOYEE_ID, Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO auth_capability (capability_id, capability_code, permission_domain, action_code) " +
            "VALUES (?, 'ATTENDANCE_REPORT:REFRESH', 'ATTENDANCE_REPORT', 'REFRESH')", CAPABILITY_ID);
        jdbc.update("INSERT INTO auth_role (role_id, role_code, role_name, permission_domain) " +
            "VALUES (?, 'HR_ADMIN_TEST_096', 'HR管理员(HTTP排除测试)', 'ATTENDANCE_REPORT')", ROLE_ID);
        jdbc.update("INSERT INTO auth_role_capability (role_id, capability_id) VALUES (?, ?)",
            ROLE_ID, CAPABILITY_ID);
        jdbc.update("INSERT INTO auth_data_scope (scope_id, scope_type, company_id, organization_id, " +
            "include_descendants, valid_from, valid_to) VALUES (?, 'COMPANY', ?, NULL, TRUE, ?, NULL)",
            SCOPE_ID, COMPANY_ID, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        jdbc.update("INSERT INTO auth_principal_role_assignment (assignment_id, principal_id, role_id, " +
            "data_scope_id, valid_from, valid_to, assigned_by, reason, row_version) " +
            "VALUES (?, ?, ?, ?, ?, NULL, 'SYSTEM', 'V74 HTTP TEST', 0)",
            UUID.randomUUID().toString(), ACTOR_PRINCIPAL_ID, ROLE_ID, SCOPE_ID,
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));

        // Short-version OA chain (used by baseline and missingAuthority/outOfScope tests)
        insertOaChain(RAW_ID_1, NORMALIZED_ID_1, OA_DOC_ID_1,
            "唐正全:LEAVE:20260907:096", "v20260907-096",
            Instant.parse("2026-09-06T16:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"),
            Instant.parse("2026-09-06T03:00:00Z"));

        // Long-version OA chain (used by longSourceVersion test, same interval)
        insertOaChain(LONG_RAW_ID, LONG_NORMALIZED_ID, LONG_OA_DOC_ID,
            "唐正全:LEAVE:20260907:096L", LONG_SOURCE_VERSION,
            Instant.parse("2026-09-06T16:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"),
            Instant.parse("2026-09-06T04:00:00Z"));
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
            "resolver-096", Timestamp.from(receivedAt.plusSeconds(360)));
        jdbc.update("INSERT INTO oa_attendance_document (oa_attendance_document_id, " +
            "normalized_attendance_record_id, attendance_source_id, source_business_key, " +
            "source_version, knowledge_rank, document_type, source_status, " +
            "created_at, leave_type, leave_serial) " +
            "VALUES (?, ?, ?, ?, ?, 1, 'LEAVE', 'APPROVED', ?, 'ANNUAL', ?)",
            docId, normId, SHARED_SOURCE_ID, businessKey, sourceVersion,
            Timestamp.from(receivedAt.plusSeconds(600)),
            "LEAVE-20260907-" + docId.substring(0, 8));
        jdbc.update("INSERT INTO oa_attendance_document_context (oa_attendance_document_id, " +
            "activation_decision, overtime_type, created_at) VALUES (?, 'NOT_APPLICABLE', NULL, ?)",
            docId, Timestamp.from(receivedAt.plusSeconds(660)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ helpers

    private String confirmText() {
        return "唐正全 2026年9月7日年假已确认，需排除考勤计算(HTTP)";
    }

    private String excludeBody(String requestId, String exclusionId, String docId,
            String businessKey, String sourceVersion) {
        String ct = confirmText();
        return """
            {
              "requestId": "%s",
              "exclusionId": "%s",
              "companyId": "%s",
              "employeeId": "%s",
              "sourceBusinessKey": "%s",
              "sourceVersion": "%s",
              "oaAttendanceDocumentId": "%s",
              "expectedBusinessDate": "2026-09-07",
              "expectedIntervalStart": "2026-09-06T16:00:00Z",
              "expectedIntervalEnd": "2026-09-07T16:00:00Z",
              "reason": "唐正全年假排除",
              "userConfirmationText": "%s",
              "userConfirmationSha256": "%s",
              "evidenceSha256": "%s"
            }
            """.formatted(requestId, exclusionId, COMPANY_ID, EMPLOYEE_ID,
                businessKey, sourceVersion, docId,
                ct, computeSha256(ct), computeSha256("evidence-http-096"));
    }

    /** Convenience overload using the short-version fixture. */
    private String excludeBody(String requestId) {
        return excludeBody(requestId, EXCLUSION_ID_1, OA_DOC_ID_1,
            "唐正全:LEAVE:20260907:096", "v20260907-096");
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
    void excludePost_200_noStore_fiveFieldResponse() throws Exception {
        mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(excludeBody(UUID.randomUUID().toString())))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.exclusionId").value(EXCLUSION_ID_1))
            .andExpect(jsonPath("$.knowledgeAt").exists())
            .andExpect(jsonPath("$.exclusionDigest").isString())
            .andExpect(jsonPath("$.reversedAt").doesNotExist())
            .andExpect(jsonPath("$.reversedBy").doesNotExist());

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            Long.class, EXCLUSION_ID_1)).isEqualTo(1L);
    }

    @Test
    void excludePost_replay_returnsSameBody() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String body = excludeBody(requestId);

        String firstResponse = mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String replayResponse = mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(replayResponse).isEqualTo(firstResponse);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            Long.class, EXCLUSION_ID_1)).isEqualTo(1L);
    }

    @Test
    void excludePost_missingAuthority_returns403_noDbRow() throws Exception {
        // SessionAuthenticationFilter re-hydrates capabilities from DB, so the DB edge must be
        // removed — altering only the MockMvc authority list has no effect.
        try {
            jdbc.update("DELETE FROM auth_role_capability WHERE role_id = ? AND capability_id = ?",
                ROLE_ID, CAPABILITY_ID);

            mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion")
                    .with(user(ACTOR_PRINCIPAL_ID).authorities(
                        new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                    .header("X-CSRF-TOKEN", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(excludeBody(UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());

            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                Long.class, EXCLUSION_ID_1)).isEqualTo(0L);
        } finally {
            jdbc.update("INSERT INTO auth_role_capability (role_id, capability_id) VALUES (?, ?)",
                ROLE_ID, CAPABILITY_ID);
        }
    }

    @Test
    void excludePost_employeeOutOfScope_returns404_noDbRow() throws Exception {
        // Temporarily redirect SCOPE_ID to a different company so EMPLOYEE_ID is out of range.
        // ResourceNotAvailableAccessDeniedException -> ApiExceptionHandler -> 404 RESOURCE_NOT_AVAILABLE.
        String outsideCompanyId = "c0000096-0000-0000-ffff-000000000096";
        try {
            jdbc.update("INSERT INTO company (company_id, code, name, status, created_at) " +
                "VALUES (?, ?, ?, 'ACTIVE', ?)",
                outsideCompanyId, "OUTSIDE-096", "范围外公司",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
            jdbc.update("UPDATE auth_data_scope SET company_id = ? WHERE scope_id = ?",
                outsideCompanyId, SCOPE_ID);

            mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion")
                    .with(user(ACTOR_PRINCIPAL_ID).authorities(
                        new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                    .header("X-CSRF-TOKEN", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(excludeBody(UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());

            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                Long.class, EXCLUSION_ID_1)).isEqualTo(0L);
        } finally {
            jdbc.update("UPDATE auth_data_scope SET company_id = ? WHERE scope_id = ?",
                COMPANY_ID, SCOPE_ID);
            jdbc.update("DELETE FROM company WHERE company_id = ?", outsideCompanyId);
        }
    }

    @Test
    void excludePost_longSourceVersion_200_persistedExact() throws Exception {
        assertThat(LONG_SOURCE_VERSION.length()).isEqualTo(78);

        String requestId = UUID.randomUUID().toString();
        String body = excludeBody(requestId, LONG_EXCLUSION_ID, LONG_OA_DOC_ID,
            "唐正全:LEAVE:20260907:096L", LONG_SOURCE_VERSION);

        mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.exclusionId").value(LONG_EXCLUSION_ID));

        String persistedVersion = jdbc.queryForObject(
            "SELECT source_version FROM attendance_report_oa_evidence_exclusion " +
            "WHERE exclusion_id = ?", String.class, LONG_EXCLUSION_ID);
        assertThat(persistedVersion).isEqualTo(LONG_SOURCE_VERSION);
        assertThat(persistedVersion.length()).isEqualTo(78);

        // Exact idempotent replay returns same body
        String firstBody = mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String replayBody = mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion")
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
    void excludePost_missingCsrfToken_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                // no X-CSRF-TOKEN header — SessionCsrfFilter rejects
                .contentType(MediaType.APPLICATION_JSON)
                .content(excludeBody(UUID.randomUUID().toString())))
            .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            Long.class, EXCLUSION_ID_1)).isEqualTo(0L);
    }

    // ---- Confirmed Source Deletion endpoint tests (new) ----

    private static final String OT_EXCLUSION_ID = UUID.randomUUID().toString();
    private static final String OT_DOC_ID = UUID.randomUUID().toString();
    private static final String OT_RAW_ID = UUID.randomUUID().toString();
    private static final String OT_NORMALIZED_ID = UUID.randomUUID().toString();

    private String confirmedDeletionBody(String requestId, String exclusionId, String docId,
            String businessKey, String sourceVersion, Instant intervalStart, Instant intervalEnd) {
        String ct = "确认源已删除";
        return """
            {
              "requestId": "%s",
              "exclusionId": "%s",
              "companyId": "%s",
              "employeeId": "%s",
              "sourceBusinessKey": "%s",
              "sourceVersion": "%s",
              "oaAttendanceDocumentId": "%s",
              "expectedIntervalStart": "%s",
              "expectedIntervalEnd": "%s",
              "reason": "Confirmed OA source deletion",
              "userConfirmationText": "%s",
              "userConfirmationSha256": "%s",
              "evidenceSha256": "%s"
            }
            """.formatted(requestId, exclusionId, COMPANY_ID, EMPLOYEE_ID,
                businessKey, sourceVersion, docId,
                intervalStart.toString(), intervalEnd.toString(),
                ct, computeSha256(ct), computeSha256("ot-evidence"));
    }

    @Test
    void confirmedSourceDeletionPost_overtimePending_success() throws Exception {
        // Setup OVERTIME DRAFT cross-month fixture (DRAFT is valid in test schema)
        String otBusinessKey = "OVERTIME:9988776655";
        String otVersion = "v20260928-ot";
        Instant otStart = Instant.parse("2026-09-28T02:30:00Z");
        Instant otEnd = Instant.parse("2026-10-28T03:30:00Z");

        jdbc.update("DELETE FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?", OT_EXCLUSION_ID);
        jdbc.update("DELETE FROM oa_attendance_document_context WHERE oa_attendance_document_id = ?", OT_DOC_ID);
        jdbc.update("DELETE FROM oa_attendance_document WHERE oa_attendance_document_id = ?", OT_DOC_ID);
        jdbc.update("DELETE FROM employee_match_decision WHERE normalized_attendance_record_id = ?", OT_NORMALIZED_ID);
        jdbc.update("DELETE FROM normalized_attendance_record WHERE normalized_attendance_record_id = ?", OT_NORMALIZED_ID);
        jdbc.update("DELETE FROM raw_attendance_fact WHERE raw_attendance_fact_id = ?", OT_RAW_ID);

        jdbc.update("INSERT INTO raw_attendance_fact (raw_attendance_fact_id, attendance_source_id, " +
            "company_id, source_business_key, source_version, canonical_payload_digest, received_at, " +
            "fact_kind, interval_start, interval_end) VALUES (?, ?, ?, ?, ?, ?, ?, 'OA_DOCUMENT', ?, ?)",
            OT_RAW_ID, SHARED_SOURCE_ID, COMPANY_ID, otBusinessKey, otVersion,
            computeSha256("raw-ot"), Timestamp.from(Instant.parse("2026-09-28T01:00:00Z")),
            Timestamp.from(otStart), Timestamp.from(otEnd));
        jdbc.update("INSERT INTO normalized_attendance_record (normalized_attendance_record_id, " +
            "raw_attendance_fact_id, normalization_revision, validation_status, " +
            "interval_start, interval_end, canonical_digest, created_at, schema_version, record_kind) " +
            "VALUES (?, ?, 1, 'VALID', ?, ?, ?, ?, 'V1', 'OA_INTERVAL')",
            OT_NORMALIZED_ID, OT_RAW_ID, Timestamp.from(otStart), Timestamp.from(otEnd),
            computeSha256("norm-ot"), Timestamp.from(Instant.parse("2026-09-28T01:05:00Z")));
        jdbc.update("INSERT INTO employee_match_decision (normalized_attendance_record_id, employee_id, " +
            "employment_period_id, resolver_snapshot_digest, match_status, created_at) " +
            "VALUES (?, ?, ?, ?, 'MATCHED', ?)",
            OT_NORMALIZED_ID, EMPLOYEE_ID, UUID.randomUUID().toString(),
            "resolver-ot", Timestamp.from(Instant.parse("2026-09-28T01:06:00Z")));
        jdbc.update("INSERT INTO oa_attendance_document (oa_attendance_document_id, " +
            "normalized_attendance_record_id, attendance_source_id, source_business_key, " +
            "source_version, knowledge_rank, document_type, source_status, created_at) " +
            "VALUES (?, ?, ?, ?, ?, 1, 'OVERTIME', 'DRAFT', ?)",
            OT_DOC_ID, OT_NORMALIZED_ID, SHARED_SOURCE_ID, otBusinessKey, otVersion,
            Timestamp.from(Instant.parse("2026-09-28T01:10:00Z")));
        jdbc.update("INSERT INTO oa_attendance_document_context (oa_attendance_document_id, " +
            "activation_decision, overtime_type, created_at) VALUES (?, 'ACTIVATED', 'PAID', ?)",
            OT_DOC_ID, Timestamp.from(Instant.parse("2026-09-28T01:11:00Z")));

        // Mock the source port to return ABSENT
        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(9988776655L)))
            .thenReturn(OvertimeDocumentPresence.ABSENT);

        String requestId = UUID.randomUUID().toString();
        String body = confirmedDeletionBody(requestId, OT_EXCLUSION_ID, OT_DOC_ID,
            otBusinessKey, otVersion, otStart, otEnd);

        mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion/confirmed-source-deletion")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.exclusionId").value(OT_EXCLUSION_ID))
            .andExpect(jsonPath("$.knowledgeAt").exists())
            .andExpect(jsonPath("$.reversedAt").doesNotExist());

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            Long.class, OT_EXCLUSION_ID)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE action_code = ? AND resource_id_ref = ?",
            Long.class, "CREATE_OA_EVIDENCE_EXCLUSION_CONFIRMED_SOURCE_DELETION", OT_EXCLUSION_ID))
            .isEqualTo(1L);
    }

    @Test
    void confirmedSourceDeletionPost_idempotentReplay_sameResponse() throws Exception {
        String otBusinessKey = "OVERTIME:7766554433";
        String otVersion = "v20260930-replay";
        Instant otStart = Instant.parse("2026-09-30T02:00:00Z");
        Instant otEnd = Instant.parse("2026-10-30T03:00:00Z");
        String replayExclusionId = UUID.randomUUID().toString();
        String replayDocId = UUID.randomUUID().toString();
        String replayRawId = UUID.randomUUID().toString();
        String replayNormId = UUID.randomUUID().toString();

        jdbc.update("DELETE FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?", replayExclusionId);
        jdbc.update("DELETE FROM oa_attendance_document_context WHERE oa_attendance_document_id = ?", replayDocId);
        jdbc.update("DELETE FROM oa_attendance_document WHERE oa_attendance_document_id = ?", replayDocId);
        jdbc.update("DELETE FROM employee_match_decision WHERE normalized_attendance_record_id = ?", replayNormId);
        jdbc.update("DELETE FROM normalized_attendance_record WHERE normalized_attendance_record_id = ?", replayNormId);
        jdbc.update("DELETE FROM raw_attendance_fact WHERE raw_attendance_fact_id = ?", replayRawId);

        jdbc.update("INSERT INTO raw_attendance_fact (raw_attendance_fact_id, attendance_source_id, " +
            "company_id, source_business_key, source_version, canonical_payload_digest, received_at, " +
            "fact_kind, interval_start, interval_end) VALUES (?, ?, ?, ?, ?, ?, ?, 'OA_DOCUMENT', ?, ?)",
            replayRawId, SHARED_SOURCE_ID, COMPANY_ID, otBusinessKey, otVersion,
            computeSha256("replay-raw"), Timestamp.from(Instant.parse("2026-09-30T01:00:00Z")),
            Timestamp.from(otStart), Timestamp.from(otEnd));
        jdbc.update("INSERT INTO normalized_attendance_record (normalized_attendance_record_id, " +
            "raw_attendance_fact_id, normalization_revision, validation_status, " +
            "interval_start, interval_end, canonical_digest, created_at, schema_version, record_kind) " +
            "VALUES (?, ?, 1, 'VALID', ?, ?, ?, ?, 'V1', 'OA_INTERVAL')",
            replayNormId, replayRawId, Timestamp.from(otStart), Timestamp.from(otEnd),
            computeSha256("replay-norm"), Timestamp.from(Instant.parse("2026-09-30T01:05:00Z")));
        jdbc.update("INSERT INTO employee_match_decision (normalized_attendance_record_id, employee_id, " +
            "employment_period_id, resolver_snapshot_digest, match_status, created_at) " +
            "VALUES (?, ?, ?, ?, 'MATCHED', ?)",
            replayNormId, EMPLOYEE_ID, UUID.randomUUID().toString(),
            "replay-resolver", Timestamp.from(Instant.parse("2026-09-30T01:06:00Z")));
        jdbc.update("INSERT INTO oa_attendance_document (oa_attendance_document_id, " +
            "normalized_attendance_record_id, attendance_source_id, source_business_key, " +
            "source_version, knowledge_rank, document_type, source_status, created_at) " +
            "VALUES (?, ?, ?, ?, ?, 1, 'OVERTIME', 'DRAFT', ?)",
            replayDocId, replayNormId, SHARED_SOURCE_ID, otBusinessKey, otVersion,
            Timestamp.from(Instant.parse("2026-09-30T01:10:00Z")));
        jdbc.update("INSERT INTO oa_attendance_document_context (oa_attendance_document_id, " +
            "activation_decision, overtime_type, created_at) VALUES (?, 'ACTIVATED', 'PAID', ?)",
            replayDocId, Timestamp.from(Instant.parse("2026-09-30T01:11:00Z")));

        // Mock the source port to return ABSENT
        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(7766554433L)))
            .thenReturn(OvertimeDocumentPresence.ABSENT);

        String requestId = UUID.randomUUID().toString();
        String body = confirmedDeletionBody(requestId, replayExclusionId, replayDocId,
            otBusinessKey, otVersion, otStart, otEnd);

        String firstResponse = mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion/confirmed-source-deletion")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String replayResponse = mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion/confirmed-source-deletion")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                .header("X-CSRF-TOKEN", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(replayResponse).isEqualTo(firstResponse);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            Long.class, replayExclusionId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE action_code = ? AND resource_id_ref = ?",
            Long.class, "CREATE_OA_EVIDENCE_EXCLUSION_CONFIRMED_SOURCE_DELETION", replayExclusionId))
            .isEqualTo(1L);
    }

    @Test
    void confirmedSourceDeletionPost_missingAuthority_returns403() throws Exception {
        try {
            jdbc.update("DELETE FROM auth_role_capability WHERE role_id = ? AND capability_id = ?",
                ROLE_ID, CAPABILITY_ID);

            String otBusinessKey = "OVERTIME:4433221100";
            String otVersion = "v20261001-noauth";
            Instant otStart = Instant.parse("2026-10-01T02:00:00Z");
            Instant otEnd = Instant.parse("2026-11-01T03:00:00Z");
            String noauthExclusionId = UUID.randomUUID().toString();
            String noauthDocId = UUID.randomUUID().toString();

            when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(4433221100L)))
                .thenReturn(OvertimeDocumentPresence.ABSENT);

            String requestId = UUID.randomUUID().toString();
            String body = confirmedDeletionBody(requestId, noauthExclusionId, noauthDocId,
                otBusinessKey, otVersion, otStart, otEnd);

            mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion/confirmed-source-deletion")
                    .with(user(ACTOR_PRINCIPAL_ID).authorities(
                        new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                    .header("X-CSRF-TOKEN", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isForbidden());

            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                Long.class, noauthExclusionId)).isEqualTo(0L);
        } finally {
            jdbc.update("INSERT INTO auth_role_capability (role_id, capability_id) VALUES (?, ?)",
                ROLE_ID, CAPABILITY_ID);
        }
    }

    @Test
    void confirmedSourceDeletionPost_employeeOutOfScope_returns404() throws Exception {
        String outsideCompanyId = "c0000096-ffff-ffff-ffff-000000000096";
        try {
            jdbc.update("INSERT INTO company (company_id, code, name, status, created_at) " +
                "VALUES (?, ?, ?, 'ACTIVE', ?)",
                outsideCompanyId, "OUTSIDE-SCOPE", "范围外公司",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
            jdbc.update("UPDATE auth_data_scope SET company_id = ? WHERE scope_id = ?",
                outsideCompanyId, SCOPE_ID);

            String otBusinessKey = "OVERTIME:1122334455";
            String otVersion = "v20261002-outofscope";
            Instant otStart = Instant.parse("2026-10-02T02:00:00Z");
            Instant otEnd = Instant.parse("2026-11-02T03:00:00Z");
            String oosExclusionId = UUID.randomUUID().toString();
            String oosDocId = UUID.randomUUID().toString();

            when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(1122334455L)))
                .thenReturn(OvertimeDocumentPresence.ABSENT);

            String requestId = UUID.randomUUID().toString();
            String body = confirmedDeletionBody(requestId, oosExclusionId, oosDocId,
                otBusinessKey, otVersion, otStart, otEnd);

            mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion/confirmed-source-deletion")
                    .with(user(ACTOR_PRINCIPAL_ID).authorities(
                        new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                    .header("X-CSRF-TOKEN", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isNotFound());

            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                Long.class, oosExclusionId)).isEqualTo(0L);
        } finally {
            jdbc.update("UPDATE auth_data_scope SET company_id = ? WHERE scope_id = ?",
                COMPANY_ID, SCOPE_ID);
            jdbc.update("DELETE FROM company WHERE company_id = ?", outsideCompanyId);
        }
    }

    @Test
    void confirmedSourceDeletionPost_missingCsrfToken_returns403() throws Exception {
        String otBusinessKey = "OVERTIME:5544332211";
        String otVersion = "v20261003-nocsrf";
        Instant otStart = Instant.parse("2026-10-03T02:00:00Z");
        Instant otEnd = Instant.parse("2026-11-03T03:00:00Z");
        String nocsrfExclusionId = UUID.randomUUID().toString();
        String nocsrfDocId = UUID.randomUUID().toString();

        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(5544332211L)))
            .thenReturn(OvertimeDocumentPresence.ABSENT);

        String requestId = UUID.randomUUID().toString();
        String body = confirmedDeletionBody(requestId, nocsrfExclusionId, nocsrfDocId,
            otBusinessKey, otVersion, otStart, otEnd);

        mockMvc.perform(post("/api/v1/attendance/oa-evidence-exclusion/confirmed-source-deletion")
                .with(user(ACTOR_PRINCIPAL_ID).authorities(
                    new SimpleGrantedAuthority("ATTENDANCE_REPORT:REFRESH")))
                // no X-CSRF-TOKEN header
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            Long.class, nocsrfExclusionId)).isEqualTo(0L);
    }
}
