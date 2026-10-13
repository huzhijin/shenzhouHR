package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.OaEvidenceExclusionWriteService.ExclusionIntent;
import com.szsemicon.hr.reporting.application.OaEvidenceExclusionWriteService.ExclusionReceipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2 integration test for OaEvidenceExclusionWriteService (V74).
 * Tests real MyBatis, AuditService, Spring @Transactional boundaries.
 *
 * Uses real service API: ExclusionIntent(16 params), ExclusionReceipt(5 fields).
 * Fixed Clock for DATETIME(6) microsecond round-trip consistency.
 */
@SpringBootTest
@ActiveProfiles("test")
class OaEvidenceExclusionWriteServiceH2IntegrationTest {

    @TestConfiguration
    static class TestClockConfiguration {
        @Bean
        @Primary
        public Clock testClock() {
            // Fixed Clock with nanoseconds to verify service truncates to MICROS
            return Clock.fixed(
                Instant.parse("2026-10-08T09:00:00.987654321Z"),
                ZoneOffset.UTC
            );
        }
    }

    @Autowired
    private OaEvidenceExclusionWriteService service;

    @Autowired
    private JdbcTemplate jdbc;

    // Fixed source ID from Service
    private static final String SHARED_SOURCE_ID = "e05866a7-97b0-11f1-8924-00155d09fd0e";
    private static final String COMPANY_ID = "c0000098-0000-0000-0000-000000000098";
    private static final String EMPLOYEE_ID = "e0000098-0000-0000-0000-000000000098";
    private static final String ACTOR_PRINCIPAL_ID = "p0000098-0000-0000-0000-000000000098";
    private static final String EXCLUSION_ID_1 = UUID.randomUUID().toString();
    private static final String OA_DOC_ID_1 = UUID.randomUUID().toString();
    private static final String ROLE_ID = "r0000098-0000-0000-0000-000000000098";
    private static final String CAPABILITY_ID = "cap00098-0000-0000-0000-000000000098";
    private static final String SCOPE_ID = "s0000098-0000-0000-0000-000000000098";
    private static final String EMPLOYEE_VERSION_ID = "ev000098-0000-0000-0000-000000000098";
    private static final String RAW_ID_1 = UUID.randomUUID().toString();
    private static final String NORMALIZED_ID_1 = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() throws Exception {
        // Clean only this test's data
        jdbc.update("DELETE FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            EXCLUSION_ID_1);
        jdbc.update("DELETE FROM audit_event WHERE resource_type = 'attendance_report_oa_evidence_exclusion' " +
            "AND resource_id_ref = ?", EXCLUSION_ID_1);
        jdbc.update("DELETE FROM oa_attendance_document_context WHERE oa_attendance_document_id = ?", OA_DOC_ID_1);
        jdbc.update("DELETE FROM employee_match_decision WHERE normalized_attendance_record_id = ?", NORMALIZED_ID_1);
        jdbc.update("DELETE FROM oa_attendance_document WHERE oa_attendance_document_id = ?", OA_DOC_ID_1);
        jdbc.update("DELETE FROM normalized_attendance_record WHERE normalized_attendance_record_id = ?", NORMALIZED_ID_1);
        jdbc.update("DELETE FROM raw_attendance_fact WHERE raw_attendance_fact_id = ?", RAW_ID_1);
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

        // Insert company
        jdbc.update(
            "INSERT INTO company (company_id, code, name, status, created_at) " +
            "VALUES (?, ?, ?, 'ACTIVE', ?)",
            COMPANY_ID, "TEST-COMP-098", "唐正全测试公司",
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        // Insert shared OA source with ACTIVE status
        jdbc.update(
            "INSERT INTO attendance_source (attendance_source_id, company_id, source_code, source_type, " +
            "display_name, status, created_by, created_at) VALUES (?, ?, ?, 'OA_ATTENDANCE', ?, 'ACTIVE', ?, ?)",
            SHARED_SOURCE_ID, COMPANY_ID, "OA-TEST-098", "测试OA源", ACTOR_PRINCIPAL_ID,
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        // Insert employee
        jdbc.update(
            "INSERT INTO employee (employee_id, company_id, display_name, employment_status) " +
            "VALUES (?, ?, ?, 'ACTIVE')",
            EMPLOYEE_ID, COMPANY_ID, "唐正全(测试)"
        );

        // Insert employee_version
        jdbc.update(
            "INSERT INTO employee_version (employee_version_id, employee_id, employee_number, " +
            "display_name, status, effective_from, effective_to, source_authority, created_at, created_by) " +
            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL, 'TEST', ?, 'SYSTEM')",
            EMPLOYEE_VERSION_ID, EMPLOYEE_ID, "EMP-098", "唐正全(测试)",
            java.sql.Date.valueOf("2026-01-01"),
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        // Insert employee_current_projection
        jdbc.update(
            "INSERT INTO employee_current_projection (employee_id, current_version_id, projected_at) " +
            "VALUES (?, ?, ?)",
            EMPLOYEE_ID, EMPLOYEE_VERSION_ID, Timestamp.from(Instant.now())
        );

        // Insert actor principal
        jdbc.update(
            "INSERT INTO auth_principal (principal_id, employee_id, status, created_at, row_version) " +
            "VALUES (?, ?, 'ACTIVE', ?, 0)",
            ACTOR_PRINCIPAL_ID, EMPLOYEE_ID, Timestamp.from(Instant.now())
        );

        // Insert ATTENDANCE_REPORT:REFRESH capability
        jdbc.update(
            "INSERT INTO auth_capability (capability_id, capability_code, permission_domain, action_code) " +
            "VALUES (?, 'ATTENDANCE_REPORT:REFRESH', 'ATTENDANCE_REPORT', 'REFRESH')",
            CAPABILITY_ID
        );

        // Insert role
        jdbc.update(
            "INSERT INTO auth_role (role_id, role_code, role_name, permission_domain) " +
            "VALUES (?, 'HR_ADMIN_TEST', 'HR管理员(测试)', 'ATTENDANCE_REPORT')",
            ROLE_ID
        );

        // Assign capability to role
        jdbc.update(
            "INSERT INTO auth_role_capability (role_id, capability_id) VALUES (?, ?)",
            ROLE_ID, CAPABILITY_ID
        );

        // Create COMPANY data scope
        jdbc.update(
            "INSERT INTO auth_data_scope (scope_id, scope_type, company_id, organization_id, " +
            "include_descendants, valid_from, valid_to) " +
            "VALUES (?, 'COMPANY', ?, NULL, TRUE, ?, NULL)",
            SCOPE_ID, COMPANY_ID, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        // Assign role to actor
        jdbc.update(
            "INSERT INTO auth_principal_role_assignment (assignment_id, principal_id, role_id, " +
            "data_scope_id, valid_from, valid_to, assigned_by, reason, row_version) " +
            "VALUES (?, ?, ?, ?, ?, NULL, 'SYSTEM', 'V74 TEST SETUP', 0)",
            UUID.randomUUID().toString(), ACTOR_PRINCIPAL_ID, ROLE_ID, SCOPE_ID,
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        // Set up SecurityContext
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                ACTOR_PRINCIPAL_ID, "test", List.of())
        );

        // Insert real OA observation chain for Tang Zhengquan 9/7 ANNUAL LEAVE exclusion
        // Business key: 唐正全:LEAVE:20260907:001
        // Interval: 2026-09-06 16:00:00Z ~ 2026-09-07 16:00:00Z (Shanghai full day 9/7)
        String businessKey = "唐正全:LEAVE:20260907:001";
        String sourceVersion = "v20260907-tang";
        Instant intervalStart = Instant.parse("2026-09-06T16:00:00Z");
        Instant intervalEnd = Instant.parse("2026-09-07T16:00:00Z");

        // raw_attendance_fact
        jdbc.update(
            "INSERT INTO raw_attendance_fact (raw_attendance_fact_id, attendance_source_id, " +
            "company_id, source_business_key, source_version, canonical_payload_digest, received_at, " +
            "fact_kind, interval_start, interval_end) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 'OA_DOCUMENT', ?, ?)",
            RAW_ID_1, SHARED_SOURCE_ID, COMPANY_ID, businessKey, sourceVersion,
            computeSha256("raw-payload-tang-annual-leave"),
            Timestamp.from(Instant.parse("2026-09-06T03:00:00Z")),
            Timestamp.from(intervalStart), Timestamp.from(intervalEnd)
        );

        // normalized_attendance_record
        jdbc.update(
            "INSERT INTO normalized_attendance_record (normalized_attendance_record_id, " +
            "raw_attendance_fact_id, normalization_revision, validation_status, " +
            "interval_start, interval_end, canonical_digest, created_at, schema_version, record_kind) " +
            "VALUES (?, ?, 1, 'VALID', ?, ?, ?, ?, 'V1', 'OA_INTERVAL')",
            NORMALIZED_ID_1, RAW_ID_1,
            Timestamp.from(intervalStart), Timestamp.from(intervalEnd),
            computeSha256("normalized-tang-annual"),
            Timestamp.from(Instant.parse("2026-09-06T03:05:00Z"))
        );

        // employee_match_decision
        jdbc.update(
            "INSERT INTO employee_match_decision (normalized_attendance_record_id, employee_id, " +
            "employment_period_id, resolver_snapshot_digest, match_status, created_at) " +
            "VALUES (?, ?, ?, ?, 'MATCHED', ?)",
            NORMALIZED_ID_1, EMPLOYEE_ID,
            UUID.randomUUID().toString(),
            "resolver-snapshot-tang",
            Timestamp.from(Instant.parse("2026-09-06T03:06:00Z"))
        );

        // oa_attendance_document (LEAVE type with ANNUAL leave_type)
        jdbc.update(
            "INSERT INTO oa_attendance_document (oa_attendance_document_id, " +
            "normalized_attendance_record_id, attendance_source_id, source_business_key, " +
            "source_version, knowledge_rank, document_type, source_status, " +
            "created_at, leave_type, leave_serial, original_leave_serial) " +
            "VALUES (?, ?, ?, ?, ?, 1, 'LEAVE', 'APPROVED', ?, 'ANNUAL', 'LEAVE-20260907-001', NULL)",
            OA_DOC_ID_1, NORMALIZED_ID_1, SHARED_SOURCE_ID, businessKey, sourceVersion,
            Timestamp.from(Instant.parse("2026-09-06T03:10:00Z"))
        );

        // oa_attendance_document_context (no overtime_type for LEAVE)
        jdbc.update(
            "INSERT INTO oa_attendance_document_context (oa_attendance_document_id, " +
            "activation_decision, overtime_type, created_at) VALUES (?, 'NOT_APPLICABLE', NULL, ?)",
            OA_DOC_ID_1, Timestamp.from(Instant.parse("2026-09-06T03:11:00Z"))
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void firstWriteSuccess_andIdempotentReplay() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String businessKey = "唐正全:LEAVE:20260907:001";
        String sourceVersion = "v20260907-tang";
        String userConfirmationText = "唐正全 2026年9月7日年假已确认，需排除考勤计算";
        String userConfirmationSha256 = computeSha256(userConfirmationText);
        String evidenceSha256 = computeSha256("evidence-tang-annual-leave-approval");

        ExclusionIntent intent = new ExclusionIntent(
            requestId,
            EXCLUSION_ID_1,
            COMPANY_ID,
            EMPLOYEE_ID,
            businessKey,
            sourceVersion,
            OA_DOC_ID_1,
            LocalDate.of(2026, 9, 7),
            Instant.parse("2026-09-06T16:00:00Z"),
            Instant.parse("2026-09-07T16:00:00Z"),
            "唐正全年假排除",
            userConfirmationText,
            userConfirmationSha256,
            evidenceSha256
        );

        // First write
        ExclusionReceipt receipt1 = service.exclude(intent);

        // Verify receipt fields
        assertThat(receipt1.exclusionId()).isEqualTo(EXCLUSION_ID_1);
        assertThat(receipt1.knowledgeAt()).isEqualTo(Instant.parse("2026-10-08T09:00:00.987654Z"));
        assertThat(receipt1.knowledgeAt().getNano() % 1000).isEqualTo(0); // Microsecond precision
        assertThat(receipt1.exclusionDigest()).hasSize(64); // SHA-256 hex
        assertThat(receipt1.reversedAt()).isNull();
        assertThat(receipt1.reversedBy()).isNull();

        // Verify DB persistence
        String dbOaDocId = jdbc.queryForObject(
            "SELECT oa_attendance_document_id FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            String.class,
            EXCLUSION_ID_1
        );
        assertThat(dbOaDocId).isEqualTo(OA_DOC_ID_1);

        // Verify source leave_type from oa_attendance_document
        String sourceLeaveType = jdbc.queryForObject(
            "SELECT leave_type FROM oa_attendance_document WHERE oa_attendance_document_id = ?",
            String.class,
            OA_DOC_ID_1
        );
        assertThat(sourceLeaveType).isEqualTo("ANNUAL");

        Timestamp dbKnowledgeAt = jdbc.queryForObject(
            "SELECT knowledge_at FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            Timestamp.class,
            EXCLUSION_ID_1
        );
        assertThat(dbKnowledgeAt.toInstant()).isEqualTo(receipt1.knowledgeAt());
        assertThat(dbKnowledgeAt.toInstant().getNano() % 1000).isEqualTo(0);

        // Verify audit log
        Long auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE resource_type = 'attendance_report_oa_evidence_exclusion' " +
            "AND resource_id_ref = ? AND action_code = 'CREATE_OA_EVIDENCE_EXCLUSION'",
            Long.class,
            EXCLUSION_ID_1
        );
        assertThat(auditCount).isEqualTo(1);

        // Idempotent replay - exact same intent
        ExclusionReceipt receipt2 = service.exclude(intent);

        // Must return identical receipt (all 5 fields)
        assertThat(receipt2).isEqualTo(receipt1);
        assertThat(receipt2.knowledgeAt().getNano()).isEqualTo(receipt1.knowledgeAt().getNano());

        // Verify no duplicate rows
        Long exclusionCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            Long.class,
            EXCLUSION_ID_1
        );
        assertThat(exclusionCount).isEqualTo(1);

        // Verify audit count unchanged
        Long auditCountAfter = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE resource_type = 'attendance_report_oa_evidence_exclusion' " +
            "AND resource_id_ref = ?",
            Long.class,
            EXCLUSION_ID_1
        );
        assertThat(auditCountAfter).isEqualTo(1);
    }

    @Test
    void reversal_firstSuccess_andIdempotentReplay() throws Exception {
        String confirmText = "唐正全 2026年9月7日年假已确认，需排除考勤计算";
        ExclusionReceipt excReceipt = service.exclude(new ExclusionIntent(
            UUID.randomUUID().toString(), EXCLUSION_ID_1, COMPANY_ID, EMPLOYEE_ID,
            "唐正全:LEAVE:20260907:001", "v20260907-tang", OA_DOC_ID_1,
            LocalDate.of(2026, 9, 7),
            Instant.parse("2026-09-06T16:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"),
            "唐正全年假排除", confirmText,
            computeSha256(confirmText), computeSha256("evidence-tang-annual-leave-approval")));

        OaEvidenceExclusionWriteService.ReversalIntent revIntent =
            new OaEvidenceExclusionWriteService.ReversalIntent(
                UUID.randomUUID().toString(), EXCLUSION_ID_1, COMPANY_ID, EMPLOYEE_ID,
                excReceipt.exclusionDigest(), "唐正全年假排除已撤销");

        ExclusionReceipt revReceipt = service.reverse(revIntent);
        assertThat(revReceipt.reversedAt()).isNotNull();
        assertThat(revReceipt.reversedBy()).isEqualTo(ACTOR_PRINCIPAL_ID);

        assertThat(jdbc.queryForObject(
            "SELECT reversed_at FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            Timestamp.class, EXCLUSION_ID_1)).isNotNull();

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE action_code = 'REVERSE_OA_EVIDENCE_EXCLUSION' " +
            "AND resource_id_ref = ?", Long.class, EXCLUSION_ID_1)).isEqualTo(1L);

        assertThat(service.reverse(revIntent)).isEqualTo(revReceipt);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE action_code = 'REVERSE_OA_EVIDENCE_EXCLUSION' " +
            "AND resource_id_ref = ?", Long.class, EXCLUSION_ID_1)).isEqualTo(1L);
    }

    @Test
    void reversal_sameReversalRequestId_differentReason_throws() throws Exception {
        String confirmText = "唐正全 2026年9月7日年假已确认，需排除考勤计算";
        ExclusionReceipt excReceipt = service.exclude(new ExclusionIntent(
            UUID.randomUUID().toString(), EXCLUSION_ID_1, COMPANY_ID, EMPLOYEE_ID,
            "唐正全:LEAVE:20260907:001", "v20260907-tang", OA_DOC_ID_1,
            LocalDate.of(2026, 9, 7),
            Instant.parse("2026-09-06T16:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"),
            "唐正全年假排除", confirmText,
            computeSha256(confirmText), computeSha256("evidence-tang-annual-leave-approval")));

        String sharedReversalRequestId = UUID.randomUUID().toString();
        OaEvidenceExclusionWriteService.ReversalIntent revIntent1 =
            new OaEvidenceExclusionWriteService.ReversalIntent(
                sharedReversalRequestId, EXCLUSION_ID_1, COMPANY_ID, EMPLOYEE_ID,
                excReceipt.exclusionDigest(), "首次撤销原因");
        service.reverse(revIntent1);

        String firstReversalReason = jdbc.queryForObject(
            "SELECT reversal_reason FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            String.class, EXCLUSION_ID_1);
        assertThat(firstReversalReason).isEqualTo("首次撤销原因");

        // Same reversalRequestId, different reversalReason — must be rejected
        OaEvidenceExclusionWriteService.ReversalIntent revIntent2 =
            new OaEvidenceExclusionWriteService.ReversalIntent(
                sharedReversalRequestId, EXCLUSION_ID_1, COMPANY_ID, EMPLOYEE_ID,
                excReceipt.exclusionDigest(), "修改后撤销原因");

        assertThatThrownBy(() -> service.reverse(revIntent2))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already reversed");

        // DB row must still have first reversal's values
        assertThat(jdbc.queryForObject(
            "SELECT reversal_reason FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            String.class, EXCLUSION_ID_1)).isEqualTo("首次撤销原因");
        assertThat(jdbc.queryForObject(
            "SELECT reversed_by FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            String.class, EXCLUSION_ID_1)).isEqualTo(ACTOR_PRINCIPAL_ID);
    }

    @Test
    void auditFailure_rollsBackBusinessRow() throws Exception {
        String confirmText = "唐正全 2026年9月7日年假已确认，需排除考勤计算";
        String requestId = UUID.randomUUID().toString();

        String constraintName = "test_v74_audit_reject_" + System.currentTimeMillis();
        try {
            jdbc.execute(
                "ALTER TABLE audit_event ADD CONSTRAINT " + constraintName + " " +
                "CHECK (NOT (resource_id_ref = '" + EXCLUSION_ID_1 + "' " +
                "AND action_code = 'CREATE_OA_EVIDENCE_EXCLUSION'))");

            ExclusionIntent intent = new ExclusionIntent(
                requestId, EXCLUSION_ID_1, COMPANY_ID, EMPLOYEE_ID,
                "唐正全:LEAVE:20260907:001", "v20260907-tang", OA_DOC_ID_1,
                LocalDate.of(2026, 9, 7),
                Instant.parse("2026-09-06T16:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"),
                "唐正全年假排除", confirmText,
                computeSha256(confirmText), computeSha256("evidence-tang-annual-leave-approval"));

            assertThatThrownBy(() -> service.exclude(intent))
                .hasMessageContaining(constraintName);

            // Business row must be rolled back
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                Long.class, EXCLUSION_ID_1)).isEqualTo(0L);

            // Audit row must be rolled back
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ? " +
                "AND action_code = 'CREATE_OA_EVIDENCE_EXCLUSION'",
                Long.class, EXCLUSION_ID_1)).isEqualTo(0L);

        } finally {
            jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT IF EXISTS " + constraintName);
        }
    }

    @Test
    void reversal_wrongDigest_throws() throws Exception {
        String confirmText = "唐正全 2026年9月7日年假已确认，需排除考勤计算";
        service.exclude(new ExclusionIntent(
            UUID.randomUUID().toString(), EXCLUSION_ID_1, COMPANY_ID, EMPLOYEE_ID,
            "唐正全:LEAVE:20260907:001", "v20260907-tang", OA_DOC_ID_1,
            LocalDate.of(2026, 9, 7),
            Instant.parse("2026-09-06T16:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"),
            "唐正全年假排除", confirmText,
            computeSha256(confirmText), computeSha256("evidence-tang-annual-leave-approval")));

        // 64 zeros: valid hex, guaranteed != real SHA-256 digest
        String wrongDigest = "0".repeat(64);
        OaEvidenceExclusionWriteService.ReversalIntent revIntent =
            new OaEvidenceExclusionWriteService.ReversalIntent(
                UUID.randomUUID().toString(), EXCLUSION_ID_1, COMPANY_ID, EMPLOYEE_ID,
                wrongDigest, "唐正全年假排除已撤销");

        assertThatThrownBy(() -> service.reverse(revIntent))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Exclusion digest mismatch");

        // Row must not be touched
        assertThat(jdbc.queryForObject(
            "SELECT reversed_at FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            Timestamp.class, EXCLUSION_ID_1)).isNull();
    }

    @Test
    void changedPayload_sameRequestId_throws() throws Exception {
        String requestIdR1 = UUID.randomUUID().toString();
        String confirmText = "唐正全 2026年9月7日年假已确认，需排除考勤计算";

        ExclusionIntent intentA = new ExclusionIntent(
            requestIdR1, EXCLUSION_ID_1, COMPANY_ID, EMPLOYEE_ID,
            "唐正全:LEAVE:20260907:001", "v20260907-tang", OA_DOC_ID_1,
            LocalDate.of(2026, 9, 7),
            Instant.parse("2026-09-06T16:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"),
            "唐正全年假排除", confirmText,
            computeSha256(confirmText), computeSha256("evidence-tang-annual-leave-approval"));
        service.exclude(intentA);

        // Same requestId and exclusionId, different reason
        ExclusionIntent intentB = new ExclusionIntent(
            requestIdR1, EXCLUSION_ID_1, COMPANY_ID, EMPLOYEE_ID,
            "唐正全:LEAVE:20260907:001", "v20260907-tang", OA_DOC_ID_1,
            LocalDate.of(2026, 9, 7),
            Instant.parse("2026-09-06T16:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"),
            "修改后原因", confirmText,
            computeSha256(confirmText), computeSha256("evidence-tang-annual-leave-approval"));

        assertThatThrownBy(() -> service.exclude(intentB))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("reason mismatch in replay");

        // DB count for EXCLUSION_ID_1 must remain 1
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
            Long.class, EXCLUSION_ID_1)).isEqualTo(1L);
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
}
