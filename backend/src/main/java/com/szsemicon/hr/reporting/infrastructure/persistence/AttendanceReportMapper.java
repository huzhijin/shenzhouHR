package com.szsemicon.hr.reporting.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendanceReportMapper {

    List<DashboardRows.CompanyRow> listDashboardAuthorizedCompanies(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("authorizationTime") Instant authorizationTime);

    List<DashboardRows.ProjectionRow>
            listLatestDashboardAuthorizedProjections(
                    @Param("principalId") String principalId,
                    @Param("capabilityCode") String capabilityCode,
                    @Param("periodStart") LocalDate periodStart,
                    @Param("periodEndExclusive")
                            LocalDate periodEndExclusive,
                    @Param("companyId") String companyId,
                    @Param("authorizationTime")
                            Instant authorizationTime);

    List<DashboardRows.ScopeRow> listDashboardAuthorizedScopes(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("companyId") String companyId,
            @Param("authorizationTime") Instant authorizationTime);

    DashboardRows.SummaryRow summarizeDashboardExceptions(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("companyId") String companyId,
            @Param("businessDate") LocalDate businessDate,
            @Param("authorizationTime") Instant authorizationTime);

    List<DashboardRows.DailyTrendRow> listDashboardDailyTrend(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("companyId") String companyId,
            @Param("trendStart") LocalDate trendStart,
            @Param("businessDate") LocalDate businessDate,
            @Param("authorizationTime") Instant authorizationTime);

    List<DashboardRows.SeverityDistributionRow>
            listDashboardSeverityDistribution(
                    @Param("principalId") String principalId,
                    @Param("capabilityCode") String capabilityCode,
                    @Param("projectionId") String projectionId,
                    @Param("companyId") String companyId,
                    @Param("businessDate") LocalDate businessDate,
                    @Param("authorizationTime")
                            Instant authorizationTime);

    List<DashboardRows.TypeDistributionRow>
            listDashboardTypeDistribution(
                    @Param("principalId") String principalId,
                    @Param("capabilityCode") String capabilityCode,
                    @Param("projectionId") String projectionId,
                    @Param("companyId") String companyId,
                    @Param("businessDate") LocalDate businessDate,
                    @Param("authorizationTime")
                            Instant authorizationTime);

    List<DashboardRows.OrganizationRankingRow>
            listDashboardOrganizationRanking(
                    @Param("principalId") String principalId,
                    @Param("capabilityCode") String capabilityCode,
                    @Param("projectionId") String projectionId,
                    @Param("companyId") String companyId,
                    @Param("businessDate") LocalDate businessDate,
                    @Param("authorizationTime")
                            Instant authorizationTime);

    List<String> listAuthorizedEmployeeIdsInScopeIntersection(
            @Param("companyId") String companyId,
            @Param("firstScopes") List<ReportRows.ScopeRow> firstScopes,
            @Param("secondScopes") List<ReportRows.ScopeRow> secondScopes,
            @Param("authorizationTime") Instant authorizationTime);

    long countCurrentOrganizationAncestor(
            @Param("companyId") String companyId,
            @Param("ancestorOrganizationId") String ancestorOrganizationId,
            @Param("descendantOrganizationId")
                    String descendantOrganizationId,
            @Param("authorizationTime") Instant authorizationTime);

    List<DashboardRows.ExceptionRow> listDashboardExceptions(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("companyId") String companyId,
            @Param("businessDate") LocalDate businessDate,
            @Param("authorizationTime") Instant authorizationTime,
            @Param("reportScopes") List<ReportRows.ScopeRow> reportScopes);

    List<ReportRows.CompanyRow> listAuthorizedCompanies(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("authorizationTime") Instant authorizationTime);

    List<ReportRows.ScopeRow> listRealtimeAuthorizedScopes(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("companyId") String companyId,
            @Param("authorizationTime") Instant authorizationTime);

    List<String> listAuthorizedOrganizationIds(
            @Param("companyId") String companyId,
            @Param("scopes") List<ReportRows.ScopeRow> scopes,
            @Param("authorizationTime") Instant authorizationTime);

    List<ReportRows.ProjectionRow> listLatestAuthorizedProjections(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("companyId") String companyId,
            @Param("authorizationTime") Instant authorizationTime);

    List<ReportRows.ScopeRow> listAuthorizedScopes(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("companyId") String companyId,
            @Param("authorizationTime") Instant authorizationTime);

    List<ReportRows.DepartmentAttendanceRateRow>
            listAuthorizedDepartmentAttendanceRates(
                    @Param("principalId") String principalId,
                    @Param("capabilityCode") String capabilityCode,
                    @Param("projectionId") String projectionId,
                    @Param("periodStart") LocalDate periodStart,
                    @Param("periodEndExclusive")
                            LocalDate periodEndExclusive,
                    @Param("companyId") String companyId,
                    @Param("organizationId") String organizationId,
                    @Param("authorizationTime")
                            Instant authorizationTime);

    List<ReportRows.EmployeeSickLeaveDaysRow>
            listAuthorizedEmployeeSickLeaveDays(
                    @Param("principalId") String principalId,
                    @Param("capabilityCode") String capabilityCode,
                    @Param("projectionId") String projectionId,
                    @Param("periodStart") LocalDate periodStart,
                    @Param("periodEndExclusive")
                            LocalDate periodEndExclusive,
                    @Param("companyId") String companyId,
                    @Param("organizationId") String organizationId,
                    @Param("employeeId") String employeeId,
                    @Param("authorizationTime")
                            Instant authorizationTime);

    List<ReportRows.EmployeeDepartmentAttendancePeriodRow>
            listAuthorizedEmployeeDepartmentAttendancePeriods(
                    @Param("principalId") String principalId,
                    @Param("capabilityCode") String capabilityCode,
                    @Param("projectionId") String projectionId,
                    @Param("periodStart") LocalDate periodStart,
                    @Param("periodEndExclusive")
                            LocalDate periodEndExclusive,
                    @Param("companyId") String companyId,
                    @Param("organizationId") String organizationId,
                    @Param("employeeId") String employeeId,
                    @Param("authorizationTime")
                            Instant authorizationTime);

    List<ReportRows.DailyRow> listAuthorizedDailyFacts(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("companyId") String companyId,
            @Param("organizationId") String organizationId,
            @Param("employeeId") String employeeId,
            @Param("authorizationTime") Instant authorizationTime);

    List<ReportRows.OaDocumentRow> listAuthorizedOaFacts(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("periodStartAt") Instant periodStartAt,
            @Param("periodEndExclusiveAt") Instant periodEndExclusiveAt,
            @Param("companyId") String companyId,
            @Param("organizationId") String organizationId,
            @Param("employeeId") String employeeId,
            @Param("authorizationTime") Instant authorizationTime);

    List<ReportRows.ExceptionRow> listAuthorizedExceptionFacts(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("companyId") String companyId,
            @Param("organizationId") String organizationId,
            @Param("employeeId") String employeeId,
            @Param("status") String status,
            @Param("authorizationTime") Instant authorizationTime);

    List<ReportRows.TimeAccountRow> listAuthorizedTimeAccountFacts(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("companyId") String companyId,
            @Param("organizationId") String organizationId,
            @Param("employeeId") String employeeId,
            @Param("authorizationTime") Instant authorizationTime);
}
