package com.szsemicon.hr.reporting.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PunchLocationMapper {

    List<DayPunchRow> listDayPunches(
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);

    LocationRow findLocation(@Param("rawFactId") String rawFactId);

    record DayPunchRow(
            String rawFactId,
            Instant punchedAt,
            String method) {
    }

    record LocationRow(
            String rawFactId,
            String companyId,
            String employeeId,
            Instant punchedAt,
            String method,
            String locationSummary,
            String sourceCoordinateSystem,
            String coordinateValidationStatus,
            String coordinateConversionStatus,
            BigDecimal mapLongitude,
            BigDecimal mapLatitude,
            String mapCoordinateSystem) {
    }
}
