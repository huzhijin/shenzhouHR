package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.application.DeliSyncLogModels;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface DeliSyncLogMapper {

    void insertStarted(
            @Param("logId") String logId,
            @Param("startedAt") Instant startedAt,
            @Param("syncTimeRangeStart") Instant syncTimeRangeStart,
            @Param("syncTimeRangeEnd") Instant syncTimeRangeEnd);

    int markSucceeded(
            @Param("logId") String logId,
            @Param("completedAt") Instant completedAt,
            @Param("recordCount") long recordCount,
            @Param("executionDurationMs") long executionDurationMs);

    int markFailed(
            @Param("logId") String logId,
            @Param("completedAt") Instant completedAt,
            @Param("recordCount") long recordCount,
            @Param("errorMessage") String errorMessage,
            @Param("executionDurationMs") long executionDurationMs);

    DeliSyncLogModels.SyncStatus findLatestCompleted();

    Instant findLastSuccessfulSyncTime();

    int deleteStartedBefore(@Param("cutoff") Instant cutoff);
}
