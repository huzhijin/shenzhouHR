package com.szsemicon.hr.attendance.interfaces.rest;

import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Assignment;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.AttendanceGroup;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Location;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Page;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class AttendanceGroupDtos {

    private AttendanceGroupDtos() {
    }

    record LocationRequest(
            @NotBlank String companyId,
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 64) String timeZone,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record LocationView(
            String locationId,
            String companyId,
            String code,
            String locationRevisionId,
            int revisionNumber,
            String name,
            String timeZone,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            Instant updatedAt) {
    }

    record LocationPage(List<LocationView> items, long total, int page, int size) {
    }

    record GroupRequest(
            @NotBlank String companyId,
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 100) String name,
            @NotBlank String locationId,
            @NotBlank String calendarId,
            @NotBlank String shiftTemplateId,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record GroupView(
            String groupId,
            String companyId,
            String code,
            String groupRevisionId,
            int revisionNumber,
            String name,
            String locationId,
            String locationRevisionId,
            String calendarId,
            String shiftTemplateId,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            Instant updatedAt) {
    }

    record GroupPage(List<GroupView> items, long total, int page, int size) {
    }

    record AssignmentRequest(
            @NotBlank String employeeId,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record AssignmentView(
            String assignmentId,
            String groupId,
            String employeeId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            long rowVersion,
            String monthlyContextKey,
            String changeReason,
            Instant updatedAt) {
    }

    record AssignmentPage(
            List<AssignmentView> items,
            long total,
            int page,
            int size) {
    }

    record ReasonRequest(@NotBlank @Size(min = 2, max = 500) String reason) {
    }

    static LocationView location(Location value) {
        return new LocationView(
                value.locationId(), value.companyId(), value.code(),
                value.locationRevisionId(), value.revisionNumber(), value.name(),
                value.timeZone(), value.status().name(), value.effectiveFrom(),
                value.effectiveTo(), value.snapshotDigest(), value.rowVersion(),
                value.changeReason(), value.updatedAt());
    }

    static LocationPage locations(Page<Location> page) {
        return new LocationPage(
                page.items().stream().map(AttendanceGroupDtos::location).toList(),
                page.total(), page.page(), page.size());
    }

    static GroupView group(AttendanceGroup value) {
        return new GroupView(
                value.groupId(), value.companyId(), value.code(),
                value.groupRevisionId(), value.revisionNumber(), value.name(),
                value.locationId(), value.locationRevisionId(),
                value.calendarId(), value.shiftTemplateId(),
                value.status().name(), value.effectiveFrom(), value.effectiveTo(),
                value.snapshotDigest(), value.rowVersion(),
                value.changeReason(), value.updatedAt());
    }

    static GroupPage groups(Page<AttendanceGroup> page) {
        return new GroupPage(
                page.items().stream().map(AttendanceGroupDtos::group).toList(),
                page.total(), page.page(), page.size());
    }

    static AssignmentView assignment(Assignment value, LocalDate asOf) {
        LocalDate contextDate = asOf == null ? value.effectiveFrom() : asOf;
        return new AssignmentView(
                value.assignmentId(), value.groupId(), value.employeeId(),
                value.effectiveFrom(), value.effectiveTo(), value.rowVersion(),
                value.monthlyContextKey(contextDate), value.changeReason(), value.updatedAt());
    }

    static AssignmentPage assignments(Page<Assignment> page, LocalDate asOf) {
        return new AssignmentPage(
                page.items().stream()
                        .map(value -> assignment(value, asOf))
                        .toList(),
                page.total(),
                page.page(),
                page.size());
    }
}
