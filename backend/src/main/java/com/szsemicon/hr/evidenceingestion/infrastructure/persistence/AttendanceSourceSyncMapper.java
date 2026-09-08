package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels;
import com.szsemicon.hr.evidenceingestion.application.DeliSourceRegistrationModels;
import com.szsemicon.hr.evidenceingestion.application.DeliSourceRegistrationModels.Command;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendanceSourceSyncMapper {

    String findAuthorizedActiveSourceType(
            @Param("sourceId") String sourceId,
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("authorizationTime") Instant authorizationTime);

    String lockAuthorizedCompany(
            @Param("companyId") String companyId,
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("at") Instant at);

    RegistrationIdempotencyRow findRegistrationIdempotency(
            @Param("principalId") String principalId,
            @Param("sourceCode") String sourceCode,
            @Param("idempotencyKey") String idempotencyKey);

    DeliSourceRegistrationModels.SourceView findDeliSourceByCode(
            @Param("companyId") String companyId,
            @Param("sourceCode") String sourceCode);

    int countActiveSourcesForCredentialReference(
            @Param("secretReferenceName") String secretReferenceName,
            @Param("at") Instant at);

    void insertRegistrationIdempotency(
            @Param("idempotencyId") String idempotencyId,
            @Param("principalId") String principalId,
            @Param("sourceCode") String sourceCode,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestDigest") String requestDigest,
            @Param("createdAt") Instant createdAt);

    void insertDeliSource(
            @Param("sourceId") String sourceId,
            @Param("command") Command command,
            @Param("principalId") String principalId,
            @Param("createdAt") Instant createdAt);

    void insertDeliSourceConfiguration(
            @Param("configurationRevisionId")
                    String configurationRevisionId,
            @Param("sourceId") String sourceId,
            @Param("command") Command command,
            @Param("snapshotDigest") String snapshotDigest,
            @Param("principalId") String principalId,
            @Param("createdAt") Instant createdAt);

    int completeRegistrationIdempotency(
            @Param("idempotencyId") String idempotencyId,
            @Param("sourceId") String sourceId,
            @Param("completedAt") Instant completedAt);

    AuthorizedDeliSourceRow lockAuthorizedDeliSource(
            @Param("sourceId") String sourceId,
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("at") Instant at);

    /** Lock a single active DELI_CLOUD source without principal auth check. */
    AuthorizedDeliSourceRow lockSystemDeliSource(
            @Param("sourceId") String sourceId,
            @Param("at") Instant at);

    /** List IDs of every ACTIVE DELI_CLOUD source for scheduler use. */
    java.util.List<String> findAllActiveDeliSourceIds();

    /** Lock page for commit without principal auth check (scheduled jobs). */
    AttendanceSourceSyncModels.PageState lockSystemPageForCommit(
            @Param("jobId") String jobId,
            @Param("sourceId") String sourceId);

    RetryCandidateRow lockAuthorizedDeliRetryCandidate(
            @Param("originalJobId") String originalJobId,
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("authorizationTime") Instant authorizationTime);

    RetryIdempotencyRow findRetryIdempotency(
            @Param("principalId") String principalId,
            @Param("originalJobId") String originalJobId,
            @Param("idempotencyKey") String idempotencyKey);

    void insertRetryIdempotency(
            @Param("idempotencyId") String idempotencyId,
            @Param("principalId") String principalId,
            @Param("originalJobId") String originalJobId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestDigest") String requestDigest,
            @Param("createdAt") Instant createdAt);

    int completeRetryIdempotency(
            @Param("idempotencyId") String idempotencyId,
            @Param("newJobId") String newJobId,
            @Param("completedAt") Instant completedAt);

    int touchRetriedJob(
            @Param("originalJobId") String originalJobId,
            @Param("expectedRowVersion") long expectedRowVersion);

    int countActiveJobs(@Param("sourceId") String sourceId);

    int expireStaleActiveJobs(
            @Param("sourceId") String sourceId,
            @Param("staleBefore") Instant staleBefore,
            @Param("finishedAt") Instant finishedAt);

    void insertWatermarkIfAbsent(@Param("sourceId") String sourceId);

    void insertJob(
            @Param("jobId") String jobId,
            @Param("sourceId") String sourceId,
            @Param("requestedWatermark") String requestedWatermark,
            @Param("correlationId") String correlationId,
            @Param("requestedBy") String requestedBy,
            @Param("createdAt") Instant createdAt);

    int markRunning(
            @Param("jobId") String jobId,
            @Param("startedAt") Instant startedAt);

    int markFailed(
            @Param("jobId") String jobId,
            @Param("safeErrorCode") String safeErrorCode,
            @Param("finishedAt") Instant finishedAt);

    int markFinished(
            @Param("jobId") String jobId,
            @Param("status") String status,
            @Param("finishedAt") Instant finishedAt);

    AttendanceSourceSyncModels.PageState lockPageForCommit(
            @Param("jobId") String jobId,
            @Param("sourceId") String sourceId,
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("authorizationTime") Instant authorizationTime);

    String findKqCommittedCursor(@Param("sourceId") String sourceId);

    AttendanceSourceSyncModels.PageState lockKqPageForCommit(
            @Param("jobId") String jobId,
            @Param("sourceId") String sourceId,
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("authorizationTime") Instant authorizationTime);

    AttendanceSourceSyncModels.PageState lockSystemKqPageForCommit(
            @Param("jobId") String jobId,
            @Param("sourceId") String sourceId);

    void insertCommittedPage(AttendanceSourceSyncModels.CommittedPage page);

    int advanceWatermark(
            @Param("sourceId") String sourceId,
            @Param("expectedVersion") long expectedVersion,
            @Param("committedCursor") String committedCursor,
            @Param("pageDigest") String pageDigest,
            @Param("committedAt") Instant committedAt);

    int advanceKqWatermark(
            @Param("sourceId") String sourceId,
            @Param("expectedVersion") long expectedVersion,
            @Param("committedCursor") String committedCursor,
            @Param("pageDigest") String pageDigest,
            @Param("committedAt") Instant committedAt);

    int incrementJobCounters(
            @Param("jobId") String jobId,
            @Param("acceptedCount") int acceptedCount,
            @Param("quarantinedCount") int quarantinedCount);

    AttendanceSourceSyncModels.JobStatus findCreatedJob(
            @Param("jobId") String jobId,
            @Param("requestedBy") String requestedBy);

    AttendanceSourceSyncModels.JobStatus findAuthorizedJob(
            @Param("jobId") String jobId,
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("at") Instant at);

    /** List IDs of every ACTIVE OA_ATTENDANCE source for scheduler use. */
    java.util.List<String> findAllActiveOaSourceIds();

    /** Lock one active OA_ATTENDANCE source without principal auth check. */
    AuthorizedOaSourceRow lockSystemOaSource(
            @Param("sourceId") String sourceId,
            @Param("at") Instant at);

    /** Lock one active OA_ATTENDANCE source with principal auth check. */
    AuthorizedOaSourceRow lockAuthorizedOaSource(
            @Param("sourceId") String sourceId,
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("authorizationTime") Instant authorizationTime);

    AttendanceSourceSyncModels.PageState lockOaPageForCommit(
            @Param("jobId") String jobId,
            @Param("sourceId") String sourceId,
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("authorizationTime") Instant authorizationTime);

    AttendanceSourceSyncModels.PageState lockSystemOaPageForCommit(
            @Param("jobId") String jobId,
            @Param("sourceId") String sourceId);

    record AuthorizedDeliSourceRow(
            String sourceId,
            String companyId,
            String displayName,
            String secretReferenceName,
            String sourceTimeZone,
            int pageSize,
            int rateLimitPerMinute,
            int backoffSeconds,
            String committedCursor) {
    }

    record RetryCandidateRow(
            String originalJobId,
            String originalStatus,
            long originalRowVersion,
            String sourceId,
            String companyId,
            String displayName,
            String secretReferenceName,
            String sourceTimeZone,
            int pageSize,
            int rateLimitPerMinute,
            int backoffSeconds,
            String committedCursor) {
    }

    record RegistrationIdempotencyRow(
            String idempotencyId,
            String requestDigest,
            String status) {
    }

    record RetryIdempotencyRow(
            String idempotencyId,
            String requestDigest,
            String status,
            String replayJobId) {
    }

    record AuthorizedOaSourceRow(
            String sourceId,
            String companyId,
            String displayName,
            String sourceTimeZone,
            String committedWatermark) {
    }
}
