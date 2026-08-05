package com.szsemicon.hr.reporting.infrastructure.export;

import com.szsemicon.hr.reporting.application.AttendanceReportExportEncoder;
import com.szsemicon.hr.reporting.application.AttendanceReportExportEncoder.ExportContext;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportDataSet;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import java.io.ByteArrayOutputStream;
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
            ReportDataSet dataSet, ExportContext context) {
        if (dataSet == null || context == null) {
            throw new IllegalArgumentException(
                    "report data and export context are required");
        }
        if (!dataSet.exportAllowlist().equals(
                        context.selectedFields())
                || !dataSet.calculationFormulaVersion().equals(
                        context.formulaVersion())) {
            throw new IllegalArgumentException(
                    "report data does not match its export context");
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
            CellStyle metadataValueStyle = workbook.createCellStyle();
            metadataValueStyle.setWrapText(true);
            writeMetadata(
                    metadata,
                    headerStyle,
                    metadataValueStyle,
                    metadataEntries(dataSet, context));

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

    private static String completeMetadataText(String value) {
        if (value == null
                || value.length() > 32_767
                || value.chars().anyMatch(character ->
                        Character.isISOControl(character)
                                && character != '\n'
                                && character != '\r'
                                && character != '\t')) {
            throw new IllegalArgumentException(
                    "export trace metadata cannot be represented completely");
        }
        return value;
    }

    private record MetadataEntry(String key, String value) {
    }
}
