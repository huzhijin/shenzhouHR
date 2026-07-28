package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import java.time.LocalDate;

record AttendanceConfigurationAuthorityRow(
        LocalDate businessDate,
        String legalEntityId,
        String employeeId,
        String employeeVersionId,
        long employeeVersion,
        String employmentPeriodId,
        String employmentAssignmentId,
        long employmentVersion,
        String organizationId,
        String attendanceGroupAssignmentId,
        String assignmentSnapshotDigest,
        String attendanceGroupId,
        String attendanceGroupRevisionId,
        String groupSnapshotDigest,
        String locationId,
        String locationRevisionId,
        String locationTimeZone,
        String locationSnapshotDigest,
        String workCalendarId,
        String workCalendarVersionId,
        String calendarTimeZone,
        String calendarSnapshotDigest,
        String workCalendarDayId,
        String dayType,
        String calendarDaySnapshotDigest,
        String groupShiftTemplateId,
        String shiftTemplateId,
        String shiftVersionId,
        String shiftTimeZone,
        String shiftSnapshotDigest) {
}
