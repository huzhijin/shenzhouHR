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

    int insertDailyFacts(
            @Param("rows") java.util.List<
                    AttendanceReportProjectionWriteRows.DailyFactRow> rows);

    int insertExceptionFact(
            AttendanceReportProjectionWriteRows.ExceptionFactRow row);

    int insertExceptionFacts(
            @Param("rows") java.util.List<
                    AttendanceReportProjectionWriteRows.ExceptionFactRow> rows);

    int insertOaDocumentFact(
            AttendanceReportProjectionWriteRows.OaDocumentFactRow row);

    int insertOaDocumentFacts(
            @Param("rows") java.util.List<
                    AttendanceReportProjectionWriteRows.OaDocumentFactRow> rows);

    int insertTimeAccountFact(
            AttendanceReportProjectionWriteRows.TimeAccountFactRow row);

    int insertTimeAccountFacts(
            @Param("rows") java.util.List<
                    AttendanceReportProjectionWriteRows.TimeAccountFactRow> rows);

    int markPublished(
            @Param("projectionId") String projectionId,
            @Param("publishedAt") Instant publishedAt);

    int copyDailyFactsOutsideRange(
            @Param("sourceProjectionId") String sourceProjectionId,
            @Param("targetProjectionId") String targetProjectionId,
            @Param("windowStart") LocalDate windowStart,
            @Param("windowEndExclusive") LocalDate windowEndExclusive,
            @Param("createdAt") Instant createdAt,
            @Param("employeeId") String employeeId,
            @Param("employeeIds") java.util.List<String> employeeIds);

    int copyExceptionFactsOutsideRange(
            @Param("sourceProjectionId") String sourceProjectionId,
            @Param("targetProjectionId") String targetProjectionId,
            @Param("windowStart") LocalDate windowStart,
            @Param("windowEndExclusive") LocalDate windowEndExclusive,
            @Param("createdAt") Instant createdAt,
            @Param("employeeId") String employeeId,
            @Param("employeeIds") java.util.List<String> employeeIds);

    int copyOaDocumentFactsExceptEmployeeWindow(
            @Param("sourceProjectionId") String sourceProjectionId,
            @Param("targetProjectionId") String targetProjectionId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEndExclusive") Instant windowEndExclusive,
            @Param("createdAt") Instant createdAt,
            @Param("employeeId") String employeeId,
            @Param("employeeIds") java.util.List<String> employeeIds);

    int copyTimeAccountFactsExceptEmployee(
            @Param("sourceProjectionId") String sourceProjectionId,
            @Param("targetProjectionId") String targetProjectionId,
            @Param("createdAt") Instant createdAt,
            @Param("employeeId") String employeeId,
            @Param("employeeIds") java.util.List<String> employeeIds);
}
