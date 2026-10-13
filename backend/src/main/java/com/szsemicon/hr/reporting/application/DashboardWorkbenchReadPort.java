package com.szsemicon.hr.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface DashboardWorkbenchReadPort {

    List<DashboardWorkbenchRows.RosterRow> listRoster(
            String companyId,
            LocalDate businessDate);

    List<String> listPunchExemptEmployeeIds(
            String companyId,
            Instant asOf);

    List<DashboardWorkbenchRows.PunchRow> listPunchPoints(
            Instant windowStart,
            Instant windowEndExclusive);

    List<DashboardWorkbenchRows.EmployeeNumberRow> listEmployeeNumbers();

    List<DashboardWorkbenchRows.NegativeLeaveRow> listNegativeLeaveBalances(
            String companyId,
            int accountYear,
            LocalDate businessDate);
}
