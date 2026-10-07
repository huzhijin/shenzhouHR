package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.application.DeliSyncLogModels;
import com.szsemicon.hr.evidenceingestion.application.DeliSyncLogRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisDeliSyncLogRepository implements DeliSyncLogRepository {

    private final DeliSyncLogMapper mapper;

    public MyBatisDeliSyncLogRepository(DeliSyncLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void start(
            String logId,
            Instant startedAt,
            Instant syncTimeRangeStart,
            Instant syncTimeRangeEnd) {
        mapper.insertStarted(
                logId, startedAt, syncTimeRangeStart, syncTimeRangeEnd);
    }

    @Override
    public void markSucceeded(
            String logId,
            Instant completedAt,
            long recordCount,
            long executionDurationMs) {
        requireUpdated(mapper.markSucceeded(
                logId, completedAt, recordCount, executionDurationMs));
    }

    @Override
    public void markFailed(
            String logId,
            Instant completedAt,
            long recordCount,
            String errorMessage,
            long executionDurationMs) {
        requireUpdated(mapper.markFailed(
                logId,
                completedAt,
                recordCount,
                errorMessage,
                executionDurationMs));
    }

    @Override
    public Optional<DeliSyncLogModels.SyncStatus> findLatestCompleted() {
        return Optional.ofNullable(mapper.findLatestCompleted());
    }

    @Override
    public Optional<Instant> findLastSuccessfulSyncTime() {
        return Optional.ofNullable(mapper.findLastSuccessfulSyncTime());
    }

    @Override
    public int deleteStartedBefore(Instant cutoff) {
        return mapper.deleteStartedBefore(cutoff);
    }

    private static void requireUpdated(int rows) {
        if (rows != 1) {
            throw new IllegalStateException(
                    "Deli sync log completion state is stale");
        }
    }
}
