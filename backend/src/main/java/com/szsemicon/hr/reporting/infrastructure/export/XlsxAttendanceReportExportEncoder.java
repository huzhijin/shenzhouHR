package com.szsemicon.hr.reporting.infrastructure.export;

import com.szsemicon.hr.reporting.application.AttendanceReportExportEncoder;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportDataSet;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import java.io.ByteArrayOutputStream;
import java.time.YearMonth;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public final class XlsxAttendanceReportExportEncoder
        implements AttendanceReportExportEncoder {

    private static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet";

    @Override
    public EncodedExport encode(
            ReportDataSet dataSet, YearMonth period) {
        if (dataSet == null || period == null) {
            throw new IllegalArgumentException(
                    "report data and period are required");
        }
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200);
                ByteArrayOutputStream output =
                        new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet(sheetName(dataSet.title()));
            CellStyle headerStyle = headerStyle(workbook);
            List<ReportField> fields = dataSet.exportAllowlist();
            Row header = sheet.createRow(0);
            for (int index = 0; index < fields.size(); index++) {
                var cell = header.createCell(index);
                cell.setCellValue(fields.get(index).label());
                cell.setCellStyle(headerStyle);
                int width = Math.min(
                        40,
                        Math.max(12, fields.get(index).label().length() * 3));
                sheet.setColumnWidth(index, width * 256);
            }
            int rowIndex = 1;
            for (var reportRow : dataSet.rows()) {
                Row row = sheet.createRow(rowIndex++);
                for (int columnIndex = 0;
                        columnIndex < fields.size();
                        columnIndex++) {
                    String value = reportRow.values().getOrDefault(
                            fields.get(columnIndex), "");
                    row.createCell(columnIndex)
                            .setCellValue(safeCellText(value));
                }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0, 0, 0, Math.max(0, fields.size() - 1)));

            Sheet metadata = workbook.createSheet("口径说明");
            metadata.createRow(0).createCell(0)
                    .setCellValue("报表");
            metadata.getRow(0).createCell(1)
                    .setCellValue(safeCellText(dataSet.title()));
            metadata.createRow(1).createCell(0)
                    .setCellValue("期间");
            metadata.getRow(1).createCell(1)
                    .setCellValue(period.toString());
            metadata.setColumnWidth(0, 16 * 256);
            metadata.setColumnWidth(1, 60 * 256);

            workbook.write(output);
            workbook.dispose();
            return new EncodedExport(
                    CONTENT_TYPE, "xlsx", output.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "attendance report workbook generation failed",
                    exception);
        }
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(
                IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setWrapText(true);
        var font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
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
}
