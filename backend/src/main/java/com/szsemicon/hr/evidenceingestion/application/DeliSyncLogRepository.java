package com.szsemicon.hr.evidenceingestion.application;

import java.time.Instant;
import java.util.Optional;

public interface DeliSyncLogRepository {

    void start(
            String logId,
            Instant startedAt,
            Instant syncTimeRangeStart,
            Instant syncTimeRangeEnd);

    void markSucceeded(
            String logId,
            Instant completedAt,
            long recordCount,
            long executionDurationMs);

    void markFailed(
            String logId,
            Instant completedAt,
            long recordCount,
            String errorMessage,
            long executionDurationMs);

    Optional<DeliSyncLogModels.SyncStatus> findLatestCompleted();

    /**
     * Legacy observational query for the end time recorded by the latest
     * successful scheduler run. This value is not an ingestion cursor and
     * must never be used to select or filter Deli punch records; each source's
     * committed {@code attendance_sync_watermark.committed_cursor}, which
     * carries the provider {@code next_id}, is authoritative.
     */
    Optional<Instant> findLastSuccessfulSyncTime();

    int deleteStartedBefore(Instant cutoff);
}
