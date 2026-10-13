package com.szsemicon.hr.employee.infrastructure.persistence;

import com.szsemicon.hr.employee.application.EmployeeAuthorizationPort;
import java.time.Instant;
import org.springframework.stereotype.Repository;

/**
 * MyBatis adapter for employee authorization port.
 * Delegates to EmployeeReadMapper.canAccessEmployee for precise authorization checks.
 */
@Repository
public class MyBatisEmployeeAuthorizationAdapter implements EmployeeAuthorizationPort {

    private final EmployeeReadMapper employeeReadMapper;

    public MyBatisEmployeeAuthorizationAdapter(EmployeeReadMapper employeeReadMapper) {
        this.employeeReadMapper = employeeReadMapper;
    }

    @Override
    public boolean canAccessEmployee(String principalId, String capabilityCode, Instant at, String employeeId) {
        return employeeReadMapper.canAccessEmployee(principalId, capabilityCode, at, employeeId);
    }
}
