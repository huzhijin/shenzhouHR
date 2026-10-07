package com.szsemicon.hr.attendance.calculation.domain;

import static com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.requireText;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.AttendanceMetrics;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AttendancePeriodModels {

    private AttendancePeriodModels() {
    }

    public enum PeriodState {
        OPEN,
        FROZEN_FOR_CLOSE,
        CLOSED,
        REOPENED,
        UNKNOWN
    }

    public enum MutationKind {
        SOURCE_PUBLICATION,
        CORRECTION,
        ADJUSTMENT,
        ADJUSTMENT_REVERSAL,
        RECALCULATION,
        CURRENT_RESULT_UPDATE,
        CLOSE_SNAPSHOT_MUTATION
    }

    public enum PeriodErrorCode {
        ATTENDANCE_PERIOD_UNKNOWN,
        ATTENDANCE_PERIOD_FROZEN,
        ATTENDANCE_PERIOD_CLOSED,
        ATTENDANCE_PERIOD_VERSION_STALE,
        ATTENDANCE_CLOSE_SNAPSHOT_IMMUTABLE
    }

    public enum PeriodTransitionType {
        FROZE_FOR_CLOSE,
        RELEASED_CLOSE_FREEZE,
        CLOSED,
        REOPENED
    }

    public record PeriodIdentity(
            String periodId,
            String companyId,
            LocalDate startDate,
            LocalDate endExclusive) {

        public PeriodIdentity {
            periodId = requireText(periodId, "periodId");
            companyId = requireText(companyId, "companyId");
            Objects.requireNonNull(startDate, "startDate");
            Objects.requireNonNull(endExclusive, "endExclusive");
            if (!startDate.isBefore(endExclusive)) {
                throw new IllegalArgumentException(
                        "period start must be before endExclusive");
            }
        }
    }

    public record PeriodStateSnapshot(
            PeriodIdentity identity,
            long version,
            PeriodState state,
            String token,
            String closeSnapshotReference) {

        public PeriodStateSnapshot {
            Objects.requireNonNull(identity, "identity");
            if (version < 0) {
                throw new IllegalArgumentException(
                        "period version must be non-negative");
            }
            Objects.requireNonNull(state, "state");
            token = requireText(token, "token");
            if (closeSnapshotReference != null
                    && closeSnapshotReference.isBlank()) {
                throw new IllegalArgumentException(
                        "close snapshot reference must be null or non-blank");
            }
        }
    }

    public record PeriodTransition(
            String transitionId,
            String periodId,
            long fromVersion,
            long toVersion,
            PeriodState fromState,
            PeriodState toState,
            PeriodTransitionType type,
            String actorId,
            String reason,
            String requestId,
            Instant occurredAt) {

        public PeriodTransition {
            transitionId = requireText(transitionId, "transitionId");
            periodId = requireText(periodId, "periodId");
            if (fromVersion < 0 || toVersion < fromVersion) {
                throw new IllegalArgumentException(
                        "period transition versions are invalid");
            }
            Objects.requireNonNull(fromState, "fromState");
            Objects.requireNonNull(toState, "toState");
            Objects.requireNonNull(type, "type");
            actorId = requireText(actorId, "actorId");
            reason = requireText(reason, "reason");
            requestId = requireText(requestId, "requestId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    public sealed interface ProtectionDecision
            permits ProtectionAllowed, ProtectionRejected {
    }

    public record ProtectionAllowed(PeriodStateSnapshot snapshot)
            implements ProtectionDecision {

        public ProtectionAllowed {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    public record ProtectionRejected(
            PeriodErrorCode errorCode,
            PeriodStateSnapshot authoritativeSnapshot)
            implements ProtectionDecision {

        public ProtectionRejected {
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(
                    authoritativeSnapshot, "authoritativeSnapshot");
        }
    }

    public record CloseBlocker(
            String blockerCode,
            long count,
            String dependencyDigest) {

        public CloseBlocker {
            blockerCode = requireText(blockerCode, "blockerCode");
            if (count < 1) {
                throw new IllegalArgumentException(
                        "close blocker count must be positive");
            }
            dependencyDigest = requireText(
                    dependencyDigest, "dependencyDigest");
        }
    }

    public record ClosePrecheckInput(
            PeriodStateSnapshot period,
            long expectedEmployeeDates,
            long currentSuccessfulCalculationDates,
            long unresolvedBlockingExceptions,
            long runningSourceOrImportJobs,
            long runningRecalculations,
            boolean sourceFresh,
            boolean controlTotalsReconcile,
            Map<String, String> dependencyDigests,
            String requestId) {

        public ClosePrecheckInput {
            Objects.requireNonNull(period, "period");
            if (expectedEmployeeDates < 0
                    || currentSuccessfulCalculationDates < 0
                    || unresolvedBlockingExceptions < 0
                    || runningSourceOrImportJobs < 0
                    || runningRecalculations < 0) {
                throw new IllegalArgumentException(
                        "precheck counts must be non-negative");
            }
            dependencyDigests = Map.copyOf(Objects.requireNonNull(
                    dependencyDigests, "dependencyDigests"));
            requestId = requireText(requestId, "requestId");
        }
    }

    public record ClosePrecheckReport(
            String precheckToken,
            PeriodStateSnapshot period,
            List<CloseBlocker> blockers,
            Map<String, String> dependencyDigests,
            Instant checkedAt) {

        public ClosePrecheckReport {
            precheckToken = requireText(precheckToken, "precheckToken");
            Objects.requireNonNull(period, "period");
            blockers = Objects.requireNonNull(blockers, "blockers").stream()
                    .sorted(Comparator.comparing(
                            CloseBlocker::blockerCode))
                    .toList();
            dependencyDigests = Map.copyOf(Objects.requireNonNull(
                    dependencyDigests, "dependencyDigests"));
            Objects.requireNonNull(checkedAt, "checkedAt");
        }

        public boolean closable() {
            return blockers.isEmpty();
        }
    }

    public record CloseSnapshotMember(
            String employeeId,
            LocalDate businessDate,
            String calculationVersionId,
            String resultDigest,
            AttendanceMetrics metrics) {

        public CloseSnapshotMember {
            employeeId = requireText(employeeId, "employeeId");
            Objects.requireNonNull(businessDate, "businessDate");
            calculationVersionId = requireText(
                    calculationVersionId, "calculationVersionId");
            resultDigest = requireText(resultDigest, "resultDigest");
            Objects.requireNonNull(metrics, "metrics");
        }

        public String stableKey() {
            return employeeId + "|" + businessDate;
        }
    }

    public record AttendanceCloseSnapshot(
            String snapshotId,
            PeriodIdentity period,
            long periodVersion,
            List<CloseSnapshotMember> members,
            Map<String, String> dependencyDigests,
            AttendanceMetrics controlTotals,
            String memberSetDigest,
            String snapshotDigest,
            String actorId,
            String reason,
            String requestId,
            String correlationId,
            Instant closedAt) {

        public AttendanceCloseSnapshot {
            snapshotId = requireText(snapshotId, "snapshotId");
            Objects.requireNonNull(period, "period");
            if (periodVersion < 0) {
                throw new IllegalArgumentException(
                        "periodVersion must be non-negative");
            }
            members = Objects.requireNonNull(members, "members").stream()
                    .sorted(Comparator.comparing(
                            CloseSnapshotMember::stableKey))
                    .toList();
            dependencyDigests = Map.copyOf(Objects.requireNonNull(
                    dependencyDigests, "dependencyDigests"));
            Objects.requireNonNull(controlTotals, "controlTotals");
            memberSetDigest = requireText(memberSetDigest, "memberSetDigest");
            snapshotDigest = requireText(snapshotDigest, "snapshotDigest");
            actorId = requireText(actorId, "actorId");
            reason = requireText(reason, "reason");
            requestId = requireText(requestId, "requestId");
            correlationId = requireText(correlationId, "correlationId");
            Objects.requireNonNull(closedAt, "closedAt");
        }
    }

    public record PostCloseDifferenceReference(
            String referenceId,
            String closeSnapshotId,
            String employeeId,
            LocalDate businessDate,
            String closedCalculationVersionId,
            String reopenedCalculationVersionId,
            String differenceId) {

        public PostCloseDifferenceReference {
            referenceId = requireText(referenceId, "referenceId");
            closeSnapshotId = requireText(
                    closeSnapshotId, "closeSnapshotId");
            employeeId = requireText(employeeId, "employeeId");
            Objects.requireNonNull(businessDate, "businessDate");
            closedCalculationVersionId = requireText(
                    closedCalculationVersionId,
                    "closedCalculationVersionId");
            reopenedCalculationVersionId = requireText(
                    reopenedCalculationVersionId,
                    "reopenedCalculationVersionId");
            differenceId = requireText(differenceId, "differenceId");
        }
    }
}
