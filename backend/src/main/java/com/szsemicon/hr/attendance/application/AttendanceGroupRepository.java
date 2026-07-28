package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Assignment;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.AttendanceGroup;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Location;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceGroupRepository {

    record AssignmentRolloverCandidate(
            String assignmentId,
            String groupId,
            String groupRevisionId,
            String employeeId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    Optional<Location> findLocation(String locationId);

    List<Location> resolveLocationRevisions(
            String locationId, LocalDate asOf, Instant knowledgeAsOf);

    Optional<Location> findLocationByIdempotency(String actorId, String idempotencyKey);

    List<Location> listLocations(
            String principalId, String capability, int limit, int offset, Instant at);

    long countLocations(String principalId, String capability, Instant at);

    List<Location> listLocationRevisions(String locationId, int limit, int offset);

    long countLocationRevisions(String locationId);

    void insertLocation(Location location, String idempotencyKey);

    boolean updateLocation(Location location, long expectedVersion);

    void lockLocation(String locationId);

    void lockLocationRevision(String locationRevisionId);

    List<String> findGroupIdsReferencingLocationRevision(
            String locationRevisionId);

    Optional<AttendanceGroup> findGroup(String groupId);

    List<AttendanceGroup> resolveGroupRevisions(
            String groupId, LocalDate asOf, Instant knowledgeAsOf);

    Optional<AttendanceGroup> findGroupByIdempotency(String actorId, String idempotencyKey);

    List<AttendanceGroup> listGroups(
            String principalId,
            String capability,
            LocalDate asOf,
            int limit,
            int offset,
            Instant at);

    long countGroups(String principalId, String capability, LocalDate asOf, Instant at);

    List<AttendanceGroup> listGroupRevisions(String groupId, int limit, int offset);

    long countGroupRevisions(String groupId);

    void insertGroup(AttendanceGroup group, String idempotencyKey);

    boolean updateGroup(AttendanceGroup group, long expectedVersion);

    void lockGroup(String groupId);

    void lockGroupRevision(String groupRevisionId);

    Optional<Assignment> findAssignment(String assignmentId);

    Optional<Assignment> findAssignmentSuccessor(String predecessorAssignmentId);

    Optional<Assignment> findAssignmentByIdempotency(String actorId, String idempotencyKey);

    List<Assignment> listAssignments(
            String groupId, LocalDate asOf, int limit, int offset);

    long countAssignments(String groupId, LocalDate asOf);

    List<Assignment> resolveAssignments(
            String employeeId, LocalDate asOf, Instant knowledgeAsOf);

    boolean hasAssignmentOverlap(
            String employeeId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String excludeAssignmentId);

    void lockEmployee(String employeeId);

    List<AssignmentRolloverCandidate> findAssignmentsCrossingBoundary(
            String groupRevisionId, LocalDate boundary);

    void lockAssignmentTimeline(String assignmentId);

    void appendAssignmentRolloverSuccessor(
            AssignmentRolloverCandidate predecessor,
            String successorGroupRevisionId,
            LocalDate boundary,
            String reason,
            String actorId,
            Instant recordedAt,
            String requestId);

    void insertAssignment(Assignment assignment, String idempotencyKey);

    boolean updateAssignment(Assignment assignment, long expectedVersion);

    int countEffectiveAssignments(
            String groupId, LocalDate effectiveFrom, LocalDate effectiveTo);
}
