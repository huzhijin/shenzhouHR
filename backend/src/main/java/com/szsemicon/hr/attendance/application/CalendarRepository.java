package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendar;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendarDay;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CalendarRepository {

    Optional<WorkCalendar> findCalendar(String calendarId);

    Optional<WorkCalendar> findVersion(String calendarVersionId);

    List<WorkCalendar> listVersions(String calendarId, int limit, int offset);

    long countVersions(String calendarId);

    List<WorkCalendar> resolvePublishedVersions(
            String calendarId, LocalDate businessDate, Instant knowledgeAsOf);

    Optional<LocalDate> findNextPublicationBoundary(
            String calendarId, LocalDate after, Instant knowledgeAsOf);

    Optional<WorkCalendar> findCalendarByIdempotency(String actorId, String idempotencyKey);

    Optional<WorkCalendar> findVersionByIdempotency(
            String actorId, String idempotencyKey);

    List<WorkCalendar> listCalendars(
            String principalId,
            String capability,
            Integer year,
            int limit,
            int offset,
            Instant at);

    long countCalendars(
            String principalId, String capability, Integer year, Instant at);

    void insertCalendar(WorkCalendar calendar, String idempotencyKey);

    void insertVersion(WorkCalendar calendar, String idempotencyKey);

    int nextVersionNumber(String calendarId);

    boolean updateCalendar(WorkCalendar calendar, long expectedVersion);

    void lockCalendar(String calendarId);

    boolean publishVersion(
            String calendarVersionId,
            long expectedVersion,
            String canonicalSnapshotDigest,
            String actorId,
            String reason,
            Instant at);

    boolean scheduleVersionDeactivation(
            String calendarVersionId,
            long expectedVersion,
            LocalDate businessEffectiveFrom,
            String successorVersionId,
            String actorId,
            String reason,
            Instant at);

    boolean hasActiveGroupReferencesAtOrAfter(
            String calendarId, LocalDate businessEffectiveFrom);

    boolean hasPublishedOverlap(
            String calendarId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String excludeVersionId);

    List<WorkCalendarDay> listDays(
            String calendarId,
            LocalDate from,
            LocalDate to,
            int limit,
            int offset);

    long countDays(String calendarId, LocalDate from, LocalDate to);

    Optional<WorkCalendarDay> findDay(
            String calendarId, LocalDate businessDate, Instant knowledgeAsOf);

    void replaceDays(
            String calendarId,
            List<WorkCalendarDay> days,
            long expectedVersion,
            String actorId,
            String reason,
            java.time.Instant at);
}
