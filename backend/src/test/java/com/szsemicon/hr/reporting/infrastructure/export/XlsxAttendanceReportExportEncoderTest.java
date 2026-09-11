package com.szsemicon.hr.reporting.infrastructure.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.application.AttendanceReportExportEncoder.ExportContext;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportColumn;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportDataSet;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportRow;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

class XlsxAttendanceReportExportEncoderTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void workbookContainsTraceContextAndSelectedFieldsInRequestOrder()
            throws Exception {
        String formulaLikeValue =
                "=HYPERLINK(\"https://invalid.example\",\"click\")";
        YearMonth period = YearMonth.of(2026, 7);
        List<ReportField> selectedFields = List.of(
                ReportField.EMPLOYEE_NAME,
                ReportField.EMPLOYEE_NUMBER);
        var dataSet = new ReportDataSet(
                ReportType.EXCEPTIONS,
                "异常明细",
                selectedFields.stream().map(ReportColumn::new).toList(),
                selectedFields,
                List.of(new ReportRow(
                        "row-1",
                        Map.of(
                                ReportField.EMPLOYEE_NAME,
                                formulaLikeValue,
                                ReportField.EMPLOYEE_NUMBER,
                                "0001"),
                        null)),
                "formula-v1");
        var context = new ExportContext(
                "export-2026-07-001",
                "principal-hr-admin",
                "月度考勤核对",
                new ReportFilter(
                        period,
                        "company-1",
                        "organization-1",
                        "employee-1",
                        "OPEN"),
                new AuthorizedScope(
                        ScopeType.ORGANIZATION,
                        "authorized-scope-set:company-1",
                        "制造中心",
                        "a".repeat(64)),
                "projection-v3",
                "formula-v1",
                List.of("daily-v3", "oa-v2"),
                Instant.parse("2026-07-31T15:59:59Z"),
                "OPEN",
                "b".repeat(64),
                "c".repeat(64),
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:05Z"),
                selectedFields,
                null);

        var encoded = new XlsxAttendanceReportExportEncoder()
                .encode(dataSet, context);

        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var reportSheet = workbook.getSheetAt(0);
            assertThat(reportSheet.getRow(0).getCell(0)
                            .getStringCellValue())
                    .isEqualTo("考勤日期");
            assertThat(reportSheet.getRow(0).getCell(2)
                            .getStringCellValue())
                    .isEqualTo("工号");
            assertThat(reportSheet.getRow(0).getCell(3)
                            .getStringCellValue())
                    .isEqualTo("姓名");
            var cell = reportSheet.getRow(1).getCell(3);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue())
                    .isEqualTo(formulaLikeValue);
            assertThat(reportSheet.getRow(1).getCell(2)
                            .getStringCellValue())
                    .isEqualTo("0001");

            Map<String, String> metadata = metadata(workbook);
            assertThat(metadata)
                    .containsEntry("导出编号", "export-2026-07-001")
                    .containsEntry("操作主体ID", "principal-hr-admin")
                    .containsEntry("导出用途", "月度考勤核对")
                    .containsEntry("报表", "异常明细")
                    .containsEntry("报表类型", "EXCEPTIONS")
                    .containsEntry("公司ID", "company-1")
                    .containsEntry("筛选-期间", "2026-07")
                    .containsEntry("筛选-公司ID", "company-1")
                    .containsEntry("筛选-组织ID", "organization-1")
                    .containsEntry("筛选-员工ID", "employee-1")
                    .containsEntry("筛选-状态", "OPEN")
                    .containsEntry("授权范围类型（scope）", "ORGANIZATION")
                    .containsEntry(
                            "授权范围标识（reference）",
                            "authorized-scope-set:company-1")
                    .containsEntry("授权范围名称", "制造中心")
                    .containsEntry("授权摘要（digest）", "a".repeat(64))
                    .containsEntry("查询指纹", "b".repeat(64))
                    .containsEntry("可见内容摘要", "c".repeat(64))
                    .containsEntry("投影版本", "projection-v3")
                    .containsEntry("公式版本", "formula-v1")
                    .containsEntry("来源版本", "daily-v3\noa-v2")
                    .containsEntry("dataAsOf", "2026-07-31T15:59:59Z")
                    .containsEntry("期间状态", "OPEN")
                    .containsEntry("创建时间", "2026-08-01T00:00:00Z")
                    .containsEntry("生成时间", "2026-08-01T00:00:05Z")
                    .containsEntry(
                            "字段顺序",
                            "1. employee-name（姓名）\n"
                                    + "2. employee-number（工号）");
        }
    }

    @Test
    void workHoursUsesOnScreenMonthHeaderAndWhiteCanvas() throws Exception {
        List<ReportField> fields = List.of(
                ReportField.EMPLOYEE_NAME,
                ReportField.ORGANIZATION,
                ReportField.SCHEDULED_HOURS);
        var dataSet = new ReportDataSet(
                ReportType.WORK_HOURS,
                "个人月度工时",
                fields.stream().map(ReportColumn::new).toList(),
                fields,
                List.of(new ReportRow(
                        "row-1",
                        Map.of(
                                ReportField.EMPLOYEE_NAME, "陈思远",
                                ReportField.ORGANIZATION,
                                "服务中心-\u200B工程二部-\u200BRF-B组",
                                ReportField.SCHEDULED_HOURS, "176.0"),
                        null)),
                "formula-v1");
        var encoded = new XlsxAttendanceReportExportEncoder()
                .encode(dataSet, contextFor(YearMonth.of(2026, 6), fields, null));
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("姓名");
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue())
                    .isEqualTo("6月应出勤工时");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue())
                    .isEqualTo("服务中心-\n工程二部-\nRF-B组");
            var fill = ((XSSFCellStyle) sheet.getRow(1).getCell(0)
                    .getCellStyle()).getFillForegroundXSSFColor();
            assertThat(fill.getRGB()).containsExactly(
                    (byte) 255, (byte) 255, (byte) 255);
        }
    }

    @Test
    void monthMatrixPaintsAfternoonAnnualLeave() throws Exception {
        var date = java.time.LocalDate.of(2026, 6, 8);
        var matrix = new com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage(
                "projection-a",
                "a".repeat(64),
                "ATTENDANCE_MONTH_MATRIX_V3",
                "OPEN",
                Instant.parse("2026-07-31T01:00:00Z"),
                List.of("attendance:v1"),
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "a".repeat(64)),
                new ReportFilter(YearMonth.of(2026, 6), "company-1", null, null, null),
                List.of("REPORT_DRILL_DOWN"),
                List.of(date),
                List.of(new com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.EmployeeRow(
                        "employee-a",
                        "SZ001",
                        "陈思远",
                        "org-a",
                        "服务中心-\u200B工程二部-\u200BRF-B组",
                        List.of(new com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.DayCell(
                                date,
                                "服务中心-\u200B工程二部-\u200BRF-B组",
                                "STANDARD",
                                null,
                                null,
                                List.of(),
                                new com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.SlotDisplay(
                                        "08:14", null, null),
                                new com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.SlotDisplay(
                                        "年假", "ANNUAL_LEAVE", null),
                                false,
                                "")))),
                0,
                20,
                1,
                1,
                false);
        List<ReportField> fields = List.of(ReportField.EMPLOYEE_NAME);
        var dataSet = new ReportDataSet(
                ReportType.ATTENDANCE_DETAIL,
                "月度考勤明细",
                fields.stream().map(ReportColumn::new).toList(),
                fields,
                List.of(),
                "formula-v1");
        var encoded = new XlsxAttendanceReportExportEncoder()
                .encode(dataSet, contextFor(YearMonth.of(2026, 6), fields, matrix));
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue())
                    .isEqualTo("签到/签退");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue())
                    .isEqualTo("服务中心-\n工程二部-\nRF-B组");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue())
                    .isEqualTo("签到");
            assertThat(sheet.getRow(2).getCell(3).getStringCellValue())
                    .isEqualTo("签退");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue())
                    .isEqualTo("08:14");
            assertThat(sheet.getRow(2).getCell(4).getStringCellValue())
                    .isEqualTo("年假");
            var fill = ((XSSFCellStyle) sheet.getRow(2).getCell(4)
                    .getCellStyle()).getFillForegroundXSSFColor();
            assertThat(fill.getRGB()).containsExactly(
                    (byte) 0x7A, (byte) 0x34, (byte) 0x34);
            var morningFill = ((XSSFCellStyle) sheet.getRow(1).getCell(4)
                    .getCellStyle()).getFillForegroundXSSFColor();
            assertThat(morningFill.getRGB()).containsExactly(
                    (byte) 255, (byte) 255, (byte) 255);
        }
    }

    private static ExportContext contextFor(
            YearMonth period,
            List<ReportField> selectedFields,
            com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage matrix) {
        return new ExportContext(
                "export-2026-07-001",
                "principal-hr-admin",
                "月度考勤核对",
                new ReportFilter(period, "company-1", null, null, "OPEN"),
                new AuthorizedScope(
                        ScopeType.ORGANIZATION,
                        "authorized-scope-set:company-1",
                        "制造中心",
                        "a".repeat(64)),
                "projection-v3",
                "formula-v1",
                List.of("daily-v3"),
                Instant.parse("2026-07-31T15:59:59Z"),
                "OPEN",
                "b".repeat(64),
                "c".repeat(64),
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:05Z"),
                selectedFields,
                matrix);
    }

    private static Map<String, String> metadata(XSSFWorkbook workbook) {
        var values = new LinkedHashMap<String, String>();
        var metadataSheet = workbook.getSheet("口径说明");
        for (var row : metadataSheet) {
            values.put(
                    row.getCell(0).getStringCellValue(),
                    row.getCell(1).getStringCellValue());
        }
        return values;
    }
}
