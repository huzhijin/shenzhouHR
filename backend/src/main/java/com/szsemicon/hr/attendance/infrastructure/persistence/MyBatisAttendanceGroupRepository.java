package com.szsemicon.hr.attendance.infrastructure.persistence;

import com.szsemicon.hr.attendance.application.AttendanceGroupRepository;
import com.szsemicon.hr.attendance.application.AttendanceGroupRepository.AssignmentRolloverCandidate;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Assignment;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.AttendanceGroup;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Location;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisAttendanceGroupRepository implements AttendanceGroupRepository {

    private final AttendanceGroupMapper mapper;

    MyBatisAttendanceGroupRepository(AttendanceGroupMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Location> findLocation(String locationId) {
        return Optional.ofNullable(mapper.findLocation(locationId)).map(this::location);
    }

    @Override
    public Optional<Location> findSharedLocation(String locationId) {
        return Optional.ofNullable(mapper.findSharedLocation(locationId))
                .map(this::location);
    }

    @Override
    public Optional<Location> findLocationByGlobalCode(String locationCode) {
        return Optional.ofNullable(mapper.findLocationByGlobalCode(locationCode))
                .map(this::location);
    }

    @Override
    public List<Location> listSharedLocationBindings(String locationId) {
        return mapper.listSharedLocationBindings(locationId).stream()
                .map(this::location)
                .toList();
    }

    @Override
    public List<String> listSharedLocationCompanyIds(String locationId) {
        return mapper.listSharedLocationCompanyIds(locationId);
    }

    @Override
    public long lockSharedLocation(String locationId) {
        Long version = mapper.lockSharedLocation(locationId);
        if (version == null) {
            throw new OptimisticLockingFailureException(
                    "shared location no longer exists");
        }
        return version;
    }

    @Override
    public boolean updateSharedLocation(Location location, long expectedVersion) {
        String predecessorRevisionId =
                mapper.latestSharedLocationRevisionId(location.locationId());
        Location predecessor = findSharedLocation(location.locationId())
                .orElse(null);
        if (predecessorRevisionId == null || predecessor == null
                || predecessor.rowVersion() != expectedVersion
                || mapper.closeSharedLocationRevision(
                        predecessorRevisionId,
                        location.effectiveFrom(),
                        sharedLocationDigest(
                                predecessor,
                                location.effectiveFrom())) != 1
                || mapper.advanceSharedLocationVersion(
                        location.locationId(), expectedVersion) != 1) {
            return false;
        }
        mapper.insertSharedLocationRevision(
                row(location), UUID.randomUUID().toString(),
                predecessor.revisionNumber() + 1, predecessorRevisionId,
                sharedLocationDigest(location));
        return true;
    }

    @Override
    public boolean isLocationAvailable(
            String locationId, String companyId, LocalDate businessDate) {
        return mapper.isLocationAvailable(locationId, companyId, businessDate);
    }

    @Override
    public List<Location> resolveLocationRevisions(
            String locationId, LocalDate asOf, Instant knowledgeAsOf) {
        return mapper.resolveLocationRevisions(locationId, asOf, knowledgeAsOf)
                .stream().map(this::location).toList();
    }

    @Override
    public Optional<Location> findLocationByIdempotency(String actorId, String idempotencyKey) {
        return Optional.ofNullable(mapper.findLocationByIdempotency(actorId, idempotencyKey))
                .map(this::location);
    }

    @Override
    public List<Location> listLocations(
            String principalId,
            String capability,
            String companyId,
            int limit,
            int offset,
            Instant at) {
        return mapper.listLocations(
                        principalId, capability, companyId, limit, offset, at)
                .stream().map(this::location).toList();
    }

    @Override
    public long countLocations(
            String principalId, String capability, String companyId, Instant at) {
        return mapper.countLocations(principalId, capability, companyId, at);
    }

    @Override
    public List<Location> listLocationRevisions(
            String locationId, int limit, int offset) {
        return mapper.listLocationRevisions(locationId, limit, offset)
                .stream().map(this::location).toList();
    }

    @Override
    public long countLocationRevisions(String locationId) {
        return mapper.countLocationRevisions(locationId);
    }

    @Override
    public void insertLocation(Location location, String idempotencyKey) {
        AttendanceGroupRows.LocationRow row = row(location);
        mapper.insertSharedLocationIdentity(row);
        mapper.insertSharedLocationRevision(
                row, row.locationRevisionId(), 1, null,
                sharedLocationDigest(location));
        mapper.insertLocationIdentity(row, idempotencyKey);
        mapper.insertLocationRevision(row, null);
        mapper.insertCompanyLocationAvailability(
                row, UUID.randomUUID().toString());
        appendLocationTimeline(
                row, row.status(), row.effectiveFrom(), idempotencyKey);
        appendLocationEndTimeline(row, idempotencyKey);
    }

    @Override
    public boolean updateLocation(Location location, long expectedVersion) {
        Location predecessor = findLocation(location.locationId()).orElse(null);
        if (predecessor == null
                || predecessor.rowVersion() != expectedVersion
                || location.revisionNumber() != predecessor.revisionNumber() + 1) {
            return false;
        }
        AttendanceGroupRows.LocationRow row = row(location);
        if (mapper.advanceLocationVersion(
                location.locationId(), expectedVersion) != 1) {
            return false;
        }
        mapper.insertLocationRevision(row, predecessor.locationRevisionId());
        String requestId = UUID.randomUUID().toString();
        appendLocationTimeline(
                row, row.status(), row.effectiveFrom(), requestId);
        appendLocationEndTimeline(row, requestId);
        return true;
    }

    @Override
    public void lockLocation(String locationId) {
        if (mapper.lockLocation(locationId) == null) {
            throw new OptimisticLockingFailureException("location no longer exists");
        }
    }

    @Override
    public void lockLocationRevision(String locationRevisionId) {
        if (mapper.lockLocationRevision(locationRevisionId) == null) {
            throw new OptimisticLockingFailureException(
                    "location revision no longer exists");
        }
    }

    @Override
    public List<String> findGroupIdsReferencingLocationRevision(
            String locationRevisionId) {
        return mapper.findGroupIdsReferencingLocationRevision(locationRevisionId);
    }

    @Override
    public boolean hasLocationTimeZoneDependencies(String locationId) {
        return mapper.hasLocationTimeZoneDependencies(locationId);
    }

    @Override
    public Optional<AttendanceGroup> findGroup(String groupId) {
        return Optional.ofNullable(mapper.findGroup(groupId)).map(this::group);
    }

    @Override
    public List<AttendanceGroup> resolveGroupRevisions(
            String groupId, LocalDate asOf, Instant knowledgeAsOf) {
        return mapper.resolveGroupRevisions(groupId, asOf, knowledgeAsOf)
                .stream().map(this::group).toList();
    }

    @Override
    public Optional<AttendanceGroup> findGroupByIdempotency(
            String actorId, String idempotencyKey) {
        return Optional.ofNullable(mapper.findGroupByIdempotency(actorId, idempotencyKey))
                .map(this::group);
    }

    @Override
    public List<AttendanceGroup> listGroups(
            String principalId,
            String capability,
            String companyId,
            LocalDate asOf,
            int limit,
            int offset,
            Instant at) {
        return mapper.listGroups(
                        principalId, capability, companyId, asOf, limit, offset, at)
                .stream().map(this::group).toList();
    }

    @Override
    public long countGroups(
            String principalId,
            String capability,
            String companyId,
            LocalDate asOf,
            Instant at) {
        return mapper.countGroups(
                principalId, capability, companyId, asOf, at);
    }

    @Override
    public List<AttendanceGroup> listGroupRevisions(
            String groupId, int limit, int offset) {
        return mapper.listGroupRevisions(groupId, limit, offset)
                .stream().map(this::group).toList();
    }

    @Override
    public long countGroupRevisions(String groupId) {
        return mapper.countGroupRevisions(groupId);
    }

    @Override
    public void insertGroup(AttendanceGroup group, String idempotencyKey) {
        AttendanceGroupRows.GroupRow row = row(group);
        mapper.insertGroupIdentity(row, idempotencyKey);
        mapper.insertGroupRevision(row, null);
        appendGroupTimeline(
                row, row.status(), row.effectiveFrom(), idempotencyKey);
        appendGroupEndTimeline(row, idempotencyKey);
    }

    @Override
    public boolean updateGroup(AttendanceGroup group, long expectedVersion) {
        AttendanceGroup predecessor = findGroup(group.groupId()).orElse(null);
        if (predecessor == null
                || predecessor.rowVersion() != expectedVersion
                || group.revisionNumber() != predecessor.revisionNumber() + 1) {
            return false;
        }
        AttendanceGroupRows.GroupRow row = row(group);
        mapper.insertGroupRevision(row, predecessor.groupRevisionId());
        String requestId = UUID.randomUUID().toString();
        appendGroupTimeline(
                row, row.status(), row.effectiveFrom(), requestId);
        appendGroupEndTimeline(row, requestId);
        return true;
    }

    @Override
    public void lockGroup(String groupId) {
        if (mapper.lockGroup(groupId) == null) {
            throw new OptimisticLockingFailureException(
                    "attendance group no longer exists");
        }
    }

    @Override
    public void lockGroupRevision(String groupRevisionId) {
        if (mapper.lockGroupRevision(groupRevisionId) == null) {
            throw new OptimisticLockingFailureException(
                    "attendance group revision no longer exists");
        }
    }

    @Override
    public Optional<Assignment> findAssignment(String assignmentId) {
        return Optional.ofNullable(mapper.findAssignment(assignmentId)).map(this::assignment);
    }

    @Override
    public Optional<Assignment> findAssignmentSuccessor(String predecessorAssignmentId) {
        return Optional.ofNullable(mapper.findAssignmentSuccessor(predecessorAssignmentId))
                .map(this::assignment);
    }

    @Override
    public Optional<Assignment> findAssignmentByIdempotency(
            String actorId, String idempotencyKey) {
        return Optional.ofNullable(
                mapper.findAssignmentByIdempotency(actorId, idempotencyKey))
                .map(this::assignment);
    }

    @Override
    public List<Assignment> listAssignments(
            String groupId, LocalDate asOf, int limit, int offset) {
        return mapper.listAssignments(groupId, asOf, limit, offset)
                .stream().map(this::assignment).toList();
    }

    @Override
    public long countAssignments(String groupId, LocalDate asOf) {
        return mapper.countAssignments(groupId, asOf);
    }

    @Override
    public List<Assignment> resolveAssignments(
            String employeeId, LocalDate asOf, Instant knowledgeAsOf) {
        return mapper.resolveAssignments(employeeId, asOf, knowledgeAsOf)
                .stream()
                .map(this::assignment)
                .toList();
    }

    @Override
    public boolean hasAssignmentCompanyMismatch(
            String employeeId, LocalDate asOf, Instant knowledgeAsOf) {
        return mapper.hasAssignmentCompanyMismatch(
                employeeId, asOf, knowledgeAsOf);
    }

    @Override
    public boolean hasAssignmentOverlap(
            String employeeId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String excludeAssignmentId) {
        return mapper.hasAssignmentOverlap(
                employeeId, effectiveFrom, effectiveTo, excludeAssignmentId);
    }

    @Override
    public void lockEmployee(String employeeId) {
        mapper.lockEmployee(employeeId);
    }

    @Override
    public List<AssignmentRolloverCandidate> findAssignmentsCrossingBoundary(
            String groupRevisionId, LocalDate boundary) {
        return mapper.findAssignmentsCrossingBoundary(groupRevisionId, boundary)
                .stream()
                .map(row -> new AssignmentRolloverCandidate(
                        row.attendanceGroupAssignmentId(),
                        row.attendanceGroupId(),
                        row.attendanceGroupRevisionId(),
                        row.employeeId(),
                        row.effectiveFrom(),
                        row.effectiveTo()))
                .toList();
    }

    @Override
    public void lockAssignmentTimeline(String assignmentId) {
        if (mapper.lockAssignmentTimeline(assignmentId).isEmpty()) {
            throw new OptimisticLockingFailureException(
                    "attendance assignment timeline no longer exists");
        }
    }

    @Override
    public void appendAssignmentRolloverSuccessor(
            AssignmentRolloverCandidate predecessor,
            String successorGroupRevisionId,
            LocalDate boundary,
            String reason,
            String actorId,
            Instant recordedAt,
            String requestId) {
        AttendanceGroupRows.AssignmentRow predecessorRow =
                mapper.findAssignment(predecessor.assignmentId());
        if (predecessorRow == null
                || !predecessor.groupRevisionId().equals(
                        predecessorRow.attendanceGroupRevisionId())) {
            throw new OptimisticLockingFailureException(
                    "attendance assignment changed during rollover");
        }
        AttendanceGroupRows.AssignmentRow lockedPredecessor =
                new AttendanceGroupRows.AssignmentRow(
                        predecessorRow.attendanceGroupAssignmentId(),
                        predecessorRow.attendanceGroupId(),
                        predecessorRow.attendanceGroupRevisionId(),
                        predecessorRow.employeeId(),
                        predecessorRow.effectiveFrom(),
                        predecessorRow.effectiveTo(),
                        predecessorRow.supersedesAssignmentId(),
                        predecessorRow.snapshotDigest(),
                        predecessorRow.rowVersion(),
                        predecessorRow.changeReason(),
                        predecessorRow.createdBy(),
                        predecessorRow.createdAt(),
                        actorId,
                        recordedAt);
        appendAssignmentTimeline(
                lockedPredecessor, "INACTIVE", boundary, requestId);

        AttendanceGroupRows.AssignmentRow successor =
                new AttendanceGroupRows.AssignmentRow(
                        UUID.randomUUID().toString(),
                        predecessor.groupId(),
                        successorGroupRevisionId,
                        predecessor.employeeId(),
                        boundary,
                        predecessor.effectiveTo(),
                        predecessor.assignmentId(),
                        digest(String.join(
                                "|",
                                successorGroupRevisionId,
                                predecessor.employeeId(),
                                boundary.toString(),
                                predecessor.effectiveTo() == null
                                        ? "NULL"
                                        : predecessor.effectiveTo().toString())),
                        0,
                        reason,
                        actorId,
                        recordedAt,
                        actorId,
                        recordedAt);
        if (mapper.insertAssignmentRolloverSuccessor(
                successor, predecessor.assignmentId()) != 1) {
            throw new OptimisticLockingFailureException(
                    "attendance assignment successor was not inserted");
        }
        appendAssignmentTimeline(
                successor, "ACTIVE", boundary, requestId);
        if (successor.effectiveTo() != null) {
            appendAssignmentTimeline(
                    successor,
                    "INACTIVE",
                    successor.effectiveTo(),
                    requestId);
        }
    }

    @Override
    public void insertAssignment(Assignment assignment, String idempotencyKey) {
        AttendanceGroupRows.AssignmentRow row = row(assignment);
        if (mapper.insertAssignment(row, idempotencyKey) != 1) {
            throw new OptimisticLockingFailureException(
                    "attendance group revision changed during assignment creation");
        }
        appendAssignmentTimeline(
                row, "ACTIVE", assignment.effectiveFrom(), idempotencyKey);
        if (assignment.effectiveTo() != null) {
            appendAssignmentTimeline(
                    row, "INACTIVE", assignment.effectiveTo(), idempotencyKey);
        }
    }

    @Override
    public boolean updateAssignment(Assignment assignment, long expectedVersion) {
        Assignment current = findAssignment(assignment.assignmentId()).orElse(null);
        if (current == null || current.rowVersion() != expectedVersion) {
            return false;
        }
        AttendanceGroupRows.AssignmentRow successor = row(new Assignment(
                UUID.randomUUID().toString(),
                assignment.groupId(),
                assignment.employeeId(),
                assignment.effectiveFrom(),
                assignment.effectiveTo(),
                0,
                assignment.changeReason(),
                assignment.updatedBy(),
                assignment.updatedAt(),
                assignment.updatedBy(),
                assignment.updatedAt()));
        String requestId = UUID.randomUUID().toString();
        AttendanceGroupRows.AssignmentRow currentRow = row(current);
        AttendanceGroupRows.AssignmentRow predecessor =
                new AttendanceGroupRows.AssignmentRow(
                currentRow.attendanceGroupAssignmentId(),
                currentRow.attendanceGroupId(),
                currentRow.attendanceGroupRevisionId(),
                currentRow.employeeId(),
                currentRow.effectiveFrom(),
                currentRow.effectiveTo(),
                currentRow.supersedesAssignmentId(),
                currentRow.snapshotDigest(),
                currentRow.rowVersion(),
                currentRow.changeReason(),
                currentRow.createdBy(),
                currentRow.createdAt(),
                assignment.updatedBy(),
                assignment.updatedAt());
        appendAssignmentTimeline(
                predecessor,
                "INACTIVE",
                assignment.effectiveFrom(),
                requestId);
        if (mapper.insertAssignmentSuccessor(
                successor, current.assignmentId()) != 1) {
            throw new OptimisticLockingFailureException(
                    "attendance assignment successor was not inserted");
        }
        appendAssignmentTimeline(
                successor,
                "ACTIVE",
                assignment.effectiveFrom(),
                requestId);
        if (assignment.effectiveTo() != null) {
            appendAssignmentTimeline(
                    successor,
                    "INACTIVE",
                    assignment.effectiveTo(),
                    requestId);
        }
        return true;
    }

    @Override
    public int countEffectiveAssignments(
            String groupId, LocalDate effectiveFrom, LocalDate effectiveTo) {
        return mapper.countEffectiveAssignments(groupId, effectiveFrom, effectiveTo);
    }

    private Location location(AttendanceGroupRows.LocationRow row) {
        return new Location(
                row.locationId(), row.sharedLocationId(),
                row.companyId(), row.locationCode(),
                row.locationRevisionId(), row.revisionNumber(), row.locationName(),
                row.timeZone(), LifecycleStatus.valueOf(row.status()),
                row.effectiveFrom(), row.effectiveTo(), row.snapshotDigest(), row.rowVersion(),
                row.changeReason(), row.createdBy(), row.createdAt(),
                row.updatedBy(), row.updatedAt());
    }

    private AttendanceGroup group(AttendanceGroupRows.GroupRow row) {
        return new AttendanceGroup(
                row.attendanceGroupId(), row.companyId(), row.groupCode(),
                row.attendanceGroupRevisionId(), row.revisionNumber(), row.groupName(),
                row.locationId(), row.locationRevisionId(), row.workCalendarId(),
                row.shiftTemplateId(), LifecycleStatus.valueOf(row.status()),
                row.effectiveFrom(), row.effectiveTo(), row.snapshotDigest(), row.rowVersion(),
                row.changeReason(), row.createdBy(), row.createdAt(),
                row.updatedBy(), row.updatedAt());
    }

    private Assignment assignment(AttendanceGroupRows.AssignmentRow row) {
        return new Assignment(
                row.attendanceGroupAssignmentId(), row.attendanceGroupId(),
                row.employeeId(), row.effectiveFrom(), row.effectiveTo(),
                row.rowVersion(), row.changeReason(), row.createdBy(), row.createdAt(),
                row.updatedBy(), row.updatedAt());
    }

    private AttendanceGroupRows.LocationRow row(Location value) {
        return new AttendanceGroupRows.LocationRow(
                value.locationId(), value.sharedLocationId(),
                value.companyId(), value.code(),
                value.locationRevisionId(), value.revisionNumber(), value.name(),
                value.timeZone(), value.status().name(), value.effectiveFrom(),
                value.effectiveTo(), value.snapshotDigest(), value.rowVersion(),
                value.changeReason(), value.createdBy(), value.createdAt(),
                value.updatedBy(), value.updatedAt());
    }

    private AttendanceGroupRows.GroupRow row(AttendanceGroup value) {
        return new AttendanceGroupRows.GroupRow(
                value.groupId(), value.companyId(), value.code(),
                value.groupRevisionId(), value.revisionNumber(), value.name(),
                value.locationId(), value.locationRevisionId(),
                value.calendarId(), value.shiftTemplateId(),
                value.status().name(), value.effectiveFrom(), value.effectiveTo(),
                value.snapshotDigest(), value.rowVersion(),
                value.changeReason(), value.createdBy(), value.createdAt(),
                value.updatedBy(), value.updatedAt());
    }

    private AttendanceGroupRows.AssignmentRow row(Assignment value) {
        return new AttendanceGroupRows.AssignmentRow(
                value.assignmentId(), value.groupId(), null,
                value.employeeId(),
                value.effectiveFrom(), value.effectiveTo(), null,
                digest(String.join(
                        "|",
                        value.groupId(),
                        value.employeeId(),
                        value.effectiveFrom().toString(),
                        value.effectiveTo() == null
                                ? "NULL" : value.effectiveTo().toString())),
                value.rowVersion(),
                value.changeReason(), value.createdBy(), value.createdAt(),
                value.updatedBy(), value.updatedAt());
    }

    private void appendLocationTimeline(
            AttendanceGroupRows.LocationRow row,
            String state,
            LocalDate businessEffectiveFrom,
            String requestId) {
        int sequence = mapper.nextLocationTimelineSequence(row.locationId());
        mapper.insertLocationTimeline(new AttendanceGroupRows.TimelineFactRow(
                UUID.randomUUID().toString(),
                row.locationId(),
                row.locationRevisionId(),
                null,
                sequence,
                state,
                businessEffectiveFrom,
                mapper.latestLocationTimelineId(row.locationId()),
                row.updatedAt(),
                row.updatedBy(),
                requestId(requestId)));
    }

    private void appendLocationEndTimeline(
            AttendanceGroupRows.LocationRow row, String requestId) {
        if (row.effectiveTo() != null) {
            appendLocationTimeline(
                    row, "INACTIVE", row.effectiveTo(), requestId);
        }
    }

    private void appendGroupTimeline(
            AttendanceGroupRows.GroupRow row,
            String state,
            LocalDate businessEffectiveFrom,
            String requestId) {
        int sequence = mapper.nextGroupTimelineSequence(row.attendanceGroupId());
        mapper.insertGroupTimeline(new AttendanceGroupRows.TimelineFactRow(
                UUID.randomUUID().toString(),
                row.attendanceGroupId(),
                row.attendanceGroupRevisionId(),
                null,
                sequence,
                state,
                businessEffectiveFrom,
                mapper.latestGroupTimelineId(row.attendanceGroupId()),
                row.updatedAt(),
                row.updatedBy(),
                requestId(requestId)));
    }

    private void appendGroupEndTimeline(
            AttendanceGroupRows.GroupRow row, String requestId) {
        if (row.effectiveTo() != null) {
            appendGroupTimeline(
                    row, "INACTIVE", row.effectiveTo(), requestId);
        }
    }

    private void appendAssignmentTimeline(
            AttendanceGroupRows.AssignmentRow row,
            String state,
            LocalDate businessEffectiveFrom,
            String requestId) {
        int sequence = mapper.nextAssignmentTimelineSequence(
                row.attendanceGroupAssignmentId());
        if (mapper.insertAssignmentTimeline(
                new AttendanceGroupRows.TimelineFactRow(
                        UUID.randomUUID().toString(),
                        null,
                        row.attendanceGroupAssignmentId(),
                        row.employeeId(),
                        sequence,
                        state,
                        businessEffectiveFrom,
                        mapper.latestAssignmentTimelineId(
                                row.attendanceGroupAssignmentId()),
                        row.updatedAt(),
                        row.updatedBy(),
                        requestId(requestId))) != 1) {
            throw new OptimisticLockingFailureException(
                    "attendance assignment company boundary changed");
        }
    }

    private String requestId(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return value.length() <= 64 ? value : digest(value);
    }

    private String sharedLocationDigest(Location value) {
        return sharedLocationDigest(value, value.effectiveTo());
    }

    private String sharedLocationDigest(
            Location value, LocalDate effectiveTo) {
        return digest(String.join(
                "|",
                value.code(),
                value.name(),
                value.timeZone(),
                value.status().name(),
                value.effectiveFrom().toString(),
                effectiveTo == null ? "NULL" : effectiveTo.toString()));
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
