package com.szsemicon.hr.evidenceingestion.application;

import java.time.Instant;

public final class AttendanceSourceSyncModels {

    private AttendanceSourceSyncModels() {
    }

    public record SourceJobStart(
            String jobId,
            String sourceId,
            String legalEntityId,
            String sourceDisplayName,
            String secretReferenceName,
            String sourceTimeZone,
            int pageSize,
            int rateLimitPerMinute,
            int backoffSeconds,
            String committedCursor) {
    }

    public record JobStatus(
            String jobId,
            String sourceId,
            String sourceDisplayName,
            String sourceType,
            String state,
            int committedPages,
            long acceptedCount,
            long quarantinedCount,
            String safeErrorCode,
            Instant startedAt,
            Instant completedAt,
            long rowVersion) {
    }

    public record PageState(
            String jobId,
            String sourceId,
            String legalEntityId,
            String jobStatus,
            int committedPages,
            String committedCursor,
            long watermarkVersion) {
    }

    public record CommittedPage(
            String pageId,
            String jobId,
            int pageNumber,
            String inputCursor,
            String nextCursor,
            int recordCount,
            int acceptedCount,
            int quarantinedCount,
            String pageDigest,
            String requestId,
            Instant committedAt) {
    }
}
