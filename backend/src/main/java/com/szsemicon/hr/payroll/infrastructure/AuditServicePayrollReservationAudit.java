package com.szsemicon.hr.payroll.infrastructure;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.payroll.application.PayrollReservationAudit;
import com.szsemicon.hr.shared.domain.ExternalPreciseId;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import org.springframework.stereotype.Component;

@Component
final class AuditServicePayrollReservationAudit implements PayrollReservationAudit {

    static final String ACTION = "PAYROLL_RESERVATION_DENIED";
    static final String RESOURCE_TYPE = "PAYROLL_PERIOD";
    static final String RESULT = "DENIED";

    private final AuditService auditService;
    private final CurrentPrincipalProvider principalProvider;
    private final SecurityTokenService tokenService;

    AuditServicePayrollReservationAudit(
            AuditService auditService,
            CurrentPrincipalProvider principalProvider,
            SecurityTokenService tokenService) {
        this.auditService = auditService;
        this.principalProvider = principalProvider;
        this.tokenService = tokenService;
    }

    @Override
    public void recordDenied(ExternalPreciseId payrollPeriodId, DenialReason reason) {
        String resourceDigest =
                "sha256:" + tokenService.digest(payrollPeriodId.toString());
        auditService.recordFailure(
                principalProvider.currentPrincipalId(),
                ACTION,
                RESOURCE_TYPE,
                resourceDigest,
                RESULT,
                reason.name());
    }
}
