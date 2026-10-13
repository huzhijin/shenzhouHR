package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.PunchLocationReadPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPunchLocationReadAdapter implements PunchLocationReadPort {

    private final PunchLocationMapper mapper;

    public MyBatisPunchLocationReadAdapter(PunchLocationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<DayPunchRow> listDayPunches(
            String companyId,
            String employeeId,
            Instant windowStart,
            Instant windowEnd) {
        List<PunchLocationMapper.DayPunchRow> rows =
                mapper.listDayPunches(companyId, employeeId, windowStart, windowEnd);
        if (rows == null) {
            return null; // caller keeps its own null-safe handling
        }
        List<DayPunchRow> converted = new ArrayList<>(rows.size());
        for (PunchLocationMapper.DayPunchRow row : rows) {
            converted.add(row == null
                    ? null
                    : new DayPunchRow(row.rawFactId(), row.punchedAt(), row.method()));
        }
        return converted;
    }

    @Override
    public LocationRow findLocation(String rawFactId) {
        PunchLocationMapper.LocationRow row = mapper.findLocation(rawFactId);
        if (row == null) {
            return null; // caller keeps the not-found + audit path
        }
        return new LocationRow(
                row.rawFactId(),
                row.companyId(),
                row.employeeId(),
                row.punchedAt(),
                row.method(),
                row.locationSummary(),
                row.sourceCoordinateSystem(),
                row.coordinateValidationStatus(),
                row.coordinateConversionStatus(),
                row.mapLongitude(),
                row.mapLatitude(),
                row.mapCoordinateSystem());
    }
}
