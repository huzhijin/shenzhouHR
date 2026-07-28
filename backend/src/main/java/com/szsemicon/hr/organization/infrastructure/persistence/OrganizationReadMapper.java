package com.szsemicon.hr.organization.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface OrganizationReadMapper {

    List<OrganizationUnitRow> findCurrentVisibleTo(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("permissionAt") Instant permissionAt,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("includeInactive") boolean includeInactive);
}
