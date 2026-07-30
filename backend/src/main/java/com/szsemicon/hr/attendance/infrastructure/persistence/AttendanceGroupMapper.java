package com.szsemicon.hr.attendance.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendanceGroupMapper {

    AttendanceGroupRows.LocationRow findLocation(@Param("locationId") String locationId);

    List<AttendanceGroupRows.LocationRow> resolveLocationRevisions(
            @Param("locationId") String locationId,
            @Param("asOf") LocalDate asOf,
            @Param("knowledgeAsOf") Instant knowledgeAsOf);

    AttendanceGroupRows.LocationRow findLocationByIdempotency(
            @Param("actorId") String actorId,
            @Param("idempotencyKey") String idempotencyKey);

    List<AttendanceGroupRows.LocationRow> listLocations(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("limit") int limit,
            @Param("offset") int offset,
            @Param("at") Instant at);

    long countLocations(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("at") Instant at);

    List<AttendanceGroupRows.LocationRow> listLocationRevisions(
            @Param("locationId") String locationId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countLocationRevisions(@Param("locationId") String locationId);

    void insertLocationIdentity(
            @Param("row") AttendanceGroupRows.LocationRow row,
            @Param("idempotencyKey") String idempotencyKey);

    void insertLocationRevision(
            @Param("row") AttendanceGroupRows.LocationRow row,
            @Param("predecessorRevisionId") String predecessorRevisionId);

    int advanceLocationVersion(
            @Param("locationId") String locationId,
            @Param("expectedVersion") long expectedVersion);

    int nextLocationTimelineSequence(@Param("locationId") String locationId);

    String latestLocationTimelineId(@Param("locationId") String locationId);

    void insertLocationTimeline(
            @Param("row") AttendanceGroupRows.TimelineFactRow row);

    String lockLocation(@Param("locationId") String locationId);

    String lockLocationRevision(
            @Param("locationRevisionId") String locationRevisionId);

    List<String> findGroupIdsReferencingLocationRevision(
            @Param("locationRevisionId") String locationRevisionId);

    AttendanceGroupRows.GroupRow findGroup(@Param("groupId") String groupId);

    List<AttendanceGroupRows.GroupRow> resolveGroupRevisions(
            @Param("groupId") String groupId,
            @Param("asOf") LocalDate asOf,
            @Param("knowledgeAsOf") Instant knowledgeAsOf);

    AttendanceGroupRows.GroupRow findGroupByIdempotency(
            @Param("actorId") String actorId,
            @Param("idempotencyKey") String idempotencyKey);

    List<AttendanceGroupRows.GroupRow> listGroups(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("asOf") LocalDate asOf,
            @Param("limit") int limit,
            @Param("offset") int offset,
            @Param("at") Instant at);

    long countGroups(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("asOf") LocalDate asOf,
            @Param("at") Instant at);

    List<AttendanceGroupRows.GroupRow> listGroupRevisions(
            @Param("groupId") String groupId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countGroupRevisions(@Param("groupId") String groupId);

    void insertGroupIdentity(
            @Param("row") AttendanceGroupRows.GroupRow row,
            @Param("idempotencyKey") String idempotencyKey);

    void insertGroupRevision(
            @Param("row") AttendanceGroupRows.GroupRow row,
            @Param("predecessorRevisionId") String predecessorRevisionId);

    int nextGroupTimelineSequence(@Param("groupId") String groupId);

    String latestGroupTimelineId(@Param("groupId") String groupId);

    void insertGroupTimeline(
            @Param("row") AttendanceGroupRows.TimelineFactRow row);

    String lockGroup(@Param("groupId") String groupId);

    String lockGroupRevision(@Param("groupRevisionId") String groupRevisionId);

    AttendanceGroupRows.AssignmentRow findAssignment(
            @Param("assignmentId") String assignmentId);

    AttendanceGroupRows.AssignmentRow findAssignmentSuccessor(
            @Param("predecessorAssignmentId") String predecessorAssignmentId);

    AttendanceGroupRows.AssignmentRow findAssignmentByIdempotency(
            @Param("actorId") String actorId,
            @Param("idempotencyKey") String idempotencyKey);

    List<AttendanceGroupRows.AssignmentRow> listAssignments(
            @Param("groupId") String groupId,
            @Param("asOf") LocalDate asOf,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countAssignments(
            @Param("groupId") String groupId,
            @Param("asOf") LocalDate asOf);

    List<AttendanceGroupRows.AssignmentRow> resolveAssignments(
            @Param("employeeId") String employeeId,
            @Param("asOf") LocalDate asOf,
            @Param("knowledgeAsOf") Instant knowledgeAsOf);

    boolean hasAssignmentCompanyMismatch(
            @Param("employeeId") String employeeId,
            @Param("asOf") LocalDate asOf,
            @Param("knowledgeAsOf") Instant knowledgeAsOf);

    boolean hasAssignmentOverlap(
            @Param("employeeId") String employeeId,
            @Param("effectiveFrom") LocalDate effectiveFrom,
            @Param("effectiveTo") LocalDate effectiveTo,
            @Param("excludeAssignmentId") String excludeAssignmentId);

    String lockEmployee(@Param("employeeId") String employeeId);

    List<AttendanceGroupRows.AssignmentRow> findAssignmentsCrossingBoundary(
            @Param("groupRevisionId") String groupRevisionId,
            @Param("boundary") LocalDate boundary);

    List<String> lockAssignmentTimeline(
            @Param("assignmentId") String assignmentId);

    int insertAssignment(
            @Param("row") AttendanceGroupRows.AssignmentRow row,
            @Param("idempotencyKey") String idempotencyKey);

    int insertAssignmentSuccessor(
            @Param("row") AttendanceGroupRows.AssignmentRow row,
            @Param("predecessorAssignmentId") String predecessorAssignmentId);

    int insertAssignmentRolloverSuccessor(
            @Param("row") AttendanceGroupRows.AssignmentRow row,
            @Param("predecessorAssignmentId") String predecessorAssignmentId);

    int nextAssignmentTimelineSequence(
            @Param("assignmentId") String assignmentId);

    String latestAssignmentTimelineId(
            @Param("assignmentId") String assignmentId);

    int insertAssignmentTimeline(
            @Param("row") AttendanceGroupRows.TimelineFactRow row);

    int countEffectiveAssignments(
            @Param("groupId") String groupId,
            @Param("effectiveFrom") LocalDate effectiveFrom,
            @Param("effectiveTo") LocalDate effectiveTo);
}
