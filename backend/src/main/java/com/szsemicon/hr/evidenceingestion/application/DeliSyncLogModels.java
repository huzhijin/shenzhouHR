package com.szsemicon.hr.evidenceingestion.application;

import java.time.Instant;

public final class DeliSyncLogModels {

    private DeliSyncLogModels() {
    }

    /** Neutral, display-only view of the most recent completed Deli sync. */
    public record SyncStatus(
            Instant lastSyncTime,
            long recordCount,
            String status,
            String errorMessage) {
    }
}
