package com.szsemicon.hr.organization.infrastructure.persistence;

import java.time.Instant;

public record OrganizationUnitRow(
        String organizationId,
        String organizationVersionId,
        String parentOrganizationId,
        String code,
        String name,
        String organizationType,
        String status,
        String sourceOrganizationId,
        Instant effectiveFrom,
        Instant effectiveTo,
        String sourceAuthority,
        long rowVersion) {
}
