package com.szsemicon.hr.attendance.domain;

import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendar;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendarDay;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The single canonical digest algorithm for immutable work-calendar content.
 *
 * <p>Lifecycle state, audit metadata and calendar-day row identities are
 * intentionally excluded. Every included value is NFC-normalized and
 * UTF-8-length-prefixed, so delimiters inside user-controlled text cannot
 * create an ambiguous snapshot.</p>
 */
public final class CalendarSnapshotDigest {

    private static final String FORMAT = "work-calendar-snapshot-v1";

    private CalendarSnapshotDigest() {
    }

    public static String digest(
            WorkCalendar calendar, List<WorkCalendarDay> sourceDays) {
        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(sourceDays, "sourceDays");
        List<WorkCalendarDay> days = sourceDays.stream()
                .sorted(Comparator
                        .comparing(WorkCalendarDay::businessDate)
                        .thenComparing(day -> day.dayType().name())
                        .thenComparing(
                                WorkCalendarDay::shiftVersionOverrideId,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        validateDays(calendar, days);

        StringBuilder canonical = new StringBuilder();
        append(canonical, FORMAT);
        append(canonical, calendar.legalEntityId());
        append(canonical, calendar.locationId());
        append(canonical, calendar.calendarId());
        append(canonical, calendar.calendarVersionId());
        append(canonical, Integer.toString(calendar.versionNumber()));
        append(canonical, calendar.code());
        append(canonical, calendar.name());
        append(canonical, Integer.toString(calendar.calendarYear()));
        append(canonical, calendar.timeZone());
        append(canonical, calendar.effectiveFrom().toString());
        append(canonical, calendar.effectiveTo().toString());
        append(canonical, Integer.toString(days.size()));
        for (WorkCalendarDay day : days) {
            append(canonical, day.businessDate().toString());
            append(canonical, day.dayType().name());
            append(canonical, day.shiftVersionOverrideId());
        }
        return sha256(canonical.toString());
    }

    private static void validateDays(
            WorkCalendar calendar, List<WorkCalendarDay> days) {
        Set<java.time.LocalDate> dates = new HashSet<>();
        for (WorkCalendarDay day : days) {
            if (!calendar.calendarId().equals(day.calendarId())
                    || !calendar.calendarVersionId()
                    .equals(day.calendarVersionId())) {
                throw new IllegalArgumentException(
                        "calendar snapshot day belongs to another version");
            }
            if (!dates.add(day.businessDate())) {
                throw new IllegalArgumentException(
                        "calendar snapshot contains a duplicate business date");
            }
            if (day.businessDate().isBefore(calendar.effectiveFrom())
                    || !day.businessDate().isBefore(calendar.effectiveTo())) {
                throw new IllegalArgumentException(
                        "calendar snapshot day is outside the effective interval");
            }
        }
    }

    private static void append(StringBuilder target, String rawValue) {
        if (rawValue == null) {
            target.append("N:0:");
            return;
        }
        String value = Normalizer.normalize(rawValue, Normalizer.Form.NFC);
        target.append("S:")
                .append(value.getBytes(StandardCharsets.UTF_8).length)
                .append(':')
                .append(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "SHA-256 must be available", exception);
        }
    }
}
