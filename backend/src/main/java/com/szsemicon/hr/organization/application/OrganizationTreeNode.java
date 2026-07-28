package com.szsemicon.hr.organization.application;

import java.time.Instant;
import java.util.List;

public record OrganizationTreeNode(
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
        List<OrganizationTreeNode> children) {
}
