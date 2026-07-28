package com.szsemicon.hr.organization.domain;

import java.time.Instant;
import java.util.Objects;

public record OrganizationUnit(
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

    public OrganizationUnit(
            String organizationId,
            String parentOrganizationId,
            String code,
            String name,
            String organizationType,
            String status,
            String sourceOrganizationId,
            Instant effectiveFrom,
            Instant effectiveTo) {
        this(
                organizationId,
                organizationId,
                parentOrganizationId,
                code,
                name,
                organizationType,
                status,
                sourceOrganizationId,
                effectiveFrom,
                effectiveTo,
                "LOCAL",
                0);
    }

    public OrganizationUnit {
        Objects.requireNonNull(organizationId, "organization id is required");
        Objects.requireNonNull(organizationVersionId, "organization version id is required");
        Objects.requireNonNull(code, "organization code is required");
        Objects.requireNonNull(name, "organization name is required");
        Objects.requireNonNull(organizationType, "organization type is required");
        Objects.requireNonNull(status, "organization status is required");
        Objects.requireNonNull(effectiveFrom, "effective from is required");
        Objects.requireNonNull(sourceAuthority, "source authority is required");
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effective to must be after effective from");
        }
    }
}
