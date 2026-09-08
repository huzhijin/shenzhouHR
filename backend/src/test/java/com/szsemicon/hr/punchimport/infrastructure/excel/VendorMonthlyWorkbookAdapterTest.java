package com.szsemicon.hr.punchimport.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.punchimport.application.PunchImportExceptions.UnsafeWorkbookException;
import com.szsemicon.hr.punchimport.application.PunchWorkbookGateway;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class VendorMonthlyWorkbookAdapterTest {

    @Test
    void extractsClockAndDropsConclusions() {
        assertThat(VendorMonthlyWorkbookAdapter.extractClock("08:30 迟到"))
                .isEqualTo("08:30");
        assertThat(VendorMonthlyWorkbookAdapter.extractClock("17:59 早退"))
                .isEqualTo("17:59");
        assertThat(VendorMonthlyWorkbookAdapter.extractClock("18:01"))
                .isEqualTo("18:01");
        assertThat(VendorMonthlyWorkbookAdapter.extractClock("漏刷")).isNull();
        assertThat(VendorMonthlyWorkbookAdapter.extractClock("-")).isNull();
        assertThat(VendorMonthlyWorkbookAdapter.extractClock("")).isNull();
    }

    @Test
    void unpivotsMonthlySummaryInOutPair() throws Exception {
        byte[] bytes = summaryWorkbook();
        var parsed = new PoiPunchWorkbookGateway().parse(
                bytes,
                "月度汇总表_20260101_20260131.xlsx",
                PunchWorkbookPolicy.XLSX_CONTENT_TYPE);
        assertThat(parsed.workbookKind()).isEqualTo("MONTHLY_SUMMARY");
        assertThat(parsed.punchRows()).containsExactly(
                row("SZST0001", "陈乐", "2026-01-16 08:19:00", "IN"),
                row("SZST0001", "陈乐", "2026-01-16 18:05:00", "OUT"));
    }

    @Test
    void unpivotsDeliMonthlyReportAndSkipsMissedPunches() throws Exception {
        byte[] bytes = deliWorkbook();
        var parsed = new PoiPunchWorkbookGateway().parse(
                bytes,
                "考勤月报-2026年01月01日至2026年01月31日.xlsx",
                PunchWorkbookPolicy.XLSX_CONTENT_TYPE);
        assertThat(parsed.workbookKind()).isEqualTo("DELI_MONTHLY_REPORT");
        assertThat(parsed.punchRows()).containsExactly(
                row("SZST0001", "陈乐", "2026-01-16 08:30:00", "IN"),
                row("SZST0001", "陈乐", "2026-01-16 17:59:00", "OUT"));
        assertThat(parsed.punchRows())
                .noneMatch(values -> values.get("punchTime").contains("01-15"));
    }

    @Test
    void unrecognizedLayoutIsRejected() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("随便");
            XSSFRow row = sheet.createRow(0);
            row.createCell(0).setCellValue("不是考勤");
            workbook.write(output);
            assertThatThrownBy(() -> new PoiPunchWorkbookGateway().parse(
                    output.toByteArray(),
                    "unknown.xlsx",
                    PunchWorkbookPolicy.XLSX_CONTENT_TYPE))
                    .isInstanceOf(UnsafeWorkbookException.class)
                    .extracting(exception -> ((UnsafeWorkbookException) exception)
                            .reasonCode())
                    .isEqualTo("UNRECOGNIZED_LAYOUT");
        }
    }

    @Test
    void officialTemplatePathIsUnchanged() {
        PunchWorkbookGateway.TemplateWorkbook template =
                new PoiPunchWorkbookGateway().currentTemplate();
        var parsed = new PoiPunchWorkbookGateway().parse(
                template.content(),
                template.filename(),
                PunchWorkbookPolicy.XLSX_CONTENT_TYPE);
        assertThat(parsed.workbookKind()).isEqualTo("OFFICIAL_TEMPLATE");
        assertThat(parsed.punchRows()).isEmpty();
    }

    private static Map<String, String> row(
            String number,
            String name,
            String punchTime,
            String direction) {
        return Map.of(
                "employeeNumber", number,
                "employeeNameForComparison", name,
                "punchTime", punchTime,
                "direction", direction,
                "sourceTimeZone", "Asia/Shanghai",
                "_rowNumber", direction.equals("IN") ? "3" : "4",
                "_businessDate", punchTime.substring(0, 10));
    }

    private static byte[] summaryWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("月度汇总表");
            XSSFRow group = sheet.createRow(0);
            group.createCell(0).setCellValue("基本信息");
            XSSFRow header = sheet.createRow(1);
            header.createCell(0).setCellValue("姓名");
            header.createCell(1).setCellValue("工号");
            header.createCell(2).setCellValue("部门");
            header.createCell(3).setCellValue("迟到次数");
            header.createCell(4).setCellValue("周四\n26-01-16");
            XSSFRow in = sheet.createRow(2);
            in.createCell(0).setCellValue("陈乐");
            in.createCell(1).setCellValue("SZST0001");
            in.createCell(2).setCellValue("采购部");
            in.createCell(3).setCellValue("1");
            in.createCell(4).setCellValue("08:19");
            XSSFRow out = sheet.createRow(3);
            out.createCell(4).setCellValue("18:05");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] deliWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("考勤月报");
            sheet.createRow(0).createCell(0).setCellValue("2026年01月考勤统计");
            XSSFRow header = sheet.createRow(1);
            header.createCell(0).setCellValue("帐号");
            header.createCell(1).setCellValue("工号");
            header.createCell(2).setCellValue("姓名");
            header.createCell(3).setCellValue("签到");
            header.createCell(4).setCellValue("15/四");
            header.createCell(5).setCellValue("16/五");
            XSSFRow in = sheet.createRow(2);
            in.createCell(0).setCellValue("123");
            in.createCell(1).setCellValue("SZST0001");
            in.createCell(2).setCellValue("陈乐");
            in.createCell(3).setCellValue("签到");
            in.createCell(4).setCellValue("漏刷");
            in.createCell(5).setCellValue("08:30 迟到");
            XSSFRow out = sheet.createRow(3);
            out.createCell(3).setCellValue("签退");
            out.createCell(4).setCellValue("漏刷");
            out.createCell(5).setCellValue("17:59 早退");
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
