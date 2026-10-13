package com.szsemicon.hr.punchimport.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PunchImportCommandMapper {

    int countCompanyScope(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("companyId") String companyId,
            @Param("at") Instant at);

    String findActiveSpreadsheetSource(
            @Param("sourceId") String sourceId,
            @Param("companyId") String companyId);

    void insertBatch(
            @Param("batchId") String batchId,
            @Param("sourceId") String sourceId,
            @Param("companyId") String companyId,
            @Param("actorId") String actorId,
            @Param("createdAt") Instant createdAt);

    void insertFile(
            @Param("fileId") String fileId,
            @Param("batchId") String batchId,
            @Param("sourceId") String sourceId,
            @Param("companyId") String companyId,
            @Param("sha256") String sha256,
            @Param("filename") String filename,
            @Param("objectRef") String objectRef,
            @Param("size") long size,
            @Param("contentType") String contentType,
            @Param("templateVersion") String templateVersion,
            @Param("contractDigest") String contractDigest,
            @Param("actorId") String actorId,
            @Param("uploadedAt") Instant uploadedAt);

    void insertState(
            @Param("eventId") String eventId,
            @Param("batchId") String batchId,
            @Param("sequence") int sequence,
            @Param("fromState") String fromState,
            @Param("toState") String toState,
            @Param("reasonCode") String reasonCode,
            @Param("requestId") String requestId,
            @Param("actorId") String actorId,
            @Param("createdAt") Instant createdAt);

    int nextStateSequence(@Param("batchId") String batchId);

    String currentState(@Param("batchId") String batchId);

    long incrementBatchVersion(
            @Param("batchId") String batchId,
            @Param("expectedVersion") long expectedVersion);

    FileRef findFile(@Param("batchId") String batchId);

    void insertRow(
            @Param("rowId") String rowId,
            @Param("batchId") String batchId,
            @Param("fileId") String fileId,
            @Param("rowNumber") int rowNumber,
            @Param("rawJson") String rawJson,
            @Param("fingerprint") String fingerprint,
            @Param("blocking") int blocking,
            @Param("warning") int warning,
            @Param("createdAt") Instant createdAt);

    void insertAttempt(
            @Param("attemptId") String attemptId,
            @Param("batchId") String batchId,
            @Param("attemptNumber") int attemptNumber,
            @Param("status") String status,
            @Param("digest") String digest,
            @Param("startedAt") Instant startedAt,
            @Param("completedAt") Instant completedAt);

    int nextAttemptNumber(@Param("batchId") String batchId);

    void insertIssue(
            @Param("issueId") String issueId,
            @Param("attemptId") String attemptId,
            @Param("rowId") String rowId,
            @Param("code") String code,
            @Param("severity") String severity,
            @Param("field") String field,
            @Param("message") String message,
            @Param("createdAt") Instant createdAt);

    void insertPrecheck(
            @Param("precheckId") String precheckId,
            @Param("batchId") String batchId,
            @Param("attemptId") String attemptId,
            @Param("tokenDigest") String tokenDigest,
            @Param("expiresAt") Instant expiresAt,
            @Param("total") int total,
            @Param("valid") int valid,
            @Param("blocking") int blocking,
            @Param("warning") int warning,
            @Param("subjectsJson") String subjectsJson,
            @Param("rowDigest") String rowDigest,
            @Param("createdAt") Instant createdAt);

    LatestPrecheck findLatestPrecheck(@Param("batchId") String batchId);

    List<StagedRow> listStagedRows(@Param("batchId") String batchId);

    void markRowPublished(
            @Param("rowId") String rowId,
            @Param("rawFactId") String rawFactId,
            @Param("eventId") String eventId);

    void insertPublication(
            @Param("publicationId") String publicationId,
            @Param("batchId") String batchId,
            @Param("precheckId") String precheckId,
            @Param("mode") String mode,
            @Param("published") int published,
            @Param("retained") int retained,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestDigest") String requestDigest,
            @Param("reason") String reason,
            @Param("actorId") String actorId,
            @Param("publishedAt") Instant publishedAt);

    List<EmployeeMatch> matchEmployees(
            @Param("companyId") String companyId,
            @Param("employeeNumber") String employeeNumber,
            @Param("businessDate") LocalDate businessDate);

    record FileRef(
            String fileId,
            String objectRef,
            String filename,
            String sha256,
            String templateVersion,
            String fieldContractDigest) {
    }

    record LatestPrecheck(
            String precheckId,
            String attemptId,
            String tokenDigest,
            Instant expiresAt,
            int totalCount,
            int validCount,
            int blockingCount) {
    }

    record StagedRow(
            String rowId,
            int rowNumber,
            String rawValuesJson,
            String stableFingerprint,
            int blockingIssueCount) {
    }

    record EmployeeMatch(String employeeId, String employmentPeriodId) {
    }
}
