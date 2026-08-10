package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.EmployeeIdentityIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.PunchEventRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Batch reads for assembling a company-month report projection. Every
 * statement is scoped to one company and one period so the orchestrator never
 * issues a per-employee or per-day query.
 */
@Mapper
interface AttendanceReportCalculationMapper {

    /**
     * Returns every active employee identity interval that overlaps the
     * period. One row per employee version / assignment / organization
     * version combination, not per day.
     */
    List<EmployeeIdentityIntervalRow> findEmployeeIdentityIntervals(
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive);

    /**
     * Returns every activated punch point in the half-open instant window.
     * Reversed or superseded events are excluded by their latest lifecycle
     * fact, consistent with the evidence-ingestion read path.
     */
    List<PunchEventRow> findActivatedPunchEvents(
            @Param("companyId") String companyId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEndExclusive") Instant windowEndExclusive);

    /**
     * Returns the published work-calendar days for the company inside the
     * period. Days absent from the calendar are classified by weekday.
     */
    List<CalendarDayRow> findPublishedCalendarDays(
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive);
}
