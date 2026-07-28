package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendancePeriodProtectionMapper {

    List<AttendancePeriodProjectionRow> resolveLatestPublished(
            @Param("legalEntityId") String legalEntityId,
            @Param("employeeId") String employeeId,
            @Param("businessDate") LocalDate businessDate);
}
