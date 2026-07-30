package com.szsemicon.hr.reporting.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendanceReportProjectionWriteMapper {

    String lockCompany(@Param("companyId") String companyId);

    AttendanceReportProjectionWriteRows.StoredProjectionRow findByDigest(
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("projectionDigest") String projectionDigest);

    AttendanceReportProjectionWriteRows.StoredProjectionRow
            findLatestPublished(
                    @Param("companyId") String companyId,
                    @Param("periodStart") LocalDate periodStart);

    int insertDraft(
            AttendanceReportProjectionWriteRows.ProjectionDraftRow row);

    int insertDailyFact(
            AttendanceReportProjectionWriteRows.DailyFactRow row);

    int insertExceptionFact(
            AttendanceReportProjectionWriteRows.ExceptionFactRow row);

    int insertOaDocumentFact(
            AttendanceReportProjectionWriteRows.OaDocumentFactRow row);

    int insertTimeAccountFact(
            AttendanceReportProjectionWriteRows.TimeAccountFactRow row);

    int markPublished(
            @Param("projectionId") String projectionId,
            @Param("publishedAt") Instant publishedAt);
}
