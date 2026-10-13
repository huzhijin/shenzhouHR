package com.szsemicon.hr.people.application;

import java.time.Instant;

/**
 * Port for roster import batch operations.
 */
public interface RosterImportBatchPort {

    void insert(Row row);

    Row find(String batchId);

    int markPublished(String batchId, long expectedVersion, Instant publishedAt);

    record Row(
            String batchId,
            String status,
            String reason,
            String originalFileName,
            String fileSha256,
            byte[] fileContent,
            String precheckJson,
            long rowVersion,
            String createdBy,
            Instant createdAt,
            Instant publishedAt) {
    }
}
