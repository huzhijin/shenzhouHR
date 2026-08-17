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
     * Returns the exclusive lower-bound marker established by the latest
     * successful run. Failed runs are deliberately excluded.
     */
    Optional<Instant> findLastSuccessfulSyncTime();

    int deleteStartedBefore(Instant cutoff);
}
