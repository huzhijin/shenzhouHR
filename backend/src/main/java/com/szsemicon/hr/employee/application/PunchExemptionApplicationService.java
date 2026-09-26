package com.szsemicon.hr.employee.application;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.employee.infrastructure.persistence.EmployeeReadMapper;
import com.szsemicon.hr.employee.infrastructure.persistence.PunchExemptionMapper;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PunchExemptionApplicationService {

    private final CurrentPrincipalProvider principalProvider;
    private final EmployeeReadMapper employeeReadMapper;
    private final PunchExemptionMapper punchExemptionMapper;
    private final Clock clock;

    public PunchExemptionApplicationService(
            CurrentPrincipalProvider principalProvider,
            EmployeeReadMapper employeeReadMapper,
            PunchExemptionMapper punchExemptionMapper,
            Clock clock) {
        this.principalProvider = principalProvider;
        this.employeeReadMapper = employeeReadMapper;
        this.punchExemptionMapper = punchExemptionMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PunchExemptionStatus get(String employeeId) {
        requireAccess(employeeId, CapabilityCodes.EMPLOYEE_READ);
        return status(employeeId);
    }

    @Transactional
    public PunchExemptionStatus setStanding(String employeeId, boolean standingExempt) {
        requireAccess(employeeId, CapabilityCodes.EMPLOYEE_EDIT);
        Instant now = clock.instant();
        if (standingExempt) {
            String employeeNumber = punchExemptionMapper.currentEmployeeNumber(employeeId);
            if (employeeNumber == null || employeeNumber.isBlank()) {
                throw new ResourceNotAvailableAccessDeniedException();
            }
            punchExemptionMapper.insertStanding(
                    UUID.randomUUID().toString(),
                    employeeId,
                    employeeNumber,
                    now);
        } else {
            punchExemptionMapper.closeStanding(employeeId, now);
        }
        return status(employeeId);
    }

    private PunchExemptionStatus status(String employeeId) {
        return new PunchExemptionStatus(
                punchExemptionMapper.hasStandingExemption(employeeId),
                punchExemptionMapper.hasExecutiveRole(employeeId));
    }

    private void requireAccess(String employeeId, String capability) {
        Objects.requireNonNull(employeeId, "employeeId");
        boolean allowed = employeeReadMapper.canAccessEmployee(
                principalProvider.currentPrincipalId(),
                capability,
                clock.instant(),
                employeeId);
        if (!allowed) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
    }

    public record PunchExemptionStatus(
            boolean standingExempt,
            boolean executiveExempt) {
    }
}
