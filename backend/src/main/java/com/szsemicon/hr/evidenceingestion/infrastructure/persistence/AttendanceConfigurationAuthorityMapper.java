package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendanceConfigurationAuthorityMapper {

    List<AttendanceConfigurationAuthorityRow> resolveForBusinessDate(
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId,
            @Param("businessDate") LocalDate businessDate,
            @Param("knowledgeAsOf") Instant knowledgeAsOf);
}
