package com.szsemicon.hr.evidenceingestion.domain;

import java.util.Objects;

public final class SourcePageCommitPolicy {

    private SourcePageCommitPolicy() {
    }

    public record PageOutcome(
            String inputCursor,
            String nextCursor,
            int recordCount,
            int acceptedCount,
            int quarantinedCount,
            String pageDigest,
            boolean transportComplete,
            boolean parseComplete,
            boolean databaseCommitComplete) {

        public PageOutcome {
            if (recordCount < 0 || acceptedCount < 0 || quarantinedCount < 0) {
                throw new IllegalArgumentException("page counts cannot be negative");
            }
            if (recordCount != acceptedCount + quarantinedCount) {
                throw new IllegalArgumentException(
                        "every record must be accepted or quarantined");
            }
            if (pageDigest == null || !pageDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("page digest must be SHA-256");
            }
        }
    }

    public record CommitDecision(
            boolean commitPage,
            boolean advanceWatermark,
            String committedCursor,
            String reason) {
    }

    public static CommitDecision decide(PageOutcome page) {
        Objects.requireNonNull(page, "page");
        if (!page.transportComplete()) {
            return rejected("TRANSPORT_INCOMPLETE");
        }
        if (!page.parseComplete()) {
            return rejected("WHOLE_PAGE_PARSE_FAILED");
        }
        if (!page.databaseCommitComplete()) {
            return rejected("DATABASE_COMMIT_FAILED");
        }
        if (page.nextCursor() != null
                && page.nextCursor().equals(page.inputCursor())) {
            return rejected("CURSOR_LOOP");
        }
        if (page.recordCount() > 0 && page.acceptedCount() == 0) {
            return rejected("ALL_RECORDS_QUARANTINED");
        }
        return new CommitDecision(
                true,
                true,
                page.nextCursor(),
                page.quarantinedCount() == 0
                        ? "COMPLETE_PAGE"
                        : "COMPLETE_PAGE_WITH_QUARANTINE");
    }

    private static CommitDecision rejected(String reason) {
        return new CommitDecision(false, false, null, reason);
    }
}
