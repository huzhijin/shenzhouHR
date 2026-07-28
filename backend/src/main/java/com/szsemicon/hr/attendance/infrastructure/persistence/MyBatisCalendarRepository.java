package com.szsemicon.hr.attendance.infrastructure.persistence;

import com.szsemicon.hr.attendance.application.CalendarRepository;
import com.szsemicon.hr.attendance.domain.CalendarSnapshotDigest;
import com.szsemicon.hr.attendance.domain.CalendarModels.CalendarStatus;
import com.szsemicon.hr.attendance.domain.CalendarModels.DayType;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendar;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendarDay;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisCalendarRepository implements CalendarRepository {

    private final CalendarMapper mapper;

    MyBatisCalendarRepository(CalendarMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<WorkCalendar> findCalendar(String calendarId) {
        return Optional.ofNullable(mapper.findCalendar(calendarId)).map(this::calendar);
    }

    @Override
    public Optional<WorkCalendar> findVersion(String calendarVersionId) {
        return Optional.ofNullable(mapper.findVersion(calendarVersionId))
                .map(this::calendar);
    }

    @Override
    public List<WorkCalendar> listVersions(
            String calendarId, int limit, int offset) {
        return mapper.listVersions(calendarId, limit, offset)
                .stream().map(this::calendar).toList();
    }

    @Override
    public long countVersions(String calendarId) {
        return mapper.countVersions(calendarId);
    }

    @Override
    public List<WorkCalendar> resolvePublishedVersions(
            String calendarId, LocalDate businessDate, Instant knowledgeAsOf) {
        return mapper.resolvePublishedVersions(calendarId, businessDate, knowledgeAsOf)
                .stream().map(this::calendar).toList();
    }

    @Override
    public Optional<LocalDate> findNextPublicationBoundary(
            String calendarId, LocalDate after, Instant knowledgeAsOf) {
        return Optional.ofNullable(mapper.findNextPublicationBoundary(
                calendarId, after, knowledgeAsOf));
    }

    @Override
    public Optional<WorkCalendar> findCalendarByIdempotency(
            String actorId, String idempotencyKey) {
        return Optional.ofNullable(
                mapper.findCalendarByIdempotency(actorId, idempotencyKey))
                .map(this::calendar);
    }

    @Override
    public Optional<WorkCalendar> findVersionByIdempotency(
            String actorId, String idempotencyKey) {
        return Optional.ofNullable(
                mapper.findVersionByIdempotency(actorId, idempotencyKey))
                .map(this::calendar);
    }

    @Override
    public List<WorkCalendar> listCalendars(
            String principalId,
            String capability,
            Integer year,
            int limit,
            int offset,
            Instant at) {
        return mapper.listCalendars(
                        principalId, capability, year, limit, offset, at)
                .stream().map(this::calendar).toList();
    }

    @Override
    public long countCalendars(
            String principalId, String capability, Integer year, Instant at) {
        return mapper.countCalendars(principalId, capability, year, at);
    }

    @Override
    public void insertCalendar(WorkCalendar calendar, String idempotencyKey) {
        CalendarRows.CalendarRow row = row(calendar);
        mapper.insertCalendarIdentity(row, idempotencyKey);
        mapper.insertCalendarVersion(row, null, idempotencyKey);
    }

    @Override
    public void insertVersion(WorkCalendar calendar, String idempotencyKey) {
        mapper.insertCalendarVersion(row(calendar), null, idempotencyKey);
    }

    @Override
    public int nextVersionNumber(String calendarId) {
        return mapper.nextVersionNumber(calendarId);
    }

    @Override
    public boolean updateCalendar(WorkCalendar calendar, long expectedVersion) {
        WorkCalendar current =
                findVersion(calendar.calendarVersionId()).orElse(null);
        if (current == null
                || calendar.status() != CalendarStatus.DRAFT
                || mapper.updateCalendarVersion(row(current), expectedVersion) != 1) {
            return false;
        }
        List<WorkCalendarDay> sourceDays = loadAllDays(current);
        String successorVersionId = UUID.randomUUID().toString();
        WorkCalendar successor = withCanonicalDigest(new WorkCalendar(
                current.calendarId(),
                current.legalEntityId(),
                current.locationId(),
                current.code(),
                successorVersionId,
                mapper.nextVersionNumber(current.calendarId()),
                calendar.name(),
                calendar.calendarYear(),
                calendar.timeZone(),
                CalendarStatus.DRAFT,
                calendar.effectiveFrom(),
                calendar.effectiveTo(),
                null,
                0,
                calendar.changeReason(),
                calendar.updatedBy(),
                calendar.updatedAt(),
                calendar.updatedBy(),
                calendar.updatedAt()), sourceDays);
        mapper.insertCalendarVersion(
                row(successor),
                current.calendarVersionId(),
                UUID.randomUUID().toString());
        copyDays(
                sourceDays,
                successor,
                calendar.updatedBy(),
                calendar.changeReason(),
                calendar.updatedAt());
        return true;
    }

    @Override
    public void lockCalendar(String calendarId) {
        if (mapper.lockCalendar(calendarId) == null) {
            throw new OptimisticLockingFailureException(
                    "work calendar no longer exists");
        }
    }

    @Override
    public boolean publishVersion(
            String calendarVersionId,
            long expectedVersion,
            String canonicalSnapshotDigest,
            String actorId,
            String reason,
            Instant at) {
        WorkCalendar current = findVersion(calendarVersionId).orElse(null);
        if (current == null
                || current.status() != CalendarStatus.DRAFT
                || current.rowVersion() != expectedVersion
                || !canonicalSnapshotDigest.equals(current.snapshotDigest())
                || mapper.publishVersion(
                        calendarVersionId,
                        expectedVersion,
                        canonicalSnapshotDigest) != 1) {
            return false;
        }
        appendPublication(
                current,
                CalendarStatus.PUBLISHED.name(),
                current.effectiveFrom(),
                actorId,
                at);
        return true;
    }

    @Override
    public boolean scheduleVersionDeactivation(
            String calendarVersionId,
            long expectedVersion,
            LocalDate businessEffectiveFrom,
            String successorVersionId,
            String actorId,
            String reason,
            Instant at) {
        WorkCalendar current = findVersion(calendarVersionId).orElse(null);
        if (current == null
                || current.status() != CalendarStatus.PUBLISHED
                || current.rowVersion() != expectedVersion
                || mapper.transitionVersionStatus(
                        calendarVersionId,
                        expectedVersion,
                        CalendarStatus.PUBLISHED.name()) != 1) {
            return false;
        }
        appendPublication(
                current,
                CalendarStatus.INACTIVE.name(),
                businessEffectiveFrom,
                actorId,
                at);
        if (successorVersionId != null) {
            WorkCalendar successor = findVersion(successorVersionId)
                    .filter(candidate -> candidate.calendarId()
                            .equals(current.calendarId()))
                    .filter(candidate -> candidate.status()
                            == CalendarStatus.PUBLISHED)
                    .orElseThrow(() -> new OptimisticLockingFailureException(
                            "work calendar successor changed"));
            appendPublication(
                    successor,
                    CalendarStatus.PUBLISHED.name(),
                    businessEffectiveFrom,
                    actorId,
                    at);
        }
        return true;
    }

    @Override
    public boolean hasActiveGroupReferencesAtOrAfter(
            String calendarId, LocalDate businessEffectiveFrom) {
        return mapper.hasActiveGroupReferencesAtOrAfter(
                calendarId, businessEffectiveFrom);
    }

    @Override
    public boolean hasPublishedOverlap(
            String calendarId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String excludeVersionId) {
        return mapper.hasPublishedOverlap(
                calendarId, effectiveFrom, effectiveTo, excludeVersionId);
    }

    @Override
    public List<WorkCalendarDay> listDays(
            String calendarId,
            LocalDate from,
            LocalDate to,
            int limit,
            int offset) {
        return mapper.listDays(calendarId, from, to, limit, offset)
                .stream().map(this::day).toList();
    }

    @Override
    public long countDays(String calendarId, LocalDate from, LocalDate to) {
        return mapper.countDays(calendarId, from, to);
    }

    @Override
    public Optional<WorkCalendarDay> findDay(
            String calendarId, LocalDate businessDate, Instant knowledgeAsOf) {
        return Optional.ofNullable(
                mapper.findDay(calendarId, businessDate, knowledgeAsOf))
                .map(this::day);
    }

    @Override
    public void replaceDays(
            String calendarId,
            List<WorkCalendarDay> days,
            long expectedVersion,
            String actorId,
            String reason,
            Instant at) {
        WorkCalendar current = findVersion(calendarId).orElse(null);
        if (current == null
                || mapper.touchCalendar(
                        calendarId, expectedVersion, actorId, reason, at) != 1) {
            throw new OptimisticLockingFailureException(
                    "work calendar version changed");
        }
        Map<LocalDate, WorkCalendarDay> mergedDays = new TreeMap<>();
        loadAllDays(current).forEach(
                day -> mergedDays.put(day.businessDate(), day));
        days.forEach(day -> mergedDays.put(day.businessDate(), day));
        String successorVersionId = UUID.randomUUID().toString();
        WorkCalendar successor = withCanonicalDigest(new WorkCalendar(
                current.calendarId(),
                current.legalEntityId(),
                current.locationId(),
                current.code(),
                successorVersionId,
                mapper.nextVersionNumber(current.calendarId()),
                current.name(),
                current.calendarYear(),
                current.timeZone(),
                CalendarStatus.DRAFT,
                current.effectiveFrom(),
                current.effectiveTo(),
                null,
                0,
                reason,
                actorId,
                at,
                actorId,
                at), mergedDays.values().stream().toList());
        mapper.insertCalendarVersion(
                row(successor),
                current.calendarVersionId(),
                UUID.randomUUID().toString());
        copyDays(
                mergedDays.values().stream().toList(),
                successor,
                actorId,
                reason,
                at);
    }

    private List<WorkCalendarDay> loadAllDays(WorkCalendar calendar) {
        java.util.ArrayList<WorkCalendarDay> days =
                new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            List<CalendarRows.DayRow> page = mapper.listDays(
                    calendar.calendarVersionId(),
                    calendar.effectiveFrom(),
                    calendar.effectiveTo().minusDays(1),
                    100,
                    offset);
            page.stream().map(this::day).forEach(days::add);
            if (page.size() < 100) {
                return List.copyOf(days);
            }
            offset += page.size();
        }
    }

    private void copyDays(
            List<WorkCalendarDay> sourceDays,
            WorkCalendar successor,
            String actorId,
            String reason,
            Instant at) {
        List<CalendarRows.DayRow> inserts = sourceDays.stream()
                .map(source -> row(new WorkCalendarDay(
                        UUID.randomUUID().toString(),
                        successor.calendarId(),
                        successor.calendarVersionId(),
                        source.businessDate(),
                        source.dayType(),
                        source.shiftVersionOverrideId(),
                        0,
                        reason,
                        actorId,
                        at,
                        actorId,
                        at)))
                .toList();
        if (!inserts.isEmpty()) {
            mapper.insertDays(inserts);
        }
    }

    private WorkCalendar calendar(CalendarRows.CalendarRow row) {
        return new WorkCalendar(
                row.workCalendarId(), row.legalEntityId(), row.locationId(),
                row.calendarCode(), row.workCalendarVersionId(), row.versionNumber(),
                row.calendarName(), row.calendarYear(), row.timeZoneSnapshot(),
                CalendarStatus.valueOf(row.status()), row.effectiveFrom(),
                row.effectiveTo(), row.snapshotDigest(), row.rowVersion(),
                row.changeReason(), row.createdBy(), row.createdAt(),
                row.updatedBy(), row.updatedAt());
    }

    private WorkCalendarDay day(CalendarRows.DayRow row) {
        return new WorkCalendarDay(
                row.workCalendarDayId(), row.workCalendarId(),
                row.workCalendarVersionId(), row.businessDate(),
                DayType.valueOf(row.dayType()), row.shiftVersionOverrideId(),
                row.rowVersion(), row.changeReason(), row.createdBy(),
                row.createdAt(), row.updatedBy(), row.updatedAt());
    }

    private CalendarRows.CalendarRow row(WorkCalendar value) {
        String snapshotDigest = value.snapshotDigest() == null
                ? CalendarSnapshotDigest.digest(value, List.of())
                : value.snapshotDigest();
        return new CalendarRows.CalendarRow(
                value.calendarId(), value.legalEntityId(), value.locationId(),
                value.code(), value.calendarVersionId(), value.versionNumber(),
                value.name(), value.calendarYear(), value.timeZone(),
                value.status().name(), value.effectiveFrom(), value.effectiveTo(),
                snapshotDigest, value.rowVersion(), value.changeReason(),
                value.createdBy(), value.createdAt(),
                value.updatedBy(), value.updatedAt());
    }

    private CalendarRows.DayRow row(WorkCalendarDay value) {
        String snapshotDigest = dayDigest(String.join(
                "|",
                value.calendarVersionId(),
                value.businessDate().toString(),
                value.dayType().name(),
                value.shiftVersionOverrideId() == null
                        ? "NULL" : value.shiftVersionOverrideId()));
        return new CalendarRows.DayRow(
                value.calendarDayId(), value.calendarId(),
                value.calendarVersionId(), value.businessDate(),
                value.dayType().name(), value.shiftVersionOverrideId(),
                snapshotDigest, value.rowVersion(), value.changeReason(),
                value.createdBy(), value.createdAt(),
                value.updatedBy(), value.updatedAt());
    }

    private WorkCalendar withCanonicalDigest(
            WorkCalendar calendar, List<WorkCalendarDay> sourceDays) {
        List<WorkCalendarDay> canonicalDays = sourceDays.stream()
                .map(day -> new WorkCalendarDay(
                        day.calendarDayId(),
                        calendar.calendarId(),
                        calendar.calendarVersionId(),
                        day.businessDate(),
                        day.dayType(),
                        day.shiftVersionOverrideId(),
                        day.rowVersion(),
                        day.changeReason(),
                        day.createdBy(),
                        day.createdAt(),
                        day.updatedBy(),
                        day.updatedAt()))
                .toList();
        String canonicalDigest =
                CalendarSnapshotDigest.digest(calendar, canonicalDays);
        return new WorkCalendar(
                calendar.calendarId(),
                calendar.legalEntityId(),
                calendar.locationId(),
                calendar.code(),
                calendar.calendarVersionId(),
                calendar.versionNumber(),
                calendar.name(),
                calendar.calendarYear(),
                calendar.timeZone(),
                calendar.status(),
                calendar.effectiveFrom(),
                calendar.effectiveTo(),
                canonicalDigest,
                calendar.rowVersion(),
                calendar.changeReason(),
                calendar.createdBy(),
                calendar.createdAt(),
                calendar.updatedBy(),
                calendar.updatedAt());
    }

    private void appendPublication(
            WorkCalendar calendar,
            String state,
            LocalDate businessEffectiveFrom,
            String actorId,
            Instant at) {
        int sequence = mapper.nextPublicationSequence(calendar.calendarId());
        mapper.insertPublicationTimeline(new AttendanceGroupRows.TimelineFactRow(
                UUID.randomUUID().toString(),
                calendar.calendarId(),
                calendar.calendarVersionId(),
                null,
                sequence,
                state,
                businessEffectiveFrom,
                mapper.latestPublicationTimelineId(calendar.calendarId()),
                at,
                actorId,
                UUID.randomUUID().toString()));
    }

    private String dayDigest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
