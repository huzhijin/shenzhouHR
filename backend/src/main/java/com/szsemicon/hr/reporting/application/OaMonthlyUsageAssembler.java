package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.QueryPageRows.MonthlyLeaveUsageRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.LegacyLeaveDayEvidenceRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.OaUsageDocumentRow;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;

/** Uses only the selected published projection's recognised form/day shares. */
public final class OaMonthlyUsageAssembler {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private OaMonthlyUsageAssembler() {}

    public static List<MonthlyLeaveUsageRow> aggregate(
            List<OaUsageDocumentRow> documents, LocalDate from, LocalDate endExclusive) {
        return aggregate(documents, from, endExclusive, List.of());
    }

    /** Only these documents need the additional, immutable-pin attribution read. */
    public static List<String> legacyEvidenceDocumentIds(
            List<OaUsageDocumentRow> documents, LocalDate from, LocalDate endExclusive) {
        return documents.stream().filter(row -> needsLegacyEvidence(row, from, endExclusive))
                .map(OaUsageDocumentRow::documentId).distinct().toList();
    }

    public static List<MonthlyLeaveUsageRow> aggregate(
            List<OaUsageDocumentRow> documents, LocalDate from, LocalDate endExclusive,
            List<LegacyLeaveDayEvidenceRow> evidence) {
        record Key(String employee, YearMonth month, String kind) {}
        record DocumentDay(String employee, String document, LocalDate day, String kind) {}
        Map<Key, Long> months = new HashMap<>();
        Map<DocumentDay, Long> seen = new HashMap<>();
        for (OaUsageDocumentRow document : documents) {
            if (document.recognizedMinutes() == 0) continue;
            if (document.startAt() == null || document.endExclusive() == null
                    || !document.startAt().isBefore(document.endExclusive())
                    || document.projectionStart() == null || document.projectionEndExclusive() == null) {
                throw unavailable("ATTENDANCE_REPORT_LEAVE_DAY_ALLOCATION_INVALID", "请假逐日使用数据无效，请重新计算所属月份");
            }
            LocalDate startDay = document.startAt().atZone(SHANGHAI).toLocalDate();
            LocalDate lastDay = document.endExclusive().minusNanos(1).atZone(SHANGHAI).toLocalDate();
            Map<LocalDate, Long> days;
            try { days = OaRecognizedDayMinutes.decode(document.recognizedMinutesByDateJson(), document.recognizedMinutes()); }
            catch (IllegalArgumentException ex) { throw unavailable("ATTENDANCE_REPORT_LEAVE_DAY_ALLOCATION_INVALID", "请假逐日使用数据校验失败，请重新计算所属月份"); }
            if (days == null) {
                if (lastDay.isBefore(from) || !startDay.isBefore(endExclusive)
                        || !document.projectionStart().isBefore(endExclusive)
                        || !from.isBefore(document.projectionEndExclusive())) continue;
                if ("COMPENSATORY_OT".equals(document.kind())) {
                    // The existing overtime policy allocates the recognised form
                    // to its starting business day, even across midnight/months.
                    days = Map.of(startDay, document.recognizedMinutes());
                } else if (needsLegacyEvidence(document, from, endExclusive)) {
                    days = uniquelyAttributedLegacyDays(document, startDay, lastDay, evidence);
                } else {
                    // A wholly included single-month legacy total has one exact month.
                    days = Map.of(startDay, document.recognizedMinutes());
                }
            }
            for (var item : days.entrySet()) {
                LocalDate day = item.getKey();
                // A uniquely referenced approved leave amendment can move the
                // actual leave to another day. Its validated immutable map is
                // authoritative; the original interval remains for traceability.
                // Preserve the original interval guard for overtime.
                if ("COMPENSATORY_OT".equals(document.kind())
                        && (day.isBefore(startDay) || day.isAfter(lastDay))) {
                    throw unavailable("ATTENDANCE_REPORT_LEAVE_DAY_ALLOCATION_INVALID", "请假逐日使用日期超出原单范围，请重新计算所属月份");
                }
                if (day.isBefore(from) || !day.isBefore(endExclusive)
                        || day.isBefore(document.projectionStart()) || !day.isBefore(document.projectionEndExclusive())) continue;
                DocumentDay id = new DocumentDay(document.employeeId(), document.documentId(), day, document.kind());
                Long previous = seen.putIfAbsent(id, item.getValue());
                if (previous != null) {
                    if (!previous.equals(item.getValue())) throw unavailable("ATTENDANCE_REPORT_LEAVE_DAY_ALLOCATION_INVALID", "同一请假逐日使用版本不一致，请重新计算所属月份");
                    continue;
                }
                Key key = new Key(document.employeeId(), YearMonth.from(day), document.kind());
                months.merge(key, item.getValue(), Math::addExact);
            }
        }
        List<MonthlyLeaveUsageRow> result = new ArrayList<>();
        months.entrySet().stream().sorted(java.util.Comparator
                .comparing((Map.Entry<Key,Long> e) -> e.getKey().employee())
                .thenComparing(e -> e.getKey().month()).thenComparing(e -> e.getKey().kind()))
                .forEach(e -> result.add(new MonthlyLeaveUsageRow(e.getKey().employee(),
                        e.getKey().month().getMonthValue(), e.getValue(), e.getKey().kind())));
        return List.copyOf(result);
    }

