package com.szsemicon.hr.employee.application;

import java.time.Instant;

public interface EmployeeReadRepository {

    EmployeePage findVisibleTo(
            String principalId,
            String capabilityCode,
            Instant at,
            int page,
            int size);

    default EmployeePage findVisibleTo(
            String principalId,
            String capabilityCode,
            Instant at,
            String query,
            String organizationId,
            String status,
            String sort,
            int page,
            int size) {
        return findVisibleTo(principalId, capabilityCode, at, page, size);
    }
}
