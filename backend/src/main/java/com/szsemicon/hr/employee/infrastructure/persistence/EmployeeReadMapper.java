package com.szsemicon.hr.employee.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface EmployeeReadMapper {

    long countVisibleTo(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("at") Instant at,
            @Param("query") String query,
            @Param("organizationId") String organizationId,
            @Param("companyId") String companyId,
            @Param("status") String status);

    List<EmployeeSummaryRow> findVisibleTo(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("at") Instant at,
            @Param("query") String query,
            @Param("organizationId") String organizationId,
            @Param("companyId") String companyId,
            @Param("status") String status,
            @Param("sort") String sort,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
