package com.szsemicon.hr.leavetimeaccount.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public final class TimeOffYearEndModels {

    private TimeOffYearEndModels() {
    }

    public enum TriggerType {
        SCHEDULED,
        MANUAL
    }

    public enum RunStatus {
        RUNNING,
        SUCCEEDED,
        COMPLETED_WITH_FAILURES,
        FAILED,
        SKIPPED_LOCKED
    }

    public enum ItemStatus {
        SUCCESS,
        FAILED,
        SKIPPED
    }

    public record AccountCandidate(
            String timeAccountId,
            String employeeNumber) {

        public AccountCandidate {
            timeAccountId = requireText(timeAccountId, "timeAccountId");
            employeeNumber = requireText(employeeNumber, "employeeNumber");
        }
    }

    public record ExpiryResult(
            String sourceRequestId,
            String operationStatus,
            String accountType,
            int accountYear,
            BigDecimal affectedHours,
            BigDecimal balanceHours,
            BigDecimal reservedHours,
            BigDecimal availableHours) {

        public ExpiryResult {
            sourceRequestId = requireText(sourceRequestId, "sourceRequestId");
            operationStatus = requireText(operationStatus, "operationStatus");
            accountType = requireText(accountType, "accountType");
            affectedHours = requireNonNegative(affectedHours, "affectedHours");
            balanceHours = requireNonNegative(balanceHours, "balanceHours");
            reservedHours = requireNonNegative(reservedHours, "reservedHours");
            availableHours = requireNonNegative(availableHours, "availableHours");
        }
    }

    public record RunRecord(
            String runId,
            int accountYear,
            TriggerType triggerType,
            RunStatus status,
            String lockOwner,
            int totalCount,
            int successCount,
            int failureCount,
            int skippedCount,
            String failureSummary,
            Instant startedAt,
            Instant completedAt) {

        public RunRecord {
            runId = requireText(runId, "runId");
            triggerType = Objects.requireNonNull(triggerType, "triggerType");
            status = Objects.requireNonNull(status, "status");
            lockOwner = requireText(lockOwner, "lockOwner");
            startedAt = Objects.requireNonNull(startedAt, "startedAt");
            requireCounts(totalCount, successCount, failureCount, skippedCount);
        }
    }

    public record RunItemRecord(
            String itemId,
            String runId,
            String timeAccountId,
            String employeeNumber,
            String eventId,
            String payloadDigest,
            ItemStatus status,
            BigDecimal affectedHours,
            String resultCode,
            String resultDetail,
            Instant attemptedAt,
            Instant completedAt) {

        public RunItemRecord {
            itemId = requireText(itemId, "itemId");
            runId = requireText(runId, "runId");
            timeAccountId = requireText(timeAccountId, "timeAccountId");
            employeeNumber = requireText(employeeNumber, "employeeNumber");
            eventId = requireText(eventId, "eventId");
            payloadDigest = requireText(payloadDigest, "payloadDigest");
            status = Objects.requireNonNull(status, "status");
            attemptedAt = Objects.requireNonNull(attemptedAt, "attemptedAt");
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
        }
    }

    public record RunSummary(
            String runId,
            int accountYear,
            RunStatus status,
            int totalCount,
            int successCount,
            int failureCount,
            int skippedCount) {

        public RunSummary {
            runId = requireText(runId, "runId");
            status = Objects.requireNonNull(status, "status");
            requireCounts(totalCount, successCount, failureCount, skippedCount);
        }
    }

    private static BigDecimal requireNonNegative(
            BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static void requireCounts(
            int total, int success, int failure, int skipped) {
        if (total < 0 || success < 0 || failure < 0 || skipped < 0) {
            throw new IllegalArgumentException("year-end counts must not be negative");
        }
    }
}
