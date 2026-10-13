package com.szsemicon.hr.reporting.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Punch location reads; SQL stays in PunchLocationMapper.xml. */
public interface PunchLocationReadPort {

    List<DayPunchRow> listDayPunches(
            String companyId,
            String employeeId,
            Instant windowStart,
            Instant windowEnd);

    /** Location of one raw punch fact, or null when it does not exist. */
    LocationRow findLocation(String rawFactId);

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
