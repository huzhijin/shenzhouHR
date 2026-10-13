package com.szsemicon.hr.attendance.interfaces.rest;

import com.szsemicon.hr.attendance.application.CalendarCommands.CalendarCommand;
import com.szsemicon.hr.attendance.application.CalendarCommands.CalendarDayCommand;
import com.szsemicon.hr.attendance.application.CalendarCommands.CalendarVersionCommand;
import com.szsemicon.hr.attendance.application.CalendarCommands.ReplaceDaysCommand;
import com.szsemicon.hr.attendance.application.CalendarService;
import com.szsemicon.hr.attendance.application.AttendanceMutationIdempotencyService;
import com.szsemicon.hr.attendance.application.ShiftCommands.TemplateCommand;
import com.szsemicon.hr.attendance.application.ShiftCommands.VersionCommand;
import com.szsemicon.hr.attendance.application.ShiftService;
import com.szsemicon.hr.attendance.domain.CalendarModels.CalendarStatus;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendar;
import com.szsemicon.hr.attendance.domain.ShiftModels.Segment;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftTemplate;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftVersion;
import com.szsemicon.hr.attendance.domain.ShiftModels.VersionStatus;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.CalendarDayRequest;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.CalendarDayPage;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.CalendarDayView;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.CalendarRequest;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.CalendarPage;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.CalendarView;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.CalendarStatusRequest;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.CalendarVersionRequest;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.FutureDeactivationRequest;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.ReasonRequest;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.ShiftTemplateRequest;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.ShiftTemplatePage;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.ShiftTemplateView;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.ShiftTemplateStatusRequest;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.ShiftVersionRequest;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.ShiftVersionPage;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.ShiftVersionView;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarDtos.ShiftVersionStatusRequest;
import com.szsemicon.hr.shared.web.ChangeReasonHeader;
import com.szsemicon.hr.shared.web.StrongEtag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-setup")
public class ShiftCalendarController {

    private final ShiftService shiftService;
    private final CalendarService calendarService;
    private final AttendanceMutationIdempotencyService mutations;

    public ShiftCalendarController(
            ShiftService shiftService,
            CalendarService calendarService,
            AttendanceMutationIdempotencyService mutations) {
        this.shiftService = shiftService;
        this.calendarService = calendarService;
        this.mutations = mutations;
    }

    @GetMapping("/shifts")
    ResponseEntity<ShiftTemplatePage> listShifts(
            @RequestParam(required = false) String companyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(ShiftCalendarDtos.templates(
                shiftService.listTemplates(companyId, page, size)));
    }

    @PostMapping("/shifts")
    ResponseEntity<ShiftTemplateView> createShift(
            @Valid @RequestBody ShiftTemplateRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var command = new TemplateCommand(
                request.companyId(), request.locationId(),
                request.code(), request.name(),
                reason(changeReason, request.reason()));
        var result = mutations.execute(
                "CREATE_SHIFT_TEMPLATE",
                "ATTENDANCE_SHIFT_TEMPLATE",
                request.companyId(),
                idempotencyKey,
                command,
                null,
                HttpStatus.CREATED.value(),
                ShiftTemplate::shiftId,
                ShiftTemplate.class,
                () -> shiftService.createTemplate(command, idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(result.rowVersion()))
                .body(ShiftCalendarDtos.template(result));
    }

    @PutMapping("/shifts/{shiftId}")
    ResponseEntity<ShiftTemplateView> updateShift(
            @PathVariable String shiftId,
            @Valid @RequestBody ShiftTemplateRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var command = new TemplateCommand(
                request.companyId(), request.locationId(),
                request.code(), request.name(),
                reason(changeReason, request.reason()));
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "UPDATE_SHIFT_TEMPLATE",
                "ATTENDANCE_SHIFT_TEMPLATE",
                shiftId,
                idempotencyKey,
                command,
                expectedVersion,
                HttpStatus.OK.value(),
                ShiftTemplate::shiftId,
                ShiftTemplate.class,
                () -> shiftService.updateTemplate(
                        shiftId, command, expectedVersion));
        return versioned(ShiftCalendarDtos.template(result), result.rowVersion());
    }

