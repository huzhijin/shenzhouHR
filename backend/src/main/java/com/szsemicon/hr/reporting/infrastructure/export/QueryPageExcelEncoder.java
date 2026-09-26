package com.szsemicon.hr.reporting.infrastructure.export;

import com.szsemicon.hr.reporting.application.AttendanceReportQueryPageService.QueryPage;
import com.szsemicon.hr.reporting.application.CustomerReportLegendColors;
import com.szsemicon.hr.reporting.application.DepartmentPathNames;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class QueryPageExcelEncoder {

    static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(BUSINESS_ZONE);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
                    .withZone(BUSINESS_ZONE);
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final String[] WEEKDAYS = {
            "一", "二", "三", "四", "五", "六", "日"};

    public record WorkbookFile(
            String fileName, String contentType, byte[] content) {
    }

    public WorkbookFile encode(QueryPage page) {
        if (page == null) {
            throw new IllegalArgumentException("query page is required");
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            if ("matrix".equals(page.sheet())) {
                writeMatrix(workbook.createSheet(sheetName(page)), page, styles);
            } else if (isFinanceOvertimeSheet(page.sheet())) {
                writeFinanceOvertime(
                        workbook.createSheet(sheetName(page)), page, styles);
            } else if ("work-hours".equals(page.sheet())) {
                writeWorkHours(
                        workbook.createSheet(sheetName(page)), page, styles);
            } else if ("absence-stat".equals(page.sheet())
                    || "leave-stat".equals(page.sheet())) {
                writeMetricStat(
                        workbook.createSheet(sheetName(page)), page, styles);
            } else {
                writeTable(workbook.createSheet(sheetName(page)), page, styles);
            }
            workbook.write(output);
            return new WorkbookFile(
                    fileName(page), CONTENT_TYPE, output.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "query report workbook generation failed", exception);
        }
    }

    private static void writeTable(Sheet sheet, QueryPage page, Styles styles) {
        List<Column> columns = columns(page.sheet());
        Row header = sheet.createRow(0);
        for (int index = 0; index < columns.size(); index++) {
            Cell cell = header.createCell(index);
            cell.setCellValue(columns.get(index).title());
            cell.setCellStyle(styles.header);
            sheet.setColumnWidth(index, columnWidth(columns.get(index).title()));
        }
        List<Map<String, Object>> rows = page.rows();
        if (rows.isEmpty()) {
            Row empty = sheet.createRow(1);
            empty.createCell(0).setCellValue(
                    page.hint() == null ? "当前筛选条件下没有记录" : page.hint());
        }
        int deptCol = departmentColumn(columns);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> source = rows.get(i);
            Row row = sheet.createRow(i + 1);
            int lines = 1;
            for (int column = 0; column < columns.size(); column++) {
                Cell cell = row.createCell(column);
                String text = formatCell(
                        page.sheet(), columns.get(column), source);
                if (column == deptCol) {
                    String wrapped = DepartmentPathNames.excelWrappedDepartment(
                            text);
                    cell.setCellValue(safe(wrapped));
                    cell.setCellStyle(styles.department);
                    lines = Math.max(lines, wrapped.split("\n", -1).length);
                } else {
                    cell.setCellValue(safe(text));
                    cell.setCellStyle(styles.body);
                }
            }
            row.setHeightInPoints(Math.max(18, 14 * lines));
        }
        sheet.createFreezePane(0, 1);
        if (!columns.isEmpty()) {
            sheet.setAutoFilter(new CellRangeAddress(
                    0, 0, 0, columns.size() - 1));
        }
    }

    private static void writeMatrix(Sheet sheet, QueryPage page, Styles styles) {
        LocalDate from = LocalDate.parse(page.fromDate());
        LocalDate to = LocalDate.parse(page.toDate());
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusDays(1)) {
            dates.add(cursor);
        }
        Row header = sheet.createRow(0);
        String[] identity = {"工号", "姓名", "部门", "签到/签退"};
        for (int index = 0; index < identity.length; index++) {
            Cell cell = header.createCell(index);
            cell.setCellValue(identity[index]);
            cell.setCellStyle(styles.header);
            sheet.setColumnWidth(index, index == 2 ? 22 * 256 : 12 * 256);
        }
        for (int d = 0; d < dates.size(); d++) {
            LocalDate date = dates.get(d);
            Cell cell = header.createCell(4 + d);
            cell.setCellValue(String.format(
                    "%02d日/周%s", date.getDayOfMonth(), weekday(date)));
            cell.setCellStyle(styles.header);
            sheet.setColumnWidth(4 + d, 12 * 256);
        }
        int rowIndex = 1;
        for (Map<String, Object> employee : page.rows()) {
            Map<LocalDate, Map<String, Object>> byDate = daysByDate(employee);
            Row inRow = sheet.createRow(rowIndex);
            Row outRow = sheet.createRow(rowIndex + 1);
            writeIdentityPair(inRow, outRow, employee, styles);
            writeText(inRow, 3, "签到", styles.body);
            writeText(outRow, 3, "签退", styles.body);
            int lines = 2;
            String wrapped = DepartmentPathNames.excelWrappedDepartment(
                    visibleDepartment(employee.get("department")));
            lines = Math.max(lines, wrapped.split("\n", -1).length);
            for (int d = 0; d < dates.size(); d++) {
                Map<String, Object> day = byDate.get(dates.get(d));
                Slot in = signIn(day);
                Slot out = signOut(day);
                writeSlot(inRow, 4 + d, in, styles);
                writeSlot(outRow, 4 + d, out, styles);
            }
            inRow.setHeightInPoints(Math.max(18, 14 * lines));
            outRow.setHeightInPoints(Math.max(18, 14 * lines));
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex + 1, 0, 0));
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex + 1, 1, 1));
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex + 1, 2, 2));
            rowIndex += 2;
        }
        if (page.rows().isEmpty()) {
            Row empty = sheet.createRow(1);
            empty.createCell(0).setCellValue(
                    page.hint() == null ? "当前筛选条件下没有记录" : page.hint());
        }
        sheet.createFreezePane(4, 1);
    }

    private static void writeFinanceOvertime(
            Sheet sheet, QueryPage page, Styles styles) {
        LocalDate from = LocalDate.parse(page.fromDate());
        LocalDate to = LocalDate.parse(page.toDate());
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusDays(1)) {
            dates.add(cursor);
        }
        List<String> identity = financeOvertimeIdentity(page.sheet());
        Row top = sheet.createRow(0);
        Row sub = sheet.createRow(1);
        for (int i = 0; i < identity.size(); i++) {
            writeText(top, i, identity.get(i), styles.header);
            writeText(sub, i, "", styles.header);
            sheet.addMergedRegion(new CellRangeAddress(0, 1, i, i));
            sheet.setColumnWidth(i, i == 0 ? 22 * 256 : 12 * 256);
        }
        for (int d = 0; d < dates.size(); d++) {
            LocalDate date = dates.get(d);
            int col = identity.size() + d;
            writeText(
                    top,
                    col,
                    date.getMonthValue() + "月" + date.getDayOfMonth() + "日",
                    styles.header);
            writeText(
                    sub,
                    col,
                    String.valueOf(date.getDayOfWeek().getValue()),
                    styles.header);
            sheet.setColumnWidth(col, 8 * 256);
        }
        if (page.rows().isEmpty()) {
            Row empty = sheet.createRow(2);
            empty.createCell(0).setCellValue(
                    page.hint() == null ? "当前筛选条件下没有记录" : page.hint());
            sheet.createFreezePane(identity.size(), 2);
            return;
        }
        int rowIndex = 2;
        List<Integer>[] dateColumns = overtimeDateColumns(
                dates, page.rows(), identity.size());
        List<Integer> weekdayCols = dateColumns[0];
        List<Integer> weekendCols = dateColumns[1];
        List<Integer> holidayCols = dateColumns[2];
        boolean paidCol = identity.contains("加班费");
        boolean compensatoryCol = identity.contains("转调休");
        boolean voluntaryCol = identity.contains("义务加班");
        for (Map<String, Object> employee : page.rows()) {
            Map<LocalDate, Map<String, Object>> byDate = daysByDate(employee);
            Row row = sheet.createRow(rowIndex++);
            String wrapped = DepartmentPathNames.excelWrappedDepartment(
                    visibleDepartment(employee.get("department")));
            Cell dept = row.createCell(0);
            dept.setCellValue(safe(wrapped));
            dept.setCellStyle(styles.department);
            writeText(row, 1, text(employee.get("employeeNumber")), styles.identity);
            writeText(row, 2, text(employee.get("employeeName")), styles.identity);
            int feeCol = 6;
            if (paidCol) {
                writeHours(
                        row, feeCol++,
                        numberDouble(employee.get("paidOvertimeHours")), false, styles);
            }
            if (compensatoryCol) {
                writeHours(
                        row, feeCol++,
                        numberDouble(employee.get("compensatoryOvertimeHours")), false, styles);
            }
            if (voluntaryCol) {
                writeHours(
                        row, feeCol,
                        numberDouble(employee.get("voluntaryOvertimeHours")), false, styles);
            }
            int lines = Math.max(1, wrapped.split("\n", -1).length);
            for (int d = 0; d < dates.size(); d++) {
                Map<String, Object> day = byDate.get(dates.get(d));
                double hours = day == null ? 0 : numberDouble(day.get("hours"));
                writeOvertimeHours(
                        row,
                        identity.size() + d,
                        hours,
                        true,
                        day == null ? "" : text(day.get("treatment")),
                        styles);
            }
            int excelRow = row.getRowNum() + 1;
            writeFormulaHours(
                    row, 3, sumFormula(weekdayCols, excelRow), styles);
            writeFormulaHours(
                    row, 4, sumFormula(weekendCols, excelRow), styles);
            writeFormulaHours(
                    row, 5, sumFormula(holidayCols, excelRow), styles);
            row.setHeightInPoints(Math.max(18, 14 * lines));
        }
        int firstDataRow = 3;
        int lastDataRow = rowIndex;
        Row total = sheet.createRow(rowIndex);
        writeText(total, 0, "总计", styles.header);
        writeText(total, 1, "", styles.header);
        writeText(total, 2, "", styles.header);
        for (int column = 3; column < identity.size() + dates.size(); column++) {
            writeFormulaHours(
                    total,
                    column,
                    "SUM(" + excelColumn(column) + firstDataRow
                            + ":" + excelColumn(column) + lastDataRow + ")",
                    styles);
        }
        sheet.createFreezePane(identity.size(), 2);
    }

    private static boolean isFinanceOvertimeSheet(String sheet) {
        return "finance-overtime".equals(sheet)
                || "overtime-fee-daily".equals(sheet)
                || "overtime-comp-daily".equals(sheet)
                || "overtime-voluntary-daily".equals(sheet);
    }

    private static List<String> financeOvertimeIdentity(String sheet) {
        List<String> identity = new ArrayList<>(List.of(
                "部门", "工号", "加班人", "平时加班", "周末加班", "节假日加班"));
        if ("overtime-fee-daily".equals(sheet)) {
            identity.add("加班费");
        } else if ("overtime-comp-daily".equals(sheet)) {
            identity.add("转调休");
        } else if ("overtime-voluntary-daily".equals(sheet)) {
            identity.add("义务加班");
        } else {
            identity.add("加班费");
            identity.add("转调休");
            identity.add("义务加班");
        }
        return identity;
    }

    private static List<Integer>[] overtimeDateColumns(
            List<LocalDate> dates, List<Map<String, Object>> employees) {
        return overtimeDateColumns(dates, employees, 9);
    }

    private static List<Integer>[] overtimeDateColumns(
            List<LocalDate> dates,
            List<Map<String, Object>> employees,
            int dateStart) {
        String[] buckets = new String[dates.size()];
        for (Map<String, Object> employee : employees) {
            Map<LocalDate, Map<String, Object>> byDate = daysByDate(employee);
            for (int d = 0; d < dates.size(); d++) {
                Map<String, Object> day = byDate.get(dates.get(d));
                if (day == null) {
                    continue;
                }
                String type = text(day.get("dayType"));
                if (!type.isBlank()) {
                    buckets[d] = overtimeDateBucket(dates.get(d), type);
                }
            }
        }
        List<Integer> weekday = new ArrayList<>();
        List<Integer> weekend = new ArrayList<>();
        List<Integer> holiday = new ArrayList<>();
        for (int d = 0; d < dates.size(); d++) {
            String bucket = buckets[d] == null
                    ? overtimeDateBucket(dates.get(d), "")
                    : buckets[d];
            int column = dateStart + d;
            switch (bucket) {
                case "weekend" -> weekend.add(column);
                case "holiday" -> holiday.add(column);
                default -> weekday.add(column);
            }
        }
        @SuppressWarnings("unchecked")
        List<Integer>[] grouped = new List[] {weekday, weekend, holiday};
        return grouped;
    }

    private static String overtimeDateBucket(LocalDate date, String dayType) {
        String type = dayType == null ? "" : dayType.toUpperCase(Locale.ROOT);
        if (type.contains("HOLIDAY")) {
            return "holiday";
        }
        if (type.equals("SATURDAY")
                || type.equals("SUNDAY")
                || type.equals("WEEKEND")) {
            return "weekend";
        }
        if (!type.isBlank()) {
            return "weekday";
        }
        DayOfWeek weekday = date.getDayOfWeek();
        return weekday == DayOfWeek.SATURDAY || weekday == DayOfWeek.SUNDAY
                ? "weekend"
                : "weekday";
    }

    private static void writeWorkHours(
            Sheet sheet, QueryPage page, Styles styles) {
        String[] headers = {
                "工号", "姓名", "部门",
                "应出勤工时", "加班时数", "义务加班",
                "事假+病假+其他假期", "年假", "加班换调休", "实际调休",
                "个人实际出勤工时", "备注"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            writeText(header, i, headers[i], styles.header);
            sheet.setColumnWidth(i, i == 2 ? 22 * 256 : 14 * 256);
        }
        if (page.rows().isEmpty()) {
            Row empty = sheet.createRow(1);
            empty.createCell(0).setCellValue(
                    page.hint() == null ? "当前筛选条件下没有记录" : page.hint());
            sheet.createFreezePane(0, 1);
            return;
        }
        int rowIndex = 1;
        for (Map<String, Object> source : page.rows()) {
            Row row = sheet.createRow(rowIndex++);
            String wrapped = DepartmentPathNames.excelWrappedDepartment(
                    visibleDepartment(source.get("department")));
            writeText(row, 0, text(source.get("employeeNumber")), styles.identity);
            writeText(row, 1, text(source.get("employeeName")), styles.identity);
            Cell dept = row.createCell(2);
            dept.setCellValue(safe(wrapped));
            dept.setCellStyle(styles.department);
            writeHours(row, 3, numberDouble(source.get("scheduledHours")), false, styles);
            writeHours(row, 4, numberDouble(source.get("paidOvertimeHours")), false, styles);
            writeHours(row, 5, numberDouble(source.get("voluntaryOvertimeHours")), false, styles);
            writeHours(row, 6, numberDouble(source.get("leaveHours")), false, styles);
            writeHours(row, 7, numberDouble(source.get("annualLeaveHours")), false, styles);
            writeHours(row, 8, numberDouble(source.get("compensatoryOvertimeHours")), false, styles);
            writeHours(row, 9, numberDouble(source.get("timeOffHours")), false, styles);
            int excelRow = row.getRowNum() + 1;
            writeFormulaHours(
                    row,
                    10,
                    excelColumn(3) + excelRow
                            + "+" + excelColumn(4) + excelRow
                            + "+" + excelColumn(5) + excelRow
                            + "-" + excelColumn(6) + excelRow
                            + "-" + excelColumn(7) + excelRow
                            + "+" + excelColumn(8) + excelRow
                            + "-" + excelColumn(9) + excelRow,
                    styles);
            writeText(row, 11, noteOrBlank(source.get("note")), styles.body);
            row.setHeightInPoints(Math.max(18, 14 * Math.max(1, wrapped.split("\n", -1).length)));
        }
        int firstDataRow = 2;
        int lastDataRow = rowIndex;
        Row total = sheet.createRow(rowIndex);
        writeText(total, 0, "总计", styles.header);
        writeText(total, 1, "", styles.header);
        writeText(total, 2, "", styles.header);
        for (int column = 3; column <= 10; column++) {
            writeFormulaHours(
                    total,
                    column,
                    "SUM(" + excelColumn(column) + firstDataRow
                            + ":" + excelColumn(column) + lastDataRow + ")",
                    styles);
        }
        writeText(total, 11, "", styles.header);
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, headers.length - 1));
    }

    private static String noteOrBlank(Object value) {
        String note = text(value);
        return note.isBlank() || "—".equals(note) ? "" : note;
    }

    private static void writeMetricStat(
            Sheet sheet, QueryPage page, Styles styles) {
        LocalDate from = LocalDate.parse(page.fromDate());
        LocalDate to = LocalDate.parse(page.toDate());
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusDays(1)) {
            dates.add(cursor);
        }
        boolean absence = "absence-stat".equals(page.sheet());
        String totalHeader = absence ? "合计旷工" : "合计请假";
        String[] identity = {"部门", "工号", "姓名", totalHeader};
        Row top = sheet.createRow(0);
        Row sub = sheet.createRow(1);
        for (int i = 0; i < identity.length; i++) {
            writeText(top, i, identity[i], styles.header);
            writeText(sub, i, "", styles.header);
            sheet.addMergedRegion(new CellRangeAddress(0, 1, i, i));
            sheet.setColumnWidth(i, i == 0 ? 22 * 256 : 12 * 256);
        }
        for (int d = 0; d < dates.size(); d++) {
            LocalDate date = dates.get(d);
            int col = identity.length + d;
            writeText(
                    top,
                    col,
                    date.getMonthValue() + "月" + date.getDayOfMonth() + "日",
                    styles.header);
            writeText(
                    sub,
                    col,
                    String.valueOf(date.getDayOfWeek().getValue()),
                    styles.header);
            sheet.setColumnWidth(col, 8 * 256);
        }
        if (page.rows().isEmpty()) {
            Row empty = sheet.createRow(2);
            empty.createCell(0).setCellValue(
                    page.hint() == null ? "当前筛选条件下没有记录" : page.hint());
            sheet.createFreezePane(identity.length, 2);
            return;
        }
        int rowIndex = 2;
        double totalHours = 0;
        double[] dayTotals = new double[dates.size()];
        String totalKey = absence ? "absenceHours" : "leaveHours";
        for (Map<String, Object> employee : page.rows()) {
            Map<LocalDate, Map<String, Object>> byDate = daysByDate(employee);
            Row row = sheet.createRow(rowIndex++);
            String wrapped = DepartmentPathNames.excelWrappedDepartment(
                    visibleDepartment(employee.get("department")));
            Cell dept = row.createCell(0);
            dept.setCellValue(safe(wrapped));
            dept.setCellStyle(styles.department);
            writeText(row, 1, text(employee.get("employeeNumber")), styles.identity);
            writeText(row, 2, text(employee.get("employeeName")), styles.identity);
            double hours = numberDouble(employee.get(totalKey));
            writeHours(row, 3, hours, false, styles);
            totalHours += hours;
            int lines = Math.max(1, wrapped.split("\n", -1).length);
            for (int d = 0; d < dates.size(); d++) {
                Map<String, Object> day = byDate.get(dates.get(d));
                double cellHours = day == null ? 0 : numberDouble(day.get("hours"));
                String tone = absence || day == null
                        ? null
                        : leaveTone(text(day.get("dayType")));
                writeMetricHours(row, identity.length + d, cellHours, tone, styles);
                dayTotals[d] += cellHours;
            }
            row.setHeightInPoints(Math.max(18, 14 * lines));
        }
        Row total = sheet.createRow(rowIndex);
        writeText(total, 0, "总计", styles.header);
        writeText(total, 1, "", styles.header);
        writeText(total, 2, "", styles.header);
        writeHours(total, 3, totalHours, false, styles);
        for (int d = 0; d < dates.size(); d++) {
            writeHours(total, identity.length + d, dayTotals[d], true, styles);
        }
        sheet.createFreezePane(identity.length, 2);
    }

    private static void writeMetricHours(
            Row row, int column, double hours, String hex, Styles styles) {
        Cell cell = row.createCell(column);
        if (hours == 0) {
            cell.setCellValue("");
            cell.setCellStyle(hex == null ? styles.body : styles.fill(hex));
            return;
        }
        cell.setCellValue(hours);
        cell.setCellStyle(hex == null ? styles.hours : styles.fill(hex));
    }

    private static void writeHours(
            Row row, int column, double hours, boolean blankIfZero, Styles styles) {
        writeOvertimeHours(row, column, hours, blankIfZero, "", styles);
    }

    private static void writeFormulaHours(
            Row row, int column, String formula, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellFormula(formula);
        cell.setCellStyle(styles.hours);
    }

    private static String sumFormula(List<Integer> columns, int excelRow) {
        if (columns == null || columns.isEmpty()) {
            return "0";
        }
        List<String> parts = new ArrayList<>();
        int start = columns.getFirst();
        int prev = start;
        for (int i = 1; i < columns.size(); i++) {
            int column = columns.get(i);
            if (column == prev + 1) {
                prev = column;
                continue;
            }
            parts.add(cellRange(start, prev, excelRow));
            start = prev = column;
        }
        parts.add(cellRange(start, prev, excelRow));
        if (parts.size() == 1 && !parts.getFirst().contains(":")) {
            return parts.getFirst();
        }
        return "SUM(" + String.join(",", parts) + ")";
    }

    private static String cellRange(int fromColumn, int toColumn, int excelRow) {
        String start = excelColumn(fromColumn) + excelRow;
        if (fromColumn == toColumn) {
            return start;
        }
        return start + ":" + excelColumn(toColumn) + excelRow;
    }

    private static String excelColumn(int index) {
        StringBuilder letters = new StringBuilder();
        int remaining = index + 1;
        while (remaining > 0) {
            int modulo = (remaining - 1) % 26;
            letters.insert(0, (char) ('A' + modulo));
            remaining = (remaining - 1) / 26;
        }
        return letters.toString();
    }

    private static void writeOvertimeHours(
            Row row,
            int column,
            double hours,
            boolean blankIfZero,
            String treatment,
            Styles styles) {
        Cell cell = row.createCell(column);
        if (blankIfZero && hours == 0) {
            cell.setCellValue("");
            cell.setCellStyle(styles.body);
            return;
        }
        cell.setCellValue(hours);
        String fill = overtimeFillHex(treatment);
        if (fill == null) {
            cell.setCellStyle(styles.hours);
            return;
        }
        cell.setCellStyle(styles.hoursFill(fill, overtimeFontHex(treatment)));
    }

    private static String overtimeFillHex(String treatment) {
        return switch (treatment == null ? "" : treatment) {
            case "PAID" -> "3F8850";
            case "COMPENSATORY" -> "F1B83D";
            case "VOLUNTARY" -> "6B4E9B";
            default -> null;
        };
    }

    private static String overtimeFontHex(String treatment) {
        return "COMPENSATORY".equals(treatment) ? "24344D" : "FFFFFF";
    }

    private static void writeIdentityPair(
            Row inRow, Row outRow, Map<String, Object> employee, Styles styles) {
        writeText(inRow, 0, text(employee.get("employeeNumber")), styles.identity);
        writeText(inRow, 1, text(employee.get("employeeName")), styles.identity);
        String wrapped = DepartmentPathNames.excelWrappedDepartment(
                visibleDepartment(employee.get("department")));
        Cell dept = inRow.createCell(2);
        dept.setCellValue(safe(wrapped));
        dept.setCellStyle(styles.department);
        writeText(outRow, 0, "", styles.identity);
        writeText(outRow, 1, "", styles.identity);
        Cell deptOut = outRow.createCell(2);
        deptOut.setCellValue("");
        deptOut.setCellStyle(styles.department);
    }

    private static void writeSlot(Row row, int column, Slot slot, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(safe(slot.text()));
        if (slot.fontHex() != null) {
            cell.setCellStyle(styles.fontFill(slot.hex(), slot.fontHex()));
        } else {
            cell.setCellStyle(slot.hex() == null ? styles.wrap : styles.fill(slot.hex()));
        }
    }

    private static void writeText(
            Row row, int column, String value, XSSFCellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(safe(value));
        cell.setCellStyle(style);
    }

    private static Map<LocalDate, Map<String, Object>> daysByDate(
            Map<String, Object> employee) {
        Map<LocalDate, Map<String, Object>> byDate = new LinkedHashMap<>();
        Object days = employee.get("days");
        if (!(days instanceof Collection<?> collection)) {
            return byDate;
        }
        for (Object item : collection) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> day = new LinkedHashMap<>();
            raw.forEach((key, value) -> day.put(String.valueOf(key), value));
            LocalDate date = asDate(day.get("date"));
            if (date != null) {
                byDate.put(date, day);
            }
        }
        return byDate;
    }

    private static Slot signIn(Map<String, Object> day) {
        if (day == null) {
            return Slot.empty();
        }
        String leave = leaveLabel(text(day.get("leaveType")));
        if (!leave.isBlank()) {
            return new Slot(leave, leaveTone(text(day.get("leaveType"))));
        }
        String punch = punch(day.get("firstPunchAt"));
        if (number(day.get("lateMinutes")) > 0 && !punch.isBlank()) {
            return new Slot(punch + " 迟到", CustomerReportLegendColors.hexForTone("LATE"));
        }
        if (number(day.get("missingPunches")) > 0 && punch.isBlank()) {
            return new Slot("漏刷", CustomerReportLegendColors.hexForTone("MISSING_PUNCH"));
        }
        if (!punch.isBlank()) {
            return new Slot(punch, overtimeTone(day, true));
        }
        return new Slot("", restTone(day));
    }

    private static Slot signOut(Map<String, Object> day) {
        if (day == null) {
            return Slot.empty();
        }
        String leave = leaveLabel(text(day.get("leaveType")));
        if (!leave.isBlank()) {
            return new Slot(leave, leaveTone(text(day.get("leaveType"))));
        }
        String punch = punch(day.get("lastPunchAt"));
        if (number(day.get("earlyMinutes")) > 0 && !punch.isBlank()) {
            return new Slot(punch + " 早退",
                    CustomerReportLegendColors.hexForTone("EARLY_DEPARTURE"));
        }
        if (number(day.get("missingPunches")) > 0 && punch.isBlank()) {
            return new Slot("漏刷", CustomerReportLegendColors.hexForTone("MISSING_PUNCH"));
        }
        if (!punch.isBlank()) {
            return new Slot(punch, overtimeTone(day, false));
        }
        return new Slot("", restTone(day));
    }

    private static String overtimeTone(Map<String, Object> day, boolean morning) {
        if (number(day.get("paidOvertimeMinutes")) > 0
                || number(day.get("compensatoryOvertimeMinutes")) > 0) {
            return CustomerReportLegendColors.hexForTone("RECOGNIZED_OVERTIME");
        }
        return restTone(day);
    }

    private static String restTone(Map<String, Object> day) {
        String type = text(day.get("dayType")).toUpperCase(Locale.ROOT);
        if (type.contains("SATURDAY")
                || type.contains("SUNDAY")
                || type.contains("HOLIDAY")
                || type.contains("REST")) {
            return CustomerReportLegendColors.hexForTone("REST_DAY");
        }
        return null;
    }

    private static String leaveTone(String raw) {
        String key = raw == null ? "" : raw.toUpperCase(Locale.ROOT);
        if (key.contains("ANNUAL")) {
            return CustomerReportLegendColors.hexForTone("ANNUAL_LEAVE");
        }
        if (key.contains("SICK")) {
            return CustomerReportLegendColors.hexForTone("SICK_LEAVE");
        }
        if (key.contains("PERSONAL")) {
            return CustomerReportLegendColors.hexForTone("PERSONAL_LEAVE");
        }
        if (key.contains("MARRIAGE")) {
            return CustomerReportLegendColors.hexForTone("MARRIAGE_LEAVE");
        }
        if (key.contains("MATERNITY")) {
            return CustomerReportLegendColors.hexForTone("MATERNITY_LEAVE");
        }
        if (key.contains("PATERNITY")) {
            return CustomerReportLegendColors.hexForTone("PATERNITY_LEAVE");
        }
        if (key.contains("BEREAVEMENT")) {
            return CustomerReportLegendColors.hexForTone("BEREAVEMENT_LEAVE");
        }
        if (key.contains("COMPENSATORY") || key.contains("TIME_OFF")) {
            return CustomerReportLegendColors.hexForTone("TIME_OFF");
        }
        if (key.contains("OUTING") || "OUT".equals(key)) {
            return CustomerReportLegendColors.hexForTone("OUTING");
        }
        if (key.contains("TRIP") || key.contains("TRAVEL")) {
            return CustomerReportLegendColors.hexForTone("TRIP");
        }
        if (key.contains("WORK_INJURY")) {
            return CustomerReportLegendColors.hexForTone("WORK_INJURY_LEAVE");
        }
        return null;
    }

    private static String leaveLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return LEAVE_TYPES.getOrDefault(raw.toUpperCase(Locale.ROOT), raw);
    }

    private static List<Column> columns(String sheet) {
        List<Column> identity = List.of(
                new Column("employeeNumber", "工号"),
                new Column("employeeName", "姓名"),
                new Column("department", "部门"));
        return switch (sheet) {
            case "exceptions" -> List.of(
                    new Column("businessDate", "考勤日期"),
                    new Column("exceptionType", "异常类型"),
                    new Column("employeeNumber", "工号"),
                    new Column("employeeName", "姓名"),
                    new Column("department", "部门"),
                    new Column("details", "详情"),
                    new Column("state", "处理状态"));
            case "leave" -> concat(identity, List.of(
                    new Column("leaveType", "假别"),
                    new Column("startAt", "开始时间"),
                    new Column("endAt", "结束时间"),
                    new Column("hours", "小时"),
                    new Column("approvalState", "审批状态")));
            case "overtime" -> concat(identity, List.of(
                    new Column("leaveType", "加班方式"),
                    new Column("startAt", "开始时间"),
                    new Column("endAt", "结束时间"),
                    new Column("hours", "小时"),
                    new Column("approvalState", "审批状态"),
                    new Column("sourceOrigin", "来源")));
            case "makeup" -> concat(identity, List.of(
                    new Column("documentType", "单据类型"),
                    new Column("startAt", "补签时间"),
                    new Column("approvalState", "审批状态")));
            case "work-hours" -> concat(identity, List.of(
                    new Column("scheduledHours", "应出勤工时"),
                    new Column("paidOvertimeHours", "加班时数"),
                    new Column("voluntaryOvertimeHours", "义务加班"),
                    new Column("leaveHours", "事假+病假+其他假期"),
                    new Column("annualLeaveHours", "年假"),
                    new Column("compensatoryOvertimeHours", "加班换调休"),
                    new Column("timeOffHours", "实际调休"),
                    new Column("actualHours", "个人实际出勤工时"),
                    new Column("note", "备注")));
            case "late" -> concat(identity, List.of(
                    new Column("lateEvents", "迟到次数"),
                    new Column("lateMinutes", "迟到分钟"),
                    new Column("penalizedLateMinutes", "计罚迟到分钟")));
            case "missed-punch" -> concat(identity, List.of(
                    new Column("businessDate", "考勤日期"),
                    new Column("exceptionType", "缺卡类型"),
                    new Column("details", "说明")));
            case "missed-punch-stat" -> List.of(
                    new Column("sequence", "序号"),
                    new Column("department", "部门"),
                    new Column("employeeName", "姓名"),
                    new Column("missedCount", "次数"),
                    new Column("remark", "备注"));
            case "annual-leave-stat" -> annualLeaveStatColumns();
            case "time-off-stat" -> timeOffStatColumns();
            case "attendance-rate" -> concat(identity, List.of(
                    new Column("scheduledDays", "应出勤天数"),
                    new Column("actualDays", "实际出勤天数"),
                    new Column("attendanceRate", "出勤率")));
            case "annual-leave", "time-off" -> concat(identity, List.of(
                    new Column("accountType", "账户类型"),
                    new Column("openingHours", "期初小时"),
                    new Column("grantedHours", "发放小时"),
                    new Column("overtimeCreditHours", "加班转入小时"),
                    new Column("usedHours", "已用小时"),
                    new Column("remainingHours", "剩余小时"),
                    new Column("remainingDays", "剩余天数")));
            case "leave-summary" -> concat(identity, List.of(
                    new Column("leaveType", "假别"),
                    new Column("hours", "合计小时"),
                    new Column("documentCount", "单据数")));
            case "overtime-daily" -> concat(identity, List.of(
                    new Column("businessDate", "日期"),
                    new Column("weekdayOvertimeHours", "工作日加班"),
                    new Column("weekendOvertimeHours", "周末加班"),
                    new Column("holidayOvertimeHours", "节假日加班"),
                    new Column("paidOvertimeHours", "加班费"),
                    new Column("compensatoryOvertimeHours", "转调休")));
            case "time-off-daily" -> concat(identity, List.of(
                    new Column("businessDate", "日期"),
                    new Column("hours", "调休小时"),
                    new Column("leaveType", "假别")));
            case "daily-journal" -> List.of(
                    new Column("sequence", "序号"),
                    new Column("department", "部门"),
                    new Column("employeeNumber", "工号"),
                    new Column("employeeName", "姓名"),
                    new Column("businessDate", "日期"),
                    new Column("shiftLabel", "班次"),
                    new Column("onDuty", "上班"),
                    new Column("offDuty", "下班"),
                    new Column("lateHours", "迟到"),
                    new Column("earlyHours", "早退"),
                    new Column("absenceHours", "旷工"),
                    new Column("leaveType", "请假"),
                    new Column("overtimeHours", "加班"),
                    new Column("remark", "备注"));
            default -> identity;
        };
    }

    private static List<Column> annualLeaveStatColumns() {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column("sequence", "序号"));
        columns.add(new Column("levelOneDepartment", "一级部门"));
        columns.add(new Column("levelTwoDepartment", "二级部门"));
        columns.add(new Column("employeeName", "姓名"));
        columns.add(new Column("hireDate", "入职日期"));
        columns.add(new Column("companyTenureYears", "公司工龄"));
        columns.add(new Column("priorTenureYears", "公司外已证明工龄"));
        columns.add(new Column("cumulativeTenureYears", "累计工龄（年）"));
        columns.add(new Column("entitledDays", "按累计工龄当年应休天数"));
        columns.add(new Column("newHireCalendarDays", "新员工计算年休假日历天数"));
        columns.add(new Column("openingHours", "期初小时"));
        columns.add(new Column("remainingDays", "可休天数"));
        columns.add(new Column("remainingHours", "可休小时数"));
        for (int month = 1; month <= 12; month++) {
            columns.add(new Column("usedMonth" + month, month + "月已休"));
        }
        return List.copyOf(columns);
    }

    private static List<Column> timeOffStatColumns() {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column("sequence", "序号"));
        columns.add(new Column("levelOneDepartment", "一级部门"));
        columns.add(new Column("levelTwoDepartment", "二级部门"));
        columns.add(new Column("employeeName", "姓名"));
        columns.add(new Column("openingHours", "期初小时"));
        columns.add(new Column("overtimeCreditHours", "加班转入小时"));
        columns.add(new Column("remainingDays", "可休天数"));
        columns.add(new Column("remainingHours", "可休小时数"));
        for (int month = 1; month <= 12; month++) {
            columns.add(new Column("usedMonth" + month, month + "月已休"));
        }
        return List.copyOf(columns);
    }

    private static String formatCell(
            String sheet, Column column, Map<String, Object> row) {
        Object value = row.get(column.key());
        if ("department".equals(column.key())
                || "levelOneDepartment".equals(column.key())
                || "levelTwoDepartment".equals(column.key())) {
            return visibleDepartment(value);
        }
        if ("/".equals(text(value))) {
            return "/";
        }
        if (value == null || "".equals(value)) {
            if (column.key().startsWith("usedMonth")
                    || "newHireCalendarDays".equals(column.key())) {
                return "0";
            }
            if ("details".equals(column.key()) || "note".equals(column.key())
                    || "remark".equals(column.key())) {
                return "—";
            }
            if ("onDuty".equals(column.key()) || "offDuty".equals(column.key())
                    || "shiftLabel".equals(column.key())
                    || "leaveType".equals(column.key())
                    || "lateHours".equals(column.key())
                    || "earlyHours".equals(column.key())
                    || "absenceHours".equals(column.key())
                    || "overtimeHours".equals(column.key())) {
                return "";
            }
            return "—";
        }
        return switch (column.key()) {
            case "exceptionType" -> EXCEPTION_TYPES.getOrDefault(
                    text(value), text(value));
            case "severity" -> SEVERITY.getOrDefault(text(value), text(value));
            case "leaveType" -> leaveTypeLabel(text(value), sheet);
            case "documentType" -> DOCUMENT_TYPES.getOrDefault(
                    text(value), text(value));
            case "accountType" -> ACCOUNT_TYPES.getOrDefault(
                    text(value), text(value));
            case "approvalState", "state", "sourceStatus" ->
                    STATUS.getOrDefault(text(value), text(value));
            case "details", "note", "remark" -> details(text(value));
            case "startAt", "endAt", "businessDate", "hireDate" -> formatDateTime(value);
            default -> formatNumber(column.key(), value);
        };
    }

    private static String leaveTypeLabel(String raw, String sheet) {
        if ("overtime".equals(sheet)) {
            return OVERTIME_TYPES.getOrDefault(raw.toUpperCase(Locale.ROOT), raw);
        }
        return LEAVE_TYPES.getOrDefault(raw.toUpperCase(Locale.ROOT), raw);
    }

    private static String details(String text) {
        if (text.startsWith("原因码=")) {
            String code = text.substring("原因码=".length()).split("[，,\\s]", 2)[0];
            return EXCEPTION_TYPES.getOrDefault(code, text);
        }
        return text;
    }

    private static String formatNumber(String key, Object value) {
        if (value instanceof Number number
                && (key.equals("hours") || key.toLowerCase(Locale.ROOT).endsWith("hours")
                || key.equals("attendanceRate") || key.endsWith("Days"))) {
            double amount = number.doubleValue();
            if ("attendanceRate".equals(key)) {
                return BigDecimal.valueOf(amount)
                        .setScale(1, RoundingMode.HALF_UP)
                        .toPlainString() + "%";
            }
            double half = Math.round(amount * 2.0) / 2.0;
            if (Math.rint(half) == half) {
                return Integer.toString((int) half);
            }
            return BigDecimal.valueOf(half).setScale(1, RoundingMode.HALF_UP)
                    .toPlainString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        String text = text(value);
        if (text.matches("[A-Z][A-Z0-9_]+")) {
            return EXCEPTION_TYPES.getOrDefault(
                    text,
                    LEAVE_TYPES.getOrDefault(
                            text,
                            DOCUMENT_TYPES.getOrDefault(
                                    text,
                                    STATUS.getOrDefault(text, text))));
        }
        return text;
    }

    private static String formatDateTime(Object value) {
        if (value instanceof LocalDate date) {
            return DATE_ONLY.format(date);
        }
        if (value instanceof Instant instant) {
            return DATE_TIME.format(instant);
        }
        String text = text(value);
        if (text.length() >= 10 && text.charAt(4) == '-') {
            try {
                if (text.length() == 10) {
                    return DATE_ONLY.format(LocalDate.parse(text.substring(0, 10)));
                }
                return DATE_TIME.format(Instant.parse(text));
            } catch (RuntimeException ignored) {
                return text;
            }
        }
        return text;
    }

    private static String punch(Object value) {
        if (value instanceof Instant instant) {
            return CLOCK.format(instant);
        }
        String text = text(value);
        if (text.isBlank()) {
            return "";
        }
        try {
            return CLOCK.format(Instant.parse(text));
        } catch (RuntimeException ignored) {
            return text.length() >= 5 ? text.substring(text.length() - 5) : text;
        }
    }

    private static LocalDate asDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        String text = text(value);
        if (text.length() >= 10) {
            try {
                return LocalDate.parse(text.substring(0, 10));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String visibleDepartment(Object value) {
        String raw = text(value);
        String visible = DepartmentPathNames.visibleDepartment(raw);
        return visible == null || visible.isBlank() ? "—" : visible;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(text(value));
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static double numberDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(text(value));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static int departmentColumn(List<Column> columns) {
        for (int index = 0; index < columns.size(); index++) {
            if ("department".equals(columns.get(index).key())) {
                return index;
            }
        }
        return -1;
    }

    private static int columnWidth(String header) {
        if ("备注".equals(header)) {
            return 48 * 256;
        }
        return Math.min(40, Math.max(12, header.length() * 3)) * 256;
    }

    private static String weekday(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return WEEKDAYS[day.getValue() - 1];
    }

    private static String sheetTitle(QueryPage page) {
        return switch (page.sheet()) {
            case "exceptions" -> "异常总览";
            case "leave" -> "请假统计";
            case "overtime" -> "加班统计";
            case "makeup" -> "补签";
            case "work-hours" -> "月度工时统计表";
            case "late" -> "迟到统计";
            case "missed-punch" -> "忘打卡";
            case "missed-punch-stat" -> "忘打卡统计表";
            case "attendance-rate" -> "出勤率";
            case "annual-leave" -> "年休假";
            case "annual-leave-stat" -> "年假统计表";
            case "time-off" -> "调休额度";
            case "time-off-stat" -> "调休统计表";
            case "time-off-daily" -> "调休日报";
            case "matrix" -> "考勤明细";
            case "leave-summary" -> "请假汇总";
            case "overtime-daily" -> "加班日报";
            case "finance-overtime" -> "每日加班查询";
            case "overtime-fee-daily" -> "每日加班费查询";
            case "overtime-comp-daily" -> "每日调休查询";
            case "overtime-voluntary-daily" -> "每日义务加班查询";
            case "absence-stat" -> "旷工统计表";
            case "leave-stat" -> "请假统计表";
            case "daily-journal" -> "考勤日报";
            default -> page.sheet();
        };
    }

    private static String sheetName(QueryPage page) {
        String safe = sheetTitle(page).replaceAll("[\\\\/*?:\\[\\]]", "_");
        return safe.length() <= 31 ? safe : safe.substring(0, 31);
    }

    private static String fileName(QueryPage page) {
        return page.fromDate().substring(0, Math.min(7, page.fromDate().length()))
                + "_"
                + sheetTitle(page)
                + ".xlsx";
    }

    private static String safe(String value) {
        String normalized = value == null ? "" : value.replace('\u0000', '\ufffd');
        return normalized.length() <= 32_767
                ? normalized
                : normalized.substring(0, 32_767);
    }

    private static List<Column> concat(List<Column> left, List<Column> right) {
        List<Column> all = new ArrayList<>(left.size() + right.size());
        all.addAll(left);
        all.addAll(right);
        return List.copyOf(all);
    }

    private record Column(String key, String title) {
    }

    private record Slot(String text, String hex, String fontHex) {
        Slot(String text, String hex) {
            this(text, hex, null);
        }

        static Slot empty() {
            return new Slot("", null, null);
        }
    }

    private static final class Styles {
        private final XSSFWorkbook workbook;
        private final XSSFCellStyle header;
        private final XSSFCellStyle body;
        private final XSSFCellStyle identity;
        private final XSSFCellStyle wrap;
        private final XSSFCellStyle department;
        private final XSSFCellStyle hours;
        private final Map<String, XSSFCellStyle> fills = new HashMap<>();

        private Styles(XSSFWorkbook workbook) {
            this.workbook = workbook;
            XSSFFont bold = workbook.createFont();
            bold.setBold(true);
            bold.setColor(rgb(CustomerReportLegendColors.INK));
            header = base(CustomerReportLegendColors.HEADER);
            header.setFont(bold);
            header.setWrapText(true);
            body = base(CustomerReportLegendColors.CANVAS);
            identity = base(CustomerReportLegendColors.CANVAS);
            identity.setAlignment(HorizontalAlignment.CENTER);
            identity.setVerticalAlignment(VerticalAlignment.CENTER);
            wrap = base(CustomerReportLegendColors.CANVAS);
            wrap.setWrapText(true);
            wrap.setAlignment(HorizontalAlignment.CENTER);
            department = base(CustomerReportLegendColors.CANVAS);
            department.setWrapText(true);
            department.setAlignment(HorizontalAlignment.LEFT);
            department.setVerticalAlignment(VerticalAlignment.CENTER);
            hours = base(CustomerReportLegendColors.CANVAS);
            hours.setAlignment(HorizontalAlignment.CENTER);
            hours.setDataFormat(workbook.createDataFormat().getFormat("0.#"));
        }

        private XSSFCellStyle fill(String hex) {
            return fills.computeIfAbsent(hex, key -> {
                XSSFCellStyle style = base(key);
                style.setWrapText(true);
                style.setAlignment(HorizontalAlignment.CENTER);
                style.setVerticalAlignment(VerticalAlignment.CENTER);
                return style;
            });
        }

        private XSSFCellStyle hoursFill(String fillHex, String fontHex) {
            String key = "h:" + fillHex + ":" + fontHex;
            return fills.computeIfAbsent(key, ignored -> {
                XSSFCellStyle style = base(fillHex);
                style.setAlignment(HorizontalAlignment.CENTER);
                style.setVerticalAlignment(VerticalAlignment.CENTER);
                style.setDataFormat(workbook.createDataFormat().getFormat("0.#"));
                XSSFFont font = workbook.createFont();
                font.setColor(rgb(fontHex));
                style.setFont(font);
                return style;
            });
        }

        private XSSFCellStyle fontFill(String fillHex, String fontHex) {
            String key = (fillHex == null ? "FFFFFF" : fillHex) + ":" + fontHex;
            return fills.computeIfAbsent(key, ignored -> {
                XSSFCellStyle style = base(fillHex == null ? "FFFFFF" : fillHex);
                style.setWrapText(true);
                style.setAlignment(HorizontalAlignment.CENTER);
                style.setVerticalAlignment(VerticalAlignment.CENTER);
                XSSFFont font = workbook.createFont();
                font.setColor(rgb(fontHex));
                style.setFont(font);
                return style;
            });
        }

        private XSSFCellStyle base(String hex) {
            XSSFCellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(rgb(hex));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            return style;
        }

        private static XSSFColor rgb(String hex) {
            String normalized = hex.startsWith("#") ? hex.substring(1) : hex;
            int value = Integer.parseInt(normalized, 16);
            byte[] bytes = {
                    (byte) ((value >> 16) & 0xFF),
                    (byte) ((value >> 8) & 0xFF),
                    (byte) (value & 0xFF)};
            return new XSSFColor(bytes, new DefaultIndexedColorMap());
        }
    }

    private static final Map<String, String> LEAVE_TYPES = Map.ofEntries(
            Map.entry("ANNUAL", "年假"),
            Map.entry("SICK", "病假"),
            Map.entry("MARRIAGE", "婚假"),
            Map.entry("MATERNITY", "产假"),
            Map.entry("PATERNITY", "陪产假"),
            Map.entry("BEREAVEMENT", "丧假"),
            Map.entry("WORK_INJURY", "工伤假"),
            Map.entry("PERSONAL", "事假"),
            Map.entry("COMPENSATORY", "调休"),
            Map.entry("TIME_OFF", "调休"),
            Map.entry("OUTING", "外出"),
            Map.entry("TRIP", "出差"),
            Map.entry("NURSING", "哺乳假"),
            Map.entry("PRENATAL_EXAM", "产检假"),
            Map.entry("FAMILY_PLANNING", "计划生育假"));
    private static final Map<String, String> OVERTIME_TYPES = Map.of(
            "PAID", "计薪加班",
            "COMPENSATORY", "转调休加班",
            "VOLUNTARY", "义务加班");
    private static final Map<String, String> DOCUMENT_TYPES = Map.of(
            "LEAVE", "请假",
            "OVERTIME", "加班",
            "PUNCH_CORRECTION", "补签",
            "CORRECTION", "补签",
            "TIME_OFF", "调休",
            "OUTING", "外出",
            "TRIP", "出差");
    private static final Map<String, String> ACCOUNT_TYPES = Map.of(
            "ANNUAL_LEAVE", "年假",
            "TIME_OFF", "调休",
            "年假", "年假",
            "调休", "调休");
    private static final Map<String, String> SEVERITY = Map.of(
            "ERROR", "高", "WARNING", "中", "INFO", "低");
    private static final Map<String, String> STATUS = Map.ofEntries(
            Map.entry("APPROVED", "已通过"),
            Map.entry("REJECTED", "已驳回"),
            Map.entry("PENDING", "审批中"),
            Map.entry("CANCELLED", "已撤销"),
            Map.entry("REVOKED", "已撤销"),
            Map.entry("OPEN", "待处理"),
            Map.entry("PENDING_EVIDENCE", "待补充凭证"),
            Map.entry("PENDING_REVIEW", "待复核"),
            Map.entry("RESOLVED", "已处理"),
            Map.entry("CLOSED", "已关闭"));
    private static final Map<String, String> EXCEPTION_TYPES = Map.ofEntries(
            Map.entry("LATE", "迟到"),
            Map.entry("EARLY_DEPARTURE", "早退"),
            Map.entry("MISSING_ON_DUTY", "上班缺卡"),
            Map.entry("MISSING_OFF_DUTY", "下班缺卡"),
            Map.entry("MISSING_PUNCH_PENDING", "缺卡待补签"),
            Map.entry("MISSING_PUNCH_OVERDUE", "缺卡超期"),
            Map.entry("MISSING_PUNCH", "缺卡待补签"),
            Map.entry("ABSENCE", "旷工"),
            Map.entry("EVIDENCE_CONFLICT", "证据冲突"),
            Map.entry("LEAVE_PUNCH_CONFLICT", "请假与打卡冲突"),
            Map.entry("FAKE_OVERTIME", "加班异常"),
            Map.entry("NO_ATTENDANCE_GROUP", "无考勤组"),
            Map.entry("NO_SHIFT_OR_CALENDAR", "无班次或日历"));
}
