package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.WorkWindowFact;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AttendanceWorkWindowQuery {

    private final AttendanceReportCalculationMapper mapper;

    public AttendanceWorkWindowQuery(AttendanceReportCalculationMapper mapper) {
        this.mapper = mapper;
    }

    public List<WorkWindowFact> list(
            String companyId, YearMonth period, Instant asOf) {
        if (companyId == null || period == null || asOf == null) {
            return List.of();
        }
        LocalDate periodStart = period.atDay(1);
        LocalDate periodEndExclusive = period.plusMonths(1).atDay(1);
        List<ShiftSegmentRow> rows = mapper.findScheduledWorkSegments(
                companyId, periodStart, periodEndExclusive, asOf);
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(row -> new WorkWindowFact(
                        row.employeeId(),
                        row.businessDate(),
                        row.segmentStart(),
                        row.segmentEnd()))
                .toList();
    }
}
