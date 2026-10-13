package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.reporting.application.AttendanceReportDependencyPlan;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.mock;

/**
 * Test-only bridge for persistence tests that need real planner window expansion
 * without exposing package-private orchestrator internals or changing product visibility.
 */
public final class PlannedWindowTestFixture {
    private PlannedWindowTestFixture() {}

    /**
     * Delegates to real planner with minimal mocked mapper for window expansion only.
     * H2 fixture keeps synthetic facts; use returned plan's range for publish boundaries.
     */
    public static AttendanceReportDependencyPlan plan(
            String companyId,
            YearMonth period,
            String baselineProjectionVersion,
            Instant dataAsOf,
            LocalDate writeFrom,
            LocalDate writeTo,
            List<String> employeeIds) {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        var orchestrator = new FullCalculationEngineOrchestrator(mapper);
        return orchestrator.planWindow(companyId, period, baselineProjectionVersion,
                dataAsOf, writeFrom, writeTo, employeeIds);
    }
}
