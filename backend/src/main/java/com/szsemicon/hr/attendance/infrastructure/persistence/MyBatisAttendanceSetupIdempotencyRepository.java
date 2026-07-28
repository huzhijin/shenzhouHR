package com.szsemicon.hr.attendance.infrastructure.persistence;

import com.szsemicon.hr.attendance.application.AttendanceSetupIdempotencyRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisAttendanceSetupIdempotencyRepository
        implements AttendanceSetupIdempotencyRepository {

    private final AttendanceSetupIdempotencyMapper mapper;

    MyBatisAttendanceSetupIdempotencyRepository(
            AttendanceSetupIdempotencyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<StoredResponse> find(
            String actorId,
            String operation,
            String resourceType,
            String resourceId,
            String idempotencyKey) {
        return Optional.ofNullable(mapper.find(
                actorId, operation, resourceType, resourceId, idempotencyKey));
    }

    @Override
    public void insertStarted(
            String recordId,
            String actorId,
            String operation,
            String resourceType,
            String resourceId,
            String idempotencyKey,
            String requestDigest,
            Instant createdAt) {
        mapper.insertStarted(
                recordId, actorId, operation, resourceType, resourceId,
                idempotencyKey, requestDigest, createdAt);
    }

    @Override
    public boolean takeOverStarted(
            String currentRecordId,
            String nextRecordId,
            String requestDigest,
            Instant staleBefore,
            Instant createdAt) {
        return mapper.takeOverStarted(
                currentRecordId,
                nextRecordId,
                requestDigest,
                staleBefore,
                createdAt) == 1;
    }

    @Override
    public boolean complete(
            String recordId,
            String requestDigest,
            int responseStatus,
            String responseHeadersJson,
            String responseBodyJson,
            Instant completedAt) {
        return mapper.complete(
                recordId, requestDigest, responseStatus,
                responseHeadersJson, responseBodyJson, completedAt) == 1;
    }
}
