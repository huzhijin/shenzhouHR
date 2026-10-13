package com.szsemicon.hr.punchimport.infrastructure.excel;

import com.szsemicon.hr.punchimport.application.PunchImportExceptions.UnsafeWorkbookException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Classifies and unpivots the two vendor monthly attendance workbooks into
 * canonical punch rows. Conclusion text and summary columns are discarded.
 */
final class VendorMonthlyWorkbookAdapter {

    static final String MONTHLY_SUMMARY = "MONTHLY_SUMMARY";
    static final String DELI_MONTHLY_REPORT = "DELI_MONTHLY_REPORT";
    static final String UNRECOGNIZED = "UNRECOGNIZED";

    private static final Pattern YY_MM_DD = Pattern.compile("(\\d{2})-(\\d{2})-(\\d{2})");
    private static final Pattern DAY_SLASH = Pattern.compile("^(\\d{1,2})/");
    private static final Pattern TITLE_YEAR_MONTH = Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月");
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.ROOT);

    private VendorMonthlyWorkbookAdapter() {
    }

    static String classify(Workbook workbook) {
        if (workbook.getSheet("考勤打卡导入") != null) {
            return "OFFICIAL_TEMPLATE";
        }
        Sheet sheet = firstSheet(workbook);
        HeaderLayout layout = inspect(sheet);
        return layout == null ? UNRECOGNIZED : layout.kind();
    }

    static List<Map<String, String>> unpivot(Workbook workbook) {
        Sheet sheet = firstSheet(workbook);
        HeaderLayout layout = inspect(sheet);
        if (layout == null) {
            throw new UnsafeWorkbookException(
                    "UNRECOGNIZED_LAYOUT",
                    "无法识别为官方打卡模板、月度汇总表或得力考勤月报");
        }
        List<Map<String, String>> rows = layout.kind().equals(MONTHLY_SUMMARY)
                ? unpivotSummary(sheet, layout)
                : unpivotDeli(sheet, layout);
        if (rows.size() > PunchWorkbookPolicy.DEFAULT_MAX_ROWS) {
            throw new UnsafeWorkbookException("ROW_LIMIT_EXCEEDED", "数据行数超过 50000");
        }
        return List.copyOf(rows);
    }

    private static Sheet firstSheet(Workbook workbook) {
        if (workbook.getNumberOfSheets() < 1) {
            throw new UnsafeWorkbookException("MISSING_HEADER", "工作簿没有工作表");
        }
        return workbook.getSheetAt(0);
    }

    private static HeaderLayout inspect(Sheet sheet) {
        int lastRow = Math.min(sheet.getLastRowNum(), 8);
        for (int headerRow = 0; headerRow <= lastRow; headerRow++) {
            Map<Integer, String> headers = rowValues(sheet, headerRow);
            if (headers.isEmpty()) {
                continue;
            }
            Integer nameCol = findHeader(headers, "姓名");
            Integer numberCol = findHeader(headers, "工号");
            if (nameCol == null || numberCol == null) {
                continue;
            }
            Map<Integer, LocalDate> days = new LinkedHashMap<>();
            YearMonth titleMonth = yearMonthFromSheet(sheet);
            for (Map.Entry<Integer, String> entry : headers.entrySet()) {
                LocalDate date = parseDayHeader(entry.getValue(), titleMonth);
                if (date != null) {
                    days.put(entry.getKey(), date);
                }
            }
            if (days.isEmpty()) {
                continue;
            }
            boolean deli = findHeader(headers, "帐号") != null
                    || findHeader(headers, "账号") != null
                    || headers.values().stream().anyMatch(value -> value.contains("签到"));
            return new HeaderLayout(
                    deli ? DELI_MONTHLY_REPORT : MONTHLY_SUMMARY,
                    headerRow,
                    nameCol,
                    numberCol,
                    findHeader(headers, "部门"),
                    days);
        }
        return null;
    }

    private static List<Map<String, String>> unpivotSummary(
            Sheet sheet,
            HeaderLayout layout) {
        List<Map<String, String>> result = new ArrayList<>();
        int rowIndex = layout.headerRow() + 1;
        int last = sheet.getLastRowNum();
        while (rowIndex <= last) {
            String name = cell(sheet, rowIndex, layout.nameCol());
            String number = cell(sheet, rowIndex, layout.numberCol());
            if (name.isEmpty() && number.isEmpty()) {
                rowIndex++;
                continue;
            }
            int outRow = rowIndex + 1;
            boolean pair = outRow <= last
                    && cell(sheet, outRow, layout.nameCol()).isEmpty()
                    && cell(sheet, outRow, layout.numberCol()).isEmpty();
            extractDayTimes(result, sheet, rowIndex, layout, number, name, "IN");
            if (pair) {
                extractDayTimes(result, sheet, outRow, layout, number, name, "OUT");
                rowIndex += 2;
            } else {
                rowIndex++;
            }
        }
        return result;
    }

    private static List<Map<String, String>> unpivotDeli(
            Sheet sheet,
            HeaderLayout layout) {
        List<Map<String, String>> result = new ArrayList<>();
        int rowIndex = layout.headerRow() + 1;
        int last = sheet.getLastRowNum();
        while (rowIndex <= last) {
            String name = cell(sheet, rowIndex, layout.nameCol());
            String number = cell(sheet, rowIndex, layout.numberCol());
            if (name.isEmpty() && number.isEmpty()) {
                rowIndex++;
                continue;
            }
            int outRow = rowIndex + 1;
            extractDayTimes(result, sheet, rowIndex, layout, number, name, "IN");
            if (outRow <= last
                    && cell(sheet, outRow, layout.nameCol()).isEmpty()
                    && cell(sheet, outRow, layout.numberCol()).isEmpty()) {
                extractDayTimes(result, sheet, outRow, layout, number, name, "OUT");
                rowIndex += 2;
            } else {
                rowIndex++;
            }
        }
        return result;
    }

    private static void extractDayTimes(
            List<Map<String, String>> result,
            Sheet sheet,
            int rowIndex,
            HeaderLayout layout,
            String employeeNumber,
            String name,
            String direction) {
        for (Map.Entry<Integer, LocalDate> day : layout.days().entrySet()) {
            String clock = extractClock(cell(sheet, rowIndex, day.getKey()));
            if (clock == null) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("employeeNumber", employeeNumber.trim());
            row.put("employeeNameForComparison", name.trim());
            row.put("punchTime", day.getValue() + " " + clock + ":00");
            row.put("direction", direction);
            row.put("sourceTimeZone", "Asia/Shanghai");
            row.put("_rowNumber", Integer.toString(rowIndex + 1));
            row.put("_businessDate", day.getValue().toString());
            result.add(row);
        }
    }

    static String extractClock(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()
                || "-".equals(value)
                || "漏刷".equals(value)
                || value.contains("漏刷")) {
            return null;
        }
        Matcher matcher = CLOCK.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        if (hour > 23 || minute > 59) {
            return null;
        }
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    private static LocalDate parseDayHeader(String header, YearMonth titleMonth) {
        Matcher ymd = YY_MM_DD.matcher(header.replace('\n', ' '));
        if (ymd.find()) {
            int year = 2000 + Integer.parseInt(ymd.group(1));
            return LocalDate.of(
                    year,
                    Integer.parseInt(ymd.group(2)),
                    Integer.parseInt(ymd.group(3)));
        }
        Matcher day = DAY_SLASH.matcher(header.trim());
        if (day.find() && titleMonth != null) {
            int dayOfMonth = Integer.parseInt(day.group(1));
            if (dayOfMonth >= 1 && dayOfMonth <= titleMonth.lengthOfMonth()) {
                return titleMonth.atDay(dayOfMonth);
            }
        }
        return null;
    }

    private static YearMonth yearMonthFromSheet(Sheet sheet) {
        for (int row = 0; row <= Math.min(sheet.getLastRowNum(), 4); row++) {
            for (String value : rowValues(sheet, row).values()) {
                Matcher matcher = TITLE_YEAR_MONTH.matcher(value);
                if (matcher.find()) {
                    return YearMonth.of(
                            Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2)));
                }
            }
        }
        return null;
    }

    private static Integer findHeader(Map<Integer, String> headers, String expected) {
        for (Map.Entry<Integer, String> entry : headers.entrySet()) {
            if (entry.getValue().replace("\n", "").trim().equals(expected)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static Map<Integer, String> rowValues(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        Map<Integer, String> values = new LinkedHashMap<>();
        if (row == null) {
            return values;
        }
        short last = row.getLastCellNum();
        for (int column = 0; column < last; column++) {
            String value = cell(sheet, rowIndex, column);
            if (!value.isEmpty()) {
                values.put(column, value);
            }
        }
        return values;
    }

    private static String cell(Sheet sheet, int rowIndex, int columnIndex) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            return "";
        }
        Cell cell = row.getCell(columnIndex);
        return cell == null ? "" : FORMATTER.formatCellValue(cell).trim();
    }

    private record HeaderLayout(
            String kind,
            int headerRow,
            int nameCol,
            int numberCol,
            Integer departmentCol,
            Map<Integer, LocalDate> days) {
    }
}
