package com.szsemicon.hr.attendance.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface CalendarMapper {

    CalendarRows.CalendarRow findCalendar(@Param("calendarId") String calendarId);

    CalendarRows.CalendarRow findVersion(
            @Param("calendarVersionId") String calendarVersionId);

    List<CalendarRows.CalendarRow> listVersions(
            @Param("calendarId") String calendarId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countVersions(@Param("calendarId") String calendarId);

    List<CalendarRows.CalendarRow> resolvePublishedVersions(
            @Param("calendarId") String calendarId,
            @Param("businessDate") LocalDate businessDate,
            @Param("knowledgeAsOf") Instant knowledgeAsOf);

    LocalDate findNextPublicationBoundary(
            @Param("calendarId") String calendarId,
            @Param("after") LocalDate after,
            @Param("knowledgeAsOf") Instant knowledgeAsOf);

    CalendarRows.CalendarRow findCalendarByIdempotency(
            @Param("actorId") String actorId,
            @Param("idempotencyKey") String idempotencyKey);

    CalendarRows.CalendarRow findVersionByIdempotency(
            @Param("actorId") String actorId,
            @Param("idempotencyKey") String idempotencyKey);

    List<CalendarRows.CalendarRow> listCalendars(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("companyId") String companyId,
            @Param("year") Integer year,
            @Param("limit") int limit,
            @Param("offset") int offset,
            @Param("at") Instant at);

    long countCalendars(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("companyId") String companyId,
            @Param("year") Integer year,
            @Param("at") Instant at);

    void insertCalendarIdentity(
            @Param("row") CalendarRows.CalendarRow row,
            @Param("idempotencyKey") String idempotencyKey);

    void insertCalendarVersion(
            @Param("row") CalendarRows.CalendarRow row,
            @Param("predecessorVersionId") String predecessorVersionId,
            @Param("idempotencyKey") String idempotencyKey);

    int nextVersionNumber(@Param("calendarId") String calendarId);

    int updateCalendarVersion(
            @Param("row") CalendarRows.CalendarRow row,
            @Param("expectedVersion") long expectedVersion);

    int publishVersion(
            @Param("calendarVersionId") String calendarVersionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("canonicalSnapshotDigest") String canonicalSnapshotDigest);

    int transitionVersionStatus(
            @Param("calendarVersionId") String calendarVersionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedStatus") String expectedStatus);

    int nextPublicationSequence(@Param("calendarId") String calendarId);

    String latestPublicationTimelineId(@Param("calendarId") String calendarId);

    void insertPublicationTimeline(
            @Param("row") AttendanceGroupRows.TimelineFactRow row);

    String lockCalendar(@Param("calendarId") String calendarId);

    boolean hasPublishedOverlap(
            @Param("calendarId") String calendarId,
            @Param("effectiveFrom") LocalDate effectiveFrom,
            @Param("effectiveTo") LocalDate effectiveTo,
            @Param("excludeVersionId") String excludeVersionId);

    List<CalendarRows.DayRow> listDays(
            @Param("calendarId") String calendarId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countDays(
            @Param("calendarId") String calendarId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    CalendarRows.DayRow findDay(
            @Param("calendarId") String calendarId,
            @Param("businessDate") LocalDate businessDate,
            @Param("knowledgeAsOf") Instant knowledgeAsOf);

    int touchCalendar(
            @Param("calendarId") String calendarId,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") String actorId,
            @Param("reason") String reason,
            @Param("at") Instant at);

    void insertDays(@Param("rows") List<CalendarRows.DayRow> rows);

    int updateDay(
            @Param("row") CalendarRows.DayRow row,
            @Param("expectedVersion") long expectedVersion);

    boolean hasActiveGroupReferencesAtOrAfter(
            @Param("calendarId") String calendarId,
            @Param("businessEffectiveFrom") LocalDate businessEffectiveFrom);
}
