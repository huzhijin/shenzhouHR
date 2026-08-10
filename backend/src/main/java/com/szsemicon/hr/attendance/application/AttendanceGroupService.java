package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.application.AttendanceGroupCommands.AssignmentCommand;
import com.szsemicon.hr.attendance.application.AttendanceGroupCommands.AssignmentTransferCommand;
import com.szsemicon.hr.attendance.application.AttendanceGroupCommands.GroupCommand;
import com.szsemicon.hr.attendance.application.AttendanceGroupCommands.LocationCommand;
import com.szsemicon.hr.attendance.application.AttendanceGroupRepository.AssignmentRolloverCandidate;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Assignment;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.AssignmentListItem;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.AttendanceGroup;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Location;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Page;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyBinding;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind;
import com.szsemicon.hr.attendance.domain.CalendarModels.CalendarStatus;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendar;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendarDay;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftTemplate;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftVersion;
import com.szsemicon.hr.attendance.domain.ShiftModels.VersionStatus;
import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceGroupService {

    private static final List<PolicyKind> ROLLOVER_POLICY_KIND_ORDER = List.of(
            PolicyKind.MEAL_DEDUCTION,
            PolicyKind.LATE_GRACE,
            PolicyKind.MONTHLY_LATE_EXEMPTION,
            PolicyKind.PUNCH_WINDOW,
            PolicyKind.PERIOD_CLOSE);
    private static final Comparator<String> BINARY_ID_ORDER =
            AttendanceGroupService::compareBinaryIds;
    private static final int CALENDAR_DAY_VALIDATION_PAGE_SIZE = 100;
    private static final long MAX_CALENDAR_VERSION_DAYS = 366;

    private final CurrentCapabilityService capabilityService;
    private final CurrentPrincipalProvider principalProvider;
    private final PeopleRepository peopleRepository;
    private final AttendanceGroupRepository repository;
    private final ShiftRepository shiftRepository;
    private final CalendarRepository calendarRepository;
    private final AttendancePolicyRepository policyRepository;
    private final AttendanceSetupIdempotencyService idempotencyService;
    private final AuditService auditService;
    private final SecurityTokenService tokenService;
    private final Clock clock;
    private final boolean testFixtureLocationBootstrapEnabled;

    public AttendanceGroupService(
            CurrentCapabilityService capabilityService,
            CurrentPrincipalProvider principalProvider,
            PeopleRepository peopleRepository,
            AttendanceGroupRepository repository,
            ShiftRepository shiftRepository,
            CalendarRepository calendarRepository,
            AttendancePolicyRepository policyRepository,
            AttendanceSetupIdempotencyService idempotencyService,
            AuditService auditService,
            SecurityTokenService tokenService,
            Clock clock,
            Environment environment) {
        this.capabilityService = capabilityService;
        this.principalProvider = principalProvider;
        this.peopleRepository = peopleRepository;
        this.repository = repository;
        this.shiftRepository = shiftRepository;
        this.calendarRepository = calendarRepository;
        this.policyRepository = policyRepository;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.tokenService = tokenService;
        this.clock = clock;
        this.testFixtureLocationBootstrapEnabled =
                environment.acceptsProfiles(Profiles.of("test"));
    }

    @Transactional(readOnly = true)
    public Page<Location> listLocations(int page, int size) {
        return listLocations(null, page, size);
    }

    @Transactional(readOnly = true)
    public Page<Location> listLocations(String companyId, int page, int size) {
        AttendanceSetupRules.page(page, size);
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_READ);
        String principal = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        return new Page<>(
                repository.listLocations(
                        principal, CapabilityCodes.ATTENDANCE_SETUP_READ,
                        companyId, size, page * size, now),
                repository.countLocations(
                        principal, CapabilityCodes.ATTENDANCE_SETUP_READ,
                        companyId, now),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public Location getLocation(String locationId) {
        Location location = repository.findSharedLocation(locationId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireSharedLocationRead(locationId);
        return location;
    }

    @Transactional(readOnly = true)
    public boolean canManageSharedLocation(String locationId) {
        List<String> companyIds =
                repository.listSharedLocationCompanyIds(locationId);
        if (companyIds.isEmpty()) {
            return false;
        }
        String actor = principalProvider.currentPrincipalId();
        Instant at = clock.instant();
        return companyIds.stream().allMatch(companyId ->
                peopleRepository.canAccessCompany(
                        actor,
                        CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP,
                        companyId,
                        at));
    }

    @Transactional(readOnly = true)
    public Page<Location> listLocationRevisions(
            String locationId, int page, int size) {
        AttendanceSetupRules.page(page, size);
        requireSharedLocationRead(locationId);
        return new Page<>(
                repository.listLocationRevisions(
                        locationId, size, page * size),
                repository.countLocationRevisions(locationId),
                page,
                size);
    }

    @Transactional
    public Location createLocation(LocationCommand command, String idempotencyKey) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP);
        if (!testFixtureLocationBootstrapEnabled) {
            throw AttendanceSetupRules.conflict(
                    "SHARED_LOCATION_CATALOG_FIXED",
                    "共享地点目录固定为七个预置地点，不支持新增；请修改现有地点");
        }
        LocationCommand normalized = normalize(command);
        String key = AttendanceSetupRules.idempotencyKey(idempotencyKey);
        requireCompany(
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP, normalized.companyId());
        String actor = principalProvider.currentPrincipalId();
        Location replay = repository.findLocationByIdempotency(actor, key).orElse(null);
        if (replay != null) {
            if (!same(replay, normalized)) {
                throw AttendanceSetupRules.conflict(
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key 已用于不同的地点请求");
            }
            return repository.findSharedLocation(replay.sharedLocationId())
                    .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        }
        Instant now = clock.instant();
        String locationId = UUID.randomUUID().toString();
        Location created = new Location(
                locationId, locationId, normalized.companyId(),
                normalized.code(), UUID.randomUUID().toString(), 1,
                normalized.name(), normalized.timeZone(), LifecycleStatus.ACTIVE,
                normalized.effectiveFrom(), normalized.effectiveTo(),
                locationDigest(normalized),
                0, normalized.reason(), actor, now, actor, now);
        repository.insertLocation(created, key);
        Location persisted = repository.findSharedLocation(locationId)
                .orElseThrow(() -> new IllegalStateException(
                        "shared location fixture was not persisted"));
        audit(actor, "ATTENDANCE_LOCATION_CREATED", "ATTENDANCE_LOCATION",
                persisted.sharedLocationId(), normalized.reason(), null, persisted);
        return persisted;
    }

    @Transactional
    public Location updateLocation(
            String locationId, LocationCommand command, long expectedVersion) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP);
        LocationCommand normalized = normalize(command);
        Location current = repository.findSharedLocation(locationId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (!current.code().equals(normalized.code())) {
            throw AttendanceSetupRules.conflict(
                    "LOCATION_IDENTITY_IMMUTABLE",
                    "地点编码属于稳定 identity，不可通过 revision 修改");
        }
        requireSharedLocationManagement(locationId);
        long sharedVersion = repository.lockSharedLocation(locationId);
        requireVersion(sharedVersion, expectedVersion);
        List<Location> bindings = lockSharedLocationBindings(locationId);
        current = repository.findSharedLocation(current.sharedLocationId())
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireVersion(current.rowVersion(), expectedVersion);
        if (bindings.stream().noneMatch(binding ->
                binding.companyId().equals(normalized.companyId()))) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        for (Location binding : bindings) {
            if (!binding.code().equals(normalized.code())) {
                throw AttendanceSetupRules.conflict(
                        "SHARED_LOCATION_PROJECTION_DRIFT",
                        "共享地点的公司兼容投影编码不一致");
            }
            requireFutureRevision(
                    binding.effectiveFrom(), normalized.effectiveFrom());
        }
        rejectReferencedTimeZoneChange(current, normalized, bindings);
        Instant now = clock.instant();
        String actor = principalProvider.currentPrincipalId();
        Map<String, Location> successors = sharedLocationSuccessors(
                bindings, normalized, null, actor, now);
        Location compatibilitySuccessor = successors.values().stream()
                .filter(value -> value.companyId()
                        .equals(normalized.companyId()))
                .findFirst()
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (!repository.updateSharedLocation(
                compatibilitySuccessor, sharedVersion)) {
            throw new OptimisticLockingFailureException(
                    "shared location version changed");
        }
        for (Location binding : bindings) {
            coordinateLocationRollover(
                    binding,
                    successors.get(binding.locationId()),
                    binding.rowVersion(),
                    normalized.reason(),
                    actor,
                    now);
        }
        Location persisted = repository.findSharedLocation(
                        current.sharedLocationId())
                .filter(value -> value.rowVersion() == sharedVersion + 1)
                .orElseThrow(() -> new OptimisticLockingFailureException(
                        "shared location successor was not persisted"));
        audit(actor, "ATTENDANCE_LOCATION_UPDATED", "ATTENDANCE_LOCATION",
                current.sharedLocationId(), normalized.reason(), current, persisted);
        return persisted;
    }

    @Transactional
    public Location changeLocationStatus(
            String locationId,
            LifecycleStatus status,
            String reason,
            long expectedVersion) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP);
        Location current = repository.findSharedLocation(locationId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        String normalizedReason = AttendanceSetupRules.reason(reason);
        requireSharedLocationManagement(locationId);
        long sharedVersion = repository.lockSharedLocation(locationId);
        requireVersion(sharedVersion, expectedVersion);
        List<Location> bindings = lockSharedLocationBindings(locationId);
        current = repository.findSharedLocation(current.sharedLocationId())
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireVersion(current.rowVersion(), expectedVersion);
        if (current.status() == status) {
            throw AttendanceSetupRules.conflict(
                    "LOCATION_STATUS_UNCHANGED", "地点已经处于目标状态");
        }
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        LocalDate nextEffectiveFrom = futureStatusBoundary(current.effectiveFrom());
        LocationCommand sharedTransition = new LocationCommand(
                current.companyId(), current.code(), current.name(),
                current.timeZone(), nextEffectiveFrom, current.effectiveTo(),
                normalizedReason);
        for (Location binding : bindings) {
            requireFutureRevision(binding.effectiveFrom(), nextEffectiveFrom);
        }
        Map<String, Location> successors = sharedLocationSuccessors(
                bindings, sharedTransition, status, actor, now);
        Location compatibilitySuccessor = successors.values().stream()
                .findFirst()
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (!repository.updateSharedLocation(
                compatibilitySuccessor, sharedVersion)) {
            throw new OptimisticLockingFailureException(
                    "shared location version changed");
        }
        for (Location binding : bindings) {
            coordinateLocationRollover(
                    binding,
                    successors.get(binding.locationId()),
                    binding.rowVersion(),
                    normalizedReason,
                    actor,
                    now);
        }
        Location persisted = repository.findSharedLocation(
                        current.sharedLocationId())
                .filter(value -> value.rowVersion() == sharedVersion + 1)
                .orElseThrow(() -> new OptimisticLockingFailureException(
                        "shared location status successor was not persisted"));
        audit(actor, "ATTENDANCE_LOCATION_" + status.name(), "ATTENDANCE_LOCATION",
                current.sharedLocationId(), normalizedReason, current, persisted);
        return persisted;
    }

    @Transactional(readOnly = true)
    public Page<AttendanceGroup> listGroups(
            LocalDate asOf, int page, int size) {
        return listGroups(null, asOf, page, size);
    }

    @Transactional(readOnly = true)
    public Page<AttendanceGroup> listGroups(
            String companyId, LocalDate asOf, int page, int size) {
        AttendanceSetupRules.page(page, size);
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_READ);
        String principal = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        return new Page<>(
                repository.listGroups(
                        principal, CapabilityCodes.ATTENDANCE_SETUP_READ,
                        companyId, asOf, size, page * size, now),
                repository.countGroups(
                        principal, CapabilityCodes.ATTENDANCE_SETUP_READ,
                        companyId, asOf, now),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public AttendanceGroup getGroup(String groupId) {
        return requireGroup(groupId, CapabilityCodes.ATTENDANCE_SETUP_READ);
    }

    @Transactional(readOnly = true)
    public Page<AttendanceGroup> listGroupRevisions(
            String groupId, int page, int size) {
        AttendanceSetupRules.page(page, size);
        requireGroup(groupId, CapabilityCodes.ATTENDANCE_SETUP_READ);
        return new Page<>(
                repository.listGroupRevisions(groupId, size, page * size),
                repository.countGroupRevisions(groupId),
                page,
                size);
    }

    @Transactional
    public AttendanceGroup createGroup(GroupCommand command, String idempotencyKey) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP);
        GroupCommand normalized = normalize(command);
        String key = AttendanceSetupRules.idempotencyKey(idempotencyKey);
        requireCompany(
                CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP, normalized.companyId());
        String actor = principalProvider.currentPrincipalId();
        Map<String, Location> lockedLocations = lockLocationReferences(
                Set.of(normalized.locationId()), normalized.effectiveFrom());
        GroupReferences references = validateGroupReferences(
                normalized, lockedLocations.get(normalized.locationId()));
        AttendanceGroup replay =
                repository.findGroupByIdempotency(actor, key).orElse(null);
        if (replay != null) {
            if (!same(replay, normalized)) {
                throw AttendanceSetupRules.conflict(
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key 已用于不同的考勤组请求");
            }
            return replay;
        }
        List<DefaultPolicy> baselines = resolveDefaultPolicies(
                normalized.companyId(),
                normalized.effectiveFrom(),
                normalized.effectiveTo());
        Instant now = clock.instant();
        AttendanceGroup created = new AttendanceGroup(
                UUID.randomUUID().toString(), normalized.companyId(),
                normalized.code(), UUID.randomUUID().toString(), 1,
                normalized.name(), references.location().locationId(),
                references.location().locationRevisionId(),
                normalized.calendarId(), normalized.shiftTemplateId(),
                LifecycleStatus.ACTIVE, normalized.effectiveFrom(), normalized.effectiveTo(),
                groupDigest(normalized, references.location()),
                0, normalized.reason(), actor, now, actor, now);
        repository.insertGroup(created, key);
        repository.lockGroup(created.groupId());
        repository.lockGroupRevision(created.groupRevisionId());
        provisionDefaultPolicies(created, baselines, actor, key, now);
        audit(actor, "ATTENDANCE_GROUP_CREATED", "ATTENDANCE_GROUP",
                created.groupId(), normalized.reason(), null, created);
        return created;
    }

    private void provisionDefaultPolicies(
            AttendanceGroup group,
            List<DefaultPolicy> baselines,
            String actor,
            String groupIdempotencyKey,
            Instant now) {
        for (DefaultPolicy baseline : baselines) {
            PolicyKind kind = baseline.kind();
            PolicyBinding binding = new PolicyBinding(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    1,
                    group.companyId(),
                    kind,
                    baseline.policyVersionId(),
                    group.groupId(),
                    group.groupRevisionId(),
                    group.effectiveFrom(),
                    group.effectiveTo(),
                    LifecycleStatus.ACTIVE,
                    bindingDigest(
                            group.groupRevisionId(),
                            baseline.policyVersionId(),
                            baseline.snapshotDigest(),
                            group.effectiveFrom(),
                            group.effectiveTo()),
                    0,
                    "考勤组创建时配置受控默认策略",
                    actor,
                    now,
                    actor,
                    now);
            String bindingKey = "DEFAULT:"
                    + tokenService.digest(
                            groupIdempotencyKey + "|" + kind.name());
            policyRepository.insertBinding(binding, bindingKey);
            audit(
                    actor,
                    "ATTENDANCE_POLICY_DEFAULT_BOUND",
                    "ATTENDANCE_POLICY_BINDING",
                    binding.bindingId(),
                    binding.changeReason(),
                    null,
                    binding);
        }
    }

    @Transactional
    public AttendanceGroup updateGroup(
            String groupId, GroupCommand command, long expectedVersion) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP);
        AttendanceGroup current = requireGroup(
                groupId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP);
        GroupCommand normalized = normalize(command);
        if (!current.companyId().equals(normalized.companyId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        if (!current.code().equals(normalized.code())) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_GROUP_IDENTITY_IMMUTABLE",
                    "考勤组编码属于稳定 identity，不可通过 revision 修改");
        }
        Map<String, Location> lockedLocations = lockLocationReferences(
                locationIds(current.locationId(), normalized.locationId()),
                normalized.effectiveFrom());
        repository.lockGroup(groupId);
        current = requireGroup(
                groupId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP);
        if (!lockedLocations.containsKey(current.locationId())) {
            throw AttendanceSetupRules.conflict(
                    "GROUP_LOCATION_CHANGED",
                    "考勤组地点在获取外层锁期间已变化");
        }
        repository.lockGroupRevision(current.groupRevisionId());
        requireVersion(current.rowVersion(), expectedVersion);
        requireFutureRevision(current.effectiveFrom(), normalized.effectiveFrom());
        if (periodShrinks(current.effectiveTo(), normalized.effectiveTo())) {
            validateGroupPeriodDependencies(groupId, normalized.effectiveTo());
        }
        GroupReferences references = validateGroupReferences(
                normalized, lockedLocations.get(normalized.locationId()));
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        AttendanceGroup updated = new AttendanceGroup(
                current.groupId(), current.companyId(), normalized.code(),
                UUID.randomUUID().toString(), current.revisionNumber() + 1,
                normalized.name(), references.location().locationId(),
                references.location().locationRevisionId(), normalized.calendarId(),
                normalized.shiftTemplateId(), current.status(),
                normalized.effectiveFrom(), normalized.effectiveTo(),
                groupDigest(normalized, references.location()),
                current.rowVersion() + 1, normalized.reason(), current.createdBy(),
                current.createdAt(), actor, now);
        GroupRolloverDependencies dependencies =
                lockGroupRolloverDependencies(current, updated);
        persistGroupRollover(
                current, updated, dependencies, expectedVersion,
                normalized.reason(), actor, now);
        audit(actor, "ATTENDANCE_GROUP_UPDATED", "ATTENDANCE_GROUP",
                groupId, normalized.reason(), current, updated);
        return updated;
    }

    @Transactional
    public AttendanceGroup changeGroupStatus(
            String groupId,
            LifecycleStatus status,
            String reason,
            long expectedVersion) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP);
        AttendanceGroup current = requireGroup(
                groupId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP);
        String normalizedReason = AttendanceSetupRules.reason(reason);
        LocalDate nextEffectiveFrom =
                futureStatusBoundary(current.effectiveFrom());
        Map<String, Location> lockedLocations = lockLocationReferences(
                Set.of(current.locationId()), nextEffectiveFrom);
        repository.lockGroup(groupId);
        current = requireGroup(
                groupId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP);
        repository.lockGroupRevision(current.groupRevisionId());
        requireVersion(current.rowVersion(), expectedVersion);
        if (current.status() == status) {
            throw AttendanceSetupRules.conflict(
                    "GROUP_STATUS_UNCHANGED", "考勤组已经处于目标状态");
        }
        nextEffectiveFrom = futureStatusBoundary(current.effectiveFrom());
        Location location = lockedLocations.get(current.locationId());
        if (location == null
                || !includes(location, nextEffectiveFrom)) {
            throw AttendanceSetupRules.conflict(
                    "GROUP_LOCATION_CHANGED",
                    "考勤组地点在获取外层锁期间已变化");
        }
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        requireFutureRevision(current.effectiveFrom(), nextEffectiveFrom);
        GroupCommand transition = new GroupCommand(
                current.companyId(), current.code(), current.name(),
                current.locationId(), current.calendarId(), current.shiftTemplateId(),
                nextEffectiveFrom, current.effectiveTo(), normalizedReason);
        validateGroupReferences(transition, location);
        AttendanceGroup updated = new AttendanceGroup(
                current.groupId(), current.companyId(), current.code(),
                UUID.randomUUID().toString(), current.revisionNumber() + 1,
                current.name(), current.locationId(), location.locationRevisionId(),
                current.calendarId(), current.shiftTemplateId(), status,
                nextEffectiveFrom, current.effectiveTo(),
                groupDigest(transition, location),
                current.rowVersion() + 1, normalizedReason, current.createdBy(),
                current.createdAt(), actor, now);
        GroupRolloverDependencies dependencies =
                lockGroupRolloverDependencies(current, updated);
        persistGroupRollover(
                current, updated, dependencies, expectedVersion,
                normalizedReason, actor, now);
        audit(actor, "ATTENDANCE_GROUP_" + status.name(), "ATTENDANCE_GROUP",
                groupId, normalizedReason, current, updated);
        return updated;
    }

    @Transactional(readOnly = true)
    public List<Assignment> listAssignments(String groupId, LocalDate asOf) {
        requireGroup(groupId, CapabilityCodes.ATTENDANCE_SETUP_READ);
        return allAssignments(groupId, asOf);
    }

    @Transactional(readOnly = true)
    public Page<AssignmentListItem> listAssignments(
            String groupId, LocalDate asOf, int page, int size) {
        AttendanceSetupRules.page(page, size);
        requireGroup(groupId, CapabilityCodes.ATTENDANCE_SETUP_READ);
        List<AssignmentListItem> assignments = repository.listAssignments(
                        groupId, asOf, size, page * size)
                .stream()
                .map(this::describeAssignment)
                .toList();
        return new Page<>(
                assignments,
                repository.countAssignments(groupId, asOf),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public AssignmentListItem describeAssignment(Assignment assignment) {
        boolean hasSuccessor = repository
                .findAssignmentSuccessor(assignment.assignmentId())
                .isPresent();
        return new AssignmentListItem(
                assignment,
                hasSuccessor,
                !hasSuccessor && hasTransferBoundary(assignment));
    }

    @Transactional
    public Assignment createAssignment(
            String groupId, AssignmentCommand command, String idempotencyKey) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_ASSIGN);
        AssignmentCommand normalized = normalize(command);
        requireGroup(groupId, CapabilityCodes.ATTENDANCE_SETUP_ASSIGN);
        String actor = principalProvider.currentPrincipalId();
        return idempotencyService.execute(
                actor,
                "CREATE_ASSIGNMENT",
                "ATTENDANCE_GROUP_ASSIGNMENT",
                normalized.employeeId(),
                idempotencyKey,
                new AssignmentCreationRequest(groupId, normalized),
                () -> requireAssignmentAccess(groupId, normalized),
                () -> repository.lockEmployee(normalized.employeeId()),
                201,
                Assignment::assignmentId,
                Assignment.class,
                () -> createAssignmentLocked(
                        groupId, normalized, actor, idempotencyKey));
    }

    private Assignment createAssignmentLocked(
            String groupId,
            AssignmentCommand command,
            String actor,
            String idempotencyKey) {
        AttendanceGroup group = requireEffectiveActiveGroup(
                groupId, command.effectiveFrom(),
                CapabilityCodes.ATTENDANCE_SETUP_ASSIGN);
        validateAssignmentWithinGroup(group, command);
        requireEmployeeInCompany(
                command.employeeId(), command.effectiveFrom(),
                group.companyId());
        validateAssignmentConfigurationInterval(group, command);
        if (repository.hasAssignmentOverlap(
                command.employeeId(), command.effectiveFrom(),
                command.effectiveTo(), null)) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_ASSIGNMENT_OVERLAP", "员工考勤组分配期间重叠");
        }
        Instant now = clock.instant();
        Assignment created = new Assignment(
                UUID.randomUUID().toString(), groupId, command.employeeId(),
                command.effectiveFrom(), command.effectiveTo(), 0,
                command.reason(), actor, now, actor, now);
        String storageKey = tokenService.digest(
                "CREATE_ASSIGNMENT|" + command.employeeId() + "|"
                        + AttendanceSetupRules.idempotencyKey(idempotencyKey));
        repository.insertAssignment(created, storageKey);
        audit(actor, "ATTENDANCE_GROUP_ASSIGNMENT_CREATED",
                "ATTENDANCE_GROUP_ASSIGNMENT", created.assignmentId(),
                command.reason(), null, created);
        return created;
    }

    @Transactional
    public Assignment updateAssignment(
            String groupId,
            String assignmentId,
            AssignmentCommand command,
            long expectedVersion) {
        Assignment current = repository.findAssignment(assignmentId)
                .filter(value -> value.groupId().equals(groupId))
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        AssignmentCommand normalized = normalize(command);
        AttendanceGroup group = requireEffectiveActiveGroup(
                groupId, normalized.effectiveFrom(),
                CapabilityCodes.ATTENDANCE_SETUP_ASSIGN);
        if (!current.employeeId().equals(normalized.employeeId())) {
            throw AttendanceSetupRules.invalid("不能通过更新分配改变员工身份");
        }
        if (!normalized.effectiveFrom().isAfter(current.effectiveFrom())) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_ASSIGNMENT_BOUNDARY_CONFLICT",
                    "人员分配 successor 生效日必须晚于 predecessor 生效日");
        }
        validateAssignmentWithinGroup(group, normalized);
        requireEmployeeInCompany(
                normalized.employeeId(), normalized.effectiveFrom(),
                group.companyId());
        validateAssignmentConfigurationInterval(group, normalized);
        requireVersion(current.rowVersion(), expectedVersion);
        repository.lockEmployee(normalized.employeeId());
        if (repository.hasAssignmentOverlap(
                normalized.employeeId(), normalized.effectiveFrom(),
                normalized.effectiveTo(), assignmentId)) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_ASSIGNMENT_OVERLAP", "员工考勤组分配期间重叠");
        }
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        Assignment updated = new Assignment(
                current.assignmentId(), current.groupId(), current.employeeId(),
                normalized.effectiveFrom(), normalized.effectiveTo(),
                current.rowVersion() + 1, normalized.reason(), current.createdBy(),
                current.createdAt(), actor, now);
        if (!repository.updateAssignment(updated, expectedVersion)) {
            throw new OptimisticLockingFailureException("assignment version changed");
        }
        Assignment successor = repository.findAssignmentSuccessor(assignmentId)
                .orElseThrow(() -> new IllegalStateException(
                        "assignment successor was not persisted"));
        audit(actor, "ATTENDANCE_GROUP_ASSIGNMENT_UPDATED",
                "ATTENDANCE_GROUP_ASSIGNMENT", assignmentId,
                normalized.reason(), current, successor);
        return successor;
    }

    @Transactional
    public Assignment transferAssignment(
            String sourceGroupId,
            String assignmentId,
            AssignmentTransferCommand command,
            long expectedVersion) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_ASSIGN);
        AssignmentTransferCommand normalized = normalize(command);
        if (!sourceGroupId.equals(normalized.sourceGroupId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        Assignment current = repository.findAssignment(assignmentId)
                .filter(value -> value.groupId().equals(sourceGroupId))
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        AttendanceGroup sourceGroup = requireGroup(
                sourceGroupId, CapabilityCodes.ATTENDANCE_SETUP_ASSIGN);
        AttendanceGroup targetGroup = requireEffectiveActiveGroup(
                normalized.targetGroupId(),
                normalized.effectiveFrom(),
                CapabilityCodes.ATTENDANCE_SETUP_ASSIGN);
        if (sourceGroup.groupId().equals(targetGroup.groupId())) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_ASSIGNMENT_TRANSFER_SAME_GROUP",
                    "目标考勤组必须与当前考勤组不同");
        }
        if (!sourceGroup.companyId().equals(targetGroup.companyId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        requireEmployeeInCompany(
                current.employeeId(),
                normalized.effectiveFrom(),
                targetGroup.companyId());

        repository.lockEmployee(current.employeeId());
        repository.lockAssignmentTimeline(current.assignmentId());
        current = repository.findAssignment(assignmentId)
                .filter(value -> value.groupId().equals(sourceGroupId))
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireVersion(current.rowVersion(), expectedVersion);
        if (repository.findAssignmentSuccessor(assignmentId).isPresent()) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_ASSIGNMENT_SUCCESSOR_EXISTS",
                    "该人员归属已经存在后继记录，不能再次回插");
        }
        if (!normalized.effectiveFrom().isAfter(current.effectiveFrom())
                || current.effectiveTo() != null
                && !normalized.effectiveFrom().isBefore(current.effectiveTo())) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_ASSIGNMENT_BOUNDARY_CONFLICT",
                    "调配生效日必须位于当前人员归属的有效期间内");
        }
        if (normalized.effectiveFrom().isBefore(LocalDate.now(clock))) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_ASSIGNMENT_BACKFILL_FORBIDDEN",
                    "人员调配不能回插到已经过去的业务日期");
        }

        AssignmentCommand targetAssignment = new AssignmentCommand(
                current.employeeId(),
                normalized.effectiveFrom(),
                current.effectiveTo(),
                normalized.reason());
        validateAssignmentWithinGroup(targetGroup, targetAssignment);
        validateAssignmentConfigurationInterval(targetGroup, targetAssignment);
        if (repository.hasAssignmentOverlap(
                current.employeeId(),
                normalized.effectiveFrom(),
                current.effectiveTo(),
                current.assignmentId())) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_ASSIGNMENT_OVERLAP",
                    "目标日期已存在其他考勤组归属，不能重复调配");
        }

        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        Assignment updated = new Assignment(
                current.assignmentId(),
                targetGroup.groupId(),
                current.employeeId(),
                normalized.effectiveFrom(),
                current.effectiveTo(),
                current.rowVersion() + 1,
                normalized.reason(),
                current.createdBy(),
                current.createdAt(),
                actor,
                now);
        if (!repository.updateAssignment(updated, expectedVersion)) {
            throw new OptimisticLockingFailureException(
                    "assignment version changed");
        }
        Assignment successor = repository.findAssignmentSuccessor(assignmentId)
                .orElseThrow(() -> new IllegalStateException(
                        "assignment transfer successor was not persisted"));
        List<Assignment> effective = repository.resolveAssignments(
                current.employeeId(), normalized.effectiveFrom(), now);
        if (effective.size() != 1
                || !effective.getFirst().assignmentId()
                        .equals(successor.assignmentId())
                || !effective.getFirst().groupId()
                        .equals(targetGroup.groupId())) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_ASSIGNMENT_UNIQUENESS_CONFLICT",
                    "调配生效日必须且只能归属一个目标考勤组");
        }
        audit(actor, "ATTENDANCE_GROUP_ASSIGNMENT_TRANSFERRED",
                "ATTENDANCE_GROUP_ASSIGNMENT", assignmentId,
                normalized.reason(), current, successor);
        return successor;
    }

    private void coordinateLocationRollover(
            Location current,
            Location successor,
            long expectedVersion,
            String reason,
            String actor,
            Instant now) {
        List<String> firstGroupSet = sortedBinaryIds(
                repository.findGroupIdsReferencingLocationRevision(
                        current.locationRevisionId()));
        for (String groupId : firstGroupSet) {
            repository.lockGroup(groupId);
        }
        List<String> secondGroupSet = sortedBinaryIds(
                repository.findGroupIdsReferencingLocationRevision(
                        current.locationRevisionId()));
        if (!firstGroupSet.equals(secondGroupSet)) {
            throw AttendanceSetupRules.conflict(
                    "LOCATION_GROUP_SET_CHANGED",
                    "地点换版锁内引用考勤组完整集合发生变化");
        }

        List<PreparedGroupRollover> prepared = new ArrayList<>();
        for (String groupId : firstGroupSet) {
            AttendanceGroup currentGroup = requireGroup(
                    groupId, CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP);
            if (!currentGroup.locationRevisionId()
                    .equals(current.locationRevisionId())) {
                throw AttendanceSetupRules.conflict(
                        "LOCATION_GROUP_SET_CHANGED",
                        "地点换版引用考勤组在锁内发生变化");
            }
            repository.lockGroupRevision(currentGroup.groupRevisionId());
            requireFutureRevision(
                    currentGroup.effectiveFrom(), successor.effectiveFrom());
            GroupCommand transition = new GroupCommand(
                    currentGroup.companyId(),
                    currentGroup.code(),
                    currentGroup.name(),
                    successor.locationId(),
                    currentGroup.calendarId(),
                    currentGroup.shiftTemplateId(),
                    successor.effectiveFrom(),
                    currentGroup.effectiveTo(),
                    reason);
            validateGroupReferences(
                    transition,
                    successor,
                    successor.status() == LifecycleStatus.ACTIVE);
            LifecycleStatus groupStatus =
                    successor.status() == LifecycleStatus.INACTIVE
                            ? LifecycleStatus.INACTIVE
                            : currentGroup.status();
            AttendanceGroup successorGroup = new AttendanceGroup(
                    currentGroup.groupId(),
                    currentGroup.companyId(),
                    currentGroup.code(),
                    UUID.randomUUID().toString(),
                    currentGroup.revisionNumber() + 1,
                    currentGroup.name(),
                    successor.locationId(),
                    successor.locationRevisionId(),
                    currentGroup.calendarId(),
                    currentGroup.shiftTemplateId(),
                    groupStatus,
                    successor.effectiveFrom(),
                    currentGroup.effectiveTo(),
                    groupDigest(transition, successor),
                    currentGroup.rowVersion() + 1,
                    reason,
                    currentGroup.createdBy(),
                    currentGroup.createdAt(),
                    actor,
                    now);
            GroupRolloverDependencies dependencies =
                    lockGroupRolloverDependencies(currentGroup, successorGroup);
            prepared.add(new PreparedGroupRollover(
                    currentGroup, successorGroup, dependencies));
        }

        if (!repository.updateLocation(successor, expectedVersion)) {
            throw new OptimisticLockingFailureException("location version changed");
        }
        for (PreparedGroupRollover groupRollover : prepared) {
            persistGroupRollover(
                    groupRollover.current(),
                    groupRollover.successor(),
                    groupRollover.dependencies(),
                    groupRollover.current().rowVersion(),
                    reason,
                    actor,
                    now);
            audit(
                    actor,
                    "ATTENDANCE_GROUP_LOCATION_ROLLED_OVER",
                    "ATTENDANCE_GROUP",
                    groupRollover.current().groupId(),
                    reason,
                    groupRollover.current(),
                    groupRollover.successor());
        }
    }

    private GroupRolloverDependencies lockGroupRolloverDependencies(
            AttendanceGroup current, AttendanceGroup successor) {
        List<AssignmentRolloverCandidate> assignments =
                sortedAssignmentCandidates(
                        repository.findAssignmentsCrossingBoundary(
                                current.groupRevisionId(),
                                successor.effectiveFrom()));
        for (AssignmentRolloverCandidate assignment : assignments) {
            if (!assignment.effectiveFrom().isBefore(
                    successor.effectiveFrom())) {
                throw AttendanceSetupRules.conflict(
                        "GROUP_ASSIGNMENT_BOUNDARY_CONFLICT",
                        "考勤组换版边界上的人员分配无法生成非空兼容 successor");
            }
        }
        assignments.stream()
                .map(AssignmentRolloverCandidate::employeeId)
                .distinct()
                .sorted(BINARY_ID_ORDER)
                .forEach(repository::lockEmployee);
        assignments.stream()
                .map(AssignmentRolloverCandidate::assignmentId)
                .sorted(BINARY_ID_ORDER)
                .forEach(repository::lockAssignmentTimeline);
        List<AssignmentRolloverCandidate> lockedAssignments =
                sortedAssignmentCandidates(
                        repository.findAssignmentsCrossingBoundary(
                                current.groupRevisionId(),
                                successor.effectiveFrom()));
        if (!assignments.equals(lockedAssignments)) {
            throw AttendanceSetupRules.conflict(
                    "GROUP_ASSIGNMENT_SET_CHANGED",
                    "考勤组换版锁内人员分配完整集合发生变化");
        }

        List<PolicyBinding> bindings = new ArrayList<>();
        for (PolicyKind kind : ROLLOVER_POLICY_KIND_ORDER) {
            List<PolicyBinding> heads =
                    policyRepository.findBindingFamilyHeads(
                            current.groupId(), kind);
            PolicyBinding head = exactlyOneBinding(kind, heads);
            if (!head.groupRevisionId().equals(current.groupRevisionId())) {
                throw AttendanceSetupRules.conflict(
                        "GROUP_POLICY_BINDING_REVISION_CONFLICT",
                        "默认策略绑定未精确引用当前考勤组 revision " + kind.name());
            }
            if (!head.effectiveFrom().isBefore(successor.effectiveFrom())) {
                throw AttendanceSetupRules.conflict(
                        "GROUP_POLICY_BINDING_BOUNDARY_CONFLICT",
                        "默认策略绑定换版边界无法形成非空 successor " + kind.name());
            }
            policyRepository.lockBindingFamily(head.bindingId());
            PolicyBinding lockedHead = exactlyOneBinding(
                    kind,
                    policyRepository.findBindingFamilyHeads(
                            current.groupId(), kind));
            if (!lockedHead.bindingRevisionId()
                    .equals(head.bindingRevisionId())) {
                throw AttendanceSetupRules.conflict(
                        "GROUP_POLICY_BINDING_CHANGED",
                        "默认策略绑定在获取稳定 family 锁期间发生变化");
            }
            if (!policyRepository.publishedVersionMatchesKind(
                    successor.companyId(),
                    lockedHead.policyVersionId(),
                    kind,
                    successor.effectiveFrom(),
                    successor.effectiveTo())) {
                throw AttendanceSetupRules.conflict(
                        "POLICY_MISSING",
                        "默认策略版本无法覆盖考勤组 successor " + kind.name());
            }
            bindings.add(lockedHead);
        }
        return new GroupRolloverDependencies(assignments, bindings);
    }

    private void persistGroupRollover(
            AttendanceGroup current,
            AttendanceGroup successor,
            GroupRolloverDependencies dependencies,
            long expectedVersion,
            String reason,
            String actor,
            Instant now) {
        if (!repository.updateGroup(successor, expectedVersion)) {
            throw new OptimisticLockingFailureException(
                    "attendance group version changed");
        }
        String requestId = UUID.randomUUID().toString();
        for (AssignmentRolloverCandidate assignment
                : dependencies.assignments()) {
            repository.appendAssignmentRolloverSuccessor(
                    assignment,
                    successor.groupRevisionId(),
                    successor.effectiveFrom(),
                    reason,
                    actor,
                    now,
                    requestId);
        }
        for (PolicyBinding binding : dependencies.bindings()) {
            String versionDigest =
                    policyRepository.publishedVersionDigest(
                            binding.policyVersionId());
            if (versionDigest == null || versionDigest.isBlank()) {
                throw AttendanceSetupRules.conflict(
                        "POLICY_SNAPSHOT_MISSING",
                        "默认策略版本缺少不可变快照摘要");
            }
            PolicyBinding bindingSuccessor = new PolicyBinding(
                    binding.bindingId(),
                    UUID.randomUUID().toString(),
                    binding.revisionNumber() + 1,
                    binding.companyId(),
                    binding.policyKind(),
                    binding.policyVersionId(),
                    binding.groupId(),
                    successor.groupRevisionId(),
                    successor.effectiveFrom(),
                    successor.effectiveTo(),
                    LifecycleStatus.ACTIVE,
                    bindingDigest(
                            successor.groupRevisionId(),
                            binding.policyVersionId(),
                            versionDigest,
                            successor.effectiveFrom(),
                            successor.effectiveTo()),
                    binding.rowVersion() + 1,
                    reason,
                    binding.createdBy(),
                    binding.createdAt(),
                    actor,
                    now);
            if (!policyRepository.updateBinding(
                    bindingSuccessor, binding.rowVersion())) {
                throw new OptimisticLockingFailureException(
                        "attendance policy binding changed during group rollover");
            }
        }
        verifyGroupRolloverBoundary(current, successor, now);
    }

    private void verifyGroupRolloverBoundary(
            AttendanceGroup current,
            AttendanceGroup successor,
            Instant knowledgeAsOf) {
        List<AttendanceGroup> before = repository.resolveGroupRevisions(
                current.groupId(),
                successor.effectiveFrom().minusDays(1),
                knowledgeAsOf);
        List<AttendanceGroup> after = repository.resolveGroupRevisions(
                current.groupId(),
                successor.effectiveFrom(),
                knowledgeAsOf);
        if (before.size() != 1
                || !before.getFirst().groupRevisionId()
                        .equals(current.groupRevisionId())
                || after.size() != 1
                || !after.getFirst().groupRevisionId()
                        .equals(successor.groupRevisionId())) {
            throw AttendanceSetupRules.conflict(
                    "GROUP_ROLLOVER_CARDINALITY",
                    "考勤组换版边界两侧必须各精确解析一个 revision");
        }
        List<PolicyBinding> resolvedBindings =
                policyRepository.resolveBindings(
                        successor.groupId(),
                        successor.groupRevisionId(),
                        successor.effectiveFrom(),
                        knowledgeAsOf);
        if (resolvedBindings.size() != ROLLOVER_POLICY_KIND_ORDER.size()
                || !resolvedBindings.stream()
                        .map(PolicyBinding::policyKind)
                        .toList()
                        .containsAll(ROLLOVER_POLICY_KIND_ORDER)) {
            throw AttendanceSetupRules.conflict(
                    "GROUP_POLICY_ROLLOVER_CARDINALITY",
                    "考勤组 successor 必须精确解析三类默认策略绑定");
        }
    }

    private PolicyBinding exactlyOneBinding(
            PolicyKind kind, List<PolicyBinding> candidates) {
        if (candidates.isEmpty()) {
            throw AttendanceSetupRules.conflict(
                    "POLICY_MISSING",
                    "缺少稳定默认策略绑定 family " + kind.name());
        }
        if (candidates.size() != 1) {
            throw AttendanceSetupRules.conflict(
                    "POLICY_AMBIGUOUS",
                    "稳定默认策略绑定 family 不唯一 " + kind.name());
        }
        return candidates.getFirst();
    }

    private List<DefaultPolicy> resolveDefaultPolicies(
            String companyId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        List<DefaultPolicy> result = new ArrayList<>();
        for (PolicyKind kind : ROLLOVER_POLICY_KIND_ORDER) {
            List<String> matches =
                    policyRepository.findPublishedVersionIdsByKind(
                            companyId, kind, effectiveFrom, effectiveTo);
            if (matches.isEmpty()) {
                throw AttendanceSetupRules.conflict(
                        "POLICY_MISSING",
                        "缺少受控默认策略版本 " + kind.name());
            }
            if (matches.size() != 1) {
                throw AttendanceSetupRules.conflict(
                        "POLICY_AMBIGUOUS",
                        "受控默认策略版本不唯一 " + kind.name());
            }
            String policyVersionId = matches.getFirst();
            String digest =
                    policyRepository.publishedVersionDigest(policyVersionId);
            if (digest == null || digest.isBlank()) {
                throw AttendanceSetupRules.conflict(
                        "POLICY_SNAPSHOT_MISSING",
                        "受控默认策略版本缺少不可变快照摘要");
            }
            result.add(new DefaultPolicy(kind, policyVersionId, digest));
        }
        return List.copyOf(result);
    }

    private Map<String, Location> lockLocationReferences(
            Set<String> locationIds, LocalDate effectiveFrom) {
        List<String> ordered = sortedBinaryIds(locationIds.stream().toList());
        Map<String, Location> locked = new LinkedHashMap<>();
        for (String locationId : ordered) {
            repository.lockLocation(locationId);
            Location resolved = exactlyOneLocationRevision(
                    locationId, effectiveFrom);
            repository.lockLocationRevision(resolved.locationRevisionId());
            Location verified = exactlyOneLocationRevision(
                    locationId, effectiveFrom);
            if (!verified.locationRevisionId()
                    .equals(resolved.locationRevisionId())) {
                throw AttendanceSetupRules.conflict(
                        "LOCATION_REVISION_CHANGED",
                        "地点 revision 在获取锁期间发生变化");
            }
            locked.put(locationId, verified);
        }
        return Map.copyOf(locked);
    }

    private Location exactlyOneLocationRevision(
            String locationId, LocalDate effectiveFrom) {
        List<Location> locations = repository.resolveLocationRevisions(
                locationId, effectiveFrom, clock.instant());
        if (locations.size() != 1) {
            throw AttendanceSetupRules.conflict(
                    locations.isEmpty()
                            ? "LOCATION_REVISION_MISSING"
                            : "LOCATION_REVISION_AMBIGUOUS",
                    "考勤组生效日必须恰好解析一个地点版本");
        }
        return locations.getFirst();
    }

    private Set<String> locationIds(String first, String second) {
        java.util.LinkedHashSet<String> result =
                new java.util.LinkedHashSet<>();
        result.add(first);
        result.add(second);
        return Set.copyOf(result);
    }

    private List<String> sortedBinaryIds(List<String> values) {
        return values.stream().sorted(BINARY_ID_ORDER).toList();
    }

    private List<AssignmentRolloverCandidate> sortedAssignmentCandidates(
            List<AssignmentRolloverCandidate> values) {
        return values.stream()
                .sorted(Comparator
                        .comparing(
                                AssignmentRolloverCandidate::employeeId,
                                BINARY_ID_ORDER)
                        .thenComparing(
                                AssignmentRolloverCandidate::assignmentId,
                                BINARY_ID_ORDER))
                .toList();
    }

    private Location requireLocation(String locationId, String capability) {
        capabilityService.require(capability);
        Location location = repository.findLocation(locationId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireCompany(capability, location.companyId());
        return location;
    }

    private void requireSharedLocationManagement(String locationId) {
        List<String> companyIds =
                repository.listSharedLocationCompanyIds(locationId);
        if (companyIds.isEmpty()) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        for (String companyId : companyIds) {
            requireCompany(
                    CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP,
                    companyId);
        }
    }

    private void requireSharedLocationRead(String locationId) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_READ);
        List<String> companyIds =
                repository.listSharedLocationCompanyIds(locationId);
        String actor = principalProvider.currentPrincipalId();
        Instant at = clock.instant();
        if (companyIds.isEmpty() || companyIds.stream().noneMatch(companyId ->
                peopleRepository.canAccessCompany(
                        actor,
                        CapabilityCodes.ATTENDANCE_SETUP_READ,
                        companyId,
                        at))) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
    }

    private void rejectReferencedTimeZoneChange(
            Location current,
            LocationCommand command,
            List<Location> bindings) {
        if (current.timeZone().equals(command.timeZone())) {
            return;
        }
        boolean referenced = bindings.stream().anyMatch(binding ->
                repository.hasLocationTimeZoneDependencies(
                        binding.locationId()));
        if (referenced) {
            throw AttendanceSetupRules.conflict(
                    "SHARED_LOCATION_TIME_ZONE_IN_USE",
                    "该地点已有考勤配置或历史引用，不能直接修改时区；请联系系统管理员评估受控迁移");
        }
    }

    private List<Location> lockSharedLocationBindings(String locationId) {
        List<Location> first = repository.listSharedLocationBindings(locationId)
                .stream()
                .sorted(Comparator.comparing(
                        Location::locationId, BINARY_ID_ORDER))
                .toList();
        if (first.isEmpty()) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        for (Location binding : first) {
            repository.lockLocation(binding.locationId());
            repository.lockLocationRevision(binding.locationRevisionId());
        }
        List<Location> locked = repository.listSharedLocationBindings(locationId)
                .stream()
                .sorted(Comparator.comparing(
                        Location::locationId, BINARY_ID_ORDER))
                .toList();
        List<String> firstHeads = first.stream()
                .map(value -> value.locationId() + ":"
                        + value.locationRevisionId())
                .toList();
        List<String> lockedHeads = locked.stream()
                .map(value -> value.locationId() + ":"
                        + value.locationRevisionId())
                .toList();
        if (!firstHeads.equals(lockedHeads)) {
            throw AttendanceSetupRules.conflict(
                    "SHARED_LOCATION_BINDING_SET_CHANGED",
                    "共享地点在获取稳定锁期间发生变化");
        }
        for (Location binding : locked) {
            requireCompany(
                    CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP,
                    binding.companyId());
        }
        return locked;
    }

    private Map<String, Location> sharedLocationSuccessors(
            List<Location> bindings,
            LocationCommand command,
            LifecycleStatus status,
            String actor,
            Instant now) {
        Map<String, Location> successors = new LinkedHashMap<>();
        for (Location current : bindings) {
            LocationCommand companyCommand = new LocationCommand(
                    current.companyId(),
                    command.code(),
                    command.name(),
                    command.timeZone(),
                    command.effectiveFrom(),
                    command.effectiveTo(),
                    command.reason());
            Location successor = new Location(
                    current.locationId(),
                    current.sharedLocationId(),
                    current.companyId(),
                    current.code(),
                    UUID.randomUUID().toString(),
                    current.revisionNumber() + 1,
                    command.name(),
                    command.timeZone(),
                    status == null ? current.status() : status,
                    command.effectiveFrom(),
                    command.effectiveTo(),
                    locationDigest(companyCommand),
                    current.rowVersion() + 1,
                    command.reason(),
                    current.createdBy(),
                    current.createdAt(),
                    actor,
                    now);
            successors.put(current.locationId(), successor);
        }
        return successors;
    }

    private AttendanceGroup requireGroup(String groupId, String capability) {
        capabilityService.require(capability);
        AttendanceGroup group = repository.findGroup(groupId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        requireCompany(capability, group.companyId());
        return group;
    }

    private void requireCompany(String capability, String companyId) {
        String actor = principalProvider.currentPrincipalId();
        if (!peopleRepository.canAccessCompany(
                actor, capability, companyId, clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
    }

    private void requireEmployeeInCompany(
            String employeeId, LocalDate asOf, String companyId) {
        String actor = principalProvider.currentPrincipalId();
        if (!peopleRepository.canAccessEmployee(
                actor, CapabilityCodes.ATTENDANCE_SETUP_ASSIGN,
                employeeId, asOf, clock.instant())
                || peopleRepository.findEmployeeAsOf(employeeId, asOf)
                        .filter(employee -> employee.companyId()
                                .equals(companyId))
                        .isEmpty()) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
    }

    private void requireAssignmentAccess(
            String groupId, AssignmentCommand command) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_ASSIGN);
        AttendanceGroup group = requireGroup(
                groupId, CapabilityCodes.ATTENDANCE_SETUP_ASSIGN);
        requireEmployeeInCompany(
                command.employeeId(), command.effectiveFrom(),
                group.companyId());
    }

    private AttendanceGroup requireEffectiveActiveGroup(
            String groupId, LocalDate asOf, String capability) {
        capabilityService.require(capability);
        List<AttendanceGroup> revisions =
                repository.resolveGroupRevisions(groupId, asOf, clock.instant());
        if (revisions.size() != 1) {
            throw AttendanceSetupRules.conflict(
                    revisions.isEmpty()
                            ? "GROUP_REVISION_MISSING"
                            : "GROUP_REVISION_AMBIGUOUS",
                    "人员分配生效日必须恰好解析一个考勤组 revision");
        }
        AttendanceGroup group = revisions.getFirst();
        requireCompany(capability, group.companyId());
        if (group.status() != LifecycleStatus.ACTIVE) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_GROUP_INACTIVE",
                    "人员不能分配到未启用的考勤组");
        }
        return group;
    }

    private GroupReferences validateGroupReferences(
            GroupCommand command, Location location) {
        return validateGroupReferences(command, location, true);
    }

    private GroupReferences validateGroupReferences(
            GroupCommand command,
            Location location,
            boolean requireActiveLocation) {
        if (!location.locationId().equals(command.locationId())
                || command.effectiveFrom().isBefore(location.effectiveFrom())
                || location.effectiveTo() != null
                && (command.effectiveTo() == null
                    || command.effectiveTo().isAfter(location.effectiveTo()))) {
            throw AttendanceSetupRules.conflict(
                    "GROUP_LOCATION_PERIOD_CONFLICT",
                    "考勤组期间必须完整位于锁定地点 revision 期间内");
        }
        var calendars = calendarRepository.resolvePublishedVersions(
                command.calendarId(), command.effectiveFrom(), clock.instant());
        if (calendars.size() != 1) {
            throw AttendanceSetupRules.conflict(
                    calendars.isEmpty()
                            ? "CALENDAR_VERSION_MISSING"
                            : "CALENDAR_VERSION_AMBIGUOUS",
                    "考勤组生效日必须恰好解析一个已发布日历版本");
        }
        var calendar = calendars.getFirst();
        var template = shiftRepository.findTemplate(command.shiftTemplateId())
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (!location.companyId().equals(command.companyId())
                || !repository.isLocationAvailable(
                        location.locationId(), command.companyId(),
                        command.effectiveFrom())
                || !calendar.companyId().equals(command.companyId())
                || !template.companyId().equals(command.companyId())
                || !template.locationId().equals(location.locationId())
                || !calendar.locationId().equals(location.locationId())
                || !calendar.timeZone().equals(location.timeZone())
                || requireActiveLocation
                    && location.status() != LifecycleStatus.ACTIVE
                || calendar.status() != CalendarStatus.PUBLISHED
                || template.status() != LifecycleStatus.ACTIVE) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return new GroupReferences(location);
    }

    private void validateAssignmentWithinGroup(
            AttendanceGroup group, AssignmentCommand command) {
        if (command.effectiveFrom().isBefore(group.effectiveFrom())
                || group.effectiveTo() != null
                && (command.effectiveTo() == null
                || command.effectiveTo().isAfter(group.effectiveTo()))) {
            throw AttendanceSetupRules.invalid(
                    "人员分配期间必须位于考勤组生效期间内");
        }
    }

    private Location requireAssignmentLocation(
            AttendanceGroup group,
            LocalDate businessDate,
            Instant knowledgeAsOf) {
        List<Location> locations = repository.resolveLocationRevisions(
                        group.locationId(), businessDate, knowledgeAsOf)
                .stream()
                .filter(value -> value.locationRevisionId()
                        .equals(group.locationRevisionId()))
                .toList();
        if (locations.size() != 1) {
            throw AttendanceSetupRules.conflict(
                    "ASSIGNMENT_CONFIGURATION_MISSING",
                    "人员分配期间必须解析唯一地点 revision");
        }
        Location location = locations.getFirst();
        if (location.status() != LifecycleStatus.ACTIVE
                || !location.companyId().equals(group.companyId())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return location;
    }

    private WorkCalendar requireAssignmentCalendar(
            AttendanceGroup group,
            Location location,
            LocalDate businessDate,
            Instant knowledgeAsOf) {
        List<WorkCalendar> calendars =
                calendarRepository.resolvePublishedVersions(
                        group.calendarId(), businessDate, knowledgeAsOf);
        if (calendars.size() != 1) {
            throw AttendanceSetupRules.conflict(
                    calendars.isEmpty()
                            ? "CALENDAR_VERSION_MISSING"
                            : "CALENDAR_VERSION_AMBIGUOUS",
                    "人员分配期间每个业务日必须解析唯一已发布日历版本");
        }
        WorkCalendar calendar = calendars.getFirst();
        if (calendar.effectiveTo() == null) {
            throw AttendanceSetupRules.conflict(
                    "ASSIGNMENT_CONFIGURATION_MISSING",
                    "人员分配引用的已发布日历版本期间无效");
        }
        long versionDays = calendar.effectiveTo().toEpochDay()
                - calendar.effectiveFrom().toEpochDay();
        if (!calendar.companyId().equals(group.companyId())
                || !calendar.locationId().equals(location.locationId())
                || !calendar.timeZone().equals(location.timeZone())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        if (calendar.status() != CalendarStatus.PUBLISHED
                || versionDays < 1
                || versionDays > MAX_CALENDAR_VERSION_DAYS) {
            throw AttendanceSetupRules.conflict(
                    "ASSIGNMENT_CONFIGURATION_MISSING",
                    "人员分配引用的已发布日历版本期间无效");
        }
        return calendar;
    }

    private void validateAssignmentCalendarSegment(
            AttendanceGroup group,
            Location location,
            WorkCalendar calendar,
            LocalDate segmentFrom,
            LocalDate segmentTo,
            Instant knowledgeAsOf,
            AssignmentConfigurationValidation validation) {
        long expectedDays =
                segmentTo.toEpochDay() - segmentFrom.toEpochDay();
        long storedDays = calendarRepository.countDays(
                calendar.calendarVersionId(),
                segmentFrom,
                segmentTo.minusDays(1));
        if (storedDays != expectedDays) {
            throw AttendanceSetupRules.conflict(
                    "ASSIGNMENT_CALENDAR_DAY_MISSING",
                    "人员分配期间每个业务日必须存在不可变日历日");
        }
        long processedDays = 0;
        int offset = 0;
        LocalDate expectedDate = segmentFrom;
        while (processedDays < expectedDays) {
            int limit = (int) Math.min(
                    CALENDAR_DAY_VALIDATION_PAGE_SIZE,
                    expectedDays - processedDays);
            List<WorkCalendarDay> days = calendarRepository.listDays(
                    calendar.calendarVersionId(),
                    segmentFrom,
                    segmentTo.minusDays(1),
                    limit,
                    offset);
            if (days.isEmpty()) {
                throw AttendanceSetupRules.conflict(
                        "ASSIGNMENT_CALENDAR_DAY_MISSING",
                        "人员分配期间每个业务日必须存在不可变日历日");
            }
            for (WorkCalendarDay day : days) {
                if (!day.calendarVersionId()
                                .equals(calendar.calendarVersionId())
                        || !day.businessDate().equals(expectedDate)) {
                    throw AttendanceSetupRules.conflict(
                            "ASSIGNMENT_CALENDAR_DAY_MISSING",
                            "人员分配期间的不可变日历日必须连续且唯一");
                }
                validateAssignmentShift(
                        group,
                        location,
                        day,
                        knowledgeAsOf,
                        validation);
                expectedDate = expectedDate.plusDays(1);
            }
            processedDays += days.size();
            offset += days.size();
        }
        if (!expectedDate.equals(segmentTo)) {
            throw AttendanceSetupRules.conflict(
                    "ASSIGNMENT_CALENDAR_DAY_MISSING",
                    "人员分配期间的不可变日历日必须完整覆盖半开区间");
        }
    }

    private void validateAssignmentShift(
            AttendanceGroup group,
            Location location,
            WorkCalendarDay day,
            Instant knowledgeAsOf,
            AssignmentConfigurationValidation validation) {
        if (!day.workingDay()) {
            return;
        }
        ShiftVersion shift;
        if (day.shiftVersionOverrideId() == null) {
            shift = validation.defaultShift;
            if (shift == null || !publishedAt(shift, day.businessDate())) {
                List<ShiftVersion> shifts = shiftRepository.resolvePublishedAt(
                        group.shiftTemplateId(),
                        day.businessDate(),
                        knowledgeAsOf);
                if (shifts.size() != 1) {
                    throw AttendanceSetupRules.conflict(
                            shifts.isEmpty()
                                    ? "ASSIGNMENT_SHIFT_VERSION_MISSING"
                                    : "ASSIGNMENT_SHIFT_VERSION_AMBIGUOUS",
                            "人员分配期间每个工作日必须解析唯一已发布班次版本");
                }
                shift = shifts.getFirst();
                validation.defaultShift = shift;
            }
        } else {
            shift = validation.overrideShifts.get(
                    day.shiftVersionOverrideId());
            if (shift == null) {
                shift = shiftRepository.findVersion(
                                day.shiftVersionOverrideId(), knowledgeAsOf)
                        .orElseThrow(() -> AttendanceSetupRules.conflict(
                                "ASSIGNMENT_SHIFT_VERSION_MISSING",
                                "人员分配期间的显式班次覆盖不可解析"));
                validation.overrideShifts.put(
                        day.shiftVersionOverrideId(), shift);
            }
            if (!publishedAt(shift, day.businessDate())) {
                throw AttendanceSetupRules.conflict(
                        "ASSIGNMENT_SHIFT_VERSION_MISSING",
                        "人员分配期间的显式班次覆盖不可解析");
            }
        }
        ShiftTemplate template = validation.templates.get(shift.shiftId());
        if (template == null) {
            template = shiftRepository.findTemplate(shift.shiftId())
                    .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
            validation.templates.put(shift.shiftId(), template);
        }
        if (!template.companyId().equals(group.companyId())
                || !template.locationId().equals(location.locationId())
                || !shift.timeZone().equals(location.timeZone())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
    }

    private void validateAssignmentConfigurationInterval(
            AttendanceGroup group, AssignmentCommand command) {
        /*
         * An open assignment is an identity/timeline fact, not a promise that
         * all future calendars have already been authored. Its write validates
         * the first business date; later resolution remains fail-closed for a
         * missing future group/calendar/day/shift and future rollovers rebind
         * the assignment explicitly. Finite assignments, in contrast, validate
         * every persisted day across every known publication boundary below.
         */
        LocalDate intervalTo = command.effectiveTo() == null
                ? command.effectiveFrom().plusDays(1)
                : command.effectiveTo();
        Instant knowledgeAsOf = clock.instant();
        AssignmentConfigurationValidation validation =
                new AssignmentConfigurationValidation();
        LocalDate segmentFrom = command.effectiveFrom();
        while (segmentFrom.isBefore(intervalTo)) {
            Location location = requireAssignmentLocation(
                    group, segmentFrom, knowledgeAsOf);
            WorkCalendar calendar = requireAssignmentCalendar(
                    group, location, segmentFrom, knowledgeAsOf);
            LocalDate segmentTo = earlier(
                    intervalTo, calendar.effectiveTo());
            LocalDate nextPublicationBoundary =
                    calendarRepository.findNextPublicationBoundary(
                                    calendar.calendarId(),
                                    segmentFrom,
                                    knowledgeAsOf)
                            .orElse(null);
            if (nextPublicationBoundary != null) {
                segmentTo = earlier(segmentTo, nextPublicationBoundary);
            }
            if (!segmentTo.isAfter(segmentFrom)) {
                throw AttendanceSetupRules.conflict(
                        "ASSIGNMENT_CONFIGURATION_MISSING",
                        "人员分配期间的日历发布边界无法连续推进");
            }
            validateAssignmentCalendarSegment(
                    group,
                    location,
                    calendar,
                    segmentFrom,
                    segmentTo,
                    knowledgeAsOf,
                    validation);
            segmentFrom = segmentTo;
        }
    }

    private boolean publishedAt(
            ShiftVersion shift, LocalDate businessDate) {
        return shift.status() == VersionStatus.PUBLISHED
                && !businessDate.isBefore(shift.effectiveFrom())
                && (shift.effectiveTo() == null
                    || businessDate.isBefore(shift.effectiveTo()));
    }

    private LocalDate earlier(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private void validateGroupPeriodDependencies(
            String groupId, LocalDate nextEffectiveTo) {
        for (Assignment assignment : allAssignments(groupId, null)) {
            if (assignment.effectiveTo() == null
                    || assignment.effectiveTo().isAfter(nextEffectiveTo)) {
                throw AttendanceSetupRules.conflict(
                        "GROUP_PERIOD_ASSIGNMENT_CONFLICT",
                        "考勤组期间收缩前必须先调整受影响人员分配");
            }
        }
        for (PolicyBinding binding : allPolicyBindings(groupId)) {
            if (binding.status() == LifecycleStatus.ACTIVE
                    && (binding.effectiveTo() == null
                        || binding.effectiveTo().isAfter(nextEffectiveTo))) {
                throw AttendanceSetupRules.conflict(
                        "GROUP_PERIOD_POLICY_BINDING_CONFLICT",
                        "考勤组期间收缩前必须先调整三类默认策略绑定");
            }
        }
    }

    private List<Assignment> allAssignments(String groupId, LocalDate asOf) {
        java.util.ArrayList<Assignment> assignments = new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            List<Assignment> page =
                    repository.listAssignments(groupId, asOf, 100, offset);
            assignments.addAll(page);
            if (page.size() < 100) {
                return List.copyOf(assignments);
            }
            offset += page.size();
        }
    }

    private List<PolicyBinding> allPolicyBindings(String groupId) {
        java.util.ArrayList<PolicyBinding> bindings = new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            List<PolicyBinding> page = policyRepository.listBindings(
                    principalProvider.currentPrincipalId(),
                    CapabilityCodes.ATTENDANCE_SETUP_MANAGE_GROUP,
                    groupId,
                    null,
                    100,
                    offset,
                    clock.instant());
            bindings.addAll(page);
            if (page.size() < 100) {
                return List.copyOf(bindings);
            }
            offset += page.size();
        }
    }

    private boolean periodShrinks(LocalDate currentTo, LocalDate nextTo) {
        return nextTo != null
                && (currentTo == null || nextTo.isBefore(currentTo));
    }

    private LocalDate futureStatusBoundary(LocalDate currentEffectiveFrom) {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        return currentEffectiveFrom.isAfter(LocalDate.now(clock))
                ? currentEffectiveFrom.plusDays(1)
                : tomorrow;
    }

    private boolean includes(Location location, LocalDate date) {
        return !date.isBefore(location.effectiveFrom())
                && (location.effectiveTo() == null
                    || date.isBefore(location.effectiveTo()));
    }

    private LocationCommand normalize(LocationCommand command) {
        AttendanceSetupRules.halfOpenPeriod(
                command.effectiveFrom(), command.effectiveTo());
        return new LocationCommand(
                Objects.requireNonNull(command.companyId()),
                AttendanceSetupRules.code(command.code()),
                AttendanceSetupRules.name(command.name()),
                AttendanceSetupRules.timeZone(command.timeZone()),
                Objects.requireNonNull(command.effectiveFrom()),
                command.effectiveTo(),
                AttendanceSetupRules.reason(command.reason()));
    }

    private GroupCommand normalize(GroupCommand command) {
        AttendanceSetupRules.halfOpenPeriod(
                command.effectiveFrom(), command.effectiveTo());
        return new GroupCommand(
                Objects.requireNonNull(command.companyId()),
                AttendanceSetupRules.code(command.code()),
                AttendanceSetupRules.name(command.name()),
                Objects.requireNonNull(command.locationId()),
                Objects.requireNonNull(command.calendarId()),
                Objects.requireNonNull(command.shiftTemplateId()),
                command.effectiveFrom(),
                command.effectiveTo(),
                AttendanceSetupRules.reason(command.reason()));
    }

    private AssignmentCommand normalize(AssignmentCommand command) {
        AttendanceSetupRules.halfOpenPeriod(
                command.effectiveFrom(), command.effectiveTo());
        return new AssignmentCommand(
                Objects.requireNonNull(command.employeeId()),
                command.effectiveFrom(),
                command.effectiveTo(),
                AttendanceSetupRules.reason(command.reason()));
    }

    private AssignmentTransferCommand normalize(
            AssignmentTransferCommand command) {
        if (command == null
                || command.sourceGroupId() == null
                || command.sourceGroupId().isBlank()
                || command.targetGroupId() == null
                || command.targetGroupId().isBlank()) {
            throw AttendanceSetupRules.invalid("目标考勤组必填");
        }
        if (command.effectiveFrom() == null) {
            throw AttendanceSetupRules.invalid("调配生效日必填");
        }
        return new AssignmentTransferCommand(
                command.sourceGroupId().trim(),
                command.targetGroupId().trim(),
                command.effectiveFrom(),
                AttendanceSetupRules.reason(command.reason()));
    }

    private boolean hasTransferBoundary(Assignment assignment) {
        LocalDate today = LocalDate.now(clock);
        LocalDate earliestBoundary = today.isAfter(assignment.effectiveFrom())
                ? today
                : assignment.effectiveFrom().plusDays(1);
        return assignment.effectiveTo() == null
                || earliestBoundary.isBefore(assignment.effectiveTo());
    }

    private boolean same(Location location, LocationCommand command) {
        return location.companyId().equals(command.companyId())
                && location.code().equals(command.code())
                && location.name().equals(command.name())
                && location.timeZone().equals(command.timeZone())
                && location.effectiveFrom().equals(command.effectiveFrom())
                && Objects.equals(location.effectiveTo(), command.effectiveTo());
    }

    private boolean same(AttendanceGroup group, GroupCommand command) {
        return group.companyId().equals(command.companyId())
                && group.code().equals(command.code())
                && group.name().equals(command.name())
                && group.locationId().equals(command.locationId())
                && group.calendarId().equals(command.calendarId())
                && group.shiftTemplateId().equals(command.shiftTemplateId())
                && group.effectiveFrom().equals(command.effectiveFrom())
                && Objects.equals(group.effectiveTo(), command.effectiveTo());
    }

    private void requireVersion(long current, long expected) {
        if (current != expected) {
            throw new OptimisticLockingFailureException("row version changed");
        }
    }

    private void requireFutureRevision(LocalDate currentFrom, LocalDate nextFrom) {
        if (!nextFrom.isAfter(currentFrom)
                || !nextFrom.isAfter(LocalDate.now(clock))) {
            throw AttendanceSetupRules.conflict(
                    "HISTORY_IMMUTABLE",
                    "已生效配置只能从未来日期创建相邻新版本");
        }
    }

    private String locationDigest(LocationCommand command) {
        return tokenService.digest(String.join(
                "|",
                command.companyId(),
                command.code(),
                command.name(),
                command.timeZone(),
                command.effectiveFrom().toString(),
                command.effectiveTo() == null ? "NULL" : command.effectiveTo().toString()));
    }

    private String groupDigest(
            GroupCommand command, Location location) {
        return tokenService.digest(String.join(
                "|",
                command.companyId(),
                command.code(),
                command.name(),
                location.locationRevisionId(),
                location.timeZone(),
                command.calendarId(),
                command.shiftTemplateId(),
                command.effectiveFrom().toString(),
                command.effectiveTo() == null ? "NULL" : command.effectiveTo().toString()));
    }

    private String bindingDigest(
            String groupRevisionId,
            String policyVersionId,
            String policyVersionDigest,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        return tokenService.digest(String.join(
                "|",
                groupRevisionId,
                policyVersionId,
                policyVersionDigest,
                effectiveFrom.toString(),
                effectiveTo == null ? "NULL" : effectiveTo.toString()));
    }

    private static int compareBinaryIds(String first, String second) {
        return Arrays.compareUnsigned(binaryId(first), binaryId(second));
    }

    private static byte[] binaryId(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            return ByteBuffer.allocate(16)
                    .putLong(uuid.getMostSignificantBits())
                    .putLong(uuid.getLeastSignificantBits())
                    .array();
        } catch (IllegalArgumentException exception) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class AssignmentConfigurationValidation {

        private ShiftVersion defaultShift;
        private final Map<String, ShiftVersion> overrideShifts =
                new HashMap<>();
        private final Map<String, ShiftTemplate> templates =
                new HashMap<>();
    }

    private record GroupReferences(Location location) {
    }

    private record DefaultPolicy(
            PolicyKind kind,
            String policyVersionId,
            String snapshotDigest) {
    }

    private record GroupRolloverDependencies(
            List<AssignmentRolloverCandidate> assignments,
            List<PolicyBinding> bindings) {

        private GroupRolloverDependencies {
            assignments = List.copyOf(assignments);
            bindings = List.copyOf(bindings);
        }
    }

    private record PreparedGroupRollover(
            AttendanceGroup current,
            AttendanceGroup successor,
            GroupRolloverDependencies dependencies) {
    }

    private record AssignmentCreationRequest(
            String groupId,
            AssignmentCommand command) {
    }

    private void audit(
            String actor,
            String action,
            String resourceType,
            String resourceId,
            String reason,
            Object before,
            Object after) {
        auditService.record(
                actor, action, resourceType, resourceId, "SUCCESS", reason,
                before == null ? null : tokenService.digest(before.toString()),
                after == null ? null : tokenService.digest(after.toString()));
    }
}
