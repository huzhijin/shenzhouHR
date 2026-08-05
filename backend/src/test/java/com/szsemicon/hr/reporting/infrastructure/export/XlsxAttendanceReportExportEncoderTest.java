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
                selectedFields);

        var encoded = new XlsxAttendanceReportExportEncoder()
                .encode(dataSet, context);

        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var reportSheet = workbook.getSheetAt(0);
            assertThat(reportSheet.getRow(0).getCell(0)
                            .getStringCellValue())
                    .isEqualTo("姓名");
            assertThat(reportSheet.getRow(0).getCell(1)
                            .getStringCellValue())
                    .isEqualTo("工号");
            var cell = reportSheet.getRow(1).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue())
                    .isEqualTo(formulaLikeValue);
            assertThat(reportSheet.getRow(1).getCell(1)
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
