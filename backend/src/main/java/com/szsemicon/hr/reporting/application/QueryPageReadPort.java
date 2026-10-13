package com.szsemicon.hr.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface QueryPageReadPort {

    QueryPageRows.PinRow findLatestPin(
            String companyId,
            LocalDate periodStart,
            LocalDate periodEndExclusive);

    boolean companyHasSourceEvidence(
            String companyId,
            LocalDate periodStart,
            LocalDate periodEndExclusive,
            Instant dataAsOf);

    List<String> listDescendantOrganizationIds(
            String organizationId);

    List<QueryPageRows.DirectoryEmployeeRow> listDirectoryEmployees(
            String companyId,
            List<String> organizationIds,
            boolean companyWide,
            List<String> employeeIds,
            Instant periodStart,
            Instant periodEndExclusive,
            Instant asOf);

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

    List<QueryPageRows.LegacyLeaveDayEvidenceRow> listLegacyLeaveDayEvidence(
            QueryPageRows.FactQuery query,
            List<String> documentIds);

    List<QueryPageRows.OaUsageDocumentRow> listLeaveUsageDocuments(
            QueryPageRows.FactQuery query);
}
