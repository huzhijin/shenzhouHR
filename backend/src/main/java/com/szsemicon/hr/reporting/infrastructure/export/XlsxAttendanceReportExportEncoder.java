package com.szsemicon.hr.reporting.infrastructure.export;

import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage;
import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.DayCell;
import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.EmployeeRow;
import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.SlotDisplay;
import com.szsemicon.hr.reporting.application.AttendanceReportExportEncoder;
import com.szsemicon.hr.reporting.application.AttendanceReportExportEncoder.ExportContext;
import com.szsemicon.hr.reporting.application.CustomerReportLegendColors;
import com.szsemicon.hr.reporting.application.DepartmentPathNames;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportDataSet;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportRow;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import java.io.ByteArrayOutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
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
import org.springframework.stereotype.Component;

@Component
public final class XlsxAttendanceReportExportEncoder
        implements AttendanceReportExportEncoder {

    private static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet";
    private static final String[] WEEKDAYS = {
            "一", "二", "三", "四", "五", "六", "日"};

    private static final Map<String, String> LEAVE_TYPES = Map.ofEntries(
            Map.entry("ANNUAL", "年假"),
            Map.entry("SICK", "病假"),
            Map.entry("MARRIAGE", "婚假"),
            Map.entry("MATERNITY", "产假"),
            Map.entry("PATERNITY", "陪产假"),
            Map.entry("BEREAVEMENT", "丧假"),
            Map.entry("WORK_INJURY", "工伤假"),
            Map.entry("PERSONAL", "事假"),
            Map.entry("COMPENSATORY", "调休"));

    @Override
    public EncodedExport encode(
            ReportDataSet dataSet, ExportContext context) {
        if (dataSet == null || context == null) {
            throw new IllegalArgumentException(
                    "report data and export context are required");
        }
        if (!dataSet.exportAllowlist().equals(context.selectedFields())
                || !dataSet.calculationFormulaVersion().equals(
                        context.formulaVersion())) {
            throw new IllegalArgumentException(
                    "report data does not match its export context");
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output =
                        new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            Sheet sheet = workbook.createSheet(sheetName(dataSet.title()));
            if (dataSet.type() == ReportType.ATTENDANCE_DETAIL
                    && context.monthMatrix() != null) {
                writeMonthMatrix(sheet, context.monthMatrix(), styles);
            } else {
                writeCustomerSheet(sheet, dataSet, context, styles);
            }
            Sheet metadata = workbook.createSheet("口径说明");
            writeMetadata(
                    metadata,
                    styles.header,
                    styles.wrap,
                    metadataEntries(dataSet, context));
            workbook.write(output);
            return new EncodedExport(
                    CONTENT_TYPE, "xlsx", output.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "attendance report workbook generation failed",
                    exception);
        }
    }

    private static void writeCustomerSheet(
            Sheet sheet,
            ReportDataSet dataSet,
            ExportContext context,
            Styles styles) {
        List<String> headers = headersFor(dataSet, context);
        Row header = sheet.createRow(0);
        for (int index = 0; index < headers.size(); index++) {
            Cell cell = header.createCell(index);
            cell.setCellValue(headers.get(index));
            cell.setCellStyle(styles.header);
            sheet.setColumnWidth(index, columnWidth(headers.get(index)));
        }
        int rowIndex = 1;
        List<ReportRow> rows = dataSet.rows();
        if (rows.isEmpty()) {
            Row empty = sheet.createRow(rowIndex);
            empty.createCell(0).setCellValue("当前筛选条件下暂无记录");
        }
        for (int i = 0; i < rows.size(); i++) {
            List<String> values = valuesFor(dataSet.type(), rows.get(i), i);
            Row row = sheet.createRow(rowIndex++);
            int deptCol = departmentColumn(dataSet.type());
            int lines = 1;
            for (int column = 0; column < values.size(); column++) {
                Cell cell = row.createCell(column);
                String text = values.get(column);
                if (column == deptCol) {
                    String wrapped = DepartmentPathNames.excelWrappedDepartment(
                            text);
                    cell.setCellValue(safeCellText(wrapped));
                    cell.setCellStyle(styles.department);
                    lines = Math.max(lines, wrapped.split("\n", -1).length);
                } else {
                    cell.setCellValue(safeCellText(text));
                    cell.setCellStyle(styles.body);
                }
            }
            row.setHeightInPoints(Math.max(18, 14 * lines));
        }
        sheet.createFreezePane(0, 1);
        if (!headers.isEmpty()) {
            sheet.setAutoFilter(new CellRangeAddress(
                    0, 0, 0, headers.size() - 1));
        }
    }

    private static void writeMonthMatrix(
            Sheet sheet, AttendanceMonthMatrixPage matrix, Styles styles) {
        List<LocalDate> dates = matrix.dates();
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
                    "%02d日/周%s",
                    date.getDayOfMonth(),
                    weekday(date)));
            cell.setCellStyle(styles.header);
            sheet.setColumnWidth(4 + d, 12 * 256);
        }
        int rowIndex = 1;
        for (EmployeeRow employee : matrix.rows()) {
            Row inRow = sheet.createRow(rowIndex);
            Row outRow = sheet.createRow(rowIndex + 1);
            writeText(inRow, 0, employee.employeeNumber(), styles.body);
            writeText(inRow, 1, employee.employeeName(), styles.body);
            String wrapped = DepartmentPathNames.excelWrappedDepartment(
                    employee.organizationName());
            Cell dept = inRow.createCell(2);
            dept.setCellValue(safeCellText(wrapped));
            dept.setCellStyle(styles.department);
            writeText(outRow, 0, "", styles.body);
            writeText(outRow, 1, "", styles.body);
            Cell deptOut = outRow.createCell(2);
            deptOut.setCellValue("");
            deptOut.setCellStyle(styles.department);
            writeText(inRow, 3, "签到", styles.body);
            writeText(outRow, 3, "签退", styles.body);
            int lines = Math.max(2, wrapped.split("\n", -1).length);
            for (int d = 0; d < employee.days().size(); d++) {
                DayCell day = employee.days().get(d);
                SlotDisplay inSlot = day.merged() ? day.morning() : day.morning();
                SlotDisplay outSlot = day.merged() ? day.morning() : day.afternoon();
                writeMatrixSlot(inRow, 4 + d, inSlot, styles);
                writeMatrixSlot(outRow, 4 + d, outSlot, styles);
            }
            inRow.setHeightInPoints(Math.max(18, 14 * lines));
            outRow.setHeightInPoints(Math.max(18, 14 * lines));
            sheet.addMergedRegion(new CellRangeAddress(
                    rowIndex, rowIndex + 1, 0, 0));
            sheet.addMergedRegion(new CellRangeAddress(
                    rowIndex, rowIndex + 1, 1, 1));
            sheet.addMergedRegion(new CellRangeAddress(
                    rowIndex, rowIndex + 1, 2, 2));
            rowIndex += 2;
        }
        sheet.createFreezePane(4, 1);
    }

    private static void writeMatrixSlot(
            Row row, int column, SlotDisplay slot, Styles styles) {
        Cell cell = row.createCell(column);
        String text = slot == null || slot.text() == null ? "" : slot.text();
        cell.setCellValue(safeCellText(text));
        String hex = slot == null
                ? null
                : CustomerReportLegendColors.hexForTone(slot.tone());
        cell.setCellStyle(hex == null ? styles.wrap : styles.fill(hex));
    }

    private static List<String> headersFor(
            ReportDataSet dataSet, ExportContext context) {
        YearMonth period = context.filter().period();
        int month = period.getMonthValue();
        return switch (dataSet.type()) {
            case WORK_HOURS -> List.of(
                    "姓名",
                    "部门",
                    month + "月应出勤工时",
                    "加班时数",
                    "事假+病假+其他假期",
                    "年假",
                    "加班换调休",
                    "实际调休",
                    "个人实际出勤工时");
            case LEAVE -> List.of(
                    "序号", "部门", "姓名", "类型", "时间（小时数）",
                    "请假期间", "备注", "审批状态");
            case OVERTIME -> List.of(
                    "部门", "员工", "计薪加班", "转调休加班",
                    "义务加班", "汇总加班");
            case EXCEPTIONS -> List.of(
                    "考勤日期", "异常类型", "工号", "姓名", "部门",
                    "详情", "处理状态");
            case LATE -> List.of(
                    "序号", "部门", "姓名", "次数", "发生明细", "复核人", "处理状态");
            case MISSED_PUNCH -> List.of(
                    "序号", "部门", "姓名", "次数", "发生明细", "复核人", "处理状态");
            case ATTENDANCE_RATE -> List.of(
                    "序号", "部门", "姓名", "应出勤天数", "实际出勤天数",
                    "病假天数", "出勤率", "口径说明");
            case ANNUAL_LEAVE -> List.of(
                    "序号", "部门", "姓名", "可休天数", "可休小时数",
                    "实际剩余天数", "备注");
            case ATTENDANCE_DETAIL -> List.of(
                    "工号", "姓名", "部门", "考勤日期", "班次");
        };
    }

    private static List<String> valuesFor(
            ReportType type, ReportRow row, int index) {
        return switch (type) {
            case WORK_HOURS -> List.of(
                    text(row, ReportField.EMPLOYEE_NAME),
                    text(row, ReportField.ORGANIZATION),
                    text(row, ReportField.SCHEDULED_HOURS),
                    text(row, ReportField.PAID_OVERTIME_HOURS),
                    text(row, ReportField.LEAVE_HOURS),
                    text(row, ReportField.ANNUAL_LEAVE_HOURS),
                    text(row, ReportField.COMPENSATORY_OVERTIME_HOURS),
                    text(row, ReportField.TIME_OFF_HOURS),
                    text(row, ReportField.ACTUAL_WORK_HOURS));
            case LEAVE -> List.of(
                    Integer.toString(index + 1),
                    text(row, ReportField.ORGANIZATION),
                    text(row, ReportField.EMPLOYEE_NAME),
                    leaveType(text(row, ReportField.DOCUMENT_TYPE)),
                    text(row, ReportField.RECOGNIZED_HOURS),
                    text(row, ReportField.DOCUMENT_START)
                            + " ~ "
                            + text(row, ReportField.DOCUMENT_END),
                    "",
                    text(row, ReportField.APPROVAL_STATE));
            case OVERTIME -> List.of(
                    text(row, ReportField.ORGANIZATION),
                    text(row, ReportField.EMPLOYEE_NAME),
                    text(row, ReportField.PAID_OVERTIME_HOURS),
                    text(row, ReportField.COMPENSATORY_OVERTIME_HOURS),
                    text(row, ReportField.VOLUNTARY_OVERTIME_HOURS),
                    text(row, ReportField.TOTAL_OVERTIME_HOURS));
            case EXCEPTIONS -> List.of(
                    text(row, ReportField.BUSINESS_DATE),
                    text(row, ReportField.EXCEPTION_TYPE),
                    text(row, ReportField.EMPLOYEE_NUMBER),
                    text(row, ReportField.EMPLOYEE_NAME),
                    text(row, ReportField.ORGANIZATION),
                    text(row, ReportField.EXCEPTION_DETAILS),
                    text(row, ReportField.EXCEPTION_STATE));
            case LATE -> List.of(
                    Integer.toString(index + 1),
                    text(row, ReportField.ORGANIZATION),
                    text(row, ReportField.EMPLOYEE_NAME),
                    text(row, ReportField.LATE_EVENT_COUNT),
                    text(row, ReportField.LATE_MINUTES),
                    "",
                    "");
            case MISSED_PUNCH -> List.of(
                    Integer.toString(index + 1),
                    text(row, ReportField.ORGANIZATION),
                    text(row, ReportField.EMPLOYEE_NAME),
                    text(row, ReportField.MISSING_PUNCH_COUNT),
                    "",
                    "",
                    "");
            case ATTENDANCE_RATE -> List.of(
                    Integer.toString(index + 1),
                    text(row, ReportField.ORGANIZATION),
                    text(row, ReportField.EMPLOYEE_NAME),
                    text(row, ReportField.SCHEDULED_ATTENDANCE_DAYS),
                    text(row, ReportField.ACTUAL_ATTENDANCE_DAYS),
                    text(row, ReportField.SICK_LEAVE_DAYS),
                    text(row, ReportField.ATTENDANCE_RATE),
                    "");
            case ANNUAL_LEAVE -> List.of(
                    Integer.toString(index + 1),
                    text(row, ReportField.ORGANIZATION),
                    text(row, ReportField.EMPLOYEE_NAME),
                    text(row, ReportField.EQUIVALENT_DAYS),
                    text(row, ReportField.BALANCE_HOURS),
                    text(row, ReportField.EQUIVALENT_DAYS),
                    text(row, ReportField.ACCOUNT_TYPE));
            case ATTENDANCE_DETAIL -> List.of(
                    text(row, ReportField.EMPLOYEE_NUMBER),
                    text(row, ReportField.EMPLOYEE_NAME),
                    text(row, ReportField.ORGANIZATION),
                    text(row, ReportField.BUSINESS_DATE),
                    text(row, ReportField.SHIFT));
        };
    }

    private static int departmentColumn(ReportType type) {
        return switch (type) {
            case WORK_HOURS, OVERTIME -> 1;
            case LEAVE, LATE, MISSED_PUNCH, ATTENDANCE_RATE, ANNUAL_LEAVE -> 1;
            case EXCEPTIONS -> 4;
            case ATTENDANCE_DETAIL -> 2;
        };
    }

    private static String text(ReportRow row, ReportField field) {
        String value = row.values().get(field);
        return value == null ? "" : value;
    }

    private static String leaveType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "—";
        }
        return LEAVE_TYPES.getOrDefault(raw.toUpperCase(Locale.ROOT), raw);
    }

    private static int columnWidth(String header) {
        return Math.min(40, Math.max(12, header.length() * 3)) * 256;
    }

    private static void writeText(
            Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(safeCellText(value));
        cell.setCellStyle(style);
    }

    private static String weekday(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        int index = day.getValue() - 1;
        return WEEKDAYS[index];
    }

    private static List<MetadataEntry> metadataEntries(
            ReportDataSet dataSet, ExportContext context) {
        var filter = context.filter();
        var scope = context.authorizationScope();
        return List.of(
                new MetadataEntry("导出编号", context.exportId()),
                new MetadataEntry("操作主体ID", context.principalId()),
                new MetadataEntry("导出用途", context.purpose()),
                new MetadataEntry("报表", dataSet.title()),
                new MetadataEntry("报表类型", dataSet.type().name()),
                new MetadataEntry("公司ID", filter.companyId()),
                new MetadataEntry("筛选-期间", filter.period().toString()),
                new MetadataEntry("筛选-公司ID", filter.companyId()),
                new MetadataEntry(
                        "筛选-组织ID", completeFilter(filter.organizationId())),
                new MetadataEntry(
                        "筛选-员工ID", completeFilter(filter.employeeId())),
                new MetadataEntry(
                        "筛选-状态", completeFilter(filter.status())),
                new MetadataEntry("授权范围类型（scope）", scope.type().name()),
                new MetadataEntry(
                        "授权范围标识（reference）", scope.reference()),
                new MetadataEntry("授权范围名称", scope.label()),
                new MetadataEntry(
                        "授权摘要（digest）", scope.authorizationDigest()),
                new MetadataEntry("查询指纹", context.queryFingerprint()),
                new MetadataEntry(
                        "可见内容摘要", context.visibleContentDigest()),
                new MetadataEntry("投影版本", context.projectionVersion()),
                new MetadataEntry("公式版本", context.formulaVersion()),
                new MetadataEntry(
                        "来源版本", sourceVersions(context.sourceVersions())),
                new MetadataEntry("dataAsOf", context.dataAsOf().toString()),
                new MetadataEntry("期间状态", context.periodState()),
                new MetadataEntry("创建时间", context.createdAt().toString()),
                new MetadataEntry("生成时间", context.generatedAt().toString()),
                new MetadataEntry(
                        "字段顺序", fieldOrder(context.selectedFields())));
    }

    private static void writeMetadata(
            Sheet sheet,
            CellStyle keyStyle,
            CellStyle valueStyle,
            List<MetadataEntry> entries) {
        for (int index = 0; index < entries.size(); index++) {
            MetadataEntry entry = entries.get(index);
            Row row = sheet.createRow(index);
            var keyCell = row.createCell(0);
            keyCell.setCellValue(entry.key());
            keyCell.setCellStyle(keyStyle);
            var valueCell = row.createCell(1);
            valueCell.setCellValue(completeMetadataText(entry.value()));
            valueCell.setCellStyle(valueStyle);
        }
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 100 * 256);
    }

    private static String completeFilter(String value) {
        return value == null ? "（全部）" : value;
    }

    private static String sourceVersions(List<String> sourceVersions) {
        return sourceVersions.isEmpty()
                ? "（无）"
                : String.join("\n", sourceVersions);
    }

    private static String fieldOrder(List<ReportField> fields) {
        StringBuilder order = new StringBuilder();
        for (int index = 0; index < fields.size(); index++) {
            if (!order.isEmpty()) {
                order.append('\n');
            }
            ReportField field = fields.get(index);
            order.append(index + 1)
                    .append(". ")
                    .append(field.key())
                    .append("（")
                    .append(field.label())
                    .append('）');
        }
        return order.toString();
    }

    private static String sheetName(String title) {
        String safe = title.replaceAll("[\\\\/*?:\\[\\]]", "_");
        return safe.length() <= 31 ? safe : safe.substring(0, 31);
    }

    private static String safeCellText(String value) {
        String normalized = value == null
                ? ""
                : value.replace('\u0000', '\ufffd');
        return normalized.length() <= 32_767
                ? normalized
                : normalized.substring(0, 32_767);
    }

    private static String completeMetadataText(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), 32_767));
        for (int index = 0; index < value.length() && sanitized.length() < 32_767; index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)
                    && character != '\n'
                    && character != '\r'
                    && character != '\t') {
                sanitized.append('\uFFFD');
            } else {
                sanitized.append(character);
            }
        }
        return sanitized.toString();
    }

    private record MetadataEntry(String key, String value) {
    }

    private static final class Styles {
        private final XSSFWorkbook workbook;
        private final XSSFCellStyle header;
        private final XSSFCellStyle body;
        private final XSSFCellStyle wrap;
        private final XSSFCellStyle department;
        private final java.util.Map<String, XSSFCellStyle> fills =
                new java.util.HashMap<>();

        private Styles(XSSFWorkbook workbook) {
            this.workbook = workbook;
            XSSFFont bold = workbook.createFont();
            bold.setBold(true);
            bold.setColor(rgb(CustomerReportLegendColors.INK));
            header = base(CustomerReportLegendColors.HEADER);
            header.setFont(bold);
            header.setWrapText(true);
            body = base(CustomerReportLegendColors.CANVAS);
            wrap = base(CustomerReportLegendColors.CANVAS);
            wrap.setWrapText(true);
            wrap.setAlignment(HorizontalAlignment.CENTER);
            department = base(CustomerReportLegendColors.CANVAS);
            department.setWrapText(true);
            department.setAlignment(HorizontalAlignment.LEFT);
            department.setVerticalAlignment(VerticalAlignment.CENTER);
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
            byte[] bytes = HexFormatBytes.parse(normalized);
            return new XSSFColor(bytes, new DefaultIndexedColorMap());
        }
    }

    private static final class HexFormatBytes {
        private static byte[] parse(String hex) {
            if (hex.length() != 6) {
                return new byte[] {(byte) 255, (byte) 255, (byte) 255};
            }
            return new byte[] {
                    (byte) Integer.parseInt(hex.substring(0, 2), 16),
                    (byte) Integer.parseInt(hex.substring(2, 4), 16),
                    (byte) Integer.parseInt(hex.substring(4, 6), 16)
            };
        }
    }
}
