package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import com.szsemicon.hr.evidenceingestion.application.DeliSyncLogModels;
import java.time.Instant;

public record DeliSyncStatusDto(
        Instant lastSyncTime,
        long recordCount,
        String status,
        String errorMessage) {

    static DeliSyncStatusDto from(DeliSyncLogModels.SyncStatus status) {
        return new DeliSyncStatusDto(
                status.lastSyncTime(),
                status.recordCount(),
                status.status(),
                status.errorMessage());
    }
}
