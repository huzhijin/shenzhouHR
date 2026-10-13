package com.szsemicon.hr.employee.application;

import java.time.Instant;

/**
 * Port for employee authorization checks.
 * Isolates application layer from infrastructure persistence details.
 */
public interface EmployeeAuthorizationPort {

    /**
     * Check if a principal can access a specific employee's data with the given capability.
     *
     * @param principalId the principal (user) ID performing the access
     * @param capabilityCode the capability code required for access
     * @param at the temporal point for the authorization check
     * @param employeeId the employee ID being accessed
     * @return true if access is granted, false otherwise
     */
    boolean canAccessEmployee(
            String principalId,
            String capabilityCode,
            Instant at,
            String employeeId);
}
