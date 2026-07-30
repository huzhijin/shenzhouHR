package com.szsemicon.hr.evidenceingestion.application;

import java.time.Instant;
import java.util.List;

public final class AttendanceSourceReadModels {

    private AttendanceSourceReadModels() {
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
                long totalElements) {
            int pages = totalElements == 0
                    ? 0
                    : (int) Math.ceil((double) totalElements / size);
            return new Page<>(items, page, size, totalElements, pages);
        }
    }

    public record SourceView(
            String sourceId,
            String companyId,
            String sourceType,
            String code,
            String displayName,
            String state,
            String timeZone,
            int configurationRevision,
            String committedWatermark,
            Instant lastSuccessfulSyncAt,
            long rowVersion) {
    }

    public record JobView(
            String jobId,
            String sourceId,
            String sourceDisplayName,
            String sourceType,
            String state,
            int committedPages,
            long rawFactCount,
            long quarantinedCount,
            String safeErrorSummary,
            Instant startedAt,
            Instant completedAt,
            long rowVersion) {
    }

    public record OaDocumentView(
            String documentId,
            String sourceDocumentId,
            String sourceVersion,
            String documentType,
            String sourceStatus,
            boolean effectiveCandidate,
            String employeeNumber,
            Instant intervalStart,
            Instant intervalEndExclusive) {
    }
}
