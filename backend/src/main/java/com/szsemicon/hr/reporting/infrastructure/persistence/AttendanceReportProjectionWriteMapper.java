package com.szsemicon.hr.reporting.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendanceReportProjectionWriteMapper {

    String lockLegalEntity(@Param("legalEntityId") String legalEntityId);

    AttendanceReportProjectionWriteRows.StoredProjectionRow findByDigest(
            @Param("legalEntityId") String legalEntityId,
            @Param("periodStart") LocalDate periodStart,
            @Param("projectionDigest") String projectionDigest);

    AttendanceReportProjectionWriteRows.StoredProjectionRow
            findLatestPublished(
                    @Param("legalEntityId") String legalEntityId,
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
