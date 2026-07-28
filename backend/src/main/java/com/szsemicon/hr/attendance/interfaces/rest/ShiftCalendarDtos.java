package com.szsemicon.hr.attendance.interfaces.rest;

import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendar;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendarDay;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.attendance.domain.ShiftModels.Segment;
import com.szsemicon.hr.attendance.domain.ShiftModels.SegmentType;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftTemplate;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftVersion;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

final class ShiftCalendarDtos {

    private ShiftCalendarDtos() {
    }

    record ShiftTemplateRequest(
            @NotBlank String legalEntityId,
            @NotBlank String locationId,
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record ShiftTemplateView(
            String shiftId,
            String legalEntityId,
            String locationId,
            String code,
            String name,
            String status,
            long rowVersion,
            String changeReason,
            Instant updatedAt) {
    }

    record ShiftTemplatePage(
            List<ShiftTemplateView> items,
            long total,
            int page,
            int size) {
    }

    record SegmentRequest(
            @NotNull SegmentType segmentType,
            @NotNull LocalTime startLocalTime,
            @NotNull @Min(0) @Max(1) Integer startDayOffset,
            @NotNull LocalTime endLocalTime,
            @NotNull @Min(0) @Max(1) Integer endDayOffset) {
    }

    record ShiftVersionRequest(
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotEmpty @Size(max = 24) List<@Valid SegmentRequest> segments,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record ShiftVersionView(
            String shiftVersionId,
            String shiftId,
            int versionNumber,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String timeZone,
            List<Segment> segments,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            Instant publishedAt,
            Instant updatedAt) {
    }

    record ShiftVersionPage(
            List<ShiftVersionView> items,
            long total,
            int page,
            int size) {
    }

    record CalendarRequest(
            @NotBlank String legalEntityId,
            @NotBlank String locationId,
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull @Min(2000) @Max(2100) Integer calendarYear,
            @NotBlank @Size(max = 64) String timeZone,
            @NotNull LocalDate effectiveFrom,
            @NotNull LocalDate effectiveTo,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record CalendarVersionRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Min(2000) @Max(2100) Integer calendarYear,
            @NotBlank @Size(max = 64) String timeZone,
            @NotNull LocalDate effectiveFrom,
            @NotNull LocalDate effectiveTo,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record CalendarView(
            String calendarId,
            String legalEntityId,
            String locationId,
            String code,
            String calendarVersionId,
            int versionNumber,
            String name,
            int calendarYear,
            String timeZone,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            Instant updatedAt) {
    }

    record CalendarPage(
            List<CalendarView> items,
            long total,
            int page,
            int size) {
    }

    record CalendarDayRequest(
            @NotNull LocalDate businessDate,
            @NotNull com.szsemicon.hr.attendance.domain.CalendarModels.DayType dayType,
            String shiftVersionOverrideId) {
    }

    record CalendarDayView(
            String calendarDayId,
            String calendarId,
            String calendarVersionId,
            LocalDate businessDate,
            String dayType,
            String shiftVersionOverrideId,
            long rowVersion,
            String changeReason) {
    }

    record CalendarDayPage(
            List<CalendarDayView> items,
            long total,
            int page,
            int size) {
    }

    record ReasonRequest(@NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record FutureDeactivationRequest(
            @NotNull LocalDate businessEffectiveFrom,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record ShiftTemplateStatusRequest(
            @NotNull LifecycleStatus status,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    enum DeactivationStatus {
        INACTIVE
    }

    record ShiftVersionStatusRequest(
            @NotNull DeactivationStatus status,
            @NotNull LocalDate businessEffectiveFrom,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record CalendarStatusRequest(
            @NotNull DeactivationStatus status,
            @NotNull LocalDate businessEffectiveFrom,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    static ShiftTemplateView template(ShiftTemplate value) {
        return new ShiftTemplateView(
                value.shiftId(), value.legalEntityId(), value.locationId(),
                value.code(), value.name(), value.status().name(), value.rowVersion(),
                value.changeReason(), value.updatedAt());
    }

    static ShiftTemplatePage templates(Page<ShiftTemplate> value) {
        return new ShiftTemplatePage(
                value.items().stream()
                        .map(ShiftCalendarDtos::template)
                        .toList(),
                value.total(),
                value.page(),
                value.size());
    }

    static ShiftVersionView version(ShiftVersion value) {
        return new ShiftVersionView(
                value.shiftVersionId(), value.shiftId(), value.versionNumber(),
                value.status().name(), value.effectiveFrom(), value.effectiveTo(),
                value.timeZone(), value.segments(),
                value.snapshotDigest(), value.rowVersion(),
                value.changeReason(), value.publishedAt(), value.updatedAt());
    }

    static ShiftVersionPage versions(Page<ShiftVersion> value) {
        return new ShiftVersionPage(
                value.items().stream()
                        .map(ShiftCalendarDtos::version)
                        .toList(),
                value.total(),
                value.page(),
                value.size());
    }

    static CalendarView calendar(WorkCalendar value) {
        return new CalendarView(
                value.calendarId(), value.legalEntityId(), value.locationId(),
                value.code(), value.calendarVersionId(), value.versionNumber(),
                value.name(), value.calendarYear(), value.timeZone(),
                value.status().name(), value.effectiveFrom(), value.effectiveTo(),
                value.snapshotDigest(),
                value.rowVersion(), value.changeReason(), value.updatedAt());
    }

    static CalendarPage calendars(Page<WorkCalendar> value) {
        return new CalendarPage(
                value.items().stream()
                        .map(ShiftCalendarDtos::calendar)
                        .toList(),
                value.total(),
                value.page(),
                value.size());
    }

    static CalendarDayView day(WorkCalendarDay value) {
        return new CalendarDayView(
                value.calendarDayId(), value.calendarId(), value.calendarVersionId(),
                value.businessDate(), value.dayType().name(),
                value.shiftVersionOverrideId(),
                value.rowVersion(), value.changeReason());
    }

    static CalendarDayPage days(Page<WorkCalendarDay> value) {
        return new CalendarDayPage(
                value.items().stream()
                        .map(ShiftCalendarDtos::day)
                        .toList(),
                value.total(),
                value.page(),
                value.size());
    }
}
