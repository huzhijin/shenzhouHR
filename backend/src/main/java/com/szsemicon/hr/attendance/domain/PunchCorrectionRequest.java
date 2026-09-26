package com.szsemicon.hr.attendance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/** A monthly-quota punch correction application and its approval state. */
public record PunchCorrectionRequest(
        String requestId,
        String employeeId,
        YearMonth requestMonth,
        LocalDate businessDate,
        PunchSide punchSide,
        String reason,
        Status status,
        Instant requestedAt,
        String requestedBy,
        Instant reviewedAt,
        String reviewedBy,
        String reviewNotes) {

    public enum PunchSide {
        ENTRY,
        EXIT,
        BOTH
    }

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        CANCELLED;

        public boolean consumesQuota() {
            return this == PENDING || this == APPROVED;
        }
    }

    public PunchCorrectionRequest {
        requestId = required(requestId, "requestId", 36);
        employeeId = required(employeeId, "employeeId", 36);
        Objects.requireNonNull(requestMonth, "requestMonth");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(punchSide, "punchSide");
        reason = required(reason, "reason", 500);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedAt, "requestedAt");
        requestedBy = required(requestedBy, "requestedBy", 36);
        if (!requestMonth.equals(YearMonth.from(businessDate))) {
            throw new IllegalArgumentException(
                    "requestMonth must match businessDate");
        }
        if (status == Status.PENDING
                && (reviewedAt != null || reviewedBy != null)) {
            throw new IllegalArgumentException(
                    "pending request cannot have review metadata");
        }
        if ((status == Status.APPROVED || status == Status.REJECTED)
                && (reviewedAt == null || reviewedBy == null)) {
            throw new IllegalArgumentException(
                    "reviewed request requires reviewer and timestamp");
        }
        if (reviewedBy != null) {
            reviewedBy = required(reviewedBy, "reviewedBy", 36);
        }
        if (reviewNotes != null) {
            reviewNotes = optional(reviewNotes, "reviewNotes", 500);
        }
    }

    public static PunchCorrectionRequest pending(
            String requestId,
            String employeeId,
            LocalDate businessDate,
            PunchSide punchSide,
            String reason,
            Instant requestedAt,
            String requestedBy) {
        return new PunchCorrectionRequest(
                requestId,
                employeeId,
                YearMonth.from(businessDate),
                businessDate,
                punchSide,
                reason,
                Status.PENDING,
                requestedAt,
                requestedBy,
                null,
                null,
                null);
    }

    public PunchCorrectionRequest approve(
            String reviewerId, Instant at, String notes) {
        if (status != Status.PENDING) {
            throw new IllegalStateException("only pending requests can be approved");
        }
        return new PunchCorrectionRequest(
                requestId,
                employeeId,
                requestMonth,
                businessDate,
                punchSide,
                reason,
                Status.APPROVED,
                requestedAt,
                requestedBy,
                Objects.requireNonNull(at, "at"),
                reviewerId,
                notes);
    }

    private static String required(
            String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }

    private static String optional(
            String value, String field, int maximumLength) {
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must be non-blank and within length limit");
        }
        return normalized;
    }
}
