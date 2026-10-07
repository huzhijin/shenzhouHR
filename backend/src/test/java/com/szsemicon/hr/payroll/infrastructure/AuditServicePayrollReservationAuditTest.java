package com.szsemicon.hr.payroll.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.payroll.application.PayrollReservationAudit.DenialReason;
import com.szsemicon.hr.shared.domain.ExternalPreciseId;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import org.junit.jupiter.api.Test;

class AuditServicePayrollReservationAuditTest {

    @Test
    void writesOnlyStableAllowlistedDenialFields() {
        AuditService auditService = mock(AuditService.class);
        SecurityTokenService tokenService = new SecurityTokenService();
        AuditServicePayrollReservationAudit audit =
                new AuditServicePayrollReservationAudit(
                        auditService,
                        () -> "principal-1",
                        tokenService);
        ExternalPreciseId periodId = new ExternalPreciseId("period-1");

        audit.recordDenied(periodId, DenialReason.FEATURE_DISABLED);

        verify(auditService).recordFailure(
                "principal-1",
                AuditServicePayrollReservationAudit.ACTION,
                AuditServicePayrollReservationAudit.RESOURCE_TYPE,
                "sha256:" + tokenService.digest(periodId.toString()),
                AuditServicePayrollReservationAudit.RESULT,
                "FEATURE_DISABLED");
        verifyNoMoreInteractions(auditService);
    }
}
