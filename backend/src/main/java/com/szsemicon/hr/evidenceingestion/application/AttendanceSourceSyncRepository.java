package com.szsemicon.hr.evidenceingestion.application;

import java.time.Instant;
import java.util.Optional;

public interface AttendanceSourceSyncRepository {

    StartResult createAuthorizedDeliJob(
            String sourceId,
            String principalId,
            String capability,
            String jobId,
            String correlationId,
            Instant at);

    RetryResult createAuthorizedDeliRetryJob(
            String originalJobId,
            String principalId,
            String capability,
            long expectedRowVersion,
            String idempotencyKey,
            String requestDigest,
            String newJobId,
            String correlationId,
            Instant at);

    void markRunning(String jobId, Instant startedAt);

    void markFailed(String jobId, String safeErrorCode, Instant finishedAt);

    void markFinished(String jobId, String status, Instant finishedAt);

    AttendanceSourceSyncModels.PageState lockPageForCommit(
            String jobId,
            String sourceId,
            String principalId,
            String capability,
            Instant authorizationTime);

    /** Lock page for commit without principal auth check (scheduled jobs). */
    AttendanceSourceSyncModels.PageState lockSystemPageForCommit(
            String jobId,
            String sourceId);

    /** Return IDs of every active DELI_CLOUD source (for scheduled jobs). */
    java.util.List<String> findAllActiveDeliSourceIds();

    /** Create a sync job with SYSTEM principal — no capability check. */
    StartResult createScheduledDeliJob(
            String sourceId,
            String jobId,
            String correlationId,
            Instant at);

    void insertCommittedPage(AttendanceSourceSyncModels.CommittedPage page);

    void advanceWatermark(
            String sourceId,
            long expectedVersion,
            String committedCursor,
            String pageDigest,
            Instant committedAt);

    void incrementJobCounters(
            String jobId,
            int acceptedCount,
            int quarantinedCount);

    Optional<AttendanceSourceSyncModels.JobStatus> findCreatedJob(
            String jobId,
            String requestedBy);

    Optional<AttendanceSourceSyncModels.JobStatus> findAuthorizedJob(
            String jobId,
            String principalId,
            String capability,
            Instant at);

    enum StartState {
        CREATED,
        RESOURCE_UNAVAILABLE,
        ALREADY_RUNNING
    }

    enum RetryState {
        CREATED,
        REPLAYED,
        RESOURCE_UNAVAILABLE,
        VERSION_CONFLICT,
        NOT_RETRYABLE,
        ALREADY_RUNNING,
        IDEMPOTENCY_CONFLICT
    }

    record StartResult(
            StartState state,
            AttendanceSourceSyncModels.SourceJobStart job,
            int recoveredStaleJobs) {

        public static StartResult created(
                AttendanceSourceSyncModels.SourceJobStart job) {
            return created(job, 0);
        }

        public static StartResult created(
                AttendanceSourceSyncModels.SourceJobStart job,
                int recoveredStaleJobs) {
            if (recoveredStaleJobs < 0) {
                throw new IllegalArgumentException(
                        "recovered stale job count cannot be negative");
            }
            return new StartResult(
                    StartState.CREATED, job, recoveredStaleJobs);
        }

        public static StartResult resourceUnavailable() {
            return new StartResult(
                    StartState.RESOURCE_UNAVAILABLE, null, 0);
        }

        public static StartResult alreadyRunning() {
            return new StartResult(
                    StartState.ALREADY_RUNNING, null, 0);
        }
    }

    record RetryResult(
            RetryState state,
            AttendanceSourceSyncModels.SourceJobStart job,
            String replayJobId,
            int recoveredStaleJobs) {

        public static RetryResult created(
                AttendanceSourceSyncModels.SourceJobStart job,
                int recoveredStaleJobs) {
            return new RetryResult(
                    RetryState.CREATED,
                    job,
                    null,
                    recoveredStaleJobs);
        }

        public static RetryResult replayed(String jobId) {
            return new RetryResult(
                    RetryState.REPLAYED, null, jobId, 0);
        }

        public static RetryResult of(RetryState state) {
            return new RetryResult(state, null, null, 0);
        }
    }
}
