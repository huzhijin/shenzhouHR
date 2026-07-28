package com.szsemicon.hr.attendance.infrastructure.persistence;

import com.szsemicon.hr.attendance.application.AttendanceSetupIdempotencyRepository.StoredResponse;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendanceSetupIdempotencyMapper {

    StoredResponse find(
            @Param("actorId") String actorId,
            @Param("operation") String operation,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("idempotencyKey") String idempotencyKey);

    void insertStarted(
            @Param("recordId") String recordId,
            @Param("actorId") String actorId,
            @Param("operation") String operation,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestDigest") String requestDigest,
            @Param("createdAt") Instant createdAt);

    int takeOverStarted(
            @Param("currentRecordId") String currentRecordId,
            @Param("nextRecordId") String nextRecordId,
            @Param("requestDigest") String requestDigest,
            @Param("staleBefore") Instant staleBefore,
            @Param("createdAt") Instant createdAt);

    int complete(
            @Param("recordId") String recordId,
            @Param("requestDigest") String requestDigest,
            @Param("responseStatus") int responseStatus,
            @Param("responseHeadersJson") String responseHeadersJson,
            @Param("responseBodyJson") String responseBodyJson,
            @Param("completedAt") Instant completedAt);
}
