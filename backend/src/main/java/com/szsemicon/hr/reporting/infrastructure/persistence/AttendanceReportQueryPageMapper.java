package com.szsemicon.hr.reporting.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AttendanceReportQueryPageMapper {

    QueryPageRows.PinRow findLatestPin(
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive);

    boolean companyHasSourceEvidence(
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("dataAsOf") Instant dataAsOf);

    List<String> listDescendantOrganizationIds(
            @Param("organizationId") String organizationId);

    List<QueryPageRows.DirectoryEmployeeRow> listDirectoryEmployees(
            @Param("companyId") String companyId,
            @Param("organizationIds") List<String> organizationIds,
            @Param("companyWide") boolean companyWide,
            @Param("employeeIds") List<String> employeeIds,
            @Param("asOf") Instant asOf);

    long countExceptions(QueryPageRows.FactQuery query);

    List<QueryPageRows.ExceptionRow> listExceptions(QueryPageRows.FactQuery query);

    long countOaDocuments(QueryPageRows.FactQuery query);

    long countLeaveSummary(QueryPageRows.FactQuery query);

    List<QueryPageRows.LeaveSummaryRow> listLeaveSummary(QueryPageRows.FactQuery query);

    List<QueryPageRows.OaRow> listOaDocuments(QueryPageRows.FactQuery query);

    long countEmployeeDailyAggregates(QueryPageRows.FactQuery query);

    List<QueryPageRows.EmployeeDailyAggregateRow> listEmployeeDailyAggregates(
            QueryPageRows.FactQuery query);

    long countTimeAccounts(QueryPageRows.FactQuery query);

    List<QueryPageRows.TimeAccountRow> listTimeAccounts(QueryPageRows.FactQuery query);

    List<QueryPageRows.DirectoryEmployeeRow> listMatrixEmployees(
            QueryPageRows.FactQuery query);

    long countMatrixEmployees(QueryPageRows.FactQuery query);

    List<QueryPageRows.DailyCellRow> listDailyCells(QueryPageRows.FactQuery query);

    long countDailyJournal(QueryPageRows.FactQuery query);

    List<QueryPageRows.DailyJournalRow> listDailyJournal(QueryPageRows.FactQuery query);

    long countOvertimeDaily(QueryPageRows.FactQuery query);

    List<QueryPageRows.DailyJournalRow> listOvertimeDaily(QueryPageRows.FactQuery query);

    long countTimeOffDaily(QueryPageRows.FactQuery query);

    List<QueryPageRows.DailyJournalRow> listTimeOffDaily(QueryPageRows.FactQuery query);

    long countFinanceOvertimePeople(QueryPageRows.FactQuery query);

    List<QueryPageRows.DirectoryEmployeeRow> listFinanceOvertimePeople(
            QueryPageRows.FactQuery query);

    List<QueryPageRows.FinanceOvertimeCellRow> listFinanceOvertimeCells(
            QueryPageRows.FactQuery query);

    long countAbsenceStatPeople(QueryPageRows.FactQuery query);

    List<QueryPageRows.DirectoryEmployeeRow> listAbsenceStatPeople(
            QueryPageRows.FactQuery query);

    List<QueryPageRows.DailyMetricCellRow> listAbsenceStatCells(
            QueryPageRows.FactQuery query);

    long countLeaveStatPeople(QueryPageRows.FactQuery query);

    List<QueryPageRows.DirectoryEmployeeRow> listLeaveStatPeople(
            QueryPageRows.FactQuery query);

    List<QueryPageRows.DailyMetricCellRow> listLeaveStatCells(
            QueryPageRows.FactQuery query);

    List<QueryPageRows.OaRow> listOaForEmployees(QueryPageRows.FactQuery query);

    List<QueryPageRows.ExceptionRow> listExceptionsForEmployees(
            QueryPageRows.FactQuery query);

    long countMissedPunchStatEmployees(QueryPageRows.FactQuery query);

    List<QueryPageRows.DirectoryEmployeeRow> listMissedPunchStatEmployees(
            QueryPageRows.FactQuery query);

    long countLeaveStatAccounts(QueryPageRows.FactQuery query);

    List<QueryPageRows.LeaveStatAccountRow> listLeaveStatAccounts(
            QueryPageRows.FactQuery query);

    List<QueryPageRows.MonthlyLeaveUsageRow> listMonthlyLeaveUsage(
            QueryPageRows.FactQuery query);
}
