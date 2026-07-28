package com.szsemicon.hr.attendance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class AttendancePolicyModels {

    private AttendancePolicyModels() {
    }

    public enum PolicyKind {
        MEAL_DEDUCTION,
        LATE_GRACE,
        MONTHLY_LATE_EXEMPTION
    }

    public enum SimulationStatus {
        MATCHED,
        NOT_MATCHED,
        ON_TIME,
        EXEMPTED,
        LATE
    }

    public enum PunchDirection {
        ENTRY,
        EXIT
    }

    public record PolicyBinding(
            String bindingId,
            String bindingRevisionId,
            int revisionNumber,
            String legalEntityId,
            PolicyKind policyKind,
            String policyVersionId,
            String groupId,
            String groupRevisionId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            AttendanceGroupModels.LifecycleStatus status,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {
    }

    public record SimulationInput(
            String employeeId,
            LocalDate businessDate,
            List<PunchInput> punches,
            Instant correctionAsOf) {

        public SimulationInput {
            punches = punches == null ? List.of() : List.copyOf(punches);
        }
    }

    public record PunchInput(
            PunchDirection direction,
            OffsetDateTime instant,
            String workSegmentId,
            String association) {
    }

    public record SimulationResult(
            PolicyKind policyKind,
            SimulationStatus status,
            String policyVersionId,
            String configurationDigest,
            boolean matched,
            boolean consumesAllowance,
            Integer rawLateMinutes,
            int predictedMonthlyConsumption,
            String usageProvenance,
            Instant usageKnowledgeTime,
            Integer deductionMinutes,
            LocalDate correctionDeadline,
            String affectedSegment,
            String explanation,
            boolean writesFormalResult) {
    }

    public record Impact(
            int groupCount,
            int assignmentCount,
            String countSource,
            String impactToken,
            Instant expiresAt) {
    }

    public record ConfigurationSnapshot(
            String status,
            String employeeId,
            LocalDate businessDate,
            String groupId,
            String groupRevisionId,
            String locationRevisionId,
            String calendarVersionId,
            CalendarModels.WorkCalendarDay calendarDay,
            ShiftModels.ShiftVersion shiftVersion,
            List<PolicyBinding> policyBindings,
            String configurationDigest,
            String monthlyContextKey,
            String explanation) {

        public ConfigurationSnapshot {
            policyBindings = List.copyOf(policyBindings);
        }
    }
}
