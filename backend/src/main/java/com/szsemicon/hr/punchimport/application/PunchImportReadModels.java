package com.szsemicon.hr.punchimport.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PunchImportReadModels {

    private PunchImportReadModels() {
    }

    public record Page<T>(
            List<T> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {

        public Page {
            items = List.copyOf(items);
        }

        public static <T> Page<T> of(
                List<T> items,
                int page,
                int size,
                long total) {
            int pages = total == 0
                    ? 0
                    : (int) Math.ceil((double) total / size);
            return new Page<>(items, page, size, total, pages);
        }
    }

    public record BatchView(
            String batchId,
            String legalEntityId,
            String sourceId,
            String originalFilename,
            String fileSha256,
            String state,
            long totalRows,
            long validRows,
            long invalidRows,
            long exactDuplicateRows,
            long nearDuplicateRows,
            long affectedEmployees,
            LocalDate affectedDateFrom,
            LocalDate affectedDateTo,
            boolean precheckTokenPresent,
            String precheckToken,
            Instant createdAt,
            long rowVersion) {
    }

    public record IssueView(
            String issueId,
            int rowNumber,
            String field,
            String code,
            String severity,
            String safeMessage) {
    }

    public record RowView(
            String rowId,
            int rowNumber,
            String employeeNumber,
            String punchTime,
            String sourceTimeZone,
            String matchState,
            String duplicateState,
            boolean publishable) {
    }
}
