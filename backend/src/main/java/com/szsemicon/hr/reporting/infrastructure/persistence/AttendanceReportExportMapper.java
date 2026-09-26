package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.infrastructure.persistence.AttendanceReportExportRows.ExportReadRow;
import com.szsemicon.hr.reporting.infrastructure.persistence.AttendanceReportExportRows.ExportWriteRow;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendanceReportExportMapper {

    void insertJob(ExportWriteRow row);

    void insertArtifact(
            @Param("exportId") String exportId,
            @Param("content") byte[] content,
            @Param("createdAt") Instant createdAt);

    ExportReadRow findOwnedJob(
            @Param("exportId") String exportId,
            @Param("principalId") String principalId);

    ExportReadRow findOwnedReadyArtifact(
            @Param("exportId") String exportId,
            @Param("principalId") String principalId,
            @Param("expectedQueryFingerprint")
                    String expectedQueryFingerprint,
            @Param("expectedVisibleContentDigest")
                    String expectedVisibleContentDigest,
            @Param("readAt") Instant readAt);

    ExportReadRow findNextQueuedForUpdate(
            @Param("claimedAt") Instant claimedAt);

    int markBuilding(
            @Param("exportId") String exportId,
            @Param("claimedAt") Instant claimedAt);

    int markReady(
            @Param("exportId") String exportId,
            @Param("contentType") String contentType,
            @Param("fileExtension") String fileExtension,
            @Param("contentSha256") String contentSha256,
            @Param("expectedVisibleContentDigest")
                    String expectedVisibleContentDigest,
            @Param("contentLength") long contentLength,
            @Param("completedAt") Instant completedAt);

    int markFailed(
            @Param("exportId") String exportId,
            @Param("failureCode") String failureCode,
            @Param("completedAt") Instant completedAt);

    int purgeExpired(
            @Param("expiredAt") Instant expiredAt,
            @Param("limit") int limit);
}
