package com.szsemicon.hr.employee.domain;

import java.time.Instant;
import java.util.Objects;

public record EmployeeSummary(
        String employeeId,
        String employeeVersionId,
        String employeeNumber,
        String displayName,
        String employmentStatus,
        String organizationId,
        String organizationName,
        String organizationCode,
        String seeyonOaCode,
        String bindingStatus,
        Instant assignmentEffectiveFrom,
        Instant assignmentEffectiveTo,
        String sourceAuthority,
        long rowVersion) {

    public EmployeeSummary {
        Objects.requireNonNull(employeeId, "employee id is required");
        Objects.requireNonNull(employeeVersionId, "employee version id is required");
        Objects.requireNonNull(employeeNumber, "employee number is required");
        Objects.requireNonNull(displayName, "employee display name is required");
        Objects.requireNonNull(employmentStatus, "employment status is required");
        Objects.requireNonNull(sourceAuthority, "source authority is required");
    }
}
