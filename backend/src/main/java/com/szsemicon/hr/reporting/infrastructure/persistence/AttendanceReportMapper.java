package com.szsemicon.hr.reporting.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendanceReportMapper {

    List<ReportRows.LegalEntityRow> listAuthorizedLegalEntities(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("authorizationTime") Instant authorizationTime);

    List<ReportRows.ProjectionRow> listLatestAuthorizedProjections(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("legalEntityId") String legalEntityId,
            @Param("authorizationTime") Instant authorizationTime);

    List<ReportRows.ScopeRow> listAuthorizedScopes(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("legalEntityId") String legalEntityId,
            @Param("authorizationTime") Instant authorizationTime);

    List<ReportRows.DailyRow> listAuthorizedDailyFacts(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("legalEntityId") String legalEntityId,
            @Param("organizationId") String organizationId,
            @Param("employeeId") String employeeId,
            @Param("authorizationTime") Instant authorizationTime);

    List<ReportRows.OaDocumentRow> listAuthorizedOaFacts(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("periodStartAt") Instant periodStartAt,
            @Param("periodEndExclusiveAt") Instant periodEndExclusiveAt,
            @Param("legalEntityId") String legalEntityId,
            @Param("organizationId") String organizationId,
            @Param("employeeId") String employeeId,
            @Param("authorizationTime") Instant authorizationTime);

    List<ReportRows.ExceptionRow> listAuthorizedExceptionFacts(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("legalEntityId") String legalEntityId,
            @Param("organizationId") String organizationId,
            @Param("employeeId") String employeeId,
            @Param("status") String status,
            @Param("authorizationTime") Instant authorizationTime);

    List<ReportRows.TimeAccountRow> listAuthorizedTimeAccountFacts(
            @Param("principalId") String principalId,
            @Param("capabilityCode") String capabilityCode,
            @Param("projectionId") String projectionId,
            @Param("legalEntityId") String legalEntityId,
            @Param("organizationId") String organizationId,
            @Param("employeeId") String employeeId,
            @Param("authorizationTime") Instant authorizationTime);
}
