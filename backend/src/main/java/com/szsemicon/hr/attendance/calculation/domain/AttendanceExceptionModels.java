package com.szsemicon.hr.attendance.calculation.domain;

import static com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.requireText;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public final class AttendanceExceptionModels {

    private AttendanceExceptionModels() {
    }

    public enum ExceptionType {
        LATE(false),
        EARLY_DEPARTURE(false),
        MISSING_PUNCH_PENDING(true),
        MISSING_PUNCH_OVERDUE(true),
        ABSENCE(true),
        EVIDENCE_CONFLICT(true),
        LEAVE_PUNCH_CONFLICT(false),
        OUTING_OR_TRIP_INCOMPLETE(true),
        OA_APPROVAL_STATUS_UNKNOWN(true),
        OA_PERSON_REFERENCE_INVALID(true),
        EMPLOYEE_UNMATCHED(true),
        DUPLICATE_SOURCE_RECORD(true),
        SOURCE_SCHEMA_CHANGED(true),
        SOURCE_SYNC_STALE(true),
        NO_ATTENDANCE_GROUP(true),
        NO_SHIFT_OR_CALENDAR(true),
        AMBIGUOUS_PUNCH_MATCH(true),
        CROSS_MIDNIGHT_REVIEW_REQUIRED(true),
        OVERTIME_DOCUMENT_MISSING_OR_LATE(false),
        EARLY_RETURN_CANDIDATE(false),
        POST_CLOSE_SOURCE_CHANGE(true),
        INPUT_INTEGRITY_ERROR(true);

        private final boolean blockingClose;

        ExceptionType(boolean blockingClose) {
            this.blockingClose = blockingClose;
        }

        public boolean blockingClose() {
            return blockingClose;
        }
    }

    public enum ExceptionSeverity {
        INFO,
        WARNING,
        ERROR
    }

    public enum ExceptionTransitionType {
        OPENED,
        OBSERVED_BY_RECALCULATION,
        PENDING_EVIDENCE,
        PENDING_REVIEW,
        RESOLVED_BY_RECALCULATION,
        REOPENED_BY_RECALCULATION
    }

    public record ExceptionFinding(
            String fingerprint,
            String legalEntityId,
            String employeeId,
            LocalDate businessDate,
            String segmentOrSliceKey,
            ExceptionType type,
            ExceptionSeverity severity,
            String calculationVersionId,
            List<String> evidenceReferences,
            String reasonCode) {

        public ExceptionFinding {
            fingerprint = requireText(fingerprint, "fingerprint");
            legalEntityId = requireText(legalEntityId, "legalEntityId");
            employeeId = requireText(employeeId, "employeeId");
            Objects.requireNonNull(businessDate, "businessDate");
            segmentOrSliceKey = requireText(
                    segmentOrSliceKey, "segmentOrSliceKey");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(severity, "severity");
            calculationVersionId = requireText(
                    calculationVersionId, "calculationVersionId");
            evidenceReferences = Objects.requireNonNull(
                            evidenceReferences, "evidenceReferences")
                    .stream()
                    .sorted()
                    .toList();
            reasonCode = requireText(reasonCode, "reasonCode");
        }
    }

    public record ExceptionTransition(
            String transitionId,
            ExceptionTransitionType type,
            String calculationVersionId,
            String reasonCode,
            String requestId,
            Instant occurredAt) {

        public ExceptionTransition {
            transitionId = requireText(transitionId, "transitionId");
            Objects.requireNonNull(type, "type");
            calculationVersionId = requireText(
                    calculationVersionId, "calculationVersionId");
            reasonCode = requireText(reasonCode, "reasonCode");
            requestId = requireText(requestId, "requestId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    public record AttendanceExceptionCase(
            String caseId,
            ExceptionFinding initialFinding,
            List<ExceptionFinding> observations,
            List<ExceptionTransition> transitions) {

        public AttendanceExceptionCase {
            caseId = requireText(caseId, "caseId");
            Objects.requireNonNull(initialFinding, "initialFinding");
            observations = List.copyOf(Objects.requireNonNull(
                    observations, "observations"));
            transitions = List.copyOf(Objects.requireNonNull(
                    transitions, "transitions"));
            if (observations.stream().anyMatch(value ->
                    !initialFinding.fingerprint().equals(
                            value.fingerprint()))) {
                throw new IllegalArgumentException(
                        "all observations must share the case fingerprint");
            }
            for (int index = 1; index < transitions.size(); index++) {
                if (transitions.get(index).occurredAt().isBefore(
                        transitions.get(index - 1).occurredAt())) {
                    throw new IllegalArgumentException(
                            "exception transitions must preserve append order");
                }
            }
        }

        public boolean resolved() {
            if (transitions.isEmpty()) {
                return false;
            }
            ExceptionTransitionType last = transitions.getLast().type();
            return last == ExceptionTransitionType.RESOLVED_BY_RECALCULATION;
        }
    }
}
