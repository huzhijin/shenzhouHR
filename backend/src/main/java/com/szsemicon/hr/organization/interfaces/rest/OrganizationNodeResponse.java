package com.szsemicon.hr.organization.interfaces.rest;

import java.time.Instant;
import java.util.List;

public record OrganizationNodeResponse(
        String organizationId,
        String organizationVersionId,
        String code,
        String name,
        String organizationType,
        String status,
        String sourceOrganizationId,
        Instant effectiveFrom,
        Instant effectiveTo,
        String sourceAuthority,
        long rowVersion,
        List<OrganizationNodeResponse> children) {
}
