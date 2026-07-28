package com.szsemicon.hr.attendance.interfaces.rest;

import com.szsemicon.hr.attendance.application.AttendanceGroupCommands.AssignmentCommand;
import com.szsemicon.hr.attendance.application.AttendanceGroupCommands.GroupCommand;
import com.szsemicon.hr.attendance.application.AttendanceGroupCommands.LocationCommand;
import com.szsemicon.hr.attendance.application.AttendanceGroupService;
import com.szsemicon.hr.attendance.application.AttendanceMutationIdempotencyService;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Assignment;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.AttendanceGroup;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Location;
import com.szsemicon.hr.attendance.interfaces.rest.AttendanceGroupDtos.AssignmentRequest;
import com.szsemicon.hr.attendance.interfaces.rest.AttendanceGroupDtos.AssignmentPage;
import com.szsemicon.hr.attendance.interfaces.rest.AttendanceGroupDtos.AssignmentView;
import com.szsemicon.hr.attendance.interfaces.rest.AttendanceGroupDtos.GroupPage;
import com.szsemicon.hr.attendance.interfaces.rest.AttendanceGroupDtos.GroupRequest;
import com.szsemicon.hr.attendance.interfaces.rest.AttendanceGroupDtos.GroupView;
import com.szsemicon.hr.attendance.interfaces.rest.AttendanceGroupDtos.LocationPage;
import com.szsemicon.hr.attendance.interfaces.rest.AttendanceGroupDtos.LocationRequest;
import com.szsemicon.hr.attendance.interfaces.rest.AttendanceGroupDtos.LocationView;
import com.szsemicon.hr.attendance.interfaces.rest.AttendanceGroupDtos.ReasonRequest;
import com.szsemicon.hr.shared.web.ChangeReasonHeader;
import com.szsemicon.hr.shared.web.StrongEtag;
import jakarta.validation.Valid;
import java.time.LocalDate;
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
public class AttendanceGroupController {

    private final AttendanceGroupService service;
    private final AttendanceMutationIdempotencyService mutations;

    public AttendanceGroupController(
            AttendanceGroupService service,
            AttendanceMutationIdempotencyService mutations) {
        this.service = service;
        this.mutations = mutations;
    }

