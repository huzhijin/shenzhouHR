package com.szsemicon.hr.attendance.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;

final class CalendarRows {

    private CalendarRows() {
    }

    record CalendarRow(
            String workCalendarId,
            String legalEntityId,
            String locationId,
            String calendarCode,
            String workCalendarVersionId,
            int versionNumber,
            String calendarName,
            int calendarYear,
            String timeZoneSnapshot,
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

    record DayRow(
            String workCalendarDayId,
            String workCalendarId,
            String workCalendarVersionId,
            LocalDate businessDate,
            String dayType,
            String shiftVersionOverrideId,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {
    }
}
