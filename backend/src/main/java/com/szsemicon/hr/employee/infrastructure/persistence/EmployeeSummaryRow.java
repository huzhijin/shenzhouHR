package com.szsemicon.hr.employee.infrastructure.persistence;

import java.time.Instant;

public record EmployeeSummaryRow(
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
}
