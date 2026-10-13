package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.RepairIntent;
import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.RepairReceipt;
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

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2 integration test for PunchExemptionRepairService.
 * Tests real MyBatis SqlSession, AuditService integration, and transaction boundaries.
 *
 * Uses @Autowired service (Spring proxy with @Transactional) to ensure real transaction rollback.
 * Fixed Clock injected via @Primary @TestConfiguration for microsecond test.
 */
@SpringBootTest
@ActiveProfiles("test")
class PunchExemptionRepairServiceH2IntegrationTest {

    /**
     * Test configuration to provide @Primary Clock bean for microsecond test.
     * Other tests use service's default Clock.
     */
    @TestConfiguration
    static class TestClockConfiguration {
        @Bean
        @Primary
        public Clock testClock() {
            // Fixed Clock with nanosecond precision to verify service truncates to MICROS
            return Clock.fixed(
                Instant.parse("2026-10-08T08:00:00.123456789Z"),
                ZoneOffset.UTC
            );
        }
    }

    @Autowired
    private PunchExemptionRepairService repairService;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String COMPANY_ID = "c0000001-0000-0000-0000-000000000001";
    private static final String EMPLOYEE_ID = "e0000001-0000-0000-0000-000000000001";
    private static final String ACTOR_PRINCIPAL_ID = "p0000001-0000-0000-0000-000000000001";
    private static final String EXEMPTION_ID_1 = "x0000001-0000-0000-0000-000000000001";
    private static final String EXEMPTION_ID_2 = "x0000002-0000-0000-0000-000000000002";
    private static final String ROLE_ID = "r0000001-0000-0000-0000-000000000001";
    private static final String CAPABILITY_ID = "cap00001-0000-0000-0000-000000000001";
    private static final String SCOPE_ID = "s0000001-0000-0000-0000-000000000001";
    private static final String EMPLOYEE_VERSION_ID = "ev000001-0000-0000-0000-000000000001";

