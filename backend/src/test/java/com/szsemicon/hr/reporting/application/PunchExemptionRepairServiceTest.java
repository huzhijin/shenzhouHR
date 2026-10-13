package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.employee.application.EmployeeAuthorizationPort;
import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.ExemptionRow;
import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.RepairIntent;
import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.RepairReceipt;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PunchExemptionRepairServiceTest {

    private static final String ACTOR_ID        = "actor-00000000-0001";
    private static final String COMPANY_ID      = "cmp-00000000-0002";
    private static final String EMPLOYEE_ID     = "60597718-c159-59fb-9689-4f4f5d25e78d";
    private static final String EMPLOYEE_NUMBER = "SZJN0004";
    private static final String EXEMPTION_ID    = "0f55a440-60a9-433c-a405-e88ab8e44757";
    private static final String REQUEST_ID      = "550e8400-e29b-41d4-a716-446655440000";
    private static final String APPROVAL_DIGEST = "a".repeat(64);

    private static final Instant NOW                = Instant.parse("2026-10-08T08:00:00Z");
    private static final Instant EXPECTED_VALID_FROM = Instant.parse("2026-10-08T02:30:25.924625Z");
    private static final Instant TARGET_VALID_FROM   = Instant.parse("2026-08-31T16:00:00Z");
    private static final Instant CREATED_AT          = Instant.parse("2026-01-01T00:00:00Z");

    @Mock private ExemptionRepairPort port;
    @Mock private AuditService auditService;
    @Mock private CurrentPrincipalProvider principalProvider;
    @Mock private EmployeeAuthorizationPort employeeAuthorizationPort;

    private PunchExemptionRepairService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new PunchExemptionRepairService(
                port, auditService, principalProvider, employeeAuthorizationPort, fixed);
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private RepairIntent baseIntent() {
        return new RepairIntent(
                EMPLOYEE_ID,
                EMPLOYEE_NUMBER,
                COMPANY_ID,
                EXEMPTION_ID,
                EXPECTED_VALID_FROM,
                TARGET_VALID_FROM,
                REQUEST_ID,
                APPROVAL_DIGEST);
    }

    /** An open STANDING_LIST row at EXPECTED_VALID_FROM (pre-repair state). */
    private ExemptionRow currentRow() {
        return new ExemptionRow(
                EXEMPTION_ID,
                EMPLOYEE_ID,
                EMPLOYEE_NUMBER,
                COMPANY_ID,
                "STANDING_LIST",
                EXPECTED_VALID_FROM,
                null,          // valid_to IS NULL
                CREATED_AT);
    }

    /** A stored receipt that exactly matches baseIntent — used for idempotent path. */
    private RepairReceipt storedReceipt(String receiptId) {
        String pd = PunchExemptionRepairService.payloadDigest(baseIntent());
        String bd = PunchExemptionRepairService.rowDigest(currentRow());
        ExemptionRow after = new ExemptionRow(
                EXEMPTION_ID, EMPLOYEE_ID, EMPLOYEE_NUMBER, COMPANY_ID,
                "STANDING_LIST", TARGET_VALID_FROM, null, CREATED_AT);
        String ad = PunchExemptionRepairService.rowDigest(after);
        return new RepairReceipt(
                receiptId,
                REQUEST_ID,
                EXEMPTION_ID,
                COMPANY_ID,
                EMPLOYEE_ID,
                "ADJUST_VALID_FROM",
                EXPECTED_VALID_FROM,
                TARGET_VALID_FROM,
                APPROVAL_DIGEST,
                pd,
                NOW,
                ACTOR_ID,
                bd,
                ad);
    }

    /** Stubs the happy-path port: row at expectedFrom, CAS succeeds, no existing receipt. */
    private void stubHappyPath() {
        when(principalProvider.currentPrincipalId()).thenReturn(ACTOR_ID);
        when(employeeAuthorizationPort.canAccessEmployee(
                ACTOR_ID, CapabilityCodes.EMPLOYEE_EDIT, NOW, EMPLOYEE_ID))
                .thenReturn(true);

        // First findExemption returns currentRow (before state)
        // Second findExemption returns afterRow (after CAS update)
        ExemptionRow afterRow = new ExemptionRow(
                EXEMPTION_ID, EMPLOYEE_ID, EMPLOYEE_NUMBER, COMPANY_ID,
                "STANDING_LIST", TARGET_VALID_FROM, null, CREATED_AT);
        when(port.findExemption(EXEMPTION_ID))
                .thenReturn(Optional.of(currentRow()))
                .thenReturn(Optional.of(afterRow));

        when(port.updateExemptionValidFrom(EXEMPTION_ID, EXPECTED_VALID_FROM, TARGET_VALID_FROM))
                .thenReturn(1);

        // Capture inserted receipt dynamically and return it on findReceipt
        org.mockito.Mockito.doAnswer(invocation -> {
            RepairReceipt captured = invocation.getArgument(0);
            when(port.findReceipt(EXEMPTION_ID, "ADJUST_VALID_FROM"))
                    .thenReturn(Optional.of(captured));
            return null;
        }).when(port).insertRepairReceipt(org.mockito.ArgumentMatchers.any());
    }

    // ==================================================================
    // Test 1 — same request, same payload: second call returns original receipt
    // ==================================================================

    @Test
    void sameRequestSamePayload_returnsExistingReceipt() {
        // First call: row is at EXPECTED_VALID_FROM — normal repair path
        stubHappyPath();
        RepairReceipt result1 = service.repair(baseIntent());

        // Capture the receipt that was passed to insertRepairReceipt
        ArgumentCaptor<RepairReceipt> captor = ArgumentCaptor.forClass(RepairReceipt.class);
        verify(port).insertRepairReceipt(captor.capture());
        RepairReceipt capturedReceipt = captor.getValue();
        assertThat(capturedReceipt).isEqualTo(result1);

        // Second call: row is now at TARGET_VALID_FROM — idempotent path
        // Stub findReceipt to return the CAPTURED receipt from the first insert
        ExemptionRow afterRow = new ExemptionRow(
                EXEMPTION_ID, EMPLOYEE_ID, EMPLOYEE_NUMBER, COMPANY_ID,
                "STANDING_LIST", TARGET_VALID_FROM, null, CREATED_AT);
        when(port.findExemption(EXEMPTION_ID)).thenReturn(Optional.of(afterRow));
        when(port.findReceipt(EXEMPTION_ID, "ADJUST_VALID_FROM"))
                .thenReturn(Optional.of(capturedReceipt));

        RepairReceipt result2 = service.repair(baseIntent());

        // Second result must equal the captured first receipt
        assertThat(result2).isEqualTo(capturedReceipt);
        // repairedAt must be from the first call's clock instant, not "now" of second call
        assertThat(result2.repairedAt()).isEqualTo(result1.repairedAt());
        // CAS must not fire a second time
        verify(port, times(1)).updateExemptionValidFrom(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    // ==================================================================
    // Test 2 — same requestId/exemptionId/dates but different approvalEvidenceDigest
    // ==================================================================

    @Test
    void sameRequestDifferentApprovalDigest_throws() {
        String differentApproval = "b".repeat(64);
        RepairIntent conflictingIntent = new RepairIntent(
                EMPLOYEE_ID, EMPLOYEE_NUMBER, COMPANY_ID, EXEMPTION_ID,
                EXPECTED_VALID_FROM, TARGET_VALID_FROM,
                REQUEST_ID, differentApproval);

        // The stored receipt was created with APPROVAL_DIGEST
        RepairReceipt original = storedReceipt("receipt-original");

        when(principalProvider.currentPrincipalId()).thenReturn(ACTOR_ID);
        when(employeeAuthorizationPort.canAccessEmployee(
                ACTOR_ID, CapabilityCodes.EMPLOYEE_EDIT, NOW, EMPLOYEE_ID))
                .thenReturn(true);
        ExemptionRow afterRow = new ExemptionRow(
                EXEMPTION_ID, EMPLOYEE_ID, EMPLOYEE_NUMBER, COMPANY_ID,
                "STANDING_LIST", TARGET_VALID_FROM, null, CREATED_AT);
        when(port.findExemption(EXEMPTION_ID)).thenReturn(Optional.of(afterRow));
        when(port.findReceipt(EXEMPTION_ID, "ADJUST_VALID_FROM")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.repair(conflictingIntent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重放冲突");
    }

    // ==================================================================
    // Test 3 — same requestId but different expectedValidFrom
    // ==================================================================

    @Test
    void sameRequestDifferentExpectedFrom_throws() {
        Instant differentExpected = Instant.parse("2020-01-01T00:00:00Z");
        RepairIntent conflictingIntent = new RepairIntent(
                EMPLOYEE_ID, EMPLOYEE_NUMBER, COMPANY_ID, EXEMPTION_ID,
                differentExpected, TARGET_VALID_FROM,
                REQUEST_ID, APPROVAL_DIGEST);

        RepairReceipt original = storedReceipt("receipt-original");

        when(principalProvider.currentPrincipalId()).thenReturn(ACTOR_ID);
        when(employeeAuthorizationPort.canAccessEmployee(
                ACTOR_ID, CapabilityCodes.EMPLOYEE_EDIT, NOW, EMPLOYEE_ID))
                .thenReturn(true);
        ExemptionRow afterRow = new ExemptionRow(
                EXEMPTION_ID, EMPLOYEE_ID, EMPLOYEE_NUMBER, COMPANY_ID,
                "STANDING_LIST", TARGET_VALID_FROM, null, CREATED_AT);
        when(port.findExemption(EXEMPTION_ID)).thenReturn(Optional.of(afterRow));
        when(port.findReceipt(EXEMPTION_ID, "ADJUST_VALID_FROM")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.repair(conflictingIntent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重放冲突");
    }

    // ==================================================================
    // Test 4 — canAccessEmployee returns false → access denied
    // ==================================================================

    @Test
    void unauthorizedActor_throwsAccessDenied() {
        when(principalProvider.currentPrincipalId()).thenReturn(ACTOR_ID);
        when(employeeAuthorizationPort.canAccessEmployee(
                ACTOR_ID, CapabilityCodes.EMPLOYEE_EDIT, NOW, EMPLOYEE_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> service.repair(baseIntent()))
                .isInstanceOf(ResourceNotAvailableAccessDeniedException.class);

        // Must not touch the port at all
        verify(port, never()).findExemption(org.mockito.ArgumentMatchers.any());
    }

    // ==================================================================
    // Test 5 — intent.companyId != current.companyId
    // ==================================================================

    @Test
    void wrongCompanyId_throws() {
        when(principalProvider.currentPrincipalId()).thenReturn(ACTOR_ID);
        when(employeeAuthorizationPort.canAccessEmployee(
                ACTOR_ID, CapabilityCodes.EMPLOYEE_EDIT, NOW, EMPLOYEE_ID))
                .thenReturn(true);
        ExemptionRow wrongCompanyRow = new ExemptionRow(
                EXEMPTION_ID, EMPLOYEE_ID, EMPLOYEE_NUMBER,
                "cmp-OTHER",     // different company
                "STANDING_LIST", EXPECTED_VALID_FROM, null, CREATED_AT);
        when(port.findExemption(EXEMPTION_ID)).thenReturn(Optional.of(wrongCompanyRow));

        assertThatThrownBy(() -> service.repair(baseIntent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("公司不匹配");
    }

    // ==================================================================
    // Test 6 — intent.employeeId != current.employeeId
    // ==================================================================

    @Test
    void wrongEmployeeId_throws() {
        when(principalProvider.currentPrincipalId()).thenReturn(ACTOR_ID);
        when(employeeAuthorizationPort.canAccessEmployee(
                ACTOR_ID, CapabilityCodes.EMPLOYEE_EDIT, NOW, EMPLOYEE_ID))
                .thenReturn(true);
        ExemptionRow wrongEmployeeRow = new ExemptionRow(
                EXEMPTION_ID, "employee-OTHER", EMPLOYEE_NUMBER,
                COMPANY_ID, "STANDING_LIST", EXPECTED_VALID_FROM, null, CREATED_AT);
        when(port.findExemption(EXEMPTION_ID)).thenReturn(Optional.of(wrongEmployeeRow));

        assertThatThrownBy(() -> service.repair(baseIntent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("员工ID不匹配");
    }

    // ==================================================================
    // Test 7 — CAS returns 0 → IllegalStateException with "CAS"
    // ==================================================================

    @Test
    void casFailure_throws() {
        when(principalProvider.currentPrincipalId()).thenReturn(ACTOR_ID);
        when(employeeAuthorizationPort.canAccessEmployee(
                ACTOR_ID, CapabilityCodes.EMPLOYEE_EDIT, NOW, EMPLOYEE_ID))
                .thenReturn(true);
        when(port.findExemption(EXEMPTION_ID)).thenReturn(Optional.of(currentRow()));
        when(port.updateExemptionValidFrom(EXEMPTION_ID, EXPECTED_VALID_FROM, TARGET_VALID_FROM))
                .thenReturn(0);

        assertThatThrownBy(() -> service.repair(baseIntent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAS");
    }

    // ==================================================================
    // Test 8 — happy path: 14-field receipt, 8-param audit called
    // ==================================================================

    @Test
    void successfulRepair_insertsReceiptAndAudits() {
        stubHappyPath();

        RepairReceipt receipt = service.repair(baseIntent());

        // Verify all 14 receipt fields are populated correctly
        assertThat(receipt.receiptId()).isNotNull().isNotBlank();
        assertThat(receipt.requestId()).isEqualTo(REQUEST_ID);
        assertThat(receipt.exemptionId()).isEqualTo(EXEMPTION_ID);
        assertThat(receipt.companyId()).isEqualTo(COMPANY_ID);
        assertThat(receipt.employeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(receipt.operation()).isEqualTo("ADJUST_VALID_FROM");
        assertThat(receipt.originalValidFrom()).isEqualTo(EXPECTED_VALID_FROM);
        assertThat(receipt.repairedValidFrom()).isEqualTo(TARGET_VALID_FROM);
        assertThat(receipt.approvalEvidenceDigest()).isEqualTo(APPROVAL_DIGEST);
        assertThat(receipt.payloadDigest()).hasSize(64);
        assertThat(receipt.repairedAt()).isEqualTo(NOW);
        assertThat(receipt.repairedBy()).isEqualTo(ACTOR_ID);
        assertThat(receipt.beforeDigest()).hasSize(64);
        assertThat(receipt.afterDigest()).hasSize(64);
        // before and after digests must differ (validFrom changed)
        assertThat(receipt.beforeDigest()).isNotEqualTo(receipt.afterDigest());

        // Verify receipt was persisted
        verify(port).insertRepairReceipt(receipt);

        // Verify 8-param audit call with beforeDigest/afterDigest
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(
                org.mockito.ArgumentMatchers.eq(ACTOR_ID),
                org.mockito.ArgumentMatchers.eq("PUNCH_EXEMPTION_REPAIR"),
                org.mockito.ArgumentMatchers.eq("PUNCH_EXEMPTION"),
                org.mockito.ArgumentMatchers.eq(EXEMPTION_ID),
                org.mockito.ArgumentMatchers.eq("SUCCESS"),
                reasonCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(receipt.beforeDigest()),
                org.mockito.ArgumentMatchers.eq(receipt.afterDigest()));

        String reason = reasonCaptor.getValue();
        // Audit reason contains rec=<receiptId>, NOT requestId (reason is limited to 64 chars)
        assertThat(reason).startsWith("rec=").contains(receipt.receiptId());
        // requestId is in the receipt object itself
        assertThat(receipt.requestId()).isEqualTo(REQUEST_ID);
    }

    // ==================================================================
    // Test 9 — payloadDigest is deterministic
    // ==================================================================

    @Test
    void payloadDigestIsDeterministic() {
        RepairIntent intent1 = baseIntent();
        RepairIntent intent2 = baseIntent();

        String d1 = PunchExemptionRepairService.payloadDigest(intent1);
        String d2 = PunchExemptionRepairService.payloadDigest(intent2);

        assertThat(d1).isEqualTo(d2);
        assertThat(d1).hasSize(64);
    }

    // ==================================================================
    // Test 10 — rowDigest changes only when validFrom changes
    // ==================================================================

    @Test
    void rowDigestChangesOnlyValidFrom() {
        ExemptionRow rowA = new ExemptionRow(
                EXEMPTION_ID, EMPLOYEE_ID, EMPLOYEE_NUMBER, COMPANY_ID,
                "STANDING_LIST", EXPECTED_VALID_FROM, null, CREATED_AT);
        ExemptionRow rowB = new ExemptionRow(
                EXEMPTION_ID, EMPLOYEE_ID, EMPLOYEE_NUMBER, COMPANY_ID,
                "STANDING_LIST", TARGET_VALID_FROM,   null, CREATED_AT);
        // identical copy of rowA
        ExemptionRow rowA2 = new ExemptionRow(
                EXEMPTION_ID, EMPLOYEE_ID, EMPLOYEE_NUMBER, COMPANY_ID,
                "STANDING_LIST", EXPECTED_VALID_FROM, null, CREATED_AT);

        String digestA  = PunchExemptionRepairService.rowDigest(rowA);
        String digestB  = PunchExemptionRepairService.rowDigest(rowB);
        String digestA2 = PunchExemptionRepairService.rowDigest(rowA2);

        // Different validFrom → different digest
        assertThat(digestA).isNotEqualTo(digestB);
        // Same fields → same digest (deterministic)
        assertThat(digestA).isEqualTo(digestA2);
        // All digests are full 64-char SHA-256 hex
        assertThat(digestA).hasSize(64);
        assertThat(digestB).hasSize(64);
    }
}
