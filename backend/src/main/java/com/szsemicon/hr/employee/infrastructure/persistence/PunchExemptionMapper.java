package com.szsemicon.hr.employee.infrastructure.persistence;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PunchExemptionMapper {

    boolean hasStandingExemption(@Param("employeeId") String employeeId);

    boolean hasExecutiveRole(@Param("employeeId") String employeeId);

    String currentEmployeeNumber(@Param("employeeId") String employeeId);

    int insertStanding(
            @Param("exemptionId") String exemptionId,
            @Param("employeeId") String employeeId,
            @Param("employeeNumber") String employeeNumber,
            @Param("validFrom") Instant validFrom);

    int closeStanding(
            @Param("employeeId") String employeeId,
            @Param("validTo") Instant validTo);
}
