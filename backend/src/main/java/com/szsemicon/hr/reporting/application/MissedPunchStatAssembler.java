package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.infrastructure.persistence.QueryPageRows.DailyCellRow;
import com.szsemicon.hr.reporting.infrastructure.persistence.QueryPageRows.OaRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds 忘打卡统计表 morning/afternoon slots from pinned daily facts and OA
 * documents. Query GET must not run month calculation.
 */
final class MissedPunchStatAssembler {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    static final String MISS_TONE = "MISSING_PUNCH";
    static final String MAKEUP_TONE = "PUNCH_CORRECTION";
    static final String MISS_TEXT = "漏刷";
    private static final LocalTime MORNING_START = LocalTime.of(8, 30);
    private static final LocalTime MORNING_END = LocalTime.of(12, 0);
    private static final LocalTime AFTERNOON_START = LocalTime.of(13, 30);
    private static final LocalTime AFTERNOON_END = LocalTime.of(18, 0);
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(BUSINESS_ZONE);
    private static final Set<String> EFFECTIVE_OA = Set.of(
            "APPROVED", "MODIFIED", "SUPPLEMENTED", "UNKNOWN");
    private static final Set<String> COVERING_TYPES = Set.of(
            "LEAVE", "TIME_OFF", "OUTING", "TRIP", "EXEMPT_PUNCH");

    private MissedPunchStatAssembler() {
    }

    static List<Map<String, Object>> days(
            List<DailyCellRow> cells,
            List<OaRow> documents) {
        Map<LocalDate, DaySlots> byDate = new LinkedHashMap<>();
        if (cells != null) {
            for (DailyCellRow cell : cells) {
                if (cell == null || cell.businessDate() == null) {
                    continue;
                }
                byDate.put(cell.businessDate(), DaySlots.fromDaily(cell));
            }
        }
        if (documents != null) {
            for (OaRow document : documents) {
                applyDocument(byDate, document);
            }
        }
        List<Map<String, Object>> days = new ArrayList<>();
        for (DaySlots day : byDate.values()) {
            days.add(day.toMap());
        }
        return days;
    }

    static Summary summary(List<Map<String, Object>> days) {
        List<String> misses = new ArrayList<>();
        List<String> makeups = new ArrayList<>();
        if (days != null) {
            for (Map<String, Object> day : days) {
                LocalDate date = parseDate(day == null ? null : day.get("date"));
                if (date == null) {
                    continue;
                }
                Slot morning = Slot.from(day.get("morning"));
                Slot afternoon = Slot.from(day.get("afternoon"));
                if (morning.isMiss()) {
                    misses.add(label(date, "上班"));
                }
                if (afternoon.isMiss()) {
                    misses.add(label(date, "下班"));
                }
                if (morning.isMakeup()) {
                    makeups.add(label(date, "上班补签"));
                }
                if (afternoon.isMakeup()) {
                    makeups.add(label(date, "下班补签"));
                }
            }
        }
        List<String> parts = misses.isEmpty() ? makeups : misses;
        return new Summary(parts.size(), String.join(" ", parts));
    }

    static String label(LocalDate date, String side) {
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日（" + side + "）";
    }

    private static LocalDate parseDate(Object raw) {
        if (raw instanceof LocalDate date) {
            return date;
        }
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.length() >= 10) {
            text = text.substring(0, 10);
        }
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static boolean matchesFilters(
            List<Map<String, Object>> days,
            String exceptionType,
            String punchSide) {
        boolean wantMiss = isMissFilter(exceptionType);
        boolean wantMakeup = isMakeupFilter(exceptionType);
        boolean wantMorning = isMorningSide(punchSide);
        boolean wantAfternoon = isAfternoonSide(punchSide);
        boolean anyMiss = false;
        boolean anyMakeup = false;
        boolean morningMiss = false;
        boolean afternoonMiss = false;
        for (Map<String, Object> day : days) {
            Slot morning = Slot.from(day.get("morning"));
            Slot afternoon = Slot.from(day.get("afternoon"));
            if (morning.isMiss()) {
                anyMiss = true;
                morningMiss = true;
            }
            if (afternoon.isMiss()) {
                anyMiss = true;
                afternoonMiss = true;
            }
            if (morning.isMakeup() || afternoon.isMakeup()) {
                anyMakeup = true;
            }
        }
        if (wantMiss && wantMakeup) {
            if (!anyMiss && !anyMakeup) {
                return false;
            }
        } else if (wantMiss && !anyMiss) {
            return false;
        } else if (wantMakeup && !anyMakeup) {
            return false;
        } else if (!wantMiss && !wantMakeup && !anyMiss && !anyMakeup) {
            return false;
        }
        if (wantMorning && !morningMiss) {
            return false;
        }
        if (wantAfternoon && !afternoonMiss) {
            return false;
        }
        return true;
    }