    private static boolean needsLegacyEvidence(
            OaUsageDocumentRow row, LocalDate from, LocalDate endExclusive) {
        if (row.recognizedMinutesByDateJson() != null || row.recognizedMinutes() <= 0
                || "COMPENSATORY_OT".equals(row.kind()) || row.startAt() == null
                || row.endExclusive() == null || !row.startAt().isBefore(row.endExclusive())
                || row.projectionStart() == null || row.projectionEndExclusive() == null) return false;
        LocalDate start = row.startAt().atZone(SHANGHAI).toLocalDate();
        LocalDate last = row.endExclusive().minusNanos(1).atZone(SHANGHAI).toLocalDate();
        if (last.isBefore(from) || !start.isBefore(endExclusive)
                || !row.projectionStart().isBefore(endExclusive)
                || !from.isBefore(row.projectionEndExclusive())) return false;
        return !YearMonth.from(start).equals(YearMonth.from(last))
                || start.isBefore(from) || !last.isBefore(endExclusive);
    }

    private static Map<LocalDate, Long> uniquelyAttributedLegacyDays(
            OaUsageDocumentRow document, LocalDate startDay, LocalDate lastDay,
            List<LegacyLeaveDayEvidenceRow> evidence) {
        if (!"ANNUAL".equals(document.kind()) && !"TIME_OFF".equals(document.kind())) throw required();
        LocalDate first = startDay.isAfter(document.projectionStart()) ? startDay : document.projectionStart();
        LocalDate end = lastDay.plusDays(1).isBefore(document.projectionEndExclusive())
                ? lastDay.plusDays(1) : document.projectionEndExclusive();
        Map<LocalDate, Long> days = new TreeMap<>();
        long total = 0;
        for (LegacyLeaveDayEvidenceRow row : evidence) {
            if (!document.documentId().equals(row.documentId())
                    || !document.employeeId().equals(row.employeeId())
                    || !document.projectionStart().equals(row.projectionStart())) continue;
            if (row.businessDate() == null || row.businessDate().isBefore(first)
                    || !row.businessDate().isBefore(end) || row.effectiveDocumentCount() != 1
                    || row.leaveMinutes() == null || row.leaveMinutes() < 0 || row.leaveMinutes() > 1440
                    || !matchesDailyType(document.kind(), row.leaveType(), row.leaveMinutes())
                    || days.putIfAbsent(row.businessDate(), row.leaveMinutes()) != null) throw required();
            total = Math.addExact(total, row.leaveMinutes());
        }
        // Validate every original covered date in this pin, not merely dates in
        // the requested subrange. Never infer an absent day or competing form.
        for (LocalDate day = first; day.isBefore(end); day = day.plusDays(1)) {
            if (!days.containsKey(day)) throw required();
        }
        if (total > document.recognizedMinutes()) throw required();
        return days;
    }

    private static boolean matchesDailyType(String kind, String leaveType, long minutes) {
        if (leaveType == null || leaveType.isBlank()) return minutes == 0;
        String value = leaveType.toUpperCase(java.util.Locale.ROOT);
        return "ANNUAL".equals(kind)
                ? List.of("ANNUAL", "ANNUAL_LEAVE", "年假").contains(value)
                : List.of("COMPENSATORY", "TIME_OFF", "调休", "调休假").contains(value);
    }

    private static ApiProblemException required() {
        return unavailable("ATTENDANCE_REPORT_LEAVE_DAY_ALLOCATION_REQUIRED",
                "跨月请假尚缺逐日使用明细，请重新计算所属月份");
    }

    private static ApiProblemException unavailable(String code, String message) {
        return new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
