package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/** Published WORK minutes for sites that may have no attendance-group day. */
final class SiteShiftTemplates {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    enum Kind {
        YANGZHOU,
        DALIAN
    }

    private SiteShiftTemplates() {
    }

    static Kind kindFor(String organizationName) {
        String path = organizationName == null ? "" : organizationName;
        if (path.contains("大连")) {
            return Kind.DALIAN;
        }
        return Kind.YANGZHOU;
    }

    static boolean noClockSite(String organizationName) {
        String path = organizationName == null ? "" : organizationName;
        return path.contains("大连") || path.contains("武汉");
    }

    /** 大连/武汉只在 2026-08 按全勤；9 月起有打卡，走普通考勤。 */
    static boolean noClockSiteFullAttendance(
            String organizationName, LocalDate date) {
        return noClockSite(organizationName)
                && date != null
                && date.getYear() == 2026
                && date.getMonthValue() == 8;
    }

    static boolean summer(LocalDate date) {
        int month = date.getMonthValue();
        return month >= 5 && month <= 9;
    }

    static List<ShiftSegmentRow> workSegments(
            String employeeId, LocalDate date, Kind kind) {
        if (kind == Kind.DALIAN) {
            return List.of(
                    segment(employeeId, date, "dalian-am",
                            LocalTime.of(7, 30), LocalTime.of(12, 0)),
                    segment(employeeId, date, "dalian-pm",
                            LocalTime.of(13, 0), LocalTime.of(16, 30)));
        }
        if (summer(date)) {
            return List.of(
                    segment(employeeId, date, "yz-am",
                            LocalTime.of(8, 30), LocalTime.of(12, 0)),
                    segment(employeeId, date, "yz-pm",
                            LocalTime.of(13, 30), LocalTime.of(18, 0)));
        }
        return List.of(
                segment(employeeId, date, "yz-am",
                        LocalTime.of(8, 30), LocalTime.of(12, 0)),
                segment(employeeId, date, "yz-pm",
                        LocalTime.of(13, 0), LocalTime.of(17, 30)));
    }

    private static ShiftSegmentRow segment(
            String employeeId,
            LocalDate date,
            String segmentId,
            LocalTime start,
            LocalTime end) {
        var startAt = date.atTime(start).atZone(ZONE).toInstant();
        var endAt = date.atTime(end).atZone(ZONE).toInstant();
        return new ShiftSegmentRow(
                employeeId,
                date,
                segmentId,
                startAt,
                endAt,
                startAt.minusSeconds(60 * 60),
                startAt.plusSeconds(60 * 60),
                endAt.minusSeconds(60 * 60),
                endAt.plusSeconds(60 * 60),
                kindName(kindForSite(segmentId)));
    }

    private static Kind kindForSite(String segmentId) {
        return segmentId.startsWith("dalian") ? Kind.DALIAN : Kind.YANGZHOU;
    }

    private static String kindName(Kind kind) {
        return kind == Kind.DALIAN ? "大连固定班" : "扬州班";
    }
}
