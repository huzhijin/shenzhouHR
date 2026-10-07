package com.szsemicon.hr.evidenceingestion.application.paper;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaperOvertimeFormParser {

    private static final Pattern NAME = Pattern.compile(
            "加班人员[:：]?\\s*([\\u4e00-\\u9fa5A-Za-z](?:\\s*[\\u4e00-\\u9fa5A-Za-z]){1,7}?)(?=\\s|共|部门|人|$)");
    private static final Pattern LINE_NAME = Pattern.compile(
            "(?:1[.、．]\\s*)?姓名[:：]?\\s*([\\u4e00-\\u9fa5A-Za-z]{2,12})");
    private static final Pattern DEPARTMENT = Pattern.compile(
            "部门[:：]?\\s*([A-Za-z]{1,12}\\s*\\d{0,6}|[\\u4e00-\\u9fa5]{2,20}|[A-Za-z0-9]{2,20})");
    private static final Pattern PAID_AFTER_HOURS = Pattern.compile(
            "共[\\d.]+\\s*小时\\s*.{0,6}加班费");
    private static final Pattern DATE = Pattern.compile(
            "(?:加班日期|申请日期|实际加班)[:：]?\\s*(\\d{1,4})[.\\-/年](\\d{1,2})(?:[.\\-/月](\\d{1,2})日?)?");
    private static final Pattern CHINESE_DATE = Pattern.compile(
            "(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(\\d{1,2})[:：.](\\d{2})\\s*时?\\s*[至到\\-–~～]\\s*(?:\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日\\s*)?(\\d{1,2})[:：.](\\d{2})");
    private static final Pattern REASON = Pattern.compile(
            "加班(?:事由|原因)[:：]?\\s*([^\\n]{1,200})");
    private static final Pattern CHECKED = Pattern.compile("[☑✓✔√☒■●]");

    private PaperOvertimeFormParser() {
    }

    public static Draft parse(String ocrText, LocalDate today) {
        String text = ocrText == null ? "" : ocrText;
        String name = compactPersonName(first(NAME, text));
        if (name == null) {
            name = compactPersonName(first(LINE_NAME, text));
        }
        String department = compactDepartment(first(DEPARTMENT, text));
        LocalDate date = parseDate(text, today);
        LocalTime[] times = parseTimes(text);
        String reason = first(REASON, text);
        OvertimeType type = parseType(text);
        return new Draft(name, department, date, times[0], times[1], type, reason);
    }

    public static OvertimeType parseType(String text) {
        List<OvertimeType> marked = new ArrayList<>();
        if (markedNear(text, "加班费") || markedNear(text, "现金")) {
            marked.add(OvertimeType.PAID);
        }
        if (markedNear(text, "调休")) {
            marked.add(OvertimeType.COMPENSATORY);
        }
        if (markedNear(text, "义务加班")) {
            marked.add(OvertimeType.VOLUNTARY);
        }
        if (marked.size() == 1) {
            return marked.getFirst();
        }
        if (PAID_AFTER_HOURS.matcher(text).find()) {
            return OvertimeType.PAID;
        }
        return null;
    }

    private static boolean markedNear(String text, String label) {
        int index = text.indexOf(label);
        if (index < 0) {
            return false;
        }
        int from = Math.max(0, index - 3);
        int to = Math.min(text.length(), index + label.length() + 3);
        return CHECKED.matcher(text.substring(from, to)).find();
    }

    private static LocalDate parseDate(String text, LocalDate today) {
        Matcher chinese = CHINESE_DATE.matcher(text);
        if (chinese.find()) {
            return LocalDate.of(
                    today.getYear(),
                    Integer.parseInt(chinese.group(1)),
                    Integer.parseInt(chinese.group(2)));
        }
        Matcher matcher = DATE.matcher(text);
        if (!matcher.find()) {
            Matcher shortDate = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})").matcher(text);
            if (shortDate.find()) {
                return LocalDate.of(
                        today.getYear(),
                        Integer.parseInt(shortDate.group(1)),
                        Integer.parseInt(shortDate.group(2)));
            }
            return null;
        }
        int first = Integer.parseInt(matcher.group(1));
        int second = Integer.parseInt(matcher.group(2));
        if (matcher.group(3) == null) {
            return LocalDate.of(today.getYear(), first, second);
        }
        int year = first < 100 ? Year.now().getValue() : first;
        return LocalDate.of(year, second, Integer.parseInt(matcher.group(3)));
    }

    private static LocalTime[] parseTimes(String text) {
        Matcher matcher = TIME_RANGE.matcher(text);
        if (!matcher.find()) {
            return new LocalTime[] {null, null};
        }
        return new LocalTime[] {
                LocalTime.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2))),
                LocalTime.of(
                        Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(4)))
        };
    }

    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String compactPersonName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String compact = value.replaceAll("\\s+", "");
        if (compact.length() < 2) {
            return null;
        }
        return compact;
    }

    private static String compactDepartment(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replaceAll("\\s+", "");
    }

    public record Draft(
            String name,
            String department,
            LocalDate overtimeDate,
            LocalTime start,
            LocalTime end,
            OvertimeType overtimeType,
            String reason) {
    }
}
