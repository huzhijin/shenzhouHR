package com.szsemicon.hr.reporting.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface SelfAttendanceDashboardMapper {

    List<SelfDashboardRows.AuthorizationRow> resolveAuthorizedSelf(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("businessDate") LocalDate businessDate,
            @Param("authorizationTime") Instant authorizationTime);

    List<SelfDashboardRows.ProjectionRow>
            listLatestPublishedSelfProjection(
                    @Param("principalId") String principalId,
                    @Param("capabilityCode") String capabilityCode,
                    @Param("companyId") String companyId,
                    @Param("periodStart") LocalDate periodStart,
                    @Param("periodEndExclusive")
                            LocalDate periodEndExclusive,
                    @Param("businessDate") LocalDate businessDate,
                    @Param("authorizationTime")
                            Instant authorizationTime);

    List<SelfDashboardRows.DailyRow> listSelfDailyFacts(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("businessDate") LocalDate businessDate,
            @Param("authorizationTime") Instant authorizationTime);

    List<SelfDashboardRows.DailyIssueCountRow>
            listSelfDailyIssueCounts(
                    @Param("principalId") String principalId,
                    @Param("capabilityCode") String capabilityCode,
                    @Param("projectionId") String projectionId,
                    @Param("companyId") String companyId,
                    @Param("trendStart") LocalDate trendStart,
                    @Param("businessDate") LocalDate businessDate,
                    @Param("authorizationTime")
                            Instant authorizationTime);

    List<SelfDashboardRows.TodayIssueLabelRow>
            listSelfTodayIssueLabels(
                    @Param("principalId") String principalId,
                    @Param("capabilityCode") String capabilityCode,
                    @Param("projectionId") String projectionId,
                    @Param("companyId") String companyId,
                    @Param("businessDate") LocalDate businessDate,
                    @Param("authorizationTime")
                            Instant authorizationTime);

    List<SelfDashboardRows.ExceptionTypeCountRow>
            listSelfExceptionTypeDistribution(
                    @Param("principalId") String principalId,
                    @Param("capabilityCode") String capabilityCode,
                    @Param("projectionId") String projectionId,
                    @Param("companyId") String companyId,
                    @Param("periodStart") LocalDate periodStart,
                    @Param("businessDate") LocalDate businessDate,
                    @Param("authorizationTime")
                            Instant authorizationTime);

    List<SelfDashboardRows.RecentExceptionRow>
            listSelfRecentExceptions(
                    @Param("principalId") String principalId,
                    @Param("capabilityCode") String capabilityCode,
                    @Param("projectionId") String projectionId,
                    @Param("companyId") String companyId,
                    @Param("periodStart") LocalDate periodStart,
                    @Param("businessDate") LocalDate businessDate,
                    @Param("authorizationTime")
                            Instant authorizationTime);
}
