package com.szsemicon.hr.organization.application;

import com.szsemicon.hr.organization.domain.OrganizationUnit;
import java.time.Instant;
import java.util.List;

public interface OrganizationReadRepository {

    List<OrganizationUnit> findCurrentVisibleTo(
            String principalId,
            String capabilityCode,
            Instant permissionAt,
            Instant effectiveAt,
            boolean includeInactive);
}
