package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.BadgeCode;
import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.DayCell;
import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.EmployeeRow;
import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.SlotDisplay;
import com.szsemicon.hr.reporting.domain.AttendanceReportCalculator;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.WorkWindowFact;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AttendanceMonthMatrixAssembler {

    static final String FORMULA_VERSION = "ATTENDANCE_MONTH_MATRIX_V3";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime MORNING_START = LocalTime.of(8, 30);
    private static final LocalTime MORNING_END = LocalTime.of(12, 0);
    private static final LocalTime AFTERNOON_START = LocalTime.of(13, 30);
    private static final LocalTime AFTERNOON_END = LocalTime.of(18, 0);
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(BUSINESS_ZONE);
    private static final String[] WEEKDAYS = {
            "日", "一", "二", "三", "四", "五", "六"};
    private static final Set<String> EFFECTIVE_OA_STATUSES = Set.of(
            "APPROVED", "MODIFIED", "SUPPLEMENTED", "UNKNOWN");
    private static final Set<String> BLOCKED_SLOT_LABELS = Set.of(
            "驻外", "不打卡", "离职", "入职", "停职留薪");

    private AttendanceMonthMatrixAssembler() {
    }

    static MatrixData assemble(ReportSourceSnapshot snapshot) {
        LocalDate periodStart = AttendanceReportCalculator.displayStart(
                snapshot.filter());
        LocalDate periodEnd = AttendanceReportCalculator.displayEnd(
                snapshot.filter());
        LocalDate asOfDate = snapshot.dataAsOf()
                .atZone(BUSINESS_ZONE)
                .toLocalDate();
        List<LocalDate> dates = periodStart.datesUntil(
                        periodEnd.plusDays(1))
                .toList();
        Map<String, EmployeeAccumulator> employees = new HashMap<>();
        Map<EmployeeDate, DayAccumulator> cells = new HashMap<>();

        snapshot.dailyFacts().stream()
                .filter(fact -> AttendanceReportCalculator.inDisplayWindow(
                        fact.businessDate(), snapshot.filter()))
                .sorted(Comparator.comparing(DailyFact::businessDate)
                        .thenComparing(DailyFact::factId))
                .forEach(fact -> {
                    EmployeeAccumulator employee = employee(
                            employees, fact.employeeId());
                    employee.markDailyFact();
                    employee.update(
                            fact.employeeNumber(),
                            fact.employeeName(),
                            fact.organizationId(),
                            fact.organizationName(),
                            fact.businessDate());
                    cell(cells, fact.employeeId(), fact.businessDate())
                            .addDailyFact(fact);
                });

        snapshot.oaDocumentFacts().stream()
                .filter(fact -> fact.sourceStatus() != null
                        && fact.start() != null
                        && EFFECTIVE_OA_STATUSES.contains(
                                fact.sourceStatus().toUpperCase(Locale.ROOT))
                        && (fact.endExclusive() != null
                                || isPunchCorrection(fact)))
                .sorted(Comparator
                        .comparing((OaDocumentFact fact) ->
                                unknownOaStatus(fact.sourceStatus()))
                        .thenComparing((OaDocumentFact fact) ->
                                Duration.between(
                                        fact.start(),
                                        fact.endExclusive() == null
                                                ? fact.start()
                                                : fact.endExclusive())
                                        .negated())
                        .thenComparing(OaDocumentFact::start)
                        .thenComparing(OaDocumentFact::documentId))
                .forEach(fact -> {
                    LocalDate firstDate = fact.start()
                            .atZone(BUSINESS_ZONE)
                            .toLocalDate();
                    employee(employees, fact.employeeId()).update(
                            fact.employeeNumber(),
                            fact.employeeName(),
                            fact.organizationId(),
                            fact.organizationName(),
                            firstDate);
                    LocalDate lastDate = fact.endExclusive() == null
                            ? firstDate
                            : fact.endExclusive()
                                    .minusNanos(1)
                                    .atZone(BUSINESS_ZONE)
                                    .toLocalDate();
                    LocalDate from = later(periodStart, firstDate);
                    LocalDate through = earlier(periodEnd, lastDate);
                    if (from.isAfter(through)) {
                        return;
                    }
                    for (LocalDate date = from;
                            !date.isAfter(through);
                            date = date.plusDays(1)) {
                        DayAccumulator accumulator = cell(
                                cells, fact.employeeId(), date);
                        if (skipWorkdayOnlyLeaveOnRest(accumulator, fact, date)) {
                            continue;
                        }
                        accumulator.badges.add(oaBadge(fact));
                        applyOaToSlots(
                                accumulator,
                                fact,
                                date,
                                snapshot.workWindows());
                    }
                });

        snapshot.exceptionFacts().stream()
                .filter(fact -> fact.state() != ExceptionState.RESOLVED)
                .sorted(Comparator.comparing(ExceptionFact::businessDate)
                        .thenComparing(ExceptionFact::caseId))
                .forEach(fact -> {
                    employee(employees, fact.employeeId()).update(
                            fact.employeeNumber(),
                            fact.employeeName(),
                            fact.organizationId(),
                            fact.organizationName(),
                            fact.businessDate());
                    DayAccumulator dayCell = cell(
                            cells, fact.employeeId(), fact.businessDate());
                    BadgeCode badge = exceptionBadge(fact.exceptionType());
                    if (badge == BadgeCode.LATE) {
                        dayCell.late = true;
                    }
                    if (badge == BadgeCode.MISSING_PUNCH
                            && (leaveCoversMissingPunches(dayCell)
                                    || (dayCell.hasDailyFact
                                            && dayCell.missingPunchCount == 0))) {
                        return;
                    }
                    if (badge == BadgeCode.ABSENCE
                            && (leaveCoversMissingPunches(dayCell)
                                    || (dayCell.hasDailyFact
                                            && dayCell.absenceMinutes == 0))) {
                        return;
                    }
                    dayCell.badges.add(badge);
                });

        markLateFromWorkWindows(cells, snapshot.workWindows());
        stampAfternoonShiftEnds(cells, snapshot.workWindows());

        List<EmployeeRow> rows = employees.values().stream()
                .sorted(Comparator.comparing(
                                EmployeeAccumulator::employeeNumber)
                        .thenComparing(EmployeeAccumulator::employeeName)
                        .thenComparing(EmployeeAccumulator::employeeId))
                .map(employee -> new EmployeeRow(
                        employee.employeeId,
                        employee.employeeNumber,
                        employee.employeeName,
                        employee.organizationId,
                        employee.organizationName,
                        dates.stream()
                                .map(date -> cellValue(
                                        cells.get(new EmployeeDate(
                                                employee.employeeId,
                                                date)),
                                        date,
                                        !employee.hasDailyFact,
                                        asOfDate,
                                        employee.organizationName))
                                .toList()))
                .toList();
        return new MatrixData(dates, rows);
    }

    private static boolean skipWorkdayOnlyLeaveOnRest(
            DayAccumulator accumulator,
            OaDocumentFact fact,
            LocalDate date) {
        String documentType = fact.documentType() == null
                ? ""
                : fact.documentType().toUpperCase(Locale.ROOT);
        if (!"LEAVE".equals(documentType) && !"TIME_OFF".equals(documentType)) {
            return false;
        }
        LeaveType type = resolvedLeaveType(fact);
        if ("TIME_OFF".equals(documentType)) {
            type = type == null ? LeaveType.COMPENSATORY : type;
        }
        if (type == null || type.includesWeekendHours()) {
            return false;
        }
        return accumulator.restDay || isCalendarWeekend(date);
    }

    private static LeaveType resolvedLeaveType(OaDocumentFact fact) {
        if (fact.leaveType() == null || fact.leaveType().isBlank()) {
            return null;
        }
        LeaveType coded = LeaveType.fromLeaveCode(fact.leaveType());
        if (coded != null) {
            return coded;
        }
        try {
            return LeaveType.valueOf(
                    fact.leaveType().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isCalendarWeekend(LocalDate date) {
        int dow = date.getDayOfWeek().getValue();
        return dow >= 6;
    }

    private static void applyOaToSlots(
            DayAccumulator accumulator,
            OaDocumentFact fact,
            LocalDate date,
            List<WorkWindowFact> workWindows) {
        String documentType = fact.documentType().toUpperCase(Locale.ROOT);
        if ("OVERTIME".equals(documentType)) {
            if (overlapsCalendarDate(fact, date)
                    && !OvernightOvertimeFold.continuationOnlyOnDate(
                            fact.start(),
                            fact.endExclusive(),
                            date,
                            shiftStart(
                                    workWindows,
                                    date,
                                    fact.employeeId()))) {
                accumulator.overtimeDocument = true;
                if (fact.start() != null
                        && (accumulator.overtimeStart == null
                                || fact.start().isBefore(
                                        accumulator.overtimeStart))) {
                    accumulator.overtimeStart = fact.start();
                }
                if (fact.endExclusive() != null
                        && (accumulator.overtimeEnd == null
                                || fact.endExclusive().isAfter(
                                        accumulator.overtimeEnd))) {
                    accumulator.overtimeEnd = fact.endExclusive();
                }
            }
            return;
        }
        if ("EXEMPT_PUNCH".equals(documentType)) {
            if (overlapsCalendarDate(fact, date)) {
                accumulator.exempt = true;
            }
            return;
        }
        if ("PUNCH_CORRECTION".equals(documentType)) {
            boolean morning = overlapsWindow(
                    fact, date, MORNING_START, MORNING_END);
            boolean afternoon = overlapsWindow(
                    fact, date, AFTERNOON_START, AFTERNOON_END);
            if (!morning && !afternoon && overlapsCalendarDate(fact, date)) {
                LocalTime start = fact.start()
                        .atZone(BUSINESS_ZONE)
                        .toLocalTime();
                morning = !start.isAfter(MORNING_END);
                afternoon = !morning;
            }
            Instant correctionAt = fact.start();
            if (morning) {
                accumulator.morning.markCorrection(correctionAt);
            }
            if (afternoon) {
                accumulator.afternoon.markCorrection(correctionAt);
            }
            return;
        }
        DocumentAppearance appearance = appearance(fact);
        if (appearance == null) {
            return;
        }
        boolean morning = overlapsSlot(
                fact, date, workWindows, true);
        boolean afternoon = overlapsSlot(
                fact, date, workWindows, false);
        if (!morning && !afternoon && overlapsCalendarDate(fact, date)
                && isCalendarCoverDocument(documentType)) {
            morning = true;
            afternoon = true;
        }
        if (morning) {
            accumulator.morning.accept(appearance);
            accumulator.morningLeaveMinutes += overlapWorkWindowMinutes(
                    fact, date, workWindows, true);
        }
        if (afternoon) {
            accumulator.afternoon.accept(appearance);
            accumulator.afternoonLeaveMinutes += overlapWorkWindowMinutes(
                    fact, date, workWindows, false);
        }
    }

    private static boolean overlapsSlot(
            OaDocumentFact fact,
            LocalDate date,
            List<WorkWindowFact> workWindows,
            boolean morning) {
        if (hasWindowsFor(workWindows, fact.employeeId(), date)) {
            return overlapWorkWindowMinutes(
                    fact, date, workWindows, morning) > 0;
        }
        LocalTime start = morning ? MORNING_START : AFTERNOON_START;
        LocalTime end = morning ? MORNING_END : AFTERNOON_END;
        return overlapsWindow(fact, date, start, end);
    }

    private static boolean hasWindowsFor(
            List<WorkWindowFact> workWindows,
            String employeeId,
            LocalDate date) {
        if (workWindows == null || workWindows.isEmpty()
                || employeeId == null || date == null) {
            return false;
        }
        for (WorkWindowFact window : workWindows) {
            if (employeeId.equals(window.employeeId())
                    && date.equals(window.businessDate())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCalendarCoverDocument(String documentType) {
        return "OUTING".equals(documentType)
                || "TRIP".equals(documentType)
                || "LEAVE".equals(documentType)
                || "TIME_OFF".equals(documentType);
    }

    private static long overlapWorkWindowMinutes(
            OaDocumentFact fact,
            LocalDate date,
            List<WorkWindowFact> workWindows,
            boolean morning) {
        if (workWindows == null || workWindows.isEmpty()) {
            return 0;
        }
        long minutes = 0;
        for (WorkWindowFact window : workWindows) {
            if (!fact.employeeId().equals(window.employeeId())
                    || !date.equals(window.businessDate())) {
                continue;
            }
            LocalTime windowStart = window.start()
                    .atZone(BUSINESS_ZONE)
                    .toLocalTime();
            boolean morningWindow = !windowStart.isAfter(MORNING_END);
            if (morning != morningWindow) {
                continue;
            }
            Instant overlapStart = fact.start().isAfter(window.start())
                    ? fact.start() : window.start();
            Instant overlapEnd = fact.endExclusive().isBefore(window.endExclusive())
                    ? fact.endExclusive() : window.endExclusive();
            if (overlapStart.isBefore(overlapEnd)) {
                minutes += Duration.between(overlapStart, overlapEnd).toMinutes();
            }
        }
        return minutes;
    }

    private static boolean overlapsWindow(
            OaDocumentFact fact,
            LocalDate date,
            LocalTime start,
            LocalTime end) {
        Instant windowStart = date.atTime(start)
                .atZone(BUSINESS_ZONE)
                .toInstant();
        Instant windowEnd = date.atTime(end)
                .atZone(BUSINESS_ZONE)
                .toInstant();
        Instant coverStart = fact.start();
        Instant coverEnd = fact.endExclusive();
        if (isBreastfeedingLeave(fact) && coverStart != null) {
            LocalTime clock = coverStart.atZone(BUSINESS_ZONE).toLocalTime();
            coverStart = date.atTime(clock).atZone(BUSINESS_ZONE).toInstant();
            coverEnd = coverStart.plus(1, ChronoUnit.HOURS);
        }
        if (coverStart == null || coverEnd == null) {
            return false;
        }
        return coverStart.isBefore(windowEnd)
                && coverEnd.isAfter(windowStart);
    }

    private static boolean isBreastfeedingLeave(OaDocumentFact fact) {
        if (fact == null || fact.leaveType() == null) {
            return false;
        }
        if (fact.documentType() != null
                && !"LEAVE".equalsIgnoreCase(fact.documentType())) {
            return false;
        }
        String key = fact.leaveType().strip().toUpperCase(Locale.ROOT);
        return key.contains("BREAST")
                || fact.leaveType().contains("哺乳");
    }

    private static boolean overlapsCalendarDate(
            OaDocumentFact fact, LocalDate date) {
        Instant dayStart = date.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant dayEnd = date.plusDays(1)
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();
        return fact.start().isBefore(dayEnd)
                && fact.endExclusive().isAfter(dayStart);
    }

    private static DayCell cellValue(
            DayAccumulator accumulator,
            LocalDate date,
            boolean oaOnlyEmployee,
            LocalDate asOfDate,
            String organizationName) {
        if (accumulator == null) {
            boolean weekend = date.getDayOfWeek().getValue() >= 6;
            boolean augustNoClockFullAttendance =
                    date.getYear() == 2026
                            && date.getMonthValue() == 8
                            && organizationName != null
                            && (organizationName.contains("大连")
                                    || organizationName.contains("武汉"));
            if (oaOnlyEmployee
                    && !weekend
                    && date.isBefore(asOfDate)
                    && !augustNoClockFullAttendance) {
                SlotDisplay missed = new SlotDisplay(
                        "漏刷", BadgeCode.MISSING_PUNCH.name(), null);
                return new DayCell(
                        date,
                        null,
                        null,
                        null,
                        null,
                        List.of(BadgeCode.MISSING_PUNCH),
                        missed,
                        missed,
                        true,
                        hover(date, null, missed, missed,
                                false, true, false, false, 0, 0));
            }
            return new DayCell(
                    date,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    SlotDisplay.empty(),
                    SlotDisplay.empty(),
                    false,
                    hover(date, null, SlotDisplay.empty(), SlotDisplay.empty(),
                            false, false, false, false, 0, 0));
        }
        if (accumulator.leaveOrTimeOffMinutes > 0
                && accumulator.badges.stream().noneMatch(
                        AttendanceMonthMatrixAssembler::isLeaveBadge)) {
            accumulator.badges.add(BadgeCode.OTHER_LEAVE);
        }
        suppressPunchExceptionsWhenExempt(accumulator);
        suppressPunchExceptionsWhenLeave(accumulator);
        suppressPunchExceptionsWhenLeaveDay(accumulator);
        if (accumulator.morning.correction) {
            accumulator.late = false;
            accumulator.badges.remove(BadgeCode.LATE);
        }
        SlotDisplay morning = composeSlot(true, accumulator, date);
        SlotDisplay afternoon = composeSlot(false, accumulator, date);
        boolean merged = accumulator.morning.mergeable
                && accumulator.afternoon.mergeable
                && accumulator.morning.label != null
                && accumulator.morning.label.equals(
                        accumulator.afternoon.label)
                && !accumulator.morning.correction
                && !accumulator.afternoon.correction;
        if (!merged
                && "加班".equals(morning.text())
                && "加班".equals(afternoon.text())
                && BadgeCode.RECOGNIZED_OVERTIME.name().equals(morning.tone())
                && BadgeCode.RECOGNIZED_OVERTIME.name().equals(
                        afternoon.tone())) {
            merged = true;
        }
        if (merged && !morning.text().equals(afternoon.text())) {
            merged = false;
        }
        return new DayCell(
                date,
                join(accumulator.organizationNames),
                join(accumulator.shiftLabels),
                accumulator.firstPunchAt,
                accumulator.lastPunchAt,
                accumulator.badges.stream()
                        .sorted(Comparator.comparingInt(Enum::ordinal))
                        .toList(),
                morning,
                afternoon,
                merged,
                hover(
                        date,
                        join(accumulator.shiftLabels),
                        morning,
                        afternoon,
                        accumulator.exempt,
                        merged,
                        accumulator.overtimeTone(),
                        accumulator.morning.correction
                                || accumulator.afternoon.correction,
                        accumulator.morningLeaveMinutes,
                        accumulator.afternoonLeaveMinutes));
    }

    private static SlotDisplay composeSlot(
            boolean morning, DayAccumulator day, LocalDate date) {
        SlotAccumulator slot = morning ? day.morning : day.afternoon;
        Instant punch = morning ? day.morningPunchAt() : day.afternoonPunchAt();
        boolean leaveCoversSlot = leaveCoversSlot(morning, day);
        boolean missing = day.scheduled
                && !day.exempt
                && !day.restDay
                && day.missingPunchCount > 0
                && punch == null
                && !leaveCoversSlot
                && !outingOrTripCoversDay(day)
                && !isHireDay(day)
                && !isLeaveDay(day);
        if (day.overtimeTone() && outingOrTripCoversDay(day)) {
            Instant clock = morning ? day.overtimeStart : day.overtimeEnd;
            if (clock == null) {
                clock = punch;
            }
            String text = clock == null
                    ? "加班"
                    : formatTime(date, clock);
            return new SlotDisplay(
                    text,
                    BadgeCode.RECOGNIZED_OVERTIME.name(),
                    clock == null ? punch : clock);
        }
        if (slot.label != null) {
            String text = slot.label;
            if (slot.correction) {
                text = correctionText(slot.correctionAt) + " " + slot.label;
            }
            return new SlotDisplay(text, slot.tone, punch);
        }
        if (leaveCoversSlot && punch == null) {
            DocumentAppearance appearance = leaveAppearance(
                    day.leaveType == null ? null : day.leaveType.name());
            return new SlotDisplay(appearance.label(), appearance.tone(), null);
        }
        if (morning && punch == null && isHireDay(day) && !leaveCoversSlot) {
            Instant hireAt = date.atTime(8, 29).atZone(BUSINESS_ZONE).toInstant();
            return new SlotDisplay("08:29", null, hireAt);
        }
        if (!morning && punch == null && isLeaveDay(day) && !leaveCoversSlot) {
            Instant offAt = leaveDayOffDutyAt(date, day);
            return new SlotDisplay(formatTime(date, offAt), null, offAt);
        }
        if (missing && !day.overtimeTone()) {
            return new SlotDisplay("漏刷", BadgeCode.MISSING_PUNCH.name(), punch);
        }
        if (slot.correction) {
            return new SlotDisplay(
                    correctionText(slot.correctionAt),
                    BadgeCode.PUNCH_CORRECTION.name(),
                    punch);
        }
        if (morning && day.late && punch != null) {
            return new SlotDisplay(
                    formatTime(date, punch) + " 迟到",
                    BadgeCode.LATE.name(),
                    punch);
        }
        if (!morning && day.early && punch != null) {
            return new SlotDisplay(
                    formatTime(date, punch) + " 早退",
                    BadgeCode.EARLY_DEPARTURE.name(),
                    punch);
        }
        if (punch != null) {
            String tone = null;
            if (day.overtimeTone()) {
                tone = BadgeCode.RECOGNIZED_OVERTIME.name();
            } else if (day.restDay) {
                tone = BadgeCode.REST_DAY.name();
            }
            return new SlotDisplay(formatTime(date, punch), tone, punch);
        }
        if (day.overtimeTone()
                && !morning
                && !leaveCoversSlot
                && !day.hasDistinctOffDutyPunch()
                && day.morningPunchAt() != null
                && !outingOrTripCoversDay(day)) {
            return new SlotDisplay(
                    "漏刷", BadgeCode.MISSING_PUNCH.name(), null);
        }
        if (day.overtimeTone()) {
            return new SlotDisplay(
                    "加班", BadgeCode.RECOGNIZED_OVERTIME.name(), null);
        }
        SlotDisplay fromDocument = documentToneFromBadges(day);
        if (fromDocument != null) {
            return fromDocument;
        }
        if (day.restDay) {
            return new SlotDisplay("", BadgeCode.REST_DAY.name(), null);
        }
        return SlotDisplay.empty();
    }

    private static String hover(
            LocalDate date,
            String shiftLabel,
            SlotDisplay morning,
            SlotDisplay afternoon,
            boolean exempt,
            boolean merged,
            boolean overtime,
            boolean correction,
            long morningLeaveMinutes,
            long afternoonLeaveMinutes) {
        StringBuilder text = new StringBuilder();
        text.append(String.format(
                "%02d日/%s",
                date.getDayOfMonth(),
                WEEKDAYS[date.getDayOfWeek().getValue() % 7]));
        if (shiftLabel != null && !shiftLabel.isBlank()) {
            text.append('\n').append("班次：").append(shiftLabel);
        }
        if (merged) {
            text.append('\n').append("状态：").append(morning.text());
        } else {
            text.append('\n').append("上班：")
                    .append(morning.text().isBlank() ? "无" : morning.text());
            text.append('\n').append("下班：")
                    .append(afternoon.text().isBlank() ? "无" : afternoon.text());
            if (isLeaveLike(morning) && !isLeaveLike(afternoon)
                    && morningLeaveMinutes > 0) {
                text.append('\n').append(leaveName(morning.text()))
                        .append(' ')
                        .append(formatHours(morningLeaveMinutes))
                        .append("小时");
            }
            if (isLeaveLike(afternoon) && !isLeaveLike(morning)
                    && afternoonLeaveMinutes > 0) {
                text.append('\n').append(leaveName(afternoon.text()))
                        .append(' ')
                        .append(formatHours(afternoonLeaveMinutes))
                        .append("小时");
            }
        }
        if (exempt
                && morning.text().isBlank()
                && afternoon.text().isBlank()) {
            text.append('\n').append("状态：正常出勤");
        }
        if (overtime) {
            text.append('\n').append("加班");
        }
        if (correction) {
            text.append('\n').append("补签");
        }
        return text.toString();
    }

    private static boolean isHireDay(DayAccumulator day) {
        if (day == null || day.shiftLabels.isEmpty()) {
            return false;
        }
        return day.shiftLabels.stream()
                .anyMatch(label -> label != null && label.contains("入职"));
    }

    private static boolean isLeaveDay(DayAccumulator day) {
        if (day == null || day.shiftLabels.isEmpty()) {
            return false;
        }
        return day.shiftLabels.stream()
                .anyMatch(label -> label != null && label.contains("离职"));
    }

    private static Instant leaveDayOffDutyAt(
            LocalDate date, DayAccumulator day) {
        if (day != null && day.afternoonShiftEnd != null) {
            return day.afternoonShiftEnd.plusSeconds(60);
        }
        return date.atTime(AFTERNOON_END.plusMinutes(1))
                .atZone(BUSINESS_ZONE)
                .toInstant();
    }

    private static SlotDisplay documentToneFromBadges(DayAccumulator day) {
        if (day == null || day.badges.isEmpty()) {
            return null;
        }
        for (BadgeCode code : day.badges) {
            if (code == BadgeCode.OUTING) {
                return new SlotDisplay("外出", code.name(), null);
            }
            if (code == BadgeCode.TRIP) {
                return new SlotDisplay("出差", code.name(), null);
            }
            if (isLeaveBadge(code)) {
                DocumentAppearance appearance = leaveAppearance(code.name());
                return new SlotDisplay(
                        appearance.label(), appearance.tone(), null);
            }
        }
        return null;
    }

    private static boolean isOutingOrTripLabel(SlotAccumulator slot) {
        if (slot == null || slot.label == null) {
            return false;
        }
        if ("外出".equals(slot.label) || "出差".equals(slot.label)) {
            return true;
        }
        String tone = slot.tone;
        return "OUTING".equals(tone) || "TRIP".equals(tone);
    }

    private static boolean outingOrTripCoversDay(DayAccumulator day) {
        if (day == null) {
            return false;
        }
        return day.badges.contains(BadgeCode.OUTING)
                || day.badges.contains(BadgeCode.TRIP)
                || isOutingOrTripLabel(day.morning)
                || isOutingOrTripLabel(day.afternoon);
    }

    private static boolean isLeaveLike(SlotDisplay slot) {
        if (slot.text().isBlank()) {
            return false;
        }
        String tone = slot.tone();
        if (tone == null) {
            return slot.text().contains("假")
                    || slot.text().contains("调休")
                    || slot.text().contains("出差")
                    || slot.text().contains("外出");
        }
        return switch (tone) {
            case "ANNUAL_LEAVE",
                    "PERSONAL_LEAVE",
                    "SICK_LEAVE",
                    "TIME_OFF",
                    "MARRIAGE_LEAVE",
                    "MATERNITY_LEAVE",
                    "PATERNITY_LEAVE",
                    "BEREAVEMENT_LEAVE",
                    "WORK_INJURY_LEAVE",
                    "NURSING_LEAVE",
                    "BREASTFEEDING_LEAVE",
                    "PRENATAL_EXAM_LEAVE",
                    "FAMILY_PLANNING_LEAVE",
                    "OUTING",
                    "TRIP" -> true;
            default -> false;
        };
    }

    private static String leaveName(String text) {
        String stripped = text.replace("补签", " ").replaceAll("\\d{1,2}:\\d{2}", " ");
        return stripped.replaceAll("\\s+", " ").strip();
    }

    private static String formatTime(LocalDate businessDate, Instant instant) {
        return PunchClockFormat.format(businessDate, instant);
    }

    private static LocalTime shiftStart(
            List<WorkWindowFact> workWindows,
            LocalDate date,
            String employeeId) {
        if (workWindows == null || date == null) {
            return OvernightOvertimeFold.DEFAULT_SHIFT_START;
        }
        return workWindows.stream()
                .filter(window -> date.equals(window.businessDate()))
                .filter(window -> employeeId == null
                        || employeeId.equals(window.employeeId()))
                .map(window -> window.start().atZone(BUSINESS_ZONE).toLocalTime())
                .min(LocalTime::compareTo)
                .orElse(OvernightOvertimeFold.DEFAULT_SHIFT_START);
    }

    private static String correctionText(Instant instant) {
        if (instant == null) {
            return "补签";
        }
        return "补签" + CLOCK.format(instant);
    }

    private static void suppressPunchExceptionsWhenExempt(
            DayAccumulator accumulator) {
        if (!accumulator.badges.contains(BadgeCode.EXEMPT_PUNCH)
                && !accumulator.exempt) {
            return;
        }
        accumulator.badges.remove(BadgeCode.MISSING_PUNCH);
        accumulator.badges.remove(BadgeCode.ABSENCE);
        accumulator.badges.remove(BadgeCode.EXEMPT_PUNCH);
        accumulator.exempt = true;
    }

    private static void suppressPunchExceptionsWhenLeave(
            DayAccumulator accumulator) {
        if (!leaveCoversMissingPunches(accumulator)) {
            return;
        }
        accumulator.badges.remove(BadgeCode.MISSING_PUNCH);
        accumulator.badges.remove(BadgeCode.ABSENCE);
        accumulator.missingPunchCount = 0;
    }

    private static void suppressPunchExceptionsWhenLeaveDay(
            DayAccumulator accumulator) {
        if (!isLeaveDay(accumulator)) {
            return;
        }
        accumulator.badges.remove(BadgeCode.MISSING_PUNCH);
        accumulator.badges.remove(BadgeCode.ABSENCE);
        accumulator.missingPunchCount = 0;
    }

    private static boolean leaveCoversSlot(
            boolean morning, DayAccumulator day) {
        SlotAccumulator slot = morning ? day.morning : day.afternoon;
        long slotLeaveMinutes = morning
                ? day.morningLeaveMinutes
                : day.afternoonLeaveMinutes;
        if (slot.label != null || slotLeaveMinutes > 0) {
            return true;
        }
        return leaveCoversMissingPunches(day);
    }

    private static boolean leaveCoversMissingPunches(DayAccumulator day) {
        boolean bothSlotsLeave = day.morning.label != null
                && day.afternoon.label != null;
        boolean fullDayMinutes = day.scheduledMinutes > 0
                && day.leaveOrTimeOffMinutes >= day.scheduledMinutes;
        boolean unmappedLeave = day.leaveOrTimeOffMinutes > 0
                && day.morning.label == null
                && day.afternoon.label == null;
        return bothSlotsLeave || fullDayMinutes || unmappedLeave;
    }

    private static boolean isLeaveBadge(BadgeCode code) {
        return switch (code) {
            case TIME_OFF,
                    PERSONAL_LEAVE,
                    SICK_LEAVE,
                    ANNUAL_LEAVE,
                    MARRIAGE_LEAVE,
                    MATERNITY_LEAVE,
                    PATERNITY_LEAVE,
                    BEREAVEMENT_LEAVE,
                    WORK_INJURY_LEAVE,
                    NURSING_LEAVE,
                    BREASTFEEDING_LEAVE,
                    PRENATAL_EXAM_LEAVE,
                    FAMILY_PLANNING_LEAVE,
                    OTHER_LEAVE -> true;
            default -> false;
        };
    }

    private static String formatHours(long minutes) {
        if (minutes % 60 == 0) {
            return Long.toString(minutes / 60);
        }
        return java.math.BigDecimal.valueOf(minutes)
                .divide(java.math.BigDecimal.valueOf(60), 1,
                        java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String join(Set<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        return String.join(" / ", values);
    }

    private static EmployeeAccumulator employee(
            Map<String, EmployeeAccumulator> employees, String employeeId) {
        return employees.computeIfAbsent(
                employeeId, EmployeeAccumulator::new);
    }

    private static void markLateFromWorkWindows(
            Map<EmployeeDate, DayAccumulator> cells,
            List<WorkWindowFact> workWindows) {
        if (workWindows == null || workWindows.isEmpty()) {
            return;
        }
        for (Map.Entry<EmployeeDate, DayAccumulator> entry : cells.entrySet()) {
            DayAccumulator day = entry.getValue();
            Instant punch = day.morningPunchAt();
            if (day.late || punch == null || day.morning.correction) {
                continue;
            }
            Instant shiftStart = null;
            for (WorkWindowFact window : workWindows) {
                if (!entry.getKey().employeeId().equals(window.employeeId())
                        || !entry.getKey().date().equals(window.businessDate())) {
                    continue;
                }
                LocalTime start = window.start()
                        .atZone(BUSINESS_ZONE)
                        .toLocalTime();
                if (start.isAfter(MORNING_END)) {
                    continue;
                }
                if (shiftStart == null || window.start().isBefore(shiftStart)) {
                    shiftStart = window.start();
                }
            }
            if (shiftStart != null && !punch.isBefore(shiftStart)) {
                day.late = true;
                day.badges.add(BadgeCode.LATE);
            }
        }
    }

    private static void stampAfternoonShiftEnds(
            Map<EmployeeDate, DayAccumulator> cells,
            List<WorkWindowFact> workWindows) {
        if (workWindows == null || workWindows.isEmpty()) {
            return;
        }
        for (WorkWindowFact window : workWindows) {
            DayAccumulator day = cells.get(new EmployeeDate(
                    window.employeeId(), window.businessDate()));
            if (day == null) {
                continue;
            }
            LocalTime start = window.start()
                    .atZone(BUSINESS_ZONE)
                    .toLocalTime();
            if (!start.isAfter(MORNING_END)) {
                continue;
            }
            if (day.afternoonShiftEnd == null
                    || window.endExclusive().isAfter(day.afternoonShiftEnd)) {
                day.afternoonShiftEnd = window.endExclusive();
            }
        }
    }

    private static DayAccumulator cell(
            Map<EmployeeDate, DayAccumulator> cells,
            String employeeId,
            LocalDate date) {
        return cells.computeIfAbsent(
                new EmployeeDate(employeeId, date),
                ignored -> new DayAccumulator());
    }

    private static boolean isPunchCorrection(OaDocumentFact fact) {
        return fact != null
                && fact.documentType() != null
                && "PUNCH_CORRECTION".equalsIgnoreCase(fact.documentType());
    }

    private static BadgeCode oaBadge(OaDocumentFact fact) {
        String documentType = fact.documentType().toUpperCase(Locale.ROOT);
        return switch (documentType) {
            case "LEAVE" -> leaveBadge(fact.leaveType());
            case "TIME_OFF" -> BadgeCode.TIME_OFF;
            case "OUTING" -> BadgeCode.OUTING;
            case "TRIP" -> BadgeCode.TRIP;
            case "PUNCH_CORRECTION" -> BadgeCode.PUNCH_CORRECTION;
            case "LEAVE_REVOCATION" -> BadgeCode.LEAVE_REVOCATION;
            case "OVERTIME" -> BadgeCode.OVERTIME_APPLICATION;
            case "EXEMPT_PUNCH" -> BadgeCode.EXEMPT_PUNCH;
            default -> BadgeCode.OTHER_ATTENDANCE_DOCUMENT;
        };
    }

    private static BadgeCode leaveBadge(String leaveType) {
        String tone = leaveAppearance(leaveType).tone();
        if (tone == null) {
            return BadgeCode.OTHER_LEAVE;
        }
        try {
            return BadgeCode.valueOf(tone);
        } catch (IllegalArgumentException ignored) {
            return BadgeCode.OTHER_LEAVE;
        }
    }

    private static DocumentAppearance appearance(OaDocumentFact fact) {
        String documentType = fact.documentType().toUpperCase(Locale.ROOT);
        DocumentAppearance resolved = switch (documentType) {
            case "LEAVE" -> leaveAppearance(fact.leaveType());
            case "TIME_OFF" -> new DocumentAppearance(
                    "调休", BadgeCode.TIME_OFF.name(), 2, true);
            case "OUTING" -> new DocumentAppearance(
                    "外出", BadgeCode.OUTING.name(), 3, true);
            case "TRIP" -> new DocumentAppearance(
                    "出差", BadgeCode.TRIP.name(), 4, true);
            default -> null;
        };
        if (resolved != null && BLOCKED_SLOT_LABELS.contains(resolved.label())) {
            return null;
        }
        if (resolved != null && unknownOaStatus(fact.sourceStatus())) {
            return new DocumentAppearance(
                    resolved.label(),
                    resolved.tone(),
                    resolved.rank() + 10,
                    resolved.mergeable());
        }
        return resolved;
    }

    private static boolean unknownOaStatus(String status) {
        return status != null && "UNKNOWN".equalsIgnoreCase(status.strip());
    }

    static String leaveLabel(String leaveType) {
        return leaveAppearance(leaveType).label();
    }

    private static DocumentAppearance leaveAppearance(String leaveType) {
        if (leaveType == null || leaveType.isBlank()) {
            return new DocumentAppearance("请假", null, 1, true);
        }
        String normalized = leaveType.strip();
        String key = normalized.toUpperCase(Locale.ROOT);
        return switch (key) {
            case "ANNUAL_LEAVE", "ANNUAL", "年假", "年休假" ->
                    new DocumentAppearance("年假", BadgeCode.ANNUAL_LEAVE.name(), 1, true);
            case "PERSONAL_LEAVE", "PERSONAL", "事假" ->
                    new DocumentAppearance("事假", BadgeCode.PERSONAL_LEAVE.name(), 1, true);
            case "SICK_LEAVE", "SICK", "病假" ->
                    new DocumentAppearance("病假", BadgeCode.SICK_LEAVE.name(), 1, true);
            case "TIME_OFF", "COMPENSATORY", "调休", "调休假" ->
                    new DocumentAppearance("调休", BadgeCode.TIME_OFF.name(), 1, true);
            case "MARRIAGE_LEAVE", "MARRIAGE", "婚假", "结婚假" ->
                    new DocumentAppearance(
                            "婚假", BadgeCode.MARRIAGE_LEAVE.name(), 1, true);
            case "MATERNITY_LEAVE", "MATERNITY", "产假" ->
                    new DocumentAppearance(
                            "产假", BadgeCode.MATERNITY_LEAVE.name(), 1, true);
            case "PATERNITY_LEAVE", "PATERNITY", "陪产假" ->
                    new DocumentAppearance(
                            "陪产假", BadgeCode.PATERNITY_LEAVE.name(), 1, true);
            case "BEREAVEMENT_LEAVE", "BEREAVEMENT", "丧假" ->
                    new DocumentAppearance(
                            "丧假", BadgeCode.BEREAVEMENT_LEAVE.name(), 1, true);
            case "WORK_INJURY_LEAVE", "WORK_INJURY", "工伤", "工伤假" ->
                    new DocumentAppearance(
                            "工伤假", BadgeCode.WORK_INJURY_LEAVE.name(), 1, true);
            case "NURSING_LEAVE", "护理假" ->
                    new DocumentAppearance(
                            "护理假", BadgeCode.NURSING_LEAVE.name(), 1, true);
            case "BREASTFEEDING_TIME", "BREASTFEEDING", "哺乳时间", "哺乳假" ->
                    new DocumentAppearance(
                            "哺乳假", BadgeCode.BREASTFEEDING_LEAVE.name(), 1, true);
            case "PRENATAL_EXAM_TIME", "PRENATAL_NURSING", "产检时间", "孕检假" ->
                    new DocumentAppearance(
                            "孕检假", BadgeCode.PRENATAL_EXAM_LEAVE.name(), 1, true);
            case "FAMILY_PLANNING_LEAVE", "FAMILY_PLANNING", "计生假" ->
                    new DocumentAppearance(
                            "计生假", BadgeCode.FAMILY_PLANNING_LEAVE.name(), 1, true);
            case "OTHER_LEAVE", "OTHER", "其他" ->
                    new DocumentAppearance("请假", null, 1, true);
            default -> looksLikeChineseLabel(normalized)
                    ? new DocumentAppearance(normalized, null, 1, true)
                    : new DocumentAppearance("请假", null, 1, true);
        };
    }

    private static boolean looksLikeChineseLabel(String value) {
        return value.codePoints().anyMatch(
                code -> Character.UnicodeScript.of(code)
                        == Character.UnicodeScript.HAN)
                && !BLOCKED_SLOT_LABELS.contains(value);
    }

    private static BadgeCode exceptionBadge(String exceptionType) {
        String normalized = exceptionType.toUpperCase(Locale.ROOT);
        if ("LATE".equals(normalized)) {
            return BadgeCode.LATE;
        }
        if ("EARLY_DEPARTURE".equals(normalized)
                || normalized.startsWith("EARLY_DEPARTURE_")) {
            return BadgeCode.EARLY_DEPARTURE;
        }
        if (normalized.startsWith("MISSING_PUNCH")) {
            return BadgeCode.MISSING_PUNCH;
        }
        if ("ABSENCE".equals(normalized)) {
            return BadgeCode.ABSENCE;
        }
        return BadgeCode.OTHER_EXCEPTION;
    }

    private static LocalDate earlier(LocalDate first, LocalDate second) {
        return first.isBefore(second) ? first : second;
    }

    private static LocalDate later(LocalDate first, LocalDate second) {
        return first.isAfter(second) ? first : second;
    }

    record MatrixData(List<LocalDate> dates, List<EmployeeRow> rows) {

        MatrixData {
            dates = List.copyOf(dates);
            rows = List.copyOf(rows);
        }
    }

    private record EmployeeDate(String employeeId, LocalDate date) {
    }

    private record DocumentAppearance(
            String label, String tone, int rank, boolean mergeable) {
    }

    private static final class EmployeeAccumulator {

        private final String employeeId;
        private String employeeNumber;
        private String employeeName;
        private String organizationId;
        private String organizationName;
        private LocalDate metadataDate;
        private boolean hasDailyFact;

        private EmployeeAccumulator(String employeeId) {
            this.employeeId = employeeId;
        }

        private void markDailyFact() {
            hasDailyFact = true;
        }

        private void update(
                String number,
                String name,
                String organization,
                String organizationLabel,
                LocalDate date) {
            if (metadataDate == null || !date.isBefore(metadataDate)) {
                employeeNumber = number;
                employeeName = name;
                organizationId = organization;
                organizationName = organizationLabel;
                metadataDate = date;
            }
        }

        private String employeeId() {
            return employeeId;
        }

        private String employeeNumber() {
            return employeeNumber;
        }

        private String employeeName() {
            return employeeName;
        }
    }

    private static final class SlotAccumulator {

        private String label;
        private String tone;
        private int rank = Integer.MAX_VALUE;
        private boolean mergeable;
        private boolean correction;
        private Instant correctionAt;

        private void accept(DocumentAppearance appearance) {
            if (appearance.rank() < rank) {
                label = appearance.label();
                tone = appearance.tone();
                rank = appearance.rank();
                mergeable = appearance.mergeable();
            }
        }

        private void markCorrection(Instant at) {
            correction = true;
            if (correctionAt == null
                    || (at != null && at.isBefore(correctionAt))) {
                correctionAt = at;
            }
        }
    }

    private static final class DayAccumulator {

        private final Set<String> organizationNames =
                new java.util.LinkedHashSet<>();
        private final Set<String> shiftLabels =
                new java.util.LinkedHashSet<>();
        private final EnumSet<BadgeCode> badges =
                EnumSet.noneOf(BadgeCode.class);
        private final SlotAccumulator morning = new SlotAccumulator();
        private final SlotAccumulator afternoon = new SlotAccumulator();
        private Instant firstPunchAt;
        private Instant lastPunchAt;
        private long scheduledMinutes;
        private LeaveType leaveType;
        private long leaveOrTimeOffMinutes;
        private long lateMinutes;
        private long penalizedLateMinutes;
        private long absenceMinutes;
        private long recognizedOvertimeMinutes;
        private int missingPunchCount;
        private boolean hasDailyFact;
        private boolean scheduled;
        private boolean late;
        private boolean early;
        private boolean restDay;
        private boolean overtimeDocument;
        private Instant overtimeStart;
        private Instant overtimeEnd;
        private Instant afternoonShiftEnd;
        private boolean exempt;
        private long morningLeaveMinutes;
        private long afternoonLeaveMinutes;

        private void addDailyFact(DailyFact fact) {
            hasDailyFact = true;
            organizationNames.add(fact.organizationName());
            shiftLabels.add(fact.shiftLabel());
            scheduledMinutes += fact.scheduledMinutes();
            if (fact.leaveType() != null) {
                leaveType = fact.leaveType();
                badges.add(leaveBadge(fact.leaveType().name()));
            }
            leaveOrTimeOffMinutes += fact.leaveOrTimeOffMinutes();
            penalizedLateMinutes += fact.penalizedLateMinutes();
            absenceMinutes += fact.absenceMinutes();
            missingPunchCount += fact.missingPunchCount();
            recognizedOvertimeMinutes += fact.recognizedOvertimeMinutes();
            if (fact.scheduledMinutes() > 0) {
                scheduled = true;
            }
            lateMinutes += fact.lateMinutes();
            if (fact.lateMinutes() > 0 || fact.penalizedLateMinutes() > 0) {
                badges.add(BadgeCode.LATE);
                late = true;
            }
            if (fact.earlyDepartureMinutes() > 0) {
                badges.add(BadgeCode.EARLY_DEPARTURE);
                early = true;
            }
            if (fact.missingPunchCount() > 0) {
                badges.add(BadgeCode.MISSING_PUNCH);
            }
            if (fact.absenceMinutes() > 0) {
                badges.add(BadgeCode.ABSENCE);
            }
            if (fact.recognizedOvertimeMinutes() > 0) {
                badges.add(BadgeCode.RECOGNIZED_OVERTIME);
            }
            if (fact.scheduledMinutes() > 0
                    && fact.actualAttendanceDays() > 0
                    && fact.firstPunchAt() == null
                    && fact.lastPunchAt() == null
                    && fact.leaveOrTimeOffMinutes() == 0
                    && fact.missingPunchCount() == 0) {
                badges.add(BadgeCode.EXEMPT_PUNCH);
                exempt = true;
            }
            if (fact.scheduledMinutes() == 0
                    && Set.of(
                                    DayType.SATURDAY,
                                    DayType.SUNDAY,
                                    DayType.PUBLIC_HOLIDAY)
                            .contains(fact.dayType())) {
                badges.add(BadgeCode.REST_DAY);
                restDay = true;
            }
            if (fact.firstPunchAt() != null
                    && (firstPunchAt == null
                            || fact.firstPunchAt().isBefore(firstPunchAt))) {
                firstPunchAt = fact.firstPunchAt();
            }
            if (fact.lastPunchAt() != null
                    && (lastPunchAt == null
                            || fact.lastPunchAt().isAfter(lastPunchAt))) {
                lastPunchAt = fact.lastPunchAt();
            }
        }

        private boolean overtimeTone() {
            if (recognizedOvertimeMinutes > 0) {
                return true;
            }
            if (!overtimeDocument) {
                return false;
            }
            if (firstPunchAt == null && lastPunchAt == null) {
                return true;
            }
            if (restDay) {
                return true;
            }
            return overtimeStart != null
                    && lastPunchAt != null
                    && lastPunchAt.isAfter(overtimeStart);
        }

        private Instant morningPunchAt() {
            if (firstPunchAt == null && lastPunchAt == null) {
                return null;
            }
            if (lastPunchAt == null || lastPunchAt.equals(firstPunchAt)) {
                Instant only = firstPunchAt != null ? firstPunchAt : lastPunchAt;
                return morningInstant(only) ? only : null;
            }
            return firstPunchAt;
        }

        private Instant afternoonPunchAt() {
            if (firstPunchAt == null && lastPunchAt == null) {
                return null;
            }
            Instant last = lastPunchAt != null ? lastPunchAt : firstPunchAt;
            if (lastPunchAt == null || lastPunchAt.equals(firstPunchAt)) {
                return morningInstant(last) ? null : last;
            }
            if (morningInstant(last)
                    && scheduled
                    && missingPunchCount > 0
                    && !restDay
                    && !overtimeDocument) {
                return null;
            }
            return lastPunchAt;
        }

        private boolean hasDistinctOffDutyPunch() {
            return afternoonPunchAt() != null;
        }

        private static boolean morningInstant(Instant instant) {
            return instant.atZone(BUSINESS_ZONE)
                    .toLocalTime()
                    .isBefore(LocalTime.NOON);
        }
    }
}
