package com.szsemicon.hr.attendance.domain;

import java.time.Instant;
import java.time.LocalDate;

public final class CalendarModels {

    private CalendarModels() {
    }

    public enum DayType {
        WORKDAY,
        WEEKEND,
        PUBLIC_HOLIDAY,
        SPECIAL_WORKDAY
    }

    public enum CalendarStatus {
        DRAFT,
        PUBLISHED,
        INACTIVE
    }

    public record WorkCalendar(
            String calendarId,
            String companyId,
            String locationId,
            String code,
            String calendarVersionId,
            int versionNumber,
            String name,
            int calendarYear,
            String timeZone,
            CalendarStatus status,
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

    public record WorkCalendarDay(
            String calendarDayId,
            String calendarId,
            String calendarVersionId,
            LocalDate businessDate,
            DayType dayType,
            String shiftVersionOverrideId,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {

        public boolean workingDay() {
            return dayType == DayType.WORKDAY || dayType == DayType.SPECIAL_WORKDAY;
        }
    }

    public record CalendarPublicationFact(
            String timelineId,
            String calendarId,
            String calendarVersionId,
            int eventSequence,
            CalendarStatus state,
            LocalDate businessEffectiveFrom,
            String predecessorTimelineId,
            Instant recordedAt,
            String actorId,
            String requestId) {
    }
}
