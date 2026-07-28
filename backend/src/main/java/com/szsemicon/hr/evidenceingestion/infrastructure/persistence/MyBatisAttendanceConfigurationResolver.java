package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.port.AttendanceConfigurationResolverPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the effective attendance setup only from published V7 timelines.
 *
 * <p>The UTC seed window covers every IANA offset and the preceding business
 * date used by cross-midnight shifts. A result is authoritative only when one
 * internally consistent setup resolves for every applicable business date.
 * This deliberately rejects a setup rollover that the current port cannot
 * represent without guessing.</p>
 */
@Repository
public class MyBatisAttendanceConfigurationResolver
        implements AttendanceConfigurationResolverPort {

    private static final LocalTime CROSS_DAY_CUTOFF = LocalTime.of(6, 0);
    private static final int IDENTIFIER_MAX = 36;
    private static final int SEED_DAYS_BEFORE_UTC = 2;
    private static final int SEED_DAYS_AFTER_UTC = 1;

    private final AttendanceConfigurationAuthorityMapper mapper;
    private final Clock clock;

    public MyBatisAttendanceConfigurationResolver(
            AttendanceConfigurationAuthorityMapper mapper, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(readOnly = true)
    public Resolution resolve(
            String legalEntityId, String employeeId, Instant instant) {
        requireIdentifier(legalEntityId, "legalEntityId");
        requireIdentifier(employeeId, "employeeId");
        Objects.requireNonNull(instant, "instant");

        Instant knowledgeAsOf = clock.instant();
        LocalDate utcDate = instant.atZone(ZoneOffset.UTC).toLocalDate();
        Map<LocalDate, List<AttendanceConfigurationAuthorityRow>> rowsByDate =
                new HashMap<>();
        for (int offset = -SEED_DAYS_BEFORE_UTC;
                offset <= SEED_DAYS_AFTER_UTC;
                offset++) {
            LocalDate businessDate = utcDate.plusDays(offset);
            List<AttendanceConfigurationAuthorityRow> rows =
                    mapper.resolveForBusinessDate(
                            legalEntityId,
                            employeeId,
                            businessDate,
                            knowledgeAsOf);
            if (rows == null) {
                throw new IllegalStateException(
                        "attendance configuration mapper returned null");
            }
            rowsByDate.put(businessDate, List.copyOf(rows));
        }

        Map<LocalDate, AttendanceConfigurationAuthorityRow> relevant =
                new HashMap<>();
        Set<Set<LocalDate>> interpretations = new LinkedHashSet<>();
        for (var entry : rowsByDate.entrySet()) {
            List<AttendanceConfigurationAuthorityRow> applicable =
                    new ArrayList<>();
            for (AttendanceConfigurationAuthorityRow row : entry.getValue()) {
                if (!validStoredRow(
                        row, legalEntityId, employeeId, entry.getKey())) {
                    return unavailable(legalEntityId, employeeId, instant);
                }
                Set<LocalDate> dates = applicableDates(row, instant);
                if (dates.contains(entry.getKey())) {
                    applicable.add(row);
                    interpretations.add(dates);
                }
            }
            if (applicable.size() > 1) {
                return unavailable(legalEntityId, employeeId, instant);
            }
            if (applicable.size() == 1) {
                relevant.put(entry.getKey(), applicable.getFirst());
            }
        }

        if (interpretations.size() != 1) {
            return unavailable(legalEntityId, employeeId, instant);
        }
        Set<LocalDate> candidateDates = interpretations.iterator().next();
        if (!relevant.keySet().containsAll(candidateDates)
                || candidateDates.size() < 1
                || candidateDates.size() > 2) {
            return unavailable(legalEntityId, employeeId, instant);
        }
        List<AttendanceConfigurationAuthorityRow> candidates = candidateDates
                .stream()
                .sorted()
                .map(relevant::get)
                .toList();
        if (candidates.stream()
                .anyMatch(row -> !applicableDates(row, instant)
                        .equals(candidateDates))) {
            return unavailable(legalEntityId, employeeId, instant);
        }

        AttendanceConfigurationAuthorityRow primary = candidates.stream()
                .max(Comparator.comparing(
                        AttendanceConfigurationAuthorityRow::businessDate))
                .orElseThrow();
        if (candidates.stream().anyMatch(row -> !compatible(primary, row))) {
            return unavailable(legalEntityId, employeeId, instant);
        }

        ZoneId zone = ZoneId.of(primary.locationTimeZone());
        LocalDate punchDate = instant.atZone(zone).toLocalDate();
        AttendanceConfigurationAuthorityRow punchDateRow =
                relevant.get(punchDate);
        if (punchDateRow == null) {
            return unavailable(legalEntityId, employeeId, instant);
        }
        String digest = digest(
                legalEntityId,
                employeeId,
                zone,
                candidateDates,
                candidates);
        return new Resolution(
                punchDateRow.locationId(),
                punchDateRow.attendanceGroupRevisionId(),
                punchDateRow.shiftVersionId(),
                zone,
                candidateDates,
                digest,
                true);
    }

    private static Set<LocalDate> applicableDates(
            AttendanceConfigurationAuthorityRow row, Instant instant) {
        ZoneId zone = ZoneId.of(row.locationTimeZone());
        var local = instant.atZone(zone);
        TreeSet<LocalDate> dates = new TreeSet<>();
        dates.add(local.toLocalDate());
        if (local.toLocalTime().isBefore(CROSS_DAY_CUTOFF)) {
            dates.add(local.toLocalDate().minusDays(1));
        }
        return Set.copyOf(dates);
    }

    private static boolean compatible(
            AttendanceConfigurationAuthorityRow left,
            AttendanceConfigurationAuthorityRow right) {
        return Objects.equals(left.legalEntityId(), right.legalEntityId())
                && Objects.equals(left.employeeId(), right.employeeId())
                && Objects.equals(
                        left.employeeVersionId(), right.employeeVersionId())
                && left.employeeVersion() == right.employeeVersion()
                && Objects.equals(
                        left.employmentPeriodId(),
                        right.employmentPeriodId())
                && Objects.equals(
                        left.employmentAssignmentId(),
                        right.employmentAssignmentId())
                && left.employmentVersion() == right.employmentVersion()
                && Objects.equals(
                        left.organizationId(), right.organizationId())
                && Objects.equals(
                        left.attendanceGroupAssignmentId(),
                        right.attendanceGroupAssignmentId())
                && Objects.equals(
                        left.assignmentSnapshotDigest(),
                        right.assignmentSnapshotDigest())
                && Objects.equals(
                        left.attendanceGroupId(),
                        right.attendanceGroupId())
                && Objects.equals(
                        left.attendanceGroupRevisionId(),
                        right.attendanceGroupRevisionId())
                && Objects.equals(
                        left.groupSnapshotDigest(),
                        right.groupSnapshotDigest())
                && Objects.equals(left.locationId(), right.locationId())
                && Objects.equals(
                        left.locationRevisionId(),
                        right.locationRevisionId())
                && Objects.equals(
                        left.locationTimeZone(), right.locationTimeZone())
                && Objects.equals(
                        left.locationSnapshotDigest(),
                        right.locationSnapshotDigest())
                && Objects.equals(
                        left.workCalendarId(), right.workCalendarId())
                && Objects.equals(
                        left.calendarTimeZone(), right.calendarTimeZone())
                && Objects.equals(
                        left.groupShiftTemplateId(),
                        right.groupShiftTemplateId())
                && Objects.equals(
                        left.shiftTemplateId(), right.shiftTemplateId())
                && Objects.equals(
                        left.shiftVersionId(), right.shiftVersionId())
                && Objects.equals(
                        left.shiftTimeZone(), right.shiftTimeZone())
                && Objects.equals(
                        left.shiftSnapshotDigest(),
                        right.shiftSnapshotDigest());
    }

    private static boolean validStoredRow(
            AttendanceConfigurationAuthorityRow row,
            String legalEntityId,
            String employeeId,
            LocalDate businessDate) {
        if (row == null
                || !businessDate.equals(row.businessDate())
                || !legalEntityId.equals(row.legalEntityId())
                || !employeeId.equals(row.employeeId())
                || row.employeeVersion() < 0
                || row.employmentVersion() < 0) {
            return false;
        }
        try {
            ZoneId locationZone = ZoneId.of(row.locationTimeZone());
            if (!locationZone.equals(ZoneId.of(row.calendarTimeZone()))
                    || !locationZone.equals(ZoneId.of(row.shiftTimeZone()))) {
                return false;
            }
        } catch (RuntimeException exception) {
            return false;
        }
        return validId(row.employeeVersionId())
                && validId(row.employmentPeriodId())
                && validId(row.employmentAssignmentId())
                && validId(row.organizationId())
                && validId(row.attendanceGroupAssignmentId())
                && validDigest(row.assignmentSnapshotDigest())
                && validId(row.attendanceGroupId())
                && validId(row.attendanceGroupRevisionId())
                && validDigest(row.groupSnapshotDigest())
                && validId(row.locationId())
                && validId(row.locationRevisionId())
                && validDigest(row.locationSnapshotDigest())
                && validId(row.workCalendarId())
                && validId(row.workCalendarVersionId())
                && validDigest(row.calendarSnapshotDigest())
                && validId(row.workCalendarDayId())
                && Set.of(
                                "WORKDAY",
                                "WEEKEND",
                                "PUBLIC_HOLIDAY",
                                "SPECIAL_WORKDAY")
                        .contains(row.dayType())
                && validDigest(row.calendarDaySnapshotDigest())
                && validId(row.groupShiftTemplateId())
                && validId(row.shiftTemplateId())
                && validId(row.shiftVersionId())
                && validDigest(row.shiftSnapshotDigest());
    }

    private static String digest(
            String legalEntityId,
            String employeeId,
            ZoneId zone,
            Set<LocalDate> dates,
            List<AttendanceConfigurationAuthorityRow> rows) {
        List<String> fields = new ArrayList<>();
        fields.add("ATTENDANCE_CONFIGURATION_AUTHORITY_V1");
        fields.add(legalEntityId);
        fields.add(employeeId);
        fields.add(zone.getId());
        dates.stream().sorted().forEach(date -> fields.add(date.toString()));
        rows.stream()
                .sorted(Comparator.comparing(
                        AttendanceConfigurationAuthorityRow::businessDate))
                .forEach(row -> {
                    fields.add(row.businessDate().toString());
                    fields.add(row.employeeVersionId());
                    fields.add(Long.toString(row.employeeVersion()));
                    fields.add(row.employmentPeriodId());
                    fields.add(row.employmentAssignmentId());
                    fields.add(Long.toString(row.employmentVersion()));
                    fields.add(row.organizationId());
                    fields.add(row.attendanceGroupAssignmentId());
                    fields.add(row.assignmentSnapshotDigest());
                    fields.add(row.attendanceGroupId());
                    fields.add(row.attendanceGroupRevisionId());
                    fields.add(row.groupSnapshotDigest());
                    fields.add(row.locationId());
                    fields.add(row.locationRevisionId());
                    fields.add(row.locationSnapshotDigest());
                    fields.add(row.workCalendarId());
                    fields.add(row.workCalendarVersionId());
                    fields.add(row.calendarSnapshotDigest());
                    fields.add(row.workCalendarDayId());
                    fields.add(row.dayType());
                    fields.add(row.calendarDaySnapshotDigest());
                    fields.add(row.groupShiftTemplateId());
                    fields.add(row.shiftTemplateId());
                    fields.add(row.shiftVersionId());
                    fields.add(row.shiftSnapshotDigest());
                });
        return StableAuthorityDigest.sha256(fields.toArray(String[]::new));
    }

    private static Resolution unavailable(
            String legalEntityId, String employeeId, Instant instant) {
        return new Resolution(
                null,
                null,
                null,
                ZoneOffset.UTC,
                Set.of(),
                StableAuthorityDigest.sha256(
                        "ATTENDANCE_CONFIGURATION_UNAVAILABLE_V1",
                        legalEntityId,
                        employeeId,
                        instant.toString()),
                false);
    }

    private static boolean validId(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= IDENTIFIER_MAX
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean validDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void requireIdentifier(String value, String field) {
        if (!validId(value)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
