package com.szsemicon.hr.attendance.application;

import java.time.Instant;
import java.util.Optional;

public interface AttendanceSetupIdempotencyRepository {

    record StoredResponse(
            String recordId,
            String requestDigest,
            String state,
            Integer responseStatus,
            String responseHeadersJson,
            String responseBodyJson,
            Instant createdAt) {
    }

    Optional<StoredResponse> find(
            String actorId,
            String operation,
            String resourceType,
            String resourceId,
            String idempotencyKey);

    void insertStarted(
            String recordId,
            String actorId,
            String operation,
            String resourceType,
            String resourceId,
            String idempotencyKey,
            String requestDigest,
            Instant createdAt);

    boolean takeOverStarted(
            String currentRecordId,
            String nextRecordId,
            String requestDigest,
            Instant staleBefore,
            Instant createdAt);

    boolean complete(
            String recordId,
            String requestDigest,
            int responseStatus,
            String responseHeadersJson,
            String responseBodyJson,
            Instant completedAt);
}
