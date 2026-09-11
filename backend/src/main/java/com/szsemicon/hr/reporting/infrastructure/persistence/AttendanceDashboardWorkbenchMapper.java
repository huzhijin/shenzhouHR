package com.szsemicon.hr.reporting.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AttendanceDashboardWorkbenchMapper {

    List<DashboardWorkbenchRows.RosterRow> listRoster(
            @Param("companyId") String companyId,
            @Param("businessDate") LocalDate businessDate);

    List<String> listPunchExemptEmployeeIds(
            @Param("companyId") String companyId,
            @Param("asOf") Instant asOf);

    List<DashboardWorkbenchRows.PunchRow> listPunchPoints(
            @Param("windowStart") Instant windowStart,
            @Param("windowEndExclusive") Instant windowEndExclusive);

    List<DashboardWorkbenchRows.EmployeeNumberRow> listEmployeeNumbers();

    List<DashboardWorkbenchRows.NegativeLeaveRow> listNegativeLeaveBalances(
            @Param("companyId") String companyId,
            @Param("accountYear") int accountYear,
            @Param("businessDate") LocalDate businessDate);
}
