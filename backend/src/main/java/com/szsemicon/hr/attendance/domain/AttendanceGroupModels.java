package com.szsemicon.hr.attendance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class AttendanceGroupModels {

    private AttendanceGroupModels() {
    }

    public enum LifecycleStatus {
        ACTIVE,
        INACTIVE
    }

    public record Location(
            String locationId,
            String legalEntityId,
            String code,
            String locationRevisionId,
            int revisionNumber,
            String name,
            String timeZone,
            LifecycleStatus status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {
    }

    public record AttendanceGroup(
            String groupId,
            String legalEntityId,
            String code,
            String groupRevisionId,
            int revisionNumber,
            String name,
            String locationId,
            String locationRevisionId,
            String calendarId,
            String shiftTemplateId,
            LifecycleStatus status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {
    }

    public record Assignment(
            String assignmentId,
            String groupId,
            String employeeId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {

        public boolean includes(LocalDate date) {
            return !date.isBefore(effectiveFrom)
                    && (effectiveTo == null || date.isBefore(effectiveTo));
        }

        public String monthlyContextKey(LocalDate date) {
            return employeeId + ":" + date.getYear() + "-"
                    + "%02d".formatted(date.getMonthValue());
        }
    }

    public record LocationTimelineFact(
            String timelineId,
            String locationId,
            String locationRevisionId,
            int eventSequence,
            LifecycleStatus state,
            LocalDate businessEffectiveFrom,
            String predecessorTimelineId,
            Instant recordedAt,
            String actorId,
            String requestId) {
    }

    public record AttendanceGroupTimelineFact(
            String timelineId,
            String groupId,
            String groupRevisionId,
            int eventSequence,
            LifecycleStatus state,
            LocalDate businessEffectiveFrom,
            String predecessorTimelineId,
            Instant recordedAt,
            String actorId,
            String requestId) {
    }

    public record AssignmentTimelineFact(
            String timelineId,
            String assignmentId,
            String employeeId,
            int eventSequence,
            LifecycleStatus state,
            LocalDate businessEffectiveFrom,
            String predecessorTimelineId,
            Instant recordedAt,
            String actorId,
            String requestId) {
    }

    public record Page<T>(List<T> items, long total, int page, int size) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