    @PostMapping("/shifts/{shiftId}/status")
    ResponseEntity<ShiftTemplateView> changeShiftStatus(
            @PathVariable String shiftId,
            @Valid @RequestBody ShiftTemplateStatusRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        String normalizedReason = reason(changeReason, request.reason());
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "CHANGE_SHIFT_TEMPLATE_STATUS",
                "ATTENDANCE_SHIFT_TEMPLATE",
                shiftId,
                idempotencyKey,
                new TemplateStatusMutation(
                        request.status(), normalizedReason),
                expectedVersion,
                HttpStatus.OK.value(),
                ShiftTemplate::shiftId,
                ShiftTemplate.class,
                () -> shiftService.changeTemplateStatus(
                        shiftId, request.status(), expectedVersion,
                        normalizedReason));
        return versioned(ShiftCalendarDtos.template(result), result.rowVersion());
    }

    @GetMapping("/shifts/{shiftId}/versions")
    ResponseEntity<ShiftVersionPage> listShiftVersions(
            @PathVariable String shiftId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(ShiftCalendarDtos.versions(
                shiftService.listVersions(shiftId, page, size)));
    }

    @PostMapping("/shifts/{shiftId}/versions")
    ResponseEntity<ShiftVersionView> createShiftVersion(
            @PathVariable String shiftId,
            @Valid @RequestBody ShiftVersionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        List<Segment> segments = request.segments().stream()
                .map(value -> new Segment(
                        value.segmentType(), value.startLocalTime(),
                        value.startDayOffset(),
                        value.endLocalTime(), value.endDayOffset()))
                .toList();
        var command = new VersionCommand(
                request.effectiveFrom(), request.effectiveTo(),
                segments, reason(changeReason, request.reason()));
        var result = mutations.execute(
                "CREATE_SHIFT_VERSION",
                "ATTENDANCE_SHIFT_VERSION",
                shiftId,
                idempotencyKey,
                command,
                null,
                HttpStatus.CREATED.value(),
                ShiftVersion::shiftVersionId,
                ShiftVersion.class,
                () -> shiftService.createVersion(
                        shiftId, command, idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(result.rowVersion()))
                .body(ShiftCalendarDtos.version(result));
    }

    @PutMapping("/shifts/{shiftId}/versions/{shiftVersionId}")
    ResponseEntity<ShiftVersionView> updateShiftVersion(
            @PathVariable String shiftId,
            @PathVariable String shiftVersionId,
            @Valid @RequestBody ShiftVersionRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        List<Segment> segments = request.segments().stream()
                .map(value -> new Segment(
                        value.segmentType(), value.startLocalTime(),
                        value.startDayOffset(),
                        value.endLocalTime(), value.endDayOffset()))
                .toList();
        var command = new VersionCommand(
                request.effectiveFrom(), request.effectiveTo(),
                segments, reason(changeReason, request.reason()));
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "UPDATE_SHIFT_VERSION",
                "ATTENDANCE_SHIFT_VERSION",
                shiftVersionId,
                idempotencyKey,
                command,
                expectedVersion,
                HttpStatus.OK.value(),
                ShiftVersion::shiftVersionId,
                ShiftVersion.class,
                () -> shiftService.updateVersion(
                        shiftId, shiftVersionId, command, expectedVersion));
        return versioned(ShiftCalendarDtos.version(result), result.rowVersion());
    }

    @PostMapping("/shifts/{shiftId}/versions/{shiftVersionId}/publish")
    ResponseEntity<ShiftVersionView> publishShiftVersion(
            @PathVariable String shiftId,
            @PathVariable String shiftVersionId,
            @Valid @RequestBody ReasonRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        String normalizedReason = reason(changeReason, request.reason());
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "PUBLISH_SHIFT_VERSION",
                "ATTENDANCE_SHIFT_VERSION",
                shiftVersionId,
                idempotencyKey,
                new ReasonMutation(normalizedReason),
                expectedVersion,
                HttpStatus.OK.value(),
                ShiftVersion::shiftVersionId,
                ShiftVersion.class,
                () -> shiftService.publish(
                        shiftId, shiftVersionId, expectedVersion,
                        normalizedReason));
        return versioned(ShiftCalendarDtos.version(result), result.rowVersion());
    }

    @PostMapping("/shifts/{shiftId}/versions/{shiftVersionId}/status")
    ResponseEntity<ShiftVersionView> changeShiftVersionStatus(
            @PathVariable String shiftId,
            @PathVariable String shiftVersionId,
            @Valid @RequestBody ShiftVersionStatusRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        String normalizedReason = reason(changeReason, request.reason());
        var result = mutations.execute(
                "CHANGE_SHIFT_VERSION_STATUS",
                "ATTENDANCE_SHIFT_VERSION",
                shiftVersionId,
                idempotencyKey,
                new StatusMutation(
                        request.status(),
                        request.businessEffectiveFrom(),
                        normalizedReason),
                expectedVersion,
                HttpStatus.OK.value(),
                ShiftVersion::shiftVersionId,
                ShiftVersion.class,
                () -> shiftService.changeVersionStatus(
                        shiftId, shiftVersionId, VersionStatus.INACTIVE,
                        expectedVersion, request.businessEffectiveFrom(),
                        normalizedReason));
        return versioned(ShiftCalendarDtos.version(result), result.rowVersion());
    }

    @GetMapping("/calendars")
    ResponseEntity<CalendarPage> listCalendars(
            @RequestParam(required = false) String companyId,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(ShiftCalendarDtos.calendars(
                calendarService.listCalendars(companyId, year, page, size)));
    }

    @PostMapping("/calendars")
    ResponseEntity<CalendarView> createCalendar(
            @Valid @RequestBody CalendarRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var command = new CalendarCommand(
                request.companyId(), request.locationId(),
                request.code(), request.name(),
                request.calendarYear(), request.timeZone(),
                request.effectiveFrom(), request.effectiveTo(),
                reason(changeReason, request.reason()));
        var result = mutations.execute(
                "CREATE_WORK_CALENDAR",
                "ATTENDANCE_WORK_CALENDAR",
                request.companyId(),
                idempotencyKey,
                command,
                null,
                HttpStatus.CREATED.value(),
                WorkCalendar::calendarId,
                WorkCalendar.class,
                () -> calendarService.createCalendar(
                        command, idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(result.rowVersion()))
                .body(ShiftCalendarDtos.calendar(result));
    }

    @PutMapping("/calendars/{calendarId}")
    ResponseEntity<CalendarView> updateCalendar(
            @PathVariable String calendarId,
            @Valid @RequestBody CalendarRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var command = new CalendarCommand(
                request.companyId(), request.locationId(),
                request.code(), request.name(),
                request.calendarYear(), request.timeZone(),
                request.effectiveFrom(), request.effectiveTo(),
                reason(changeReason, request.reason()));
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "UPDATE_WORK_CALENDAR",
                "ATTENDANCE_WORK_CALENDAR",
                calendarId,
                idempotencyKey,
                command,
                expectedVersion,
                HttpStatus.OK.value(),
                WorkCalendar::calendarId,
                WorkCalendar.class,
                () -> calendarService.updateCalendar(
                        calendarId, command, expectedVersion));
        return versioned(ShiftCalendarDtos.calendar(result), result.rowVersion());
    }

    @GetMapping("/calendars/{calendarId}/versions")
    ResponseEntity<CalendarPage> listCalendarVersions(
            @PathVariable String calendarId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(ShiftCalendarDtos.calendars(
                calendarService.listVersions(calendarId, page, size)));
    }

    @PostMapping("/calendars/{calendarId}/versions")
    ResponseEntity<CalendarView> createCalendarVersion(
            @PathVariable String calendarId,
            @Valid @RequestBody CalendarVersionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var command = new CalendarVersionCommand(
                request.name(), request.calendarYear(),
                request.timeZone(), request.effectiveFrom(),
                request.effectiveTo(),
                reason(changeReason, request.reason()));
        var result = mutations.execute(
                "CREATE_WORK_CALENDAR_VERSION",
                "ATTENDANCE_WORK_CALENDAR_VERSION",
                calendarId,
                idempotencyKey,
                command,
                null,
                HttpStatus.CREATED.value(),
                WorkCalendar::calendarVersionId,
                WorkCalendar.class,
                () -> calendarService.createVersion(
                        calendarId, command, idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(result.rowVersion()))
                .body(ShiftCalendarDtos.calendar(result));
    }

    @PutMapping("/calendars/{calendarId}/versions/{versionId}")
    ResponseEntity<CalendarView> updateCalendarVersion(
            @PathVariable String calendarId,
            @PathVariable String versionId,
            @Valid @RequestBody CalendarVersionRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var command = new CalendarVersionCommand(
                request.name(), request.calendarYear(),
                request.timeZone(), request.effectiveFrom(),
                request.effectiveTo(),
                reason(changeReason, request.reason()));
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "UPDATE_WORK_CALENDAR_VERSION",
                "ATTENDANCE_WORK_CALENDAR_VERSION",
                versionId,
                idempotencyKey,
                command,
                expectedVersion,
                HttpStatus.OK.value(),
                WorkCalendar::calendarVersionId,
                WorkCalendar.class,
                () -> calendarService.updateVersion(
                        calendarId, versionId, command, expectedVersion));
        return versioned(ShiftCalendarDtos.calendar(result), result.rowVersion());
    }

    @PostMapping("/calendars/{calendarId}/versions/{versionId}/publish")
    ResponseEntity<CalendarView> publishCalendarVersion(
            @PathVariable String calendarId,
            @PathVariable String versionId,
            @Valid @RequestBody ReasonRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        String normalizedReason = reason(changeReason, request.reason());
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "PUBLISH_WORK_CALENDAR_VERSION",
                "ATTENDANCE_WORK_CALENDAR_VERSION",
                versionId,
                idempotencyKey,
                new ReasonMutation(normalizedReason),
                expectedVersion,
                HttpStatus.OK.value(),
                WorkCalendar::calendarVersionId,
                WorkCalendar.class,
                () -> calendarService.publishVersion(
                        calendarId, versionId, expectedVersion,
                        normalizedReason));
        return versioned(ShiftCalendarDtos.calendar(result), result.rowVersion());
    }

    @PostMapping("/calendars/{calendarId}/versions/{versionId}/deactivate")
    ResponseEntity<CalendarView> deactivateCalendarVersion(
            @PathVariable String calendarId,
            @PathVariable String versionId,
            @Valid @RequestBody FutureDeactivationRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        String normalizedReason = reason(changeReason, request.reason());
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "DEACTIVATE_WORK_CALENDAR_VERSION",
                "ATTENDANCE_WORK_CALENDAR_VERSION",
                versionId,
                idempotencyKey,
                new FutureDeactivationMutation(
                        request.businessEffectiveFrom(), normalizedReason),
                expectedVersion,
                HttpStatus.OK.value(),
                WorkCalendar::calendarVersionId,
                WorkCalendar.class,
                () -> calendarService.deactivateVersion(
                        calendarId, versionId, expectedVersion,
                        request.businessEffectiveFrom(),
                        normalizedReason));
        return versioned(ShiftCalendarDtos.calendar(result), result.rowVersion());
    }

    @PostMapping("/calendars/{calendarId}/publish")
    ResponseEntity<CalendarView> publishCalendar(
            @PathVariable String calendarId,
            @Valid @RequestBody ReasonRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        String normalizedReason = reason(changeReason, request.reason());
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "PUBLISH_WORK_CALENDAR",
                "ATTENDANCE_WORK_CALENDAR",
                calendarId,
                idempotencyKey,
                new ReasonMutation(normalizedReason),
                expectedVersion,
                HttpStatus.OK.value(),
                WorkCalendar::calendarId,
                WorkCalendar.class,
                () -> calendarService.publish(
                        calendarId, expectedVersion, normalizedReason));
        return versioned(ShiftCalendarDtos.calendar(result), result.rowVersion());
    }

    @PostMapping("/calendars/{calendarId}/status")
    ResponseEntity<CalendarView> changeCalendarStatus(
            @PathVariable String calendarId,
            @Valid @RequestBody CalendarStatusRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        String normalizedReason = reason(changeReason, request.reason());
        var result = mutations.execute(
                "CHANGE_WORK_CALENDAR_STATUS",
                "ATTENDANCE_WORK_CALENDAR",
                calendarId,
                idempotencyKey,
                new StatusMutation(
                        request.status(),
                        request.businessEffectiveFrom(),
                        normalizedReason),
                expectedVersion,
                HttpStatus.OK.value(),
                WorkCalendar::calendarId,
                WorkCalendar.class,
                () -> calendarService.changeStatus(
                        calendarId, CalendarStatus.INACTIVE, expectedVersion,
                        request.businessEffectiveFrom(),
                        normalizedReason));
        return versioned(ShiftCalendarDtos.calendar(result), result.rowVersion());
    }

    @GetMapping("/calendars/{calendarId}/days")
    ResponseEntity<CalendarDayPage> listCalendarDays(
            @PathVariable String calendarId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return noStore(ShiftCalendarDtos.days(
                calendarService.listDays(
                        calendarId, from, to, page, size)));
    }

    @GetMapping("/calendars/{calendarId}/versions/{versionId}/days")
    ResponseEntity<CalendarDayPage> listCalendarVersionDays(
            @PathVariable String calendarId,
            @PathVariable String versionId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return noStore(ShiftCalendarDtos.days(
                calendarService.listVersionDays(
                        calendarId, versionId, from, to, page, size)));
    }

    @PutMapping("/calendars/{calendarId}/days")
    ResponseEntity<CalendarView> replaceCalendarDays(
            @PathVariable String calendarId,
            @Size(min = 1, max = 366)
            @RequestBody List<@Valid CalendarDayRequest> request,
            @RequestHeader("X-Change-Reason") String reason,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        List<CalendarDayCommand> days = request.stream()
                .map(value -> new CalendarDayCommand(
                        value.businessDate(), value.dayType(),
                        value.shiftVersionOverrideId()))
                .toList();
        var command = new ReplaceDaysCommand(
                days, ChangeReasonHeader.decodeAndValidate(reason));
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "REPLACE_WORK_CALENDAR_DAYS",
                "ATTENDANCE_WORK_CALENDAR",
                calendarId,
                idempotencyKey,
                command,
                expectedVersion,
                HttpStatus.OK.value(),
                WorkCalendar::calendarId,
                WorkCalendar.class,
                () -> calendarService.replaceDays(
                        calendarId, command, expectedVersion));
        return versioned(ShiftCalendarDtos.calendar(result), result.rowVersion());
    }

    @org.springframework.web.bind.annotation.PatchMapping(
            "/calendars/{calendarId}/versions/{versionId}/days")
    ResponseEntity<CalendarView> upsertCalendarVersionDays(
            @PathVariable String calendarId,
            @PathVariable String versionId,
            @Size(min = 1, max = 366)
            @RequestBody List<@Valid CalendarDayRequest> request,
            @RequestHeader("X-Change-Reason") String reason,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        List<CalendarDayCommand> days = request.stream()
                .map(value -> new CalendarDayCommand(
                        value.businessDate(), value.dayType(),
                        value.shiftVersionOverrideId()))
                .toList();
        var command = new ReplaceDaysCommand(
                days, ChangeReasonHeader.decodeAndValidate(reason));
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "PATCH_WORK_CALENDAR_VERSION_DAYS",
                "ATTENDANCE_WORK_CALENDAR_VERSION",
                versionId,
                idempotencyKey,
                command,
                expectedVersion,
                HttpStatus.OK.value(),
                WorkCalendar::calendarVersionId,
                WorkCalendar.class,
                () -> calendarService.upsertVersionDays(
                        calendarId, versionId, command, expectedVersion));
        return versioned(ShiftCalendarDtos.calendar(result), result.rowVersion());
    }

    private static <T> ResponseEntity<T> versioned(T body, long rowVersion) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(rowVersion))
                .body(body);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static String reason(String encodedHeader, String bodyReason) {
        return ChangeReasonHeader.requireMatches(encodedHeader, bodyReason);
    }

    private record ReasonMutation(String reason) {
    }

    private record FutureDeactivationMutation(
            LocalDate businessEffectiveFrom, String reason) {
    }

    private record TemplateStatusMutation(Object status, String reason) {
    }

    private record StatusMutation(
            Object status,
            LocalDate businessEffectiveFrom,
            String reason) {
    }
}