    @BeforeEach
    void setUp() {
        // Clean only this test's data (not shared fixtures)
        jdbc.update("DELETE FROM punch_exemption_repair_receipt WHERE exemption_id IN (?, ?)",
            EXEMPTION_ID_1, EXEMPTION_ID_2);
        jdbc.update("DELETE FROM audit_event WHERE resource_id_ref IN (?, ?)",
            EXEMPTION_ID_1, EXEMPTION_ID_2);
        jdbc.update("DELETE FROM punch_exemption_assignment WHERE exemption_id IN (?, ?)",
            EXEMPTION_ID_1, EXEMPTION_ID_2);
        jdbc.update("DELETE FROM auth_principal_role_assignment WHERE principal_id = ?",
            ACTOR_PRINCIPAL_ID);
        jdbc.update("DELETE FROM auth_role_capability WHERE role_id = ?", ROLE_ID);
        jdbc.update("DELETE FROM auth_capability WHERE capability_id = ?", CAPABILITY_ID);
        jdbc.update("DELETE FROM auth_role WHERE role_id = ?", ROLE_ID);
        jdbc.update("DELETE FROM auth_data_scope WHERE scope_id = ?", SCOPE_ID);
        jdbc.update("DELETE FROM employee_current_projection WHERE employee_id = ?", EMPLOYEE_ID);
        jdbc.update("DELETE FROM employee_version WHERE employee_version_id = ?", EMPLOYEE_VERSION_ID);
        jdbc.update("DELETE FROM employee WHERE employee_id = ?", EMPLOYEE_ID);
        jdbc.update("DELETE FROM auth_principal WHERE principal_id = ?", ACTOR_PRINCIPAL_ID);
        jdbc.update("DELETE FROM company WHERE company_id = ?", COMPANY_ID);

        // Insert company
        jdbc.update(
            "INSERT INTO company (company_id, code, name, status, created_at) " +
            "VALUES (?, ?, ?, 'ACTIVE', ?)",
            COMPANY_ID, "TEST-COMP-001", "测试公司",
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        // Insert employee
        jdbc.update(
            "INSERT INTO employee (employee_id, company_id, display_name, employment_status) " +
            "VALUES (?, ?, ?, 'ACTIVE')",
            EMPLOYEE_ID, COMPANY_ID, "测试员工"
        );

        // Insert employee_version
        jdbc.update(
            "INSERT INTO employee_version (employee_version_id, employee_id, employee_number, " +
            "display_name, status, effective_from, effective_to, source_authority, created_at, created_by) " +
            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL, 'TEST', ?, 'SYSTEM')",
            EMPLOYEE_VERSION_ID, EMPLOYEE_ID, "EMP-001", "测试员工",
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

        // Insert EMPLOYEE:EDIT capability
        jdbc.update(
            "INSERT INTO auth_capability (capability_id, capability_code, permission_domain, action_code) " +
            "VALUES (?, 'EMPLOYEE:EDIT', 'EMPLOYEE', 'EDIT')",
            CAPABILITY_ID
        );

        // Insert role
        jdbc.update(
            "INSERT INTO auth_role (role_id, role_code, role_name, permission_domain) " +
            "VALUES (?, 'HR_ADMIN', 'HR管理员', 'EMPLOYEE')",
            ROLE_ID
        );

        // Assign capability to role
        jdbc.update(
            "INSERT INTO auth_role_capability (role_id, capability_id) VALUES (?, ?)",
            ROLE_ID, CAPABILITY_ID
        );

        // Create data scope
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
            "VALUES (?, ?, ?, ?, ?, NULL, 'SYSTEM', 'TEST SETUP', 0)",
            UUID.randomUUID().toString(), ACTOR_PRINCIPAL_ID, ROLE_ID, SCOPE_ID,
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        // Set up SecurityContext (String principal)
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                ACTOR_PRINCIPAL_ID, "test", List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Helper to create RepairIntent with correct parameter order.
     */
    private static RepairIntent intent(String requestId, String exemptionId,
            Instant expectedFrom, Instant targetFrom, String approvalDigest) {
        return new RepairIntent(EMPLOYEE_ID, "EMP-001", COMPANY_ID, exemptionId,
                expectedFrom, targetFrom, requestId, approvalDigest);
    }

    @Test
    void invalidUuid_allHyphens_rejected() {
        String invalidRequestId = "------------------------------------";
        RepairIntent intent = intent(
            invalidRequestId,
            EXEMPTION_ID_1,
            Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-10-05T00:00:00Z"),
            "a".repeat(64)
        );

        assertThatThrownBy(() -> repairService.repair(intent))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("UUID");
    }

    @Test
    void invalidUuid_allHexNoHyphens_rejected() {
        String invalidRequestId = "a".repeat(36);
        RepairIntent intent = intent(
            invalidRequestId,
            EXEMPTION_ID_1,
            Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-10-05T00:00:00Z"),
            "b".repeat(64)
        );

        assertThatThrownBy(() -> repairService.repair(intent))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("UUID");
    }

    @Test
    void validUuid_accepted() {
        String validRequestId = UUID.randomUUID().toString();
        Instant originalFrom = Instant.parse("2026-10-01T00:00:00Z");
        insertExemption(EXEMPTION_ID_1, originalFrom);

        RepairIntent intent = intent(
            validRequestId,
            EXEMPTION_ID_1,
            originalFrom,
            Instant.parse("2026-10-05T00:00:00Z"),
            "c".repeat(64)
        );

        RepairReceipt receipt = repairService.repair(intent);

        assertThat(receipt.requestId()).isEqualTo(validRequestId);
        assertThat(receipt.exemptionId()).isEqualTo(EXEMPTION_ID_1);
    }

    @Test
    void sameExemptionAndRequest_differentApprovalDigest_rejected() {
        String requestId = UUID.randomUUID().toString();
        Instant originalFrom = Instant.parse("2026-10-01T00:00:00Z");
        Instant targetFrom = Instant.parse("2026-10-05T00:00:00Z");
        insertExemption(EXEMPTION_ID_1, originalFrom);

        // First repair
        RepairIntent intent1 = intent(
            requestId,
            EXEMPTION_ID_1,
            originalFrom,
            targetFrom,
            "a".repeat(64)
        );
        repairService.repair(intent1);

        // Second repair: only change approval digest
        RepairIntent intent2 = intent(
            requestId,
            EXEMPTION_ID_1,
            originalFrom,
            targetFrom,
            "b".repeat(64)
        );

        assertThatThrownBy(() -> repairService.repair(intent2))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("重放冲突")
            .hasMessageContaining("approvalEvidenceDigest");
    }

    @Test
    void microseconds_surviveDbRoundTrip_andIdempotentReplayIdentical() {
        // Uses @Primary Clock from TestClockConfiguration: .123456789Z input
        String requestId = UUID.randomUUID().toString();
        Instant originalWithMicros = Instant.parse("2026-10-08T08:30:25.123456Z");
        Instant targetWithMicros = Instant.parse("2026-10-08T09:45:30.987654Z");

        insertExemption(EXEMPTION_ID_1, originalWithMicros);

        RepairIntent intent1 = intent(
            requestId,
            EXEMPTION_ID_1,
            originalWithMicros,
            targetWithMicros,
            "d".repeat(64)
        );

        // First repair
        RepairReceipt receipt1 = repairService.repair(intent1);

        // Verify service truncated Clock's nanoseconds to microseconds
        Instant expectedRepairedAt = Instant.parse("2026-10-08T08:00:00.123456Z");
        assertThat(receipt1.repairedAt()).isEqualTo(expectedRepairedAt);
        assertThat(receipt1.repairedAt().getNano() % 1000).isEqualTo(0);

        // Verify business timestamps
        assertThat(receipt1.originalValidFrom()).isEqualTo(originalWithMicros);
        assertThat(receipt1.repairedValidFrom()).isEqualTo(targetWithMicros);

        // Verify DB persistence - receipt table
        Timestamp dbRepairedAt = jdbc.queryForObject(
            "SELECT repaired_at FROM punch_exemption_repair_receipt WHERE receipt_id = ?",
            Timestamp.class,
            receipt1.receiptId()
        );
        assertThat(dbRepairedAt.toInstant()).isEqualTo(expectedRepairedAt);
        assertThat(dbRepairedAt.toInstant().getNano() % 1000).isEqualTo(0);

        Timestamp dbRepairedFrom = jdbc.queryForObject(
            "SELECT repaired_valid_from FROM punch_exemption_repair_receipt WHERE receipt_id = ?",
            Timestamp.class,
            receipt1.receiptId()
        );
        assertThat(dbRepairedFrom.toInstant()).isEqualTo(targetWithMicros);

        // Verify DB persistence - exemption table
        Timestamp dbExemptionFrom = jdbc.queryForObject(
            "SELECT valid_from FROM punch_exemption_assignment WHERE exemption_id = ?",
            Timestamp.class,
            EXEMPTION_ID_1
        );
        assertThat(dbExemptionFrom.toInstant()).isEqualTo(targetWithMicros);

        // Idempotent call - reuse exact same intent
        RepairIntent intent2 = intent(
            requestId,
            EXEMPTION_ID_1,
            originalWithMicros,
            targetWithMicros,
            "d".repeat(64)
        );

        RepairReceipt receipt2 = repairService.repair(intent2);

        // Must return exact same receipt - all 14 fields identical
        assertThat(receipt2.receiptId()).isEqualTo(receipt1.receiptId());
        assertThat(receipt2.requestId()).isEqualTo(receipt1.requestId());
        assertThat(receipt2.exemptionId()).isEqualTo(receipt1.exemptionId());
        assertThat(receipt2.companyId()).isEqualTo(receipt1.companyId());
        assertThat(receipt2.employeeId()).isEqualTo(receipt1.employeeId());
        assertThat(receipt2.operation()).isEqualTo(receipt1.operation());
        assertThat(receipt2.originalValidFrom()).isEqualTo(receipt1.originalValidFrom());
        assertThat(receipt2.repairedValidFrom()).isEqualTo(receipt1.repairedValidFrom());
        assertThat(receipt2.approvalEvidenceDigest()).isEqualTo(receipt1.approvalEvidenceDigest());
        assertThat(receipt2.payloadDigest()).isEqualTo(receipt1.payloadDigest());
        assertThat(receipt2.repairedAt()).isEqualTo(receipt1.repairedAt());
        assertThat(receipt2.repairedAt().getNano()).isEqualTo(receipt1.repairedAt().getNano());
        assertThat(receipt2.repairedBy()).isEqualTo(receipt1.repairedBy());
        assertThat(receipt2.beforeDigest()).isEqualTo(receipt1.beforeDigest());
        assertThat(receipt2.afterDigest()).isEqualTo(receipt1.afterDigest());
    }

    @Test
    void batchRepair_secondIntentFails_bothRolledBack() {
        Instant from1 = Instant.parse("2026-10-01T10:00:00Z");
        Instant from2 = Instant.parse("2026-10-02T10:00:00Z");

        insertExemption(EXEMPTION_ID_1, from1);
        insertExemption(EXEMPTION_ID_2, from2);

        // Both intents use valid UUIDs; intent1 valid, intent2 has wrong expectedValidFrom
        RepairIntent intent1 = intent(
            UUID.randomUUID().toString(),
            EXEMPTION_ID_1,
            from1,
            Instant.parse("2026-10-05T10:00:00Z"),
            "e".repeat(64)
        );

        RepairIntent intent2 = intent(
            UUID.randomUUID().toString(),
            EXEMPTION_ID_2,
            Instant.parse("2026-09-01T10:00:00Z"), // WRONG - causes CAS failure
            Instant.parse("2026-10-06T10:00:00Z"),
            "f".repeat(64)
        );

        // Batch should fail due to intent2 CAS failure
        assertThatThrownBy(() -> repairService.batchRepair(List.of(intent1, intent2)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CAS")
            .hasMessageContaining(EXEMPTION_ID_2)
            .hasMessageContaining("CAS更新行数异常(0)");

        // Verify BOTH rolled back - no receipts
        Long receiptCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM punch_exemption_repair_receipt WHERE exemption_id IN (?, ?)",
            Long.class,
            EXEMPTION_ID_1, EXEMPTION_ID_2
        );
        assertThat(receiptCount).isEqualTo(0);

        // Verify BOTH exemptions unchanged
        Instant ex1From = jdbc.queryForObject(
            "SELECT valid_from FROM punch_exemption_assignment WHERE exemption_id = ?",
            Timestamp.class,
            EXEMPTION_ID_1
        ).toInstant();
        assertThat(ex1From).isEqualTo(from1);

        Instant ex2From = jdbc.queryForObject(
            "SELECT valid_from FROM punch_exemption_assignment WHERE exemption_id = ?",
            Timestamp.class,
            EXEMPTION_ID_2
        ).toInstant();
        assertThat(ex2From).isEqualTo(from2);

        // Verify no audit logs
        Long auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref IN (?, ?)",
            Long.class,
            EXEMPTION_ID_1, EXEMPTION_ID_2
        );
        assertThat(auditCount).isEqualTo(0);
    }

    @Test
    void successfulRepair_atomicCommit() {
        String requestId = UUID.randomUUID().toString();
        Instant originalFrom = Instant.parse("2026-10-01T12:00:00Z");
        Instant targetFrom = Instant.parse("2026-10-05T12:00:00Z");

        insertExemption(EXEMPTION_ID_1, originalFrom);

        RepairIntent intent = intent(
            requestId,
            EXEMPTION_ID_1,
            originalFrom,
            targetFrom,
            "0".repeat(64)
        );

        RepairReceipt receipt = repairService.repair(intent);

        // Verify receipt persisted
        Long receiptCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM punch_exemption_repair_receipt WHERE receipt_id = ?",
            Long.class,
            receipt.receiptId()
        );
        assertThat(receiptCount).isEqualTo(1);

        // Verify exemption updated
        Instant updatedFrom = jdbc.queryForObject(
            "SELECT valid_from FROM punch_exemption_assignment WHERE exemption_id = ?",
            Timestamp.class,
            EXEMPTION_ID_1
        ).toInstant();
        assertThat(updatedFrom).isEqualTo(targetFrom);

        // Verify audit log with correct reason format (reason_code, not reason)
        String auditReason = jdbc.queryForObject(
            "SELECT reason_code FROM audit_event WHERE resource_id_ref = ?",
            String.class,
            EXEMPTION_ID_1
        );
        assertThat(auditReason).startsWith("rec=").contains(receipt.receiptId());

        String beforeDigest = jdbc.queryForObject(
            "SELECT before_digest FROM audit_event WHERE resource_id_ref = ?",
            String.class,
            EXEMPTION_ID_1
        );
        assertThat(beforeDigest).isEqualTo(receipt.beforeDigest());

        String afterDigest = jdbc.queryForObject(
            "SELECT after_digest FROM audit_event WHERE resource_id_ref = ?",
            String.class,
            EXEMPTION_ID_1
        );
        assertThat(afterDigest).isEqualTo(receipt.afterDigest());
    }

    @Test
    void auditFailure_rollsBackReceiptAndExemptionUpdate() {
        String requestId = UUID.randomUUID().toString();
        Instant originalFrom = Instant.parse("2026-10-01T14:00:00Z");
        Instant targetFrom = Instant.parse("2026-10-05T14:00:00Z");

        insertExemption(EXEMPTION_ID_1, originalFrom);

        // Add temporary CHECK constraint to cause audit INSERT failure
        // Use correct column: action_code (not event_type)
        String constraintName = "test_audit_reject_" + System.currentTimeMillis();
        try {
            jdbc.execute(
                "ALTER TABLE audit_event ADD CONSTRAINT " + constraintName + " " +
                "CHECK (NOT (resource_id_ref = '" + EXEMPTION_ID_1 + "' AND action_code = 'PUNCH_EXEMPTION_REPAIR'))"
            );

            RepairIntent intent = intent(
                requestId,
                EXEMPTION_ID_1,
                originalFrom,
                targetFrom,
                "9".repeat(64)
            );

            // Should fail due to audit constraint violation
            assertThatThrownBy(() -> repairService.repair(intent))
                .hasMessageContaining("constraint")
                .hasMessageContaining(constraintName);

            // Verify rollback - no receipt
            Long receiptCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM punch_exemption_repair_receipt WHERE exemption_id = ?",
                Long.class,
                EXEMPTION_ID_1
            );
            assertThat(receiptCount).isEqualTo(0);

            // Verify rollback - exemption unchanged
            Instant currentFrom = jdbc.queryForObject(
                "SELECT valid_from FROM punch_exemption_assignment WHERE exemption_id = ?",
                Timestamp.class,
                EXEMPTION_ID_1
            ).toInstant();
            assertThat(currentFrom).isEqualTo(originalFrom);

            // Verify rollback - no audit log
            Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class,
                EXEMPTION_ID_1
            );
            assertThat(auditCount).isEqualTo(0);

        } finally {
            // Clean up constraint
            jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT IF EXISTS " + constraintName);
        }
    }

    private void insertExemption(String exemptionId, Instant validFrom) {
        jdbc.update(
            "INSERT INTO punch_exemption_assignment " +
            "(exemption_id, employee_id, listed_employee_number, source, valid_from, valid_to, created_at) " +
            "VALUES (?, ?, ?, 'STANDING_LIST', ?, NULL, ?)",
            exemptionId, EMPLOYEE_ID, "EMP-001",
            Timestamp.from(validFrom),
            Timestamp.from(Instant.parse("2026-09-01T00:00:00Z"))
        );
    }
}
