package com.szsemicon.hr.payroll.infrastructure;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.payroll.application.PayrollCapabilityAuthorizer;
import org.springframework.stereotype.Component;

@Component
final class CurrentCapabilityPayrollAuthorizer
        implements PayrollCapabilityAuthorizer {

    private final CurrentCapabilityService capabilityService;

    CurrentCapabilityPayrollAuthorizer(CurrentCapabilityService capabilityService) {
        this.capabilityService = capabilityService;
    }

    @Override
    public void requireReservationRead() {
        capabilityService.require(CapabilityCodes.PAYROLL_RESERVATION_READ);
    }
}