    static boolean isMissFilter(String exceptionType) {
        return exceptionType != null
                && (exceptionType.equalsIgnoreCase("MISSING_PUNCH")
                        || "漏刷".equals(exceptionType));
    }

    static boolean isMakeupFilter(String exceptionType) {
        return exceptionType != null
                && (exceptionType.equalsIgnoreCase("PUNCH_CORRECTION")
                        || "已补签".equals(exceptionType)
                        || "MAKEUP".equalsIgnoreCase(exceptionType));
    }

    static boolean isMorningSide(String punchSide) {
        return punchSide != null
                && (punchSide.equalsIgnoreCase("MORNING")
                        || punchSide.contains("上班"));
    }

    static boolean isAfternoonSide(String punchSide) {
        return punchSide != null
                && (punchSide.equalsIgnoreCase("AFTERNOON")
                        || punchSide.contains("下班"));
    }

    private static void applyDocument(
            Map<LocalDate, DaySlots> byDate, OaRow document) {
        if (document == null
                || document.startAt() == null
                || !effective(document.sourceStatus())) {
            return;
        }
        String type = document.documentType() == null
                ? ""
                : document.documentType().toUpperCase(Locale.ROOT);
        Instant end = document.endExclusive() == null
                ? document.startAt().plusSeconds(1)
                : document.endExclusive();
        LocalDate from = document.startAt().atZone(BUSINESS_ZONE).toLocalDate();
        LocalDate through = end.minusNanos(1).atZone(BUSINESS_ZONE).toLocalDate();
        if (through.isBefore(from)) {
            through = from;
        }
        for (LocalDate date = from; !date.isAfter(through); date = date.plusDays(1)) {
            DaySlots day = byDate.get(date);
            if (day == null) {
                continue;
            }
            if ("PUNCH_CORRECTION".equals(type) || "CORRECTION".equals(type)) {
                boolean morning = overlapsWindow(
                        document.startAt(), end, date, MORNING_START, MORNING_END);
                boolean afternoon = overlapsWindow(
                        document.startAt(), end, date, AFTERNOON_START, AFTERNOON_END);
                if (!morning && !afternoon) {
                    LocalTime start = document.startAt()
                            .atZone(BUSINESS_ZONE)
                            .toLocalTime();
                    morning = !start.isAfter(MORNING_END);
                    afternoon = !morning;
                }
                if (morning) {
                    day.morning.markCorrection(document.startAt());
                }
                if (afternoon) {
                    day.afternoon.markCorrection(document.startAt());
                }
                continue;
            }
            if (!COVERING_TYPES.contains(type) && !leaveLike(document.leaveType())) {
                continue;
            }
            if ("EXEMPT_PUNCH".equals(type)) {
                day.exempt = true;
                continue;
            }
            if (overlapsWindow(
                    document.startAt(), end, date, MORNING_START, MORNING_END)) {
                day.morning.covered = true;
            }
            if (overlapsWindow(
                    document.startAt(), end, date, AFTERNOON_START, AFTERNOON_END)) {
                day.afternoon.covered = true;
            }
        }
    }

    private static boolean effective(String status) {
        return status != null
                && EFFECTIVE_OA.contains(status.toUpperCase(Locale.ROOT));
    }

    private static boolean leaveLike(String leaveType) {
        if (leaveType == null || leaveType.isBlank()) {
            return false;
        }
        String key = leaveType.toUpperCase(Locale.ROOT);
        return key.contains("LEAVE")
                || key.contains("TIME_OFF")
                || key.contains("COMPENSATORY")
                || key.contains("OUTING")
                || key.contains("TRIP")
                || key.contains("TRAVEL")
                || leaveType.contains("假")
                || leaveType.contains("调休")
                || leaveType.contains("外出")
                || leaveType.contains("出差");
    }

    private static boolean overlapsWindow(
            Instant start,
            Instant end,
            LocalDate date,
            LocalTime windowStart,
            LocalTime windowEnd) {
        Instant from = date.atTime(windowStart).atZone(BUSINESS_ZONE).toInstant();
        Instant to = date.atTime(windowEnd).atZone(BUSINESS_ZONE).toInstant();
        return start.isBefore(to) && end.isAfter(from);
    }

    private static final class DaySlots {
        private final LocalDate date;
        private final String dayType;
        private final String leaveType;
        private final int missingPunchCount;
        private final Instant firstPunchAt;
        private final Instant lastPunchAt;
        private final boolean restDay;
        private boolean exempt;
        private final SlotAcc morning = new SlotAcc();
        private final SlotAcc afternoon = new SlotAcc();

