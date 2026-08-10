package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisAttendanceSourceSyncRepository
        implements AttendanceSourceSyncRepository {

    private static final Duration ACTIVE_JOB_LEASE =
            Duration.ofMinutes(15);

    private final AttendanceSourceSyncMapper mapper;

    public MyBatisAttendanceSourceSyncRepository(
            AttendanceSourceSyncMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StartResult createAuthorizedDeliJob(
            String sourceId,
            String principalId,
            String capability,
            String jobId,
            String correlationId,
            Instant at) {
        var source = mapper.lockAuthorizedDeliSource(
                sourceId, principalId, capability, at);
        if (source == null) {
            return StartResult.resourceUnavailable();
        }
        int recoveredStaleJobs = mapper.expireStaleActiveJobs(
                sourceId, at.minus(ACTIVE_JOB_LEASE), at);
        if (mapper.countActiveJobs(sourceId) != 0) {
            return StartResult.alreadyRunning();
        }
        mapper.insertWatermarkIfAbsent(sourceId);
        mapper.insertJob(
                jobId,
                sourceId,
                source.committedCursor(),
                correlationId,
                principalId,
                at);
        return StartResult.created(
                new AttendanceSourceSyncModels.SourceJobStart(
                        jobId,
                        source.sourceId(),
                        source.companyId(),
                        source.displayName(),
                        source.secretReferenceName(),
                        source.sourceTimeZone(),
                        source.pageSize(),
                        source.rateLimitPerMinute(),
                        source.backoffSeconds(),
                        source.committedCursor()),
                recoveredStaleJobs);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RetryResult createAuthorizedDeliRetryJob(
            String originalJobId,
            String principalId,
            String capability,
            long expectedRowVersion,
            String idempotencyKey,
            String requestDigest,
            String newJobId,
            String correlationId,
            Instant at) {
        var candidate = mapper.lockAuthorizedDeliRetryCandidate(
                originalJobId, principalId, capability, at);
        if (candidate == null) {
            return RetryResult.of(RetryState.RESOURCE_UNAVAILABLE);
        }
        var idempotency = mapper.findRetryIdempotency(
                principalId, originalJobId, idempotencyKey);
        if (idempotency != null) {
            if (!requestDigest.equals(idempotency.requestDigest())) {
                return RetryResult.of(
                        RetryState.IDEMPOTENCY_CONFLICT);
            }
            if ("COMPLETED_SUCCESS".equals(idempotency.status())
                    && idempotency.replayJobId() != null) {
                return RetryResult.replayed(
                        idempotency.replayJobId());
            }
            return RetryResult.of(RetryState.ALREADY_RUNNING);
        }
        if (candidate.originalRowVersion() != expectedRowVersion) {
            return RetryResult.of(RetryState.VERSION_CONFLICT);
        }
        if (!isRetryableStatus(candidate.originalStatus())) {
            return RetryResult.of(RetryState.NOT_RETRYABLE);
        }
        int recoveredStaleJobs = mapper.expireStaleActiveJobs(
                candidate.sourceId(),
                at.minus(ACTIVE_JOB_LEASE),
                at);
        if (mapper.countActiveJobs(candidate.sourceId()) != 0) {
            return RetryResult.of(RetryState.ALREADY_RUNNING);
        }

        String idempotencyId = java.util.UUID.randomUUID().toString();
        mapper.insertRetryIdempotency(
                idempotencyId,
                principalId,
                originalJobId,
                idempotencyKey,
                requestDigest,
                at);
        requireOne(
                mapper.touchRetriedJob(
                        originalJobId, expectedRowVersion),
                "source sync job changed before retry");
        mapper.insertWatermarkIfAbsent(candidate.sourceId());
        mapper.insertJob(
                newJobId,
                candidate.sourceId(),
                candidate.committedCursor(),
                correlationId,
                principalId,
                at);
        requireOne(
                mapper.completeRetryIdempotency(
                        idempotencyId, newJobId, at),
                "source sync retry idempotency cannot complete");
        return RetryResult.created(
                new AttendanceSourceSyncModels.SourceJobStart(
                        newJobId,
                        candidate.sourceId(),
                        candidate.companyId(),
                        candidate.displayName(),
                        candidate.secretReferenceName(),
                        candidate.sourceTimeZone(),
                        candidate.pageSize(),
                        candidate.rateLimitPerMinute(),
                        candidate.backoffSeconds(),
                        candidate.committedCursor()),
                recoveredStaleJobs);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(String jobId, Instant startedAt) {
        requireOne(mapper.markRunning(jobId, startedAt), "sync job cannot start");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(
            String jobId, String safeErrorCode, Instant finishedAt) {
        requireOne(
                mapper.markFailed(jobId, safeErrorCode, finishedAt),
                "sync job cannot be failed");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFinished(String jobId, String status, Instant finishedAt) {
        requireOne(
                mapper.markFinished(jobId, status, finishedAt),
                "sync job cannot be finished");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AttendanceSourceSyncModels.PageState lockPageForCommit(
            String jobId,
            String sourceId,
            String principalId,
            String capability,
            Instant authorizationTime) {
        return mapper.lockPageForCommit(
                jobId,
                sourceId,
                principalId,
                capability,
                authorizationTime);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertCommittedPage(
            AttendanceSourceSyncModels.CommittedPage page) {
        mapper.insertCommittedPage(page);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void advanceWatermark(
            String sourceId,
            long expectedVersion,
            String committedCursor,
            String pageDigest,
            Instant committedAt) {
        requireOne(
                mapper.advanceWatermark(
                        sourceId,
                        expectedVersion,
                        committedCursor,
                        pageDigest,
                        committedAt),
                "attendance watermark changed concurrently");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void incrementJobCounters(
            String jobId, int acceptedCount, int quarantinedCount) {
        requireOne(
                mapper.incrementJobCounters(
                        jobId, acceptedCount, quarantinedCount),
                "sync job counters cannot be updated");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AttendanceSourceSyncModels.PageState lockSystemPageForCommit(
            String jobId, String sourceId) {
        return mapper.lockSystemPageForCommit(jobId, sourceId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<String> findAllActiveDeliSourceIds() {
        return mapper.findAllActiveDeliSourceIds();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StartResult createScheduledDeliJob(
            String sourceId,
            String jobId,
            String correlationId,
            Instant at) {
        var source = mapper.lockSystemDeliSource(sourceId, at);
        if (source == null) {
            return StartResult.resourceUnavailable();
        }
        int recoveredStaleJobs = mapper.expireStaleActiveJobs(
                sourceId, at.minus(ACTIVE_JOB_LEASE), at);
        if (mapper.countActiveJobs(sourceId) != 0) {
            return StartResult.alreadyRunning();
        }
        mapper.insertWatermarkIfAbsent(sourceId);
        mapper.insertJob(
                jobId,
                sourceId,
                source.committedCursor(),
                correlationId,
                "SYSTEM",
                at);
        return StartResult.created(
                new AttendanceSourceSyncModels.SourceJobStart(
                        jobId,
                        source.sourceId(),
                        source.companyId(),
                        source.displayName(),
                        source.secretReferenceName(),
                        source.sourceTimeZone(),
                        source.pageSize(),
                        source.rateLimitPerMinute(),
                        source.backoffSeconds(),
                        source.committedCursor()),
                recoveredStaleJobs);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AttendanceSourceSyncModels.JobStatus> findCreatedJob(
            String jobId, String requestedBy) {
        return Optional.ofNullable(mapper.findCreatedJob(jobId, requestedBy));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AttendanceSourceSyncModels.JobStatus> findAuthorizedJob(
            String jobId,
            String principalId,
            String capability,
            Instant at) {
        return Optional.ofNullable(mapper.findAuthorizedJob(
                jobId, principalId, capability, at));
    }

    private static void requireOne(int count, String message) {
        if (count != 1) {
            throw new OptimisticLockingFailureException(message);
        }
    }

    private static boolean isRetryableStatus(String status) {
        return "FAILED".equals(status)
                || "PARTIALLY_QUARANTINED".equals(status)
                || "CANCELLED".equals(status);
    }
}
