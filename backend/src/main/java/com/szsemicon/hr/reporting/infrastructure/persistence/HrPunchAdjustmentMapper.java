package com.szsemicon.hr.reporting.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HrPunchAdjustmentMapper {

    void insert(
            @Param("adjustmentId") String adjustmentId,
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId,
            @Param("businessDate") LocalDate businessDate,
            @Param("onDutyAt") Instant onDutyAt,
            @Param("offDutyAt") Instant offDutyAt,
            @Param("reason") String reason,
            @Param("createdBy") String createdBy,
            @Param("createdAt") Instant createdAt,
            @Param("overtimeMinutesOverride") Integer overtimeMinutesOverride,
            @Param("clearedExceptionTypes") String clearedExceptionTypes,
            @Param("dayTypes") String dayTypes);

    String findEmployeeCompanyId(@Param("employeeId") String employeeId);
}
