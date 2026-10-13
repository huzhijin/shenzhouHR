package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.AttendanceExceptionCase;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionFinding;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionTransition;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionTransitionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AttendanceExceptionReconciler {

    public List<AttendanceExceptionCase> reconcile(
            List<AttendanceExceptionCase> previous,
            List<ExceptionFinding> findings,
            String requestId,
            Instant occurredAt) {
        String calculationVersionId = findings.isEmpty()
                ? "resolution@" + requestId
                : findings.stream()
                        .map(ExceptionFinding::calculationVersionId)
                        .max(Comparator.naturalOrder())
                        .orElseThrow();
        return reconcile(
                previous,
                findings,
                calculationVersionId,
                requestId,
                occurredAt);
    }

    public List<AttendanceExceptionCase> reconcile(
            List<AttendanceExceptionCase> previous,
            List<ExceptionFinding> findings,
            String calculationVersionId,
            String requestId,
            Instant occurredAt) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(findings, "findings");
        AttendanceCalculationModels.requireText(
                calculationVersionId, "calculationVersionId");
        AttendanceCalculationModels.requireText(requestId, "requestId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Map<String, ExceptionFinding> current = new LinkedHashMap<>();
        findings.stream()
                .sorted(Comparator.comparing(ExceptionFinding::fingerprint))
                .forEach(finding -> {
                    ExceptionFinding duplicate = current.put(
                            finding.fingerprint(), finding);
                    if (duplicate != null) {
                        throw new IllegalArgumentException(
                                "duplicate exception finding fingerprint");
                    }
                });
        List<AttendanceExceptionCase> reconciled = new ArrayList<>();
        for (AttendanceExceptionCase existing : previous.stream()
                .sorted(Comparator.comparing(AttendanceExceptionCase::caseId))
                .toList()) {
            ExceptionFinding finding =
                    current.remove(existing.initialFinding().fingerprint());
            if (finding == null) {
                if (existing.resolved()) {
                    reconciled.add(existing);
                } else {
                    reconciled.add(append(
                            existing,
                            null,
                            transition(
                                    existing.caseId(),
                                    ExceptionTransitionType
                                            .RESOLVED_BY_RECALCULATION,
                                    calculationVersionId,
                                    "FINDING_ABSENT_AFTER_RECALCULATION",
                                    requestId,
                                    occurredAt)));
                }
                continue;
            }
            ExceptionTransitionType transitionType = existing.resolved()
                    ? ExceptionTransitionType.REOPENED_BY_RECALCULATION
                    : ExceptionTransitionType.OBSERVED_BY_RECALCULATION;
            reconciled.add(append(
                    existing,
                    finding,
                    transition(
                            existing.caseId(),
                            transitionType,
                            finding.calculationVersionId(),
                            finding.reasonCode(),
                            requestId,
                            occurredAt)));
        }
        for (ExceptionFinding finding : current.values()) {
            String caseId = "case-" + CanonicalAttendanceDigests
                    .digestStrings(
                            "W5_EXCEPTION_CASE_V1",
                            List.of(finding.fingerprint()))
                    .substring(0, 24);
            ExceptionTransition opened = transition(
                    caseId,
                    ExceptionTransitionType.OPENED,
                    finding.calculationVersionId(),
                    finding.reasonCode(),
                    requestId,
                    occurredAt);
            reconciled.add(new AttendanceExceptionCase(
                    caseId,
                    finding,
                    List.of(finding),
                    List.of(opened)));
        }
        return reconciled.stream()
                .sorted(Comparator.comparing(AttendanceExceptionCase::caseId))
                .toList();
    }

    private AttendanceExceptionCase append(
            AttendanceExceptionCase existing,
            ExceptionFinding observation,
            ExceptionTransition transition) {
        List<ExceptionFinding> observations =
                new ArrayList<>(existing.observations());
        if (observation != null) {
            observations.add(observation);
        }
        List<ExceptionTransition> transitions =
                new ArrayList<>(existing.transitions());
        transitions.add(transition);
        return new AttendanceExceptionCase(
                existing.caseId(),
                existing.initialFinding(),
                observations,
                transitions);
    }

    private ExceptionTransition transition(
            String caseId,
            ExceptionTransitionType type,
            String calculationVersionId,
            String reasonCode,
            String requestId,
            Instant occurredAt) {
        String transitionId = "transition-" + CanonicalAttendanceDigests
                .digestStrings(
                        "W5_EXCEPTION_TRANSITION_V1",
                        List.of(
                                caseId,
                                type.name(),
                                calculationVersionId,
                                requestId,
                                occurredAt.toString()))
                .substring(0, 24);
        return new ExceptionTransition(
                transitionId,
                type,
                calculationVersionId,
                reasonCode,
                requestId,
                occurredAt);
    }
}
