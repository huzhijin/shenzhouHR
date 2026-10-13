package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.IntervalEvidence;
import com.szsemicon.hr.reporting.application.PunchClockFormat;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionSeverity;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OffScheduleAttendanceExceptions {

    private static final ZoneId ZONE = PunchClockFormat.BUSINESS_ZONE;
    private static final Duration LONG_SPAN = Duration.ofHours(14);

    private OffScheduleAttendanceExceptions() {
    }

    static List<ExceptionFact> extra(
            DailyFact daily,
            Instant shiftOff,
            Instant nextShiftStart,
            List<IntervalEvidence> oaEvidence,
            String calculationVersionId) {
        List<ExceptionFact> extras = new ArrayList<>();
        if (daily == null) {
            return extras;
        }
        Instant first = daily.firstPunchAt();
        Instant last = daily.lastPunchAt();
        Instant undeclaredAfter = daily.businessDate()
                .atTime(18, 30)
                .atZone(ZONE)
                .toInstant();
        if (last != null
                && last.isAfter(undeclaredAfter)
                && !coveredByOvertime(last, oaEvidence)) {
            extras.add(new ExceptionFact(
                    daily.factId() + ":UNDECLARED_OT",
                    daily.employeeId(),
                    daily.employeeNumber(),
                    daily.employeeName(),
                    daily.organizationId(),
                    daily.organizationName(),
                    daily.businessDate(),
                    "OVERTIME_DOCUMENT_MISSING_OR_LATE",
                    ExceptionSeverity.WARNING,
                    ExceptionState.PENDING_EVIDENCE,
                    0,
                    "班后在岗至 "
                            + PunchClockFormat.format(daily.businessDate(), last)
                            + "；原因码=OVERTIME_DOCUMENT_MISSING_OR_LATE；证据数量=1",
                    calculationVersionId));
        }
        if (first != null
                && last != null
                && !last.isBefore(first)
                && !Duration.between(first, last).minus(LONG_SPAN).isNegative()) {
            extras.add(new ExceptionFact(
                    daily.factId() + ":LONG_SPAN",
                    daily.employeeId(),
                    daily.employeeNumber(),
                    daily.employeeName(),
                    daily.organizationId(),
                    daily.organizationName(),
                    daily.businessDate(),
                    "LONG_PUNCH_SPAN_REVIEW",
                    ExceptionSeverity.WARNING,
                    ExceptionState.PENDING_REVIEW,
                    Duration.between(first, last).toMinutes(),
                    "首卡 "
                            + PunchClockFormat.format(daily.businessDate(), first)
                            + " 至 "
                            + PunchClockFormat.format(daily.businessDate(), last)
                            + "；原因码=LONG_PUNCH_SPAN_REVIEW；证据数量=1",
                    calculationVersionId));
        }
        return extras;
    }

    static List<ExceptionFact> formBeyondLastPunch(
            DailyFact daily,
            Instant lastPunch,
            List<IntervalEvidence> oaEvidence,
            String calculationVersionId) {
        List<ExceptionFact> extras = new ArrayList<>();
        if (daily == null || oaEvidence == null) {
            return extras;
        }
        Map<String, IntervalEvidence> unique = new LinkedHashMap<>();
        for (IntervalEvidence evidence : oaEvidence) {
            if (!evidence.effective() || evidence.kind() != EvidenceKind.OVERTIME) {
                continue;
            }
            Instant start = evidence.interval().start();
            Instant end = evidence.interval().end();
            if (start == null || end == null) {
                continue;
            }
            LocalDate startDate = start.atZone(ZONE).toLocalDate();
            if (!daily.businessDate().equals(startDate)) {
                continue;
            }
            String key = daily.employeeNumber() + "|" + start + "|" + end;
            IntervalEvidence existing = unique.get(key);
            if (existing == null
                    || String.valueOf(evidence.evidenceId())
                            .compareTo(String.valueOf(existing.evidenceId())) < 0) {
                unique.put(key, evidence);
            }
        }
        for (IntervalEvidence evidence : unique.values()) {
            Instant snappedEnd = OaIntervalGrid.snap(evidence.interval().end());
            if (lastPunch == null || snappedEnd.isAfter(lastPunch)) {
                extras.add(new ExceptionFact(
                        daily.factId() + ":OT_FORM_BEYOND:" + evidence.evidenceId(),
                        daily.employeeId(),
                        daily.employeeNumber(),
                        daily.employeeName(),
                        daily.organizationId(),
                        daily.organizationName(),
                        daily.businessDate(),
                        "OVERTIME_FORM_BEYOND_LAST_PUNCH",
                        ExceptionSeverity.WARNING,
                        ExceptionState.PENDING_REVIEW,
                        lastPunch == null
                                ? 0
                                : Duration.between(lastPunch, snappedEnd).toMinutes(),
                        "加班单结束晚于打卡；原因码=OVERTIME_FORM_BEYOND_LAST_PUNCH；证据数量=1",
                        calculationVersionId));
            }
        }
        return extras;
    }

    static Instant shiftOff(List<AttendanceReportCalculationRows.ShiftSegmentRow> segments) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        Instant off = null;
        for (var segment : segments) {
            if (off == null || segment.segmentEnd().isAfter(off)) {
                off = segment.segmentEnd();
            }
        }
        return off;
    }

    static Instant shiftStart(
            List<AttendanceReportCalculationRows.ShiftSegmentRow> segments,
            LocalDate date) {
        if (segments != null) {
            Instant start = null;
            for (var segment : segments) {
                if (start == null || segment.segmentStart().isBefore(start)) {
                    start = segment.segmentStart();
                }
            }
            if (start != null) {
                return start;
            }
        }
        if (date == null) {
            return null;
        }
        return date.atTime(8, 30).atZone(ZONE).toInstant();
    }

    private static boolean coveredByOvertime(
            Instant lastPunch, List<IntervalEvidence> oaEvidence) {
        if (lastPunch == null || oaEvidence == null) {
            return false;
        }
        for (IntervalEvidence evidence : oaEvidence) {
            if (!evidence.effective() || evidence.kind() != EvidenceKind.OVERTIME) {
                continue;
            }
            Instant start = evidence.interval().start();
            Instant end = evidence.interval().end();
            if (!lastPunch.isBefore(start) && lastPunch.isBefore(end)) {
                return true;
            }
        }
        return false;
    }

    private static String spanHours(Instant first, Instant last) {
        long minutes = Duration.between(first, last).toMinutes();
        if (minutes % 60 == 0) {
            return Long.toString(minutes / 60);
        }
        return String.format("%.1f", minutes / 60.0);
    }
}