        private DaySlots(
                LocalDate date,
                String dayType,
                String leaveType,
                int missingPunchCount,
                Instant firstPunchAt,
                Instant lastPunchAt) {
            this.date = date;
            this.dayType = dayType;
            this.leaveType = leaveType;
            this.missingPunchCount = missingPunchCount;
            this.firstPunchAt = firstPunchAt;
            this.lastPunchAt = lastPunchAt;
            this.restDay = restDay(dayType);
            if (leaveType != null && !leaveType.isBlank()) {
                this.morning.covered = true;
                this.afternoon.covered = true;
            }
        }

        static DaySlots fromDaily(DailyCellRow cell) {
            return new DaySlots(
                    cell.businessDate(),
                    cell.dayType(),
                    cell.leaveType(),
                    cell.missingPunchCount(),
                    cell.firstPunchAt(),
                    cell.lastPunchAt());
        }

        Map<String, Object> toMap() {
            Instant morningPunch = morningPunch(firstPunchAt, lastPunchAt);
            Instant afternoonPunch = afternoonPunch(firstPunchAt, lastPunchAt);
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", date.toString());
            day.put("dayType", dayType);
            day.put("leaveType", leaveType);
            day.put("missingPunches", missingPunchCount);
            day.put("firstPunchAt", firstPunchAt);
            day.put("lastPunchAt", lastPunchAt);
            day.put("morning", compose(true, morningPunch).toMap());
            day.put("afternoon", compose(false, afternoonPunch).toMap());
            return day;
        }

        private Slot compose(boolean morningSlot, Instant punch) {
            SlotAcc acc = morningSlot ? morning : afternoon;
            boolean covered = acc.covered || exempt || restDay;
            if (acc.correction) {
                return Slot.makeup(acc.correctionAt);
            }
            if (covered) {
                return punch == null ? Slot.empty() : Slot.normal(format(punch));
            }
            boolean scheduledMiss = !restDay
                    && missingPunchCount > 0
                    && punch == null;
            if (scheduledMiss) {
                return Slot.missing();
            }
            if (punch != null) {
                return Slot.normal(format(punch));
            }
            return Slot.empty();
        }
    }

    private static boolean restDay(String dayType) {
        if (dayType == null || dayType.isBlank()) {
            return false;
        }
        String type = dayType.toUpperCase(Locale.ROOT);
        return type.contains("REST")
                || type.contains("SATURDAY")
                || type.contains("SUNDAY")
                || type.contains("HOLIDAY")
                || type.contains("WEEKEND");
    }

    private static Instant morningPunch(Instant first, Instant last) {
        if (first == null && last == null) {
            return null;
        }
        if (last == null || last.equals(first)) {
            Instant only = first != null ? first : last;
            return only.atZone(BUSINESS_ZONE).getHour() < 12 ? only : null;
        }
        return first;
    }

    private static Instant afternoonPunch(Instant first, Instant last) {
        if (first == null && last == null) {
            return null;
        }
        if (last == null || last.equals(first)) {
            Instant only = first != null ? first : last;
            return only.atZone(BUSINESS_ZONE).getHour() >= 12 ? only : null;
        }
        return last;
    }

    private static String format(Instant instant) {
        return CLOCK.format(instant);
    }

    private static final class SlotAcc {
        private boolean covered;
        private boolean correction;
        private Instant correctionAt;

        private void markCorrection(Instant at) {
            correction = true;
            correctionAt = at;
        }
    }

    record Summary(int missedCount, String remark) {
    }

    record Slot(String text, String tone, Instant punchAt) {
        static Slot empty() {
            return new Slot("", null, null);
        }

        static Slot normal(String text) {
            return new Slot(text, null, null);
        }

        static Slot missing() {
            return new Slot(MISS_TEXT, MISS_TONE, null);
        }

        static Slot makeup(Instant at) {
            String text = at == null ? "补签" : "补签" + CLOCK.format(at);
            return new Slot(text, MAKEUP_TONE, at);
        }

        boolean isMiss() {
            return MISS_TONE.equals(tone);
        }

        boolean isMakeup() {
            return MAKEUP_TONE.equals(tone);
        }

        Map<String, Object> toMap() {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("text", text);
            slot.put("tone", tone);
            slot.put("punchAt", punchAt);
            return slot;
        }

        @SuppressWarnings("unchecked")
        static Slot from(Object raw) {
            if (!(raw instanceof Map<?, ?> map)) {
                return empty();
            }
            Map<String, Object> values = (Map<String, Object>) map;
            return new Slot(
                    String.valueOf(values.getOrDefault("text", "")),
                    values.get("tone") == null ? null : String.valueOf(values.get("tone")),
                    values.get("punchAt") instanceof Instant instant ? instant : null);
        }
    }
}