    @GetMapping("/locations")
    ResponseEntity<LocationPage> listLocations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(AttendanceGroupDtos.locations(service.listLocations(page, size)));
    }

    @PostMapping("/locations")
    ResponseEntity<LocationView> createLocation(
            @Valid @RequestBody LocationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var command = new LocationCommand(
                request.legalEntityId(), request.code(), request.name(),
                request.timeZone(), request.effectiveFrom(),
                request.effectiveTo(),
                reason(changeReason, request.reason()));
        var result = mutations.execute(
                "CREATE_LOCATION",
                "ATTENDANCE_LOCATION",
                request.legalEntityId(),
                idempotencyKey,
                command,
                null,
                HttpStatus.CREATED.value(),
                Location::locationId,
                Location.class,
                () -> service.createLocation(command, idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(result.rowVersion()))
                .body(AttendanceGroupDtos.location(result));
    }

    @GetMapping("/locations/{locationId}")
    ResponseEntity<LocationView> getLocation(@PathVariable String locationId) {
        var result = service.getLocation(locationId);
        return versioned(
                AttendanceGroupDtos.location(result), result.rowVersion());
    }

    @GetMapping("/locations/{locationId}/revisions")
    ResponseEntity<LocationPage> listLocationRevisions(
            @PathVariable String locationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(AttendanceGroupDtos.locations(
                service.listLocationRevisions(locationId, page, size)));
    }

    @PutMapping("/locations/{locationId}")
    ResponseEntity<LocationView> updateLocation(
            @PathVariable String locationId,
            @Valid @RequestBody LocationRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var command = new LocationCommand(
                request.legalEntityId(), request.code(), request.name(),
                request.timeZone(), request.effectiveFrom(),
                request.effectiveTo(),
                reason(changeReason, request.reason()));
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "UPDATE_LOCATION",
                "ATTENDANCE_LOCATION",
                locationId,
                idempotencyKey,
                command,
                expectedVersion,
                HttpStatus.OK.value(),
                Location::locationId,
                Location.class,
                () -> service.updateLocation(
                        locationId, command, expectedVersion));
        return versioned(
                AttendanceGroupDtos.location(result), result.rowVersion());
    }

    @PostMapping("/locations/{locationId}/{lifecycleAction:activate|deactivate}")
    ResponseEntity<LocationView> changeLocationStatus(
            @PathVariable String locationId,
            @PathVariable String lifecycleAction,
            @Valid @RequestBody ReasonRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        LifecycleStatus status = "activate".equals(lifecycleAction)
                ? LifecycleStatus.ACTIVE : LifecycleStatus.INACTIVE;
        String normalizedReason = reason(changeReason, request.reason());
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "CHANGE_LOCATION_STATUS",
                "ATTENDANCE_LOCATION",
                locationId,
                idempotencyKey,
                new LifecycleMutation(status, normalizedReason),
                expectedVersion,
                HttpStatus.OK.value(),
                Location::locationId,
                Location.class,
                () -> service.changeLocationStatus(
                        locationId, status, normalizedReason, expectedVersion));
        return versioned(
                AttendanceGroupDtos.location(result), result.rowVersion());
    }

    @GetMapping("/groups")
    ResponseEntity<GroupPage> listGroups(
            @RequestParam(required = false) LocalDate asOf,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(AttendanceGroupDtos.groups(
                service.listGroups(asOf, page, size)));
    }

    @PostMapping("/groups")
    ResponseEntity<GroupView> createGroup(
            @Valid @RequestBody GroupRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var command = groupCommand(
                request, reason(changeReason, request.reason()));
        var result = mutations.execute(
                "CREATE_GROUP",
                "ATTENDANCE_GROUP",
                request.legalEntityId(),
                idempotencyKey,
                command,
                null,
                HttpStatus.CREATED.value(),
                AttendanceGroup::groupId,
                AttendanceGroup.class,
                () -> service.createGroup(command, idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(result.rowVersion()))
                .body(AttendanceGroupDtos.group(result));
    }

    @GetMapping("/groups/{groupId}")
    ResponseEntity<GroupView> getGroup(@PathVariable String groupId) {
        var result = service.getGroup(groupId);
        return versioned(AttendanceGroupDtos.group(result), result.rowVersion());
    }

    @GetMapping("/groups/{groupId}/revisions")
    ResponseEntity<GroupPage> listGroupRevisions(
            @PathVariable String groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(AttendanceGroupDtos.groups(
                service.listGroupRevisions(groupId, page, size)));
    }

    @PutMapping("/groups/{groupId}")
    ResponseEntity<GroupView> updateGroup(
            @PathVariable String groupId,
            @Valid @RequestBody GroupRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var command = groupCommand(
                request, reason(changeReason, request.reason()));
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "UPDATE_GROUP",
                "ATTENDANCE_GROUP",
                groupId,
                idempotencyKey,
                command,
                expectedVersion,
                HttpStatus.OK.value(),
                AttendanceGroup::groupId,
                AttendanceGroup.class,
                () -> service.updateGroup(
                        groupId, command, expectedVersion));
        return versioned(AttendanceGroupDtos.group(result), result.rowVersion());
    }

    @PostMapping("/groups/{groupId}/{lifecycleAction:activate|deactivate}")
    ResponseEntity<GroupView> changeGroupStatus(
            @PathVariable String groupId,
            @PathVariable String lifecycleAction,
            @Valid @RequestBody ReasonRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        LifecycleStatus status = "activate".equals(lifecycleAction)
                ? LifecycleStatus.ACTIVE : LifecycleStatus.INACTIVE;
        String normalizedReason = reason(changeReason, request.reason());
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "CHANGE_GROUP_STATUS",
                "ATTENDANCE_GROUP",
                groupId,
                idempotencyKey,
                new LifecycleMutation(status, normalizedReason),
                expectedVersion,
                HttpStatus.OK.value(),
                AttendanceGroup::groupId,
                AttendanceGroup.class,
                () -> service.changeGroupStatus(
                        groupId, status, normalizedReason, expectedVersion));
        return versioned(AttendanceGroupDtos.group(result), result.rowVersion());
    }

    @GetMapping("/groups/{groupId}/assignments")
    ResponseEntity<AssignmentPage> listAssignments(
            @PathVariable String groupId,
            @RequestParam(required = false) LocalDate asOf,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(AttendanceGroupDtos.assignments(
                service.listAssignments(groupId, asOf, page, size),
                asOf));
    }

    @PostMapping("/groups/{groupId}/assignments")
    ResponseEntity<AssignmentView> createAssignment(
            @PathVariable String groupId,
            @Valid @RequestBody AssignmentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var result = service.createAssignment(
                groupId,
                assignmentCommand(request, reason(changeReason, request.reason())),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(result.rowVersion()))
                .body(AttendanceGroupDtos.assignment(result, result.effectiveFrom()));
    }

    @PutMapping("/groups/{groupId}/assignments/{assignmentId}")
    ResponseEntity<AssignmentView> updateAssignment(
            @PathVariable String groupId,
            @PathVariable String assignmentId,
            @Valid @RequestBody AssignmentRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var command = assignmentCommand(
                request, reason(changeReason, request.reason()));
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = mutations.execute(
                "UPDATE_ASSIGNMENT",
                "ATTENDANCE_GROUP_ASSIGNMENT",
                assignmentId,
                idempotencyKey,
                command,
                expectedVersion,
                HttpStatus.OK.value(),
                Assignment::assignmentId,
                Assignment.class,
                () -> service.updateAssignment(
                        groupId, assignmentId, command, expectedVersion));
        return versioned(
                AttendanceGroupDtos.assignment(result, result.effectiveFrom()),
                result.rowVersion());
    }

    private GroupCommand groupCommand(GroupRequest request, String reason) {
        return new GroupCommand(
                request.legalEntityId(), request.code(), request.name(),
                request.locationId(), request.calendarId(),
                request.shiftTemplateId(), request.effectiveFrom(),
                request.effectiveTo(), reason);
    }

    private AssignmentCommand assignmentCommand(
            AssignmentRequest request, String reason) {
        return new AssignmentCommand(
                request.employeeId(), request.effectiveFrom(),
                request.effectiveTo(), reason);
    }

    private static String reason(String encodedHeader, String bodyReason) {
        return ChangeReasonHeader.requireMatches(encodedHeader, bodyReason);
    }

    private record LifecycleMutation(
            LifecycleStatus status,
            String reason) {
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
}
