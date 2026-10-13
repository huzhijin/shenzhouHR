package com.szsemicon.hr.attendance.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;

final class AttendanceGroupRows {

    private AttendanceGroupRows() {
    }

    record LocationRow(
            String locationId,
            String sharedLocationId,
            String companyId,
            String locationCode,
            String locationRevisionId,
            int revisionNumber,
            String locationName,
            String timeZone,
            String status,
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

    record GroupRow(
            String attendanceGroupId,
            String companyId,
            String groupCode,
            String attendanceGroupRevisionId,
            int revisionNumber,
            String groupName,
            String locationId,
            String locationRevisionId,
            String workCalendarId,
            String shiftTemplateId,
            String status,
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

    record AssignmentRow(
            String attendanceGroupAssignmentId,
            String attendanceGroupId,
            String attendanceGroupRevisionId,
            String employeeId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String supersedesAssignmentId,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {
    }

    record TimelineFactRow(
            String timelineId,
            String familyId,
            String contentId,
            String employeeId,
            int eventSequence,
            String state,
            LocalDate businessEffectiveFrom,
            String predecessorTimelineId,
            Instant recordedAt,
            String actorId,
            String requestId) {
    }
}
