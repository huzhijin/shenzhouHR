package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.OaEvidenceExclusionWriteService.ConfirmedSourceDeletionIntent;
import com.szsemicon.hr.reporting.application.OaEvidenceExclusionWriteService.ExclusionReceipt;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class OaEvidenceExclusionConfirmedSourceDeletionTest {

    @TestConfiguration
    static class TestClockConfiguration {
        @Bean
        @Primary
        public Clock testClock() {
            return Clock.fixed(
                Instant.parse("2026-10-12T08:00:00.123456Z"),
                ZoneOffset.UTC
            );
        }
    }

    @Autowired
    private OaEvidenceExclusionWriteService service;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private OaAttendanceDocumentSourcePort syntheticOaAttendanceDocumentAdapter;

    private static final String SHARED_SOURCE_ID = "e05866a7-97b0-11f1-8924-00155d09fd0e";
    private static final String COMPANY_ID = UUID.randomUUID().toString();
    private static final String EMPLOYEE_ID = UUID.randomUUID().toString();
    private static final String ACTOR_PRINCIPAL_ID = UUID.randomUUID().toString();
    private static final String EXCLUSION_ID = UUID.randomUUID().toString();
    private static final String OA_DOC_ID = UUID.randomUUID().toString();
    private static final String ROLE_ID = UUID.randomUUID().toString();
    private static final String CAPABILITY_ID = UUID.randomUUID().toString();
    private static final String SCOPE_ID = UUID.randomUUID().toString();
    private static final String EMPLOYEE_VERSION_ID = UUID.randomUUID().toString();
    private static final String RAW_ID = UUID.randomUUID().toString();
    private static final String NORMALIZED_ID = UUID.randomUUID().toString();
    private static final String SOURCE_KEY = "OVERTIME:1088037850126502373";
    private static final long OVERTIME_DETAIL_ID = 1088037850126502373L;
    private static final String SOURCE_VERSION = "2026-10-07T16:37:37:state=0:Asia/Shanghai";
    private static final Instant INTERVAL_START = Instant.parse("2026-09-28T02:30:00Z");
    private static final Instant INTERVAL_END = Instant.parse("2026-10-28T03:30:00Z");

    @BeforeEach
    void setUp() throws Exception {
        // Clean only this test's data with stable IDs
        jdbc.update("DELETE FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?", EXCLUSION_ID);
        jdbc.update("DELETE FROM audit_event WHERE resource_type = 'attendance_report_oa_evidence_exclusion' AND resource_id_ref = ?", EXCLUSION_ID);
        jdbc.update("DELETE FROM oa_attendance_document_context WHERE oa_attendance_document_id = ?", OA_DOC_ID);
        jdbc.update("DELETE FROM employee_match_decision WHERE normalized_attendance_record_id = ?", NORMALIZED_ID);
        jdbc.update("DELETE FROM oa_attendance_document WHERE oa_attendance_document_id = ?", OA_DOC_ID);
        jdbc.update("DELETE FROM normalized_attendance_record WHERE normalized_attendance_record_id = ?", NORMALIZED_ID);
        jdbc.update("DELETE FROM raw_attendance_fact WHERE raw_attendance_fact_id = ?", RAW_ID);
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
            "INSERT INTO company (company_id, code, name, status, created_at) VALUES (?, ?, ?, 'ACTIVE', ?)",
            COMPANY_ID, "TEST-099", "测试公司099", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        // Insert shared OA source
        jdbc.update(
            "INSERT INTO attendance_source (attendance_source_id, company_id, source_code, source_type, " +
            "display_name, status, created_by, created_at) VALUES (?, ?, ?, 'OA_ATTENDANCE', ?, 'ACTIVE', ?, ?)",
            SHARED_SOURCE_ID, COMPANY_ID, "OA-099", "OA源099", ACTOR_PRINCIPAL_ID,
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        // Insert employee
        jdbc.update(
            "INSERT INTO employee (employee_id, company_id, display_name, employment_status) VALUES (?, ?, ?, 'ACTIVE')",
            EMPLOYEE_ID, COMPANY_ID, "员工099"
        );

        // Insert employee_version
        jdbc.update(
            "INSERT INTO employee_version (employee_version_id, employee_id, employee_number, " +
            "display_name, status, effective_from, effective_to, source_authority, created_at, created_by) " +
            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL, 'TEST', ?, 'SYSTEM')",
            EMPLOYEE_VERSION_ID, EMPLOYEE_ID, "EMP-099", "员工099",
            java.sql.Date.valueOf("2026-01-01"), Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        // Insert employee_current_projection
        jdbc.update(
            "INSERT INTO employee_current_projection (employee_id, current_version_id, projected_at) VALUES (?, ?, ?)",
            EMPLOYEE_ID, EMPLOYEE_VERSION_ID, Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"))
        );

        // Insert actor principal
        jdbc.update(
            "INSERT INTO auth_principal (principal_id, employee_id, status, created_at, row_version) VALUES (?, ?, 'ACTIVE', ?, 0)",
            ACTOR_PRINCIPAL_ID, EMPLOYEE_ID, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        // Insert ATTENDANCE_REPORT:REFRESH capability
        jdbc.update(
            "INSERT INTO auth_capability (capability_id, capability_code, permission_domain, action_code) " +
            "VALUES (?, 'ATTENDANCE_REPORT:REFRESH', 'ATTENDANCE_REPORT', 'REFRESH')",
            CAPABILITY_ID
        );

        // Insert role
        jdbc.update(
            "INSERT INTO auth_role (role_id, role_code, role_name, permission_domain) VALUES (?, 'TEST_ROLE_099', '测试角色099', 'ATTENDANCE_REPORT')",
            ROLE_ID
        );

        // Assign capability to role
        jdbc.update("INSERT INTO auth_role_capability (role_id, capability_id) VALUES (?, ?)", ROLE_ID, CAPABILITY_ID);

        // Create COMPANY data scope
        jdbc.update(
            "INSERT INTO auth_data_scope (scope_id, scope_type, company_id, organization_id, " +
            "include_descendants, valid_from, valid_to) VALUES (?, 'COMPANY', ?, NULL, TRUE, ?, NULL)",
            SCOPE_ID, COMPANY_ID, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        // Assign role to actor with valid_from in the past
        jdbc.update(
            "INSERT INTO auth_principal_role_assignment (assignment_id, principal_id, role_id, " +
            "data_scope_id, valid_from, valid_to, assigned_by, reason, row_version) " +
            "VALUES (?, ?, ?, ?, ?, NULL, 'SYSTEM', 'TEST', 0)",
            UUID.randomUUID().toString(), ACTOR_PRINCIPAL_ID, ROLE_ID, SCOPE_ID,
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        // Insert raw_attendance_fact
        jdbc.update(
            "INSERT INTO raw_attendance_fact (raw_attendance_fact_id, attendance_source_id, company_id, " +
            "source_business_key, source_version, canonical_payload_digest, received_at, fact_kind, " +
            "interval_start, interval_end) VALUES (?, ?, ?, ?, ?, ?, ?, 'OA_DOCUMENT', ?, ?)",
            RAW_ID, SHARED_SOURCE_ID, COMPANY_ID, SOURCE_KEY, SOURCE_VERSION,
            sha256("payload-099"), Timestamp.from(Instant.parse("2026-10-07T08:00:00Z")),
            Timestamp.from(INTERVAL_START), Timestamp.from(INTERVAL_END)
        );

        // Insert normalized_attendance_record
        jdbc.update(
            "INSERT INTO normalized_attendance_record (normalized_attendance_record_id, raw_attendance_fact_id, " +
            "normalization_revision, validation_status, interval_start, interval_end, canonical_digest, " +
            "created_at, schema_version, record_kind) VALUES (?, ?, 1, 'VALID', ?, ?, ?, ?, 'V1', 'OA_INTERVAL')",
            NORMALIZED_ID, RAW_ID, Timestamp.from(INTERVAL_START), Timestamp.from(INTERVAL_END),
            sha256("normalized-099"), Timestamp.from(Instant.parse("2026-10-07T08:01:00Z"))
        );

        // Insert employee_match_decision
        jdbc.update(
            "INSERT INTO employee_match_decision (normalized_attendance_record_id, employee_id, " +
            "employment_period_id, resolver_snapshot_digest, match_status, created_at) VALUES (?, ?, ?, ?, 'MATCHED', ?)",
            NORMALIZED_ID, EMPLOYEE_ID, UUID.randomUUID().toString(), "snapshot-099",
            Timestamp.from(Instant.parse("2026-10-07T08:02:00Z"))
        );

        // Insert oa_attendance_document (OVERTIME, UNKNOWN for realistic pending state)
        jdbc.update(
            "INSERT INTO oa_attendance_document (oa_attendance_document_id, normalized_attendance_record_id, " +
            "attendance_source_id, source_business_key, source_version, knowledge_rank, document_type, " +
            "source_status, created_at) VALUES (?, ?, ?, ?, ?, 1, 'OVERTIME', 'UNKNOWN', ?)",
            OA_DOC_ID, NORMALIZED_ID, SHARED_SOURCE_ID, SOURCE_KEY, SOURCE_VERSION,
            Timestamp.from(Instant.parse("2026-10-07T08:03:00Z"))
        );

        // Insert oa_attendance_document_context (ACTIVATED, PAID)
        jdbc.update(
            "INSERT INTO oa_attendance_document_context (oa_attendance_document_id, activation_decision, " +
            "overtime_type, created_at) VALUES (?, 'ACTIVATED', 'PAID', ?)",
            OA_DOC_ID, Timestamp.from(Instant.parse("2026-10-07T08:04:00Z"))
        );

        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(ACTOR_PRINCIPAL_ID, "test", List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void excludeConfirmedSourceDeletion_success() throws Exception {
        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(OVERTIME_DETAIL_ID)))
                .thenReturn(OvertimeDocumentPresence.ABSENT);

        String requestId = UUID.randomUUID().toString();
        String userText = "查不到就是删除了";
        String userSha = sha256(userText);
        String evidenceSha = sha256("test-evidence");

        ConfirmedSourceDeletionIntent intent = new ConfirmedSourceDeletionIntent(
                requestId, EXCLUSION_ID, COMPANY_ID, EMPLOYEE_ID,
                SOURCE_KEY, SOURCE_VERSION, OA_DOC_ID,
                INTERVAL_START, INTERVAL_END,
                "Confirmed source deletion for SZST0764 OVERTIME:1088037850126502373",
                userText, userSha, evidenceSha
        );

        ExclusionReceipt receipt = service.excludeConfirmedSourceDeletion(intent);

        assertThat(receipt.exclusionId()).isEqualTo(EXCLUSION_ID);
        assertThat(receipt.knowledgeAt()).isEqualTo(Instant.parse("2026-10-12T08:00:00.123456Z"));
        assertThat(receipt.reversedAt()).isNull();
        assertThat(receipt.reversedBy()).isNull();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                Integer.class, EXCLUSION_ID);
        assertThat(count).isEqualTo(1);

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action_code = 'CREATE_OA_EVIDENCE_EXCLUSION_CONFIRMED_SOURCE_DELETION' " +
                "AND resource_id_ref = ?", Integer.class, EXCLUSION_ID);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void excludeConfirmedSourceDeletion_rejectsWhenSourcePresent() {
        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(OVERTIME_DETAIL_ID)))
                .thenReturn(OvertimeDocumentPresence.PRESENT);

        String requestId = UUID.randomUUID().toString();
        String userText = "查不到就是删除了";
        String userSha = sha256(userText);
        String evidenceSha = sha256("test-evidence");

        ConfirmedSourceDeletionIntent intent = new ConfirmedSourceDeletionIntent(
                requestId, EXCLUSION_ID, COMPANY_ID, EMPLOYEE_ID,
                SOURCE_KEY, SOURCE_VERSION, OA_DOC_ID,
                INTERVAL_START, INTERVAL_END,
                "Confirmed source deletion for SZST0764",
                userText, userSha, evidenceSha
        );

        assertThatThrownBy(() -> service.excludeConfirmedSourceDeletion(intent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Source overtime document presence check failed: PRESENT");
    }

    @Test
    void excludeConfirmedSourceDeletion_rejectsUnsupported() {
        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(OVERTIME_DETAIL_ID)))
                .thenReturn(OvertimeDocumentPresence.UNSUPPORTED);

        String requestId = UUID.randomUUID().toString();
        String userText = "查不到就是删除了";
        String userSha = sha256(userText);
        String evidenceSha = sha256("test-evidence");

        ConfirmedSourceDeletionIntent intent = new ConfirmedSourceDeletionIntent(
                requestId, EXCLUSION_ID, COMPANY_ID, EMPLOYEE_ID,
                SOURCE_KEY, SOURCE_VERSION, OA_DOC_ID,
                INTERVAL_START, INTERVAL_END,
                "Test reason",
                userText, userSha, evidenceSha
        );

        assertThatThrownBy(() -> service.excludeConfirmedSourceDeletion(intent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNSUPPORTED");
    }

    @Test
    void excludeConfirmedSourceDeletion_rejectsError() {
        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(OVERTIME_DETAIL_ID)))
                .thenReturn(OvertimeDocumentPresence.ERROR);

        String requestId = UUID.randomUUID().toString();
        String userText = "查不到就是删除了";
        String userSha = sha256(userText);
        String evidenceSha = sha256("test-evidence");

        ConfirmedSourceDeletionIntent intent = new ConfirmedSourceDeletionIntent(
                requestId, EXCLUSION_ID, COMPANY_ID, EMPLOYEE_ID,
                SOURCE_KEY, SOURCE_VERSION, OA_DOC_ID,
                INTERVAL_START, INTERVAL_END,
                "Test reason",
                userText, userSha, evidenceSha
        );

        assertThatThrownBy(() -> service.excludeConfirmedSourceDeletion(intent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ERROR");
    }

    @Test
    void excludeConfirmedSourceDeletion_idempotentReplay() throws Exception {
        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(OVERTIME_DETAIL_ID)))
                .thenReturn(OvertimeDocumentPresence.ABSENT);

        String requestId = UUID.randomUUID().toString();
        String userText = "查不到就是删除了";
        String userSha = sha256(userText);
        String evidenceSha = sha256("test-evidence");

        ConfirmedSourceDeletionIntent intent = new ConfirmedSourceDeletionIntent(
                requestId, EXCLUSION_ID, COMPANY_ID, EMPLOYEE_ID,
                SOURCE_KEY, SOURCE_VERSION, OA_DOC_ID,
                INTERVAL_START, INTERVAL_END,
                "Confirmed source deletion",
                userText, userSha, evidenceSha
        );

        ExclusionReceipt receipt1 = service.excludeConfirmedSourceDeletion(intent);
        ExclusionReceipt receipt2 = service.excludeConfirmedSourceDeletion(intent);

        assertThat(receipt1.exclusionId()).isEqualTo(receipt2.exclusionId());
        assertThat(receipt1.knowledgeAt()).isEqualTo(receipt2.knowledgeAt());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                Integer.class, EXCLUSION_ID);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void excludeConfirmedSourceDeletion_rejectsNonOvertimeType() throws Exception {
        jdbc.update("UPDATE oa_attendance_document SET document_type = 'LEAVE' WHERE oa_attendance_document_id = ?",
                OA_DOC_ID);

        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(OVERTIME_DETAIL_ID)))
                .thenReturn(OvertimeDocumentPresence.ABSENT);

        String requestId = UUID.randomUUID().toString();
        String userText = "查不到就是删除了";
        String userSha = sha256(userText);
        String evidenceSha = sha256("test-evidence");

        ConfirmedSourceDeletionIntent intent = new ConfirmedSourceDeletionIntent(
                requestId, EXCLUSION_ID, COMPANY_ID, EMPLOYEE_ID,
                SOURCE_KEY, SOURCE_VERSION, OA_DOC_ID,
                INTERVAL_START, INTERVAL_END,
                "Test reason",
                userText, userSha, evidenceSha
        );

        assertThatThrownBy(() -> service.excludeConfirmedSourceDeletion(intent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Document type not OVERTIME");
    }

    @Test
    void auditFailure_rollsBackConfirmedSourceDeletion() throws Exception {
        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(OVERTIME_DETAIL_ID)))
                .thenReturn(OvertimeDocumentPresence.ABSENT);

        String requestId = UUID.randomUUID().toString();
        String userText = "查不到就是删除了";
        String userSha = sha256(userText);
        String evidenceSha = sha256("test-evidence");
        String constraintName = "test_confirmed_deletion_audit_reject_" + System.currentTimeMillis();

        try {
            jdbc.execute(
                "ALTER TABLE audit_event ADD CONSTRAINT " + constraintName + " " +
                "CHECK (NOT (resource_id_ref = '" + EXCLUSION_ID + "' " +
                "AND action_code = 'CREATE_OA_EVIDENCE_EXCLUSION_CONFIRMED_SOURCE_DELETION'))");

            ConfirmedSourceDeletionIntent intent = new ConfirmedSourceDeletionIntent(
                    requestId, EXCLUSION_ID, COMPANY_ID, EMPLOYEE_ID,
                    SOURCE_KEY, SOURCE_VERSION, OA_DOC_ID,
                    INTERVAL_START, INTERVAL_END,
                    "Confirmed source deletion",
                    userText, userSha, evidenceSha
            );

            assertThatThrownBy(() -> service.excludeConfirmedSourceDeletion(intent))
                    .hasMessageContaining(constraintName);

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                    Long.class, EXCLUSION_ID)).isEqualTo(0L);

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ? " +
                    "AND action_code = 'CREATE_OA_EVIDENCE_EXCLUSION_CONFIRMED_SOURCE_DELETION'",
                    Long.class, EXCLUSION_ID)).isEqualTo(0L);

        } finally {
            jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT IF EXISTS " + constraintName);
        }
    }

    @Test
    void excludeConfirmedSourceDeletion_noWriteOnPresent() throws Exception {
        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(OVERTIME_DETAIL_ID)))
                .thenReturn(OvertimeDocumentPresence.PRESENT);

        String requestId = UUID.randomUUID().toString();
        String userText = "查不到就是删除了";
        String userSha = sha256(userText);
        String evidenceSha = sha256("test-evidence");

        ConfirmedSourceDeletionIntent intent = new ConfirmedSourceDeletionIntent(
                requestId, EXCLUSION_ID, COMPANY_ID, EMPLOYEE_ID,
                SOURCE_KEY, SOURCE_VERSION, OA_DOC_ID,
                INTERVAL_START, INTERVAL_END,
                "Confirmed source deletion",
                userText, userSha, evidenceSha
        );

        assertThatThrownBy(() -> service.excludeConfirmedSourceDeletion(intent))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                Long.class, EXCLUSION_ID)).isEqualTo(0L);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, EXCLUSION_ID)).isEqualTo(0L);
    }

    @Test
    void excludeConfirmedSourceDeletion_noWriteOnError() throws Exception {
        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(OVERTIME_DETAIL_ID)))
                .thenReturn(OvertimeDocumentPresence.ERROR);

        String requestId = UUID.randomUUID().toString();
        String userText = "查不到就是删除了";
        String userSha = sha256(userText);
        String evidenceSha = sha256("test-evidence");

        ConfirmedSourceDeletionIntent intent = new ConfirmedSourceDeletionIntent(
                requestId, EXCLUSION_ID, COMPANY_ID, EMPLOYEE_ID,
                SOURCE_KEY, SOURCE_VERSION, OA_DOC_ID,
                INTERVAL_START, INTERVAL_END,
                "Confirmed source deletion",
                userText, userSha, evidenceSha
        );

        assertThatThrownBy(() -> service.excludeConfirmedSourceDeletion(intent))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                Long.class, EXCLUSION_ID)).isEqualTo(0L);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, EXCLUSION_ID)).isEqualTo(0L);
    }

    @Test
    void excludeConfirmedSourceDeletion_noWriteOnUnsupported() throws Exception {
        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(OVERTIME_DETAIL_ID)))
                .thenReturn(OvertimeDocumentPresence.UNSUPPORTED);

        String requestId = UUID.randomUUID().toString();
        String userText = "查不到就是删除了";
        String userSha = sha256(userText);
        String evidenceSha = sha256("test-evidence");

        ConfirmedSourceDeletionIntent intent = new ConfirmedSourceDeletionIntent(
                requestId, EXCLUSION_ID, COMPANY_ID, EMPLOYEE_ID,
                SOURCE_KEY, SOURCE_VERSION, OA_DOC_ID,
                INTERVAL_START, INTERVAL_END,
                "Confirmed source deletion",
                userText, userSha, evidenceSha
        );

        assertThatThrownBy(() -> service.excludeConfirmedSourceDeletion(intent))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                Long.class, EXCLUSION_ID)).isEqualTo(0L);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, EXCLUSION_ID)).isEqualTo(0L);
    }

    @Test
    void idempotentReplay_auditCountRemainsOne() throws Exception {
        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(OVERTIME_DETAIL_ID)))
                .thenReturn(OvertimeDocumentPresence.ABSENT);

        String requestId = UUID.randomUUID().toString();
        String userText = "查不到就是删除了";
        String userSha = sha256(userText);
        String evidenceSha = sha256("test-evidence");

        ConfirmedSourceDeletionIntent intent = new ConfirmedSourceDeletionIntent(
                requestId, EXCLUSION_ID, COMPANY_ID, EMPLOYEE_ID,
                SOURCE_KEY, SOURCE_VERSION, OA_DOC_ID,
                INTERVAL_START, INTERVAL_END,
                "Confirmed source deletion",
                userText, userSha, evidenceSha
        );

        ExclusionReceipt receipt1 = service.excludeConfirmedSourceDeletion(intent);
        ExclusionReceipt receipt2 = service.excludeConfirmedSourceDeletion(intent);

        assertThat(receipt1.exclusionId()).isEqualTo(receipt2.exclusionId());
        assertThat(receipt1.knowledgeAt()).isEqualTo(receipt2.knowledgeAt());
        assertThat(receipt1.reversedAt()).isEqualTo(receipt2.reversedAt());
        assertThat(receipt1.reversedBy()).isEqualTo(receipt2.reversedBy());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ? " +
                "AND action_code = 'CREATE_OA_EVIDENCE_EXCLUSION_CONFIRMED_SOURCE_DELETION'",
                Long.class, EXCLUSION_ID)).isEqualTo(1L);
    }

    @Test
    void crossRouteReplay_confirmedDeletionThenAnnualRejection() throws Exception {
        // Step 1: Successfully create confirmed deletion exclusion (OVERTIME with ABSENT probe)
        when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), eq(OVERTIME_DETAIL_ID)))
                .thenReturn(OvertimeDocumentPresence.ABSENT);

        String requestId = UUID.randomUUID().toString();
        String userText = "查不到就是删除了";
        String userSha = sha256(userText);
        String evidenceSha = sha256("test-evidence");

        ConfirmedSourceDeletionIntent deletionIntent = new ConfirmedSourceDeletionIntent(
                requestId, EXCLUSION_ID, COMPANY_ID, EMPLOYEE_ID,
                SOURCE_KEY, SOURCE_VERSION, OA_DOC_ID,
                INTERVAL_START, INTERVAL_END,
                "Confirmed source deletion",
                userText, userSha, evidenceSha
        );

        ExclusionReceipt receipt1 = service.excludeConfirmedSourceDeletion(deletionIntent);
        assertThat(receipt1.exclusionId()).isEqualTo(EXCLUSION_ID);

        // Verify first creation succeeded
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                Long.class, EXCLUSION_ID)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, EXCLUSION_ID)).isEqualTo(1L);

        // Step 2: Attempt replay via annual leave route with same requestId and same reason/confirmation
        // Only the route differs - annual route requires APPROVED status but fixture is UNKNOWN
        OaEvidenceExclusionWriteService.ExclusionIntent annualIntent = new OaEvidenceExclusionWriteService.ExclusionIntent(
                requestId, EXCLUSION_ID, COMPANY_ID, EMPLOYEE_ID,
                SOURCE_KEY, SOURCE_VERSION, OA_DOC_ID,
                java.time.LocalDate.of(2026, 9, 28),
                INTERVAL_START, INTERVAL_END,
                "Confirmed source deletion",
                userText, userSha, evidenceSha
        );

        assertThatThrownBy(() -> service.exclude(annualIntent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Replay validation failed: source status not APPROVED");

        // DB still has exactly 1 business row and 1 audit row
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                Long.class, EXCLUSION_ID)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, EXCLUSION_ID)).isEqualTo(1L);
    }

    @Test
    void crossRouteReplay_annualLeaveThenConfirmedDeletionRejection() throws Exception {
        // Step 1: Create local fixture for annual leave route (LEAVE, ANNUAL, APPROVED, single-day)
        String annualDocId = UUID.randomUUID().toString();
        String annualNormId = UUID.randomUUID().toString();
        String annualRawId = UUID.randomUUID().toString();
        String annualExclusionId = UUID.randomUUID().toString();
        String annualKey = "LEAVE:888";
        String annualVersion = "annual-v1";
        Instant singleDayStart = Instant.parse("2026-09-15T00:00:00Z");
        Instant singleDayEnd = Instant.parse("2026-09-15T16:00:00Z");

        try {
            // Insert raw fact for annual leave
            jdbc.update(
                "INSERT INTO raw_attendance_fact (raw_attendance_fact_id, attendance_source_id, company_id, " +
                "source_business_key, source_version, canonical_payload_digest, received_at, fact_kind, " +
                "interval_start, interval_end) VALUES (?, ?, ?, ?, ?, ?, ?, 'OA_DOCUMENT', ?, ?)",
                annualRawId, SHARED_SOURCE_ID, COMPANY_ID, annualKey, annualVersion,
                sha256("annual-payload"), Timestamp.from(Instant.parse("2026-09-15T10:00:00Z")),
                Timestamp.from(singleDayStart), Timestamp.from(singleDayEnd)
            );

            // Insert normalized record
            jdbc.update(
                "INSERT INTO normalized_attendance_record (normalized_attendance_record_id, raw_attendance_fact_id, " +
                "normalization_revision, validation_status, interval_start, interval_end, canonical_digest, " +
                "created_at, schema_version, record_kind) VALUES (?, ?, 1, 'VALID', ?, ?, ?, ?, 'V1', 'OA_INTERVAL')",
                annualNormId, annualRawId, Timestamp.from(singleDayStart), Timestamp.from(singleDayEnd),
                sha256("annual-normalized"), Timestamp.from(Instant.parse("2026-09-15T10:01:00Z"))
            );

            // Insert employee match
            jdbc.update(
                "INSERT INTO employee_match_decision (normalized_attendance_record_id, employee_id, " +
                "employment_period_id, resolver_snapshot_digest, match_status, created_at) VALUES (?, ?, ?, ?, 'MATCHED', ?)",
                annualNormId, EMPLOYEE_ID, UUID.randomUUID().toString(), "snapshot-annual",
                Timestamp.from(Instant.parse("2026-09-15T10:02:00Z"))
            );

            // Insert LEAVE document with ANNUAL leave_type and APPROVED status
            jdbc.update(
                "INSERT INTO oa_attendance_document (oa_attendance_document_id, normalized_attendance_record_id, " +
                "attendance_source_id, source_business_key, source_version, knowledge_rank, document_type, " +
                "leave_type, source_status, created_at) VALUES (?, ?, ?, ?, ?, 1, 'LEAVE', 'ANNUAL', 'APPROVED', ?)",
                annualDocId, annualNormId, SHARED_SOURCE_ID, annualKey, annualVersion,
                Timestamp.from(Instant.parse("2026-09-15T10:03:00Z"))
            );

            // Step 2: Successfully create annual leave exclusion
            String requestId = UUID.randomUUID().toString();
            String confirmText = "Annual leave confirmed";
            String confirmSha = sha256(confirmText);
            String evidenceSha = sha256("test-evidence");

            OaEvidenceExclusionWriteService.ExclusionIntent annualIntent = new OaEvidenceExclusionWriteService.ExclusionIntent(
                    requestId, annualExclusionId, COMPANY_ID, EMPLOYEE_ID,
                    annualKey, annualVersion, annualDocId,
                    java.time.LocalDate.of(2026, 9, 15),
                    singleDayStart, singleDayEnd,
                    "Annual leave exclusion",
                    confirmText, confirmSha, evidenceSha
            );

            ExclusionReceipt receipt1 = service.exclude(annualIntent);
            assertThat(receipt1.exclusionId()).isEqualTo(annualExclusionId);

            // Verify first creation succeeded
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                    Long.class, annualExclusionId)).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                    Long.class, annualExclusionId)).isEqualTo(1L);

            // Step 3: Attempt replay via confirmed deletion route with same requestId and same reason/confirmation
            // Only the route differs - confirmed deletion route requires OVERTIME document but fixture is LEAVE
            when(syntheticOaAttendanceDocumentAdapter.checkOvertimeDocumentPresence(eq(SHARED_SOURCE_ID), anyLong()))
                    .thenReturn(OvertimeDocumentPresence.ABSENT);

            ConfirmedSourceDeletionIntent deletionIntent = new ConfirmedSourceDeletionIntent(
                    requestId, annualExclusionId, COMPANY_ID, EMPLOYEE_ID,
                    annualKey, annualVersion, annualDocId,
                    singleDayStart, singleDayEnd,
                    "Annual leave exclusion",
                    confirmText, confirmSha, evidenceSha
            );

            assertThatThrownBy(() -> service.excludeConfirmedSourceDeletion(deletionIntent))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("documentType mismatch in replay: expected OVERTIME");

            // DB still has exactly 1 business row and 1 audit row
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?",
                    Long.class, annualExclusionId)).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                    Long.class, annualExclusionId)).isEqualTo(1L);

        } finally {
            // Cleanup local fixture
            jdbc.update("DELETE FROM attendance_report_oa_evidence_exclusion WHERE exclusion_id = ?", annualExclusionId);
            jdbc.update("DELETE FROM audit_event WHERE resource_id_ref = ?", annualExclusionId);
            jdbc.update("DELETE FROM employee_match_decision WHERE normalized_attendance_record_id = ?", annualNormId);
            jdbc.update("DELETE FROM oa_attendance_document WHERE oa_attendance_document_id = ?", annualDocId);
            jdbc.update("DELETE FROM normalized_attendance_record WHERE normalized_attendance_record_id = ?", annualNormId);
            jdbc.update("DELETE FROM raw_attendance_fact WHERE raw_attendance_fact_id = ?", annualRawId);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
