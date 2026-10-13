package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.application.AttendanceGroupCommands.AssignmentTransferCommand;
import com.szsemicon.hr.attendance.application.AttendanceGroupCommands.GroupCommand;
import com.szsemicon.hr.attendance.application.CalendarCommands.CalendarCommand;
import com.szsemicon.hr.attendance.application.ShiftCommands.TemplateCommand;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Assignment;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.AttendanceGroup;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Location;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional boundary used by W3 controllers whose legacy application
 * methods pre-date the durable idempotency ledger.
 *
 * <p>Current capability and company scope are checked before the quick
 * lookup and again after the stable business resource is locked. The durable
 * lookup that can return a replay therefore always runs under that lock. The
 * supplied mutation joins this transaction, so a failed business write also
 * rolls back the STARTED ledger row.</p>
 */
@Service
public class AttendanceMutationIdempotencyService {

    private final CurrentPrincipalProvider principalProvider;
    private final CurrentCapabilityService capabilityService;
    private final PeopleRepository peopleRepository;
    private final AttendanceGroupRepository groupRepository;
    private final ShiftRepository shiftRepository;
    private final CalendarRepository calendarRepository;
    private final AttendanceSetupIdempotencyService idempotencyService;
    private final Clock clock;

    public AttendanceMutationIdempotencyService(
            CurrentPrincipalProvider principalProvider,
            CurrentCapabilityService capabilityService,
            PeopleRepository peopleRepository,
            AttendanceGroupRepository groupRepository,
            ShiftRepository shiftRepository,
            CalendarRepository calendarRepository,
            AttendanceSetupIdempotencyService idempotencyService,
            Clock clock) {
        this.principalProvider = principalProvider;
        this.capabilityService = capabilityService;
        this.peopleRepository = peopleRepository;
        this.groupRepository = groupRepository;
        this.shiftRepository = shiftRepository;
        this.calendarRepository = calendarRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    @Transactional
    public <T> T execute(
            String operation,
            String resourceType,
            String resourceId,
            String idempotencyKey,
            Object command,
            Long expectedVersion,
            int responseStatus,
            Function<T, String> responseResourceId,
            Class<T> responseType,
            Supplier<T> mutation) {
        Runnable currentAccessCheck = () -> requireCurrentAccess(
                operation, resourceType, resourceId, command);
        Runnable resourceLock = () -> lockStableResource(
                operation, resourceType, resourceId, command);
        return idempotencyService.execute(
                principalProvider.currentPrincipalId(),
                operation,
                resourceType,
                resourceId,
                idempotencyKey,
                new CanonicalMutation(resourceId, command, expectedVersion),
                currentAccessCheck,
                resourceLock,
                responseStatus,
                responseResourceId,
                responseType,
                mutation);
    }

    private void requireCurrentAccess(
            String operation,
            String resourceType,
            String resourceId,
            Object command) {
        String capability = capability(resourceType);
        capabilityService.require(capability);
        if ("ATTENDANCE_LOCATION".equals(resourceType)
                && !isCreate(operation)) {
            List<String> boundCompanyIds =
                    groupRepository.listSharedLocationCompanyIds(resourceId);
            String actor = principalProvider.currentPrincipalId();
            var at = clock.instant();
            if (boundCompanyIds.isEmpty()
                    || boundCompanyIds.stream().anyMatch(companyId ->
                            !peopleRepository.canAccessCompany(
                                    actor, capability, companyId, at))) {
                throw new ResourceNotAvailableAccessDeniedException();
            }
            return;
        }
        String companyId = companyId(
                operation, resourceType, resourceId);
        if (!peopleRepository.canAccessCompany(
                principalProvider.currentPrincipalId(),
                capability,
                companyId,
                clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        if ("ATTENDANCE_GROUP_ASSIGNMENT".equals(resourceType)
                && command instanceof AssignmentTransferCommand transfer) {
            Assignment source = groupRepository.findAssignment(resourceId)
                    .filter(assignment -> assignment.groupId()
                            .equals(transfer.sourceGroupId()))
                    .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
            if (!requireGroup(source.groupId()).companyId().equals(companyId)) {
                throw new ResourceNotAvailableAccessDeniedException();
            }
            AttendanceGroup target = requireGroup(transfer.targetGroupId());
            if (!peopleRepository.canAccessCompany(
                    principalProvider.currentPrincipalId(),
                    capability,
                    target.companyId(),
                    clock.instant())) {
                throw new ResourceNotAvailableAccessDeniedException();
            }
        }
    }

    private String companyId(
            String operation, String resourceType, String resourceId) {
        return switch (resourceType) {
            case "ATTENDANCE_LOCATION" -> isCreate(operation)
                    ? resourceId
                    : groupRepository.findLocation(resourceId)
                            .orElseThrow(
                                    ResourceNotAvailableAccessDeniedException::new)
                            .companyId();
            case "ATTENDANCE_GROUP" -> isCreate(operation)
                    ? resourceId
                    : requireGroup(resourceId).companyId();
            case "ATTENDANCE_GROUP_ASSIGNMENT" -> {
                Assignment assignment = groupRepository.findAssignment(resourceId)
                        .orElseThrow(
                                ResourceNotAvailableAccessDeniedException::new);
                yield requireGroup(assignment.groupId()).companyId();
            }
            case "ATTENDANCE_SHIFT_TEMPLATE" -> isCreate(operation)
                    ? resourceId
                    : shiftRepository.findTemplate(resourceId)
                            .orElseThrow(
                                    ResourceNotAvailableAccessDeniedException::new)
                            .companyId();
            case "ATTENDANCE_SHIFT_VERSION" -> {
                String shiftId = isCreate(operation)
                        ? resourceId
                        : shiftRepository.findVersion(resourceId)
                                .orElseThrow(
                                        ResourceNotAvailableAccessDeniedException::new)
                                .shiftId();
                yield shiftRepository.findTemplate(shiftId)
                        .orElseThrow(
                                ResourceNotAvailableAccessDeniedException::new)
                        .companyId();
            }
            case "ATTENDANCE_WORK_CALENDAR" -> isCreate(operation)
                    ? resourceId
                    : calendarRepository.findCalendar(resourceId)
                            .orElseThrow(
                                    ResourceNotAvailableAccessDeniedException::new)
                            .companyId();
            case "ATTENDANCE_WORK_CALENDAR_VERSION" -> {
                String calendarId = isCreate(operation)
                        ? resourceId
                        : calendarRepository.findVersion(resourceId)
                                .orElseThrow(
                                        ResourceNotAvailableAccessDeniedException::new)
                                .calendarId();
                yield calendarRepository.findCalendar(calendarId)
                        .orElseThrow(
                                ResourceNotAvailableAccessDeniedException::new)
                        .companyId();
            }
            default -> throw new IllegalArgumentException(
                    "unsupported attendance mutation resource type: "
                            + resourceType);
        };
    }

    private void lockStableResource(
            String operation,
            String resourceType,
            String resourceId,
            Object command) {
        switch (resourceType) {
            case "ATTENDANCE_LOCATION" -> {
                if (isCreate(operation)) {
                    peopleRepository.lockCompany(resourceId);
                } else {
                    groupRepository.lockSharedLocation(resourceId);
                    var bindings = groupRepository
                            .listSharedLocationBindings(resourceId)
                            .stream()
                            .sorted(Comparator.comparing(
                                    Location::locationId,
                                    AttendanceMutationIdempotencyService
                                            ::compareBinaryIds))
                            .toList();
                    if (bindings.isEmpty()) {
                        throw new ResourceNotAvailableAccessDeniedException();
                    }
                    for (var location : bindings) {
                        groupRepository.lockLocation(location.locationId());
                        groupRepository.lockLocationRevision(
                                location.locationRevisionId());
                    }
                }
            }
            case "ATTENDANCE_GROUP" -> {
                if (isCreate(operation)) {
                    GroupCommand create = requireGroupCommand(command);
                    lockLocations(List.of(create.locationId()));
                } else {
                    AttendanceGroup current = requireGroup(resourceId);
                    List<String> locationIds = new ArrayList<>();
                    locationIds.add(current.locationId());
                    if (command instanceof GroupCommand update
                            && !locationIds.contains(update.locationId())) {
                        locationIds.add(update.locationId());
                    }
                    lockLocations(locationIds);
                    groupRepository.lockGroup(resourceId);
                    AttendanceGroup locked = requireGroup(resourceId);
                    groupRepository.lockGroupRevision(
                            locked.groupRevisionId());
                }
            }
            case "ATTENDANCE_GROUP_ASSIGNMENT" -> {
                Assignment assignment = groupRepository.findAssignment(resourceId)
                        .orElseThrow(
                                ResourceNotAvailableAccessDeniedException::new);
                List<String> groupIds = new ArrayList<>();
                groupIds.add(assignment.groupId());
                if (command instanceof AssignmentTransferCommand transfer) {
                    groupIds.add(transfer.targetGroupId());
                }
                lockGroups(groupIds);
                groupRepository.lockEmployee(assignment.employeeId());
                groupRepository.lockAssignmentTimeline(
                        assignment.assignmentId());
            }
            case "ATTENDANCE_SHIFT_TEMPLATE" -> {
                if (isCreate(operation)) {
                    peopleRepository.lockCompany(resourceId);
                    if (!(command instanceof TemplateCommand create)) {
                        throw new IllegalArgumentException(
                                "shift template create requires TemplateCommand");
                    }
                    lockLocations(List.of(create.locationId()));
                } else {
                    shiftRepository.lockTemplate(resourceId);
                }
            }
            case "ATTENDANCE_SHIFT_VERSION" -> {
                String shiftId = isCreate(operation)
                        ? resourceId
                        : shiftRepository.findVersion(resourceId)
                                .orElseThrow(
                                        ResourceNotAvailableAccessDeniedException::new)
                                .shiftId();
                shiftRepository.lockTemplate(shiftId);
            }
            case "ATTENDANCE_WORK_CALENDAR" -> {
                if (isCreate(operation)) {
                    peopleRepository.lockCompany(resourceId);
                    if (!(command instanceof CalendarCommand create)) {
                        throw new IllegalArgumentException(
                                "work calendar create requires CalendarCommand");
                    }
                    lockLocations(List.of(create.locationId()));
                } else {
                    calendarRepository.lockCalendar(resourceId);
                }
            }
            case "ATTENDANCE_WORK_CALENDAR_VERSION" -> {
                String calendarId = isCreate(operation)
                        ? resourceId
                        : calendarRepository.findVersion(resourceId)
                                .orElseThrow(
                                        ResourceNotAvailableAccessDeniedException::new)
                                .calendarId();
                calendarRepository.lockCalendar(calendarId);
            }
            default -> throw new IllegalArgumentException(
                    "unsupported attendance mutation resource type: "
                            + resourceType);
        }
    }

    private void lockLocations(List<String> locationIds) {
        locationIds.stream()
                .distinct()
                .sorted(AttendanceMutationIdempotencyService::compareBinaryIds)
                .forEach(locationId -> {
                    groupRepository.lockLocation(locationId);
                    var location = groupRepository.findLocation(locationId)
                            .orElseThrow(
                                    ResourceNotAvailableAccessDeniedException::new);
                    groupRepository.lockLocationRevision(
                            location.locationRevisionId());
                });
    }

    private void lockGroups(List<String> groupIds) {
        groupIds.stream()
                .distinct()
                .sorted(AttendanceMutationIdempotencyService::compareBinaryIds)
                .forEach(groupId -> {
                    groupRepository.lockGroup(groupId);
                    AttendanceGroup group = requireGroup(groupId);
                    groupRepository.lockGroupRevision(group.groupRevisionId());
                });
    }

    private AttendanceGroup requireGroup(String groupId) {
        return groupRepository.findGroup(groupId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
    }

    private GroupCommand requireGroupCommand(Object command) {
        if (command instanceof GroupCommand groupCommand) {
            return groupCommand;
        }
        throw new IllegalArgumentException(
                "attendance group create requires GroupCommand");
    }

    private String capability(String resourceType) {
        return switch (resourceType) {
            case "ATTENDANCE_LOCATION", "ATTENDANCE_GROUP" ->
                    CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP;
            case "ATTENDANCE_GROUP_ASSIGNMENT" ->
                    CapabilityCodes.ATTENDANCE_SETUP_ASSIGN;
            case "ATTENDANCE_SHIFT_TEMPLATE", "ATTENDANCE_SHIFT_VERSION" ->
                    CapabilityCodes.ATTENDANCE_SETUP_MANAGE_SHIFT;
            case "ATTENDANCE_WORK_CALENDAR",
                    "ATTENDANCE_WORK_CALENDAR_VERSION" ->
                    CapabilityCodes.ATTENDANCE_SETUP_MANAGE_CALENDAR;
            default -> throw new IllegalArgumentException(
                    "unsupported attendance mutation resource type: "
                            + resourceType);
        };
    }

    private static boolean isCreate(String operation) {
        return operation.startsWith("CREATE_");
    }

    private static int compareBinaryIds(String left, String right) {
        try {
            return compareBytes(uuidBytes(UUID.fromString(left)),
                    uuidBytes(UUID.fromString(right)));
        } catch (IllegalArgumentException ignored) {
            return left.compareTo(right);
        }
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static int compareBytes(byte[] left, byte[] right) {
        for (int index = 0; index < Math.min(left.length, right.length); index++) {
            int compared = Integer.compare(
                    Byte.toUnsignedInt(left[index]),
                    Byte.toUnsignedInt(right[index]));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private record CanonicalMutation(
            String resourceId,
            Object command,
            Long expectedVersion) {
    }
}
