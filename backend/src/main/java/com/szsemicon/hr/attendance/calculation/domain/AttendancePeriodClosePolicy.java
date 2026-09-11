package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.AttendanceMetrics;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.AttendanceCloseSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.CloseBlocker;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.ClosePrecheckInput;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.ClosePrecheckReport;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.CloseSnapshotMember;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodState;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodStateSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AttendancePeriodClosePolicy {

    public ClosePrecheckReport precheck(
            ClosePrecheckInput input, Instant checkedAt) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(checkedAt, "checkedAt");
        List<CloseBlocker> blockers = new ArrayList<>();
        if (input.period().state() != PeriodState.OPEN
                && input.period().state() != PeriodState.REOPENED) {
            blockers.add(blocker(
                    "PERIOD_NOT_OPEN",
                    1,
                    input.period().token()));
        }
        if (input.expectedEmployeeDates()
                != input.currentSuccessfulCalculationDates()) {
            blockers.add(blocker(
                    "CALCULATION_COVERAGE_MISMATCH",
                    Math.max(
                            1,
                            Math.abs(input.expectedEmployeeDates()
                                    - input.currentSuccessfulCalculationDates())),
                    dependency(
                            input.dependencyDigests(),
                            "calculations")));
        }
        addCountBlocker(
                blockers,
                "UNRESOLVED_BLOCKING_EXCEPTIONS",
                input.unresolvedBlockingExceptions(),
                dependency(input.dependencyDigests(), "exceptions"));
        addCountBlocker(
                blockers,
                "SOURCE_OR_IMPORT_RUNNING",
                input.runningSourceOrImportJobs(),
                dependency(input.dependencyDigests(), "source"));
        addCountBlocker(
                blockers,
                "RECALCULATION_RUNNING",
                input.runningRecalculations(),
                dependency(input.dependencyDigests(), "recalculation"));
        if (!input.sourceFresh()) {
            blockers.add(blocker(
                    "SOURCE_NOT_FRESH",
                    1,
                    dependency(input.dependencyDigests(), "source")));
        }
        if (!input.controlTotalsReconcile()) {
            blockers.add(blocker(
                    "CONTROL_TOTAL_MISMATCH",
                    1,
                    dependency(input.dependencyDigests(), "results")));
        }
        List<String> tokenParts = new ArrayList<>(List.of(
                input.period().identity().periodId(),
                Long.toString(input.period().version()),
                input.period().state().name(),
                input.period().token(),
                Long.toString(input.expectedEmployeeDates()),
                Long.toString(input.currentSuccessfulCalculationDates()),
                Long.toString(input.unresolvedBlockingExceptions()),
                Long.toString(input.runningSourceOrImportJobs()),
                Long.toString(input.runningRecalculations()),
                Boolean.toString(input.sourceFresh()),
                Boolean.toString(input.controlTotalsReconcile()),
                input.requestId()));
        input.dependencyDigests().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> tokenParts.add(
                        entry.getKey() + "=" + entry.getValue()));
        return new ClosePrecheckReport(
                CanonicalAttendanceDigests.digestStrings(
                        "W5_CLOSE_PRECHECK_V1", tokenParts),
                input.period(),
                blockers,
                input.dependencyDigests(),
                checkedAt);
    }

    public AttendanceCloseSnapshot close(
            String snapshotId,
            ClosePrecheckReport precheck,
            PeriodStateSnapshot lockedPeriod,
            List<CloseSnapshotMember> members,
            String actorId,
            String reason,
            String requestId,
            String correlationId,
            Instant closedAt) {
        Objects.requireNonNull(precheck, "precheck");
        Objects.requireNonNull(lockedPeriod, "lockedPeriod");
        if (!precheck.closable()) {
            throw new IllegalStateException(
                    "ATTENDANCE_CLOSE_PRECHECK_BLOCKED");
        }
        if (!precheck.period().identity().equals(lockedPeriod.identity())
                || precheck.period().version() != lockedPeriod.version()
                || precheck.period().state() != lockedPeriod.state()
                || !precheck.period().token().equals(lockedPeriod.token())) {
            throw new IllegalStateException(
                    "ATTENDANCE_PERIOD_VERSION_STALE");
        }
        if (lockedPeriod.state() != PeriodState.OPEN
                && lockedPeriod.state() != PeriodState.REOPENED) {
            throw new IllegalStateException(
                    "ATTENDANCE_PERIOD_NOT_OPEN");
        }
        List<CloseSnapshotMember> stableMembers =
                Objects.requireNonNull(members, "members").stream()
                        .sorted(java.util.Comparator.comparing(
                                CloseSnapshotMember::stableKey))
                        .toList();
        if (stableMembers.stream()
                        .map(CloseSnapshotMember::stableKey)
                        .distinct()
                        .count()
                != stableMembers.size()) {
            throw new IllegalArgumentException(
                    "close snapshot member keys must be unique");
        }
        AttendanceMetrics totals = total(stableMembers);
        List<String> memberParts = stableMembers.stream()
                .map(value -> value.stableKey()
                        + "|" + value.calculationVersionId()
                        + "|" + value.resultDigest()
                        + "|" + value.metrics())
                .toList();
        String memberDigest = CanonicalAttendanceDigests.digestStrings(
                "W5_CLOSE_MEMBERS_V1", memberParts);
        List<String> snapshotParts = new ArrayList<>(List.of(
                snapshotId,
                lockedPeriod.identity().periodId(),
                Long.toString(lockedPeriod.version()),
                precheck.precheckToken(),
                memberDigest,
                totals.toString(),
                actorId,
                reason,
                requestId,
                correlationId,
                closedAt.toString()));
        precheck.dependencyDigests().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> snapshotParts.add(
                        entry.getKey() + "=" + entry.getValue()));
        String snapshotDigest = CanonicalAttendanceDigests.digestStrings(
                "W5_CLOSE_SNAPSHOT_V1", snapshotParts);
        return new AttendanceCloseSnapshot(
                snapshotId,
                lockedPeriod.identity(),
                lockedPeriod.version(),
                stableMembers,
                precheck.dependencyDigests(),
                totals,
                memberDigest,
                snapshotDigest,
                actorId,
                reason,
                requestId,
                correlationId,
                closedAt);
    }

    public PeriodStateSnapshot reopen(
            PeriodStateSnapshot closed,
            String approvalReference,
            String reason,
            String actorId,
            String requestId,
            Instant reopenedAt) {
        Objects.requireNonNull(closed, "closed");
        AttendanceCalculationModels.requireText(
                approvalReference, "approvalReference");
        AttendanceCalculationModels.requireText(reason, "reason");
        AttendanceCalculationModels.requireText(actorId, "actorId");
        AttendanceCalculationModels.requireText(requestId, "requestId");
        Objects.requireNonNull(reopenedAt, "reopenedAt");
        if (closed.state() != PeriodState.CLOSED
                || closed.closeSnapshotReference() == null) {
            throw new IllegalStateException(
                    "ATTENDANCE_PERIOD_NOT_CLOSED");
        }
        long newVersion = closed.version() + 1;
        String token = CanonicalAttendanceDigests.digestStrings(
                "W5_REOPEN_PERIOD_V1",
                List.of(
                        closed.identity().periodId(),
                        Long.toString(closed.version()),
                        closed.token(),
                        closed.closeSnapshotReference(),
                        Long.toString(newVersion),
                        approvalReference,
                        reason,
                        actorId,
                        requestId,
                        reopenedAt.toString()));
        return new PeriodStateSnapshot(
                closed.identity(),
                newVersion,
                PeriodState.REOPENED,
                token,
                closed.closeSnapshotReference());
    }

    public boolean reconciles(
            AttendanceCloseSnapshot snapshot) {
        return snapshot.controlTotals().equals(total(snapshot.members()));
    }

    private AttendanceMetrics total(List<CloseSnapshotMember> members) {
        long scheduled = 0;
        long confirmed = 0;
        long extended = 0;
        long overtime = 0;
        long paidOvertime = 0;
        long compensatoryOvertime = 0;
        long voluntaryOvertime = 0;
        long totalOvertime = 0;
        long leave = 0;
        long absence = 0;
        for (CloseSnapshotMember member : members) {
            AttendanceMetrics metrics = member.metrics();
            scheduled += metrics.scheduledMinutes();
            confirmed += metrics.confirmedScheduledWorkMinutes();
            extended += metrics.extendedPresenceMinutes();
            overtime += metrics.recognizedOvertimeMinutes();
            paidOvertime += metrics.paidOvertimeMinutes();
            compensatoryOvertime += metrics.compensatoryOvertimeMinutes();
            voluntaryOvertime += metrics.voluntaryOvertimeMinutes();
            totalOvertime += metrics.totalOvertimeMinutes();
            leave += metrics.leaveOrTimeOffMinutes();
            absence += metrics.absenceMinutes();
        }
        return new AttendanceMetrics(
                scheduled,
                confirmed,
                extended,
                overtime,
                paidOvertime,
                compensatoryOvertime,
                voluntaryOvertime,
                totalOvertime,
                leave,
                absence,
                confirmed + overtime);
    }

    private void addCountBlocker(
            List<CloseBlocker> blockers,
            String code,
            long count,
            String digest) {
        if (count > 0) {
            blockers.add(blocker(code, count, digest));
        }
    }

    private CloseBlocker blocker(
            String code, long count, String digest) {
        return new CloseBlocker(code, count, digest);
    }

    private String dependency(
            Map<String, String> digests, String key) {
        return digests.getOrDefault(key, "NOT_PROVIDED");
    }
}
