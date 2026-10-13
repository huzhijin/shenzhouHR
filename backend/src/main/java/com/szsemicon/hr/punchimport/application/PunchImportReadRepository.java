package com.szsemicon.hr.punchimport.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PunchImportReadRepository {

    long countBatches(String principalId, String capability, Instant at);

    List<PunchImportReadModels.BatchView> listBatches(
            String principalId,
            String capability,
            Instant at,
            int limit,
            int offset);

    Optional<PunchImportReadModels.BatchView> findBatch(
            String principalId,
            String capability,
            String batchId,
            Instant at);

    long countIssues(
            String principalId,
            String capability,
            String batchId,
            Instant at);

    List<PunchImportReadModels.IssueView> listIssues(
            String principalId,
            String capability,
            String batchId,
            Instant at,
            int limit,
            int offset);

    long countRows(
            String principalId,
            String capability,
            String batchId,
            Instant at);

    List<PunchImportReadModels.RowView> listRows(
            String principalId,
            String capability,
            String batchId,
            Instant at,
            int limit,
            int offset);
}
