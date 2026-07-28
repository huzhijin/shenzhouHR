package com.szsemicon.hr.organization.infrastructure.persistence;

import com.szsemicon.hr.organization.application.OrganizationReadRepository;
import com.szsemicon.hr.organization.domain.OrganizationUnit;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisOrganizationReadRepository implements OrganizationReadRepository {

    private final OrganizationReadMapper mapper;

    public MyBatisOrganizationReadRepository(OrganizationReadMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<OrganizationUnit> findCurrentVisibleTo(
            String principalId,
            String capabilityCode,
            Instant permissionAt,
            Instant effectiveAt,
            boolean includeInactive) {
        return mapper.findCurrentVisibleTo(
                        principalId, capabilityCode, permissionAt, effectiveAt, includeInactive)
                .stream()
                .map(row -> new OrganizationUnit(
                        row.organizationId(),
                        row.organizationVersionId(),
                        row.parentOrganizationId(),
                        row.code(),
                        row.name(),
                        row.organizationType(),
                        row.status(),
                        row.sourceOrganizationId(),
                        row.effectiveFrom(),
                        row.effectiveTo(),
                        row.sourceAuthority(),
                        row.rowVersion()))
                .toList();
    }
}
