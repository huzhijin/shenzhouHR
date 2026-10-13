package com.szsemicon.hr.reporting.infrastructure.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.application.AttendanceReportQueryPageService.QueryPage;
import com.szsemicon.hr.reporting.application.DepartmentPathNames;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class QueryPageExcelEncoderTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void makeupSheetUsesPunchCorrectionColumns() throws Exception {
        var page = new QueryPage(
                "makeup",
                "company-1",
                "2026-08-01",
                "2026-08-31",
                "proj-1",
                Instant.parse("2026-08-20T00:00:00Z"),
                "OPEN",
                List.of(),
                null,
                1,
                0,
                50,
                List.of(Map.of(
                        "employeeNumber", "SZST0001",
                        "employeeName", "张三",
                        "department", DepartmentPathNames.fromRootToLeaf(
                                List.of("服务中心", "工程二部")).reportDepartment(),
                        "documentType", "PUNCH_CORRECTION",
                        "startAt", Instant.parse("2026-08-03T00:32:00Z"),
                        "approvalState", "APPROVED")),
                List.of("REPORT_QUERY", "REPORT_EXPORT_CREATE"),
                "a".repeat(64),
                "scope-1",
                List.of());
        var encoded = new QueryPageExcelEncoder().encode(page);
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("补签");
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue())
                    .isEqualTo("单据类型");
            assertThat(sheet.getRow(0).getCell(4).getStringCellValue())
                    .isEqualTo("补签时间");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue())
                    .isEqualTo("补签");
            assertThat(encoded.fileName()).isEqualTo("2026-08_补签.xlsx");
        }
    }

    @Test
    void overtimeSheetKeepsDocumentHours() throws Exception {
        var page = new QueryPage(
                "overtime",
                "company-1",
                "2026-08-01",
                "2026-08-31",
                "proj-1",
                Instant.parse("2026-08-20T00:00:00Z"),
                "OPEN",
                List.of(),
                null,
                1,
                0,
                50,
                List.of(Map.of(
                        "employeeNumber", "SZST0001",
                        "employeeName", "张三",
                        "department", "工程部",
                        "leaveType", "PAID",
                        "startAt", Instant.parse("2026-08-03T10:00:00Z"),
                        "endAt", Instant.parse("2026-08-03T13:00:00Z"),
                        "hours", 2.5,
                        "approvalState", "APPROVED")),
                List.of(),
                "a".repeat(64),
                "scope-1",
                List.of());
        var encoded = new QueryPageExcelEncoder().encode(page);
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue())
                    .isEqualTo("加班方式");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue())
                    .isEqualTo("计薪加班");
            assertThat(sheet.getRow(1).getCell(6).getStringCellValue())
                    .isEqualTo("2.5");
        }
    }

    @Test
    void matrixExportsSignInAndSignOutRows() throws Exception {
        var page = new QueryPage(
                "matrix",
                "company-1",
                "2026-08-03",
                "2026-08-03",
                "proj-1",
                Instant.parse("2026-08-20T00:00:00Z"),
                "OPEN",
                List.of(),
                null,
                1,
                0,
                50,
                List.of(Map.of(
                        "employeeNumber", "SZST0001",
                        "employeeName", "张三",
                        "department", "工程部",
                        "days", List.of(Map.of(
                                "date", LocalDate.of(2026, 8, 3),
                                "dayType", "WEEKDAY",
                                "leaveType", "",
                                "lateMinutes", 2,
                                "earlyMinutes", 0,
                                "missingPunches", 0,
                                "firstPunchAt", Instant.parse("2026-08-03T00:32:00Z"),
                                "lastPunchAt", Instant.parse("2026-08-03T09:00:00Z"))))),
                List.of(),
                "a".repeat(64),
                "scope-1",
                List.of());
        var encoded = new QueryPageExcelEncoder().encode(page);
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue())
                    .isEqualTo("签到/签退");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue())
                    .isEqualTo("签到");
            assertThat(sheet.getRow(2).getCell(3).getStringCellValue())
                    .isEqualTo("签退");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue())
                    .contains("迟到");
        }
    }

    @Test
    void financeOvertimeExportsPersonMonthMatrix() throws Exception {
        var page = new QueryPage(
                "finance-overtime",
                "company-1",
                "2026-08-04",
                "2026-08-09",
                "proj-1",
                Instant.parse("2026-08-20T00:00:00Z"),
                "OPEN",
                List.of(),
                null,
                1,
                0,
                50,
                List.of(Map.of(
                        "employeeNumber", "SZST0131",
                        "employeeName", "周旋",
                        "department", "工程一部",
                        "weekdayOvertimeHours", 2.5,
                        "weekendOvertimeHours", 7.5,
                        "holidayOvertimeHours", 0,
                        "days", List.of(
                                Map.of(
                                        "date", LocalDate.of(2026, 8, 4),
                                        "dayType", "WEEKDAY",
                                        "hours", 2.5),
                                Map.of(
                                        "date", LocalDate.of(2026, 8, 9),
                                        "dayType", "SATURDAY",
                                        "hours", 7.5)))),
                List.of(),
                "a".repeat(64),
                "scope-1",
                List.of());
        var encoded = new QueryPageExcelEncoder().encode(page);
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("每日加班查询");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("部门");
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue())
                    .isEqualTo("平时加班");
            assertThat(sheet.getRow(0).getCell(6).getStringCellValue())
                    .isEqualTo("加班费");
            assertThat(sheet.getRow(0).getCell(7).getStringCellValue())
                    .isEqualTo("转调休");
            assertThat(sheet.getRow(0).getCell(8).getStringCellValue())
                    .isEqualTo("义务加班");
            assertThat(sheet.getRow(0).getCell(9).getStringCellValue())
                    .isEqualTo("8月4日");
            assertThat(sheet.getRow(1).getCell(9).getStringCellValue())
                    .isEqualTo("2");
            assertThat(sheet.getRow(2).getCell(2).getStringCellValue())
                    .isEqualTo("周旋");
            assertThat(sheet.getRow(2).getCell(3).getCellFormula())
                    .isEqualTo("SUM(J3:M3)");
            assertThat(sheet.getRow(2).getCell(4).getCellFormula())
                    .isEqualTo("SUM(N3:O3)");
            assertThat(sheet.getRow(2).getCell(5).getCellFormula())
                    .isEqualTo("0");
            assertThat(sheet.getRow(2).getCell(9).getNumericCellValue())
                    .isEqualTo(2.5);
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue())
                    .isEqualTo("总计");
            assertThat(sheet.getRow(3).getCell(3).getCellFormula())
                    .isEqualTo("SUM(D3:D3)");
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateAll();
            assertThat(sheet.getRow(2).getCell(3).getNumericCellValue())
                    .isEqualTo(2.5);
            assertThat(sheet.getRow(2).getCell(4).getNumericCellValue())
                    .isEqualTo(7.5);
            assertThat(sheet.getRow(3).getCell(9).getNumericCellValue())
                    .isEqualTo(2.5);
        }
    }

    @Test
    void financeOvertimePublicHolidayBucketFollowsStatutoryCalendar() throws Exception {
        // 2026-09-25 is in the statutory set → holiday; 2026-09-26 is not → weekend.
        // Both arrive with dayType=PUBLIC_HOLIDAY; encoder must classify correctly.
        var page = new QueryPage(
                "finance-overtime",
                "company-1",
                "2026-09-25",
                "2026-09-26",
                "proj-1",
                Instant.parse("2026-10-06T00:00:00Z"),
                "OPEN",
                List.of(),
                null,
                1,
                0,
                50,
                List.of(Map.of(
                        "employeeNumber", "SZST9999",
                        "employeeName", "测试员",
                        "department", "测试部",
                        "weekdayOvertimeHours", 0,
                        "weekendOvertimeHours", 4,
                        "holidayOvertimeHours", 8,
                        "days", List.of(
                                Map.of("date", LocalDate.of(2026, 9, 25),
                                        "dayType", "PUBLIC_HOLIDAY",
                                        "hours", 8),
                                Map.of("date", LocalDate.of(2026, 9, 26),
                                        "dayType", "PUBLIC_HOLIDAY",
                                        "hours", 4)))),
                List.of(),
                "a".repeat(64),
                "scope-1",
                List.of());
        var encoded = new QueryPageExcelEncoder().encode(page);
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var sheet = workbook.getSheetAt(0);
            // Row 2 (0-indexed) is the data row
            var dataRow = sheet.getRow(2);
            // D=weekday(col3), E=weekend(col4), F=holiday(col5)
            // 9/25 → holiday (J=col9), 9/26 → weekend (K=col10)
            assertThat(dataRow.getCell(9).getNumericCellValue()).isEqualTo(8.0);
            assertThat(dataRow.getCell(10).getNumericCellValue()).isEqualTo(4.0);
            // weekend formula references 9/26 (K column)
            assertThat(dataRow.getCell(4).getCellFormula()).contains("K");
            // holiday formula references 9/25 (J column)
            assertThat(dataRow.getCell(5).getCellFormula()).contains("J");
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateAll();
            // After evaluation: weekend=4, holiday=8, weekday=0
            assertThat(dataRow.getCell(3).getNumericCellValue()).isEqualTo(0.0);
            assertThat(dataRow.getCell(4).getNumericCellValue()).isEqualTo(4.0);
            assertThat(dataRow.getCell(5).getNumericCellValue()).isEqualTo(8.0);
        }
    }

    @Test
    void overtimeFeeDailyExportsOnlyPaidColumn() throws Exception {
        var page = new QueryPage(
                "overtime-fee-daily",
                "company-1",
                "2026-08-04",
                "2026-08-09",
                "proj-1",
                Instant.parse("2026-08-20T00:00:00Z"),
                "OPEN",
                List.of(),
                null,
                1,
                0,
                50,
                List.of(Map.of(
                        "employeeNumber", "SZST0131",
                        "employeeName", "周旋",
                        "department", "工程一部",
                        "weekdayOvertimeHours", 2.5,
                        "weekendOvertimeHours", 7.5,
                        "holidayOvertimeHours", 0,
                        "paidOvertimeHours", 10.0,
                        "days", List.of(
                                Map.of(
                                        "date", LocalDate.of(2026, 8, 4),
                                        "dayType", "WEEKDAY",
                                        "hours", 2.5),
                                Map.of(
                                        "date", LocalDate.of(2026, 8, 9),
                                        "dayType", "SATURDAY",
                                        "hours", 7.5)))),
                List.of(),
                "a".repeat(64),
                "scope-1",
                List.of());
        var encoded = new QueryPageExcelEncoder().encode(page);
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("每日加班费查询");
            assertThat(sheet.getRow(0).getCell(6).getStringCellValue())
                    .isEqualTo("加班费");
            assertThat(sheet.getRow(0).getCell(7).getStringCellValue())
                    .isEqualTo("8月4日");
            assertThat(sheet.getRow(2).getCell(3).getCellFormula())
                    .isEqualTo("SUM(H3:K3)");
            assertThat(sheet.getRow(2).getCell(6).getNumericCellValue())
                    .isEqualTo(10.0);
        }
    }

    @Test
    void workHoursWorkbookUsesTheRenamedSheetTitle() throws Exception {
        var page = new QueryPage(
                "work-hours",
                "company-1",
                "2026-08-01",
                "2026-08-31",
                "proj-1",
                Instant.parse("2026-08-20T00:00:00Z"),
                "OPEN",
                List.of(),
                "当前筛选条件下没有记录",
                0,
                0,
                50,
                List.of(),
                List.of(),
                "a".repeat(64),
                "scope-1",
                List.of());
        var encoded = new QueryPageExcelEncoder().encode(page);
        assertThat(encoded.fileName()).isEqualTo("2026-08_月度工时统计表.xlsx");
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("月度工时统计表");
        }
    }

    @Test
    void workHoursExportUsesAttendanceFormula() throws Exception {
        var page = new QueryPage(
                "work-hours",
                "company-1",
                "2026-08-01",
                "2026-08-31",
                "proj-1",
                Instant.parse("2026-08-20T00:00:00Z"),
                "OPEN",
                List.of(),
                null,
                1,
                0,
                50,
                List.of(
                        Map.ofEntries(
                                Map.entry("employeeNumber", "SZST0487"),
                                Map.entry("employeeName", "赵俊杰"),
                                Map.entry("department", "DC部-软件设计组"),
                                Map.entry("scheduledHours", 168.0),
                                Map.entry("paidOvertimeHours", 4.25),
                                Map.entry("voluntaryOvertimeHours", 3.125),
                                Map.entry("leaveHours", 0.5),
                                Map.entry("annualLeaveHours", 0.25),
                                Map.entry("compensatoryOvertimeHours", 1.75),
                                Map.entry("timeOffHours", 0.125),
                                Map.entry("actualHours", 176.25),
                                Map.entry("note", "")),
                        Map.ofEntries(
                                Map.entry("employeeNumber", "SZST0001"),
                                Map.entry("employeeName", "测试员工"),
                                Map.entry("department", "测试部门"),
                                Map.entry("scheduledHours", 0.0),
                                Map.entry("paidOvertimeHours", 0.0),
                                Map.entry("voluntaryOvertimeHours", 0.0),
                                Map.entry("leaveHours", 0.0),
                                Map.entry("annualLeaveHours", 0.0),
                                Map.entry("compensatoryOvertimeHours", 0.0),
                                Map.entry("timeOffHours", 0.0),
                                Map.entry("actualHours", 0.0),
                                Map.entry("note", ""))),
                List.of(),
                "a".repeat(64),
                "scope-1",
                List.of());
        var encoded = new QueryPageExcelEncoder().encode(page);

        // Verify formula and cached result WITHOUT calling evaluateAll
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var sheet = workbook.getSheetAt(0);

            // Row 1: formula and cached result with non-half-hour decimals
            var row1K = sheet.getRow(1).getCell(10);
            assertThat(row1K.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(row1K.getCellFormula()).isEqualTo("D2+E2+F2-G2-H2+I2-J2");
            assertThat(row1K.getCachedFormulaResultType()).isEqualTo(CellType.NUMERIC);
            assertThat(row1K.getNumericCellValue()).isEqualTo(176.25);

            // Row 2: all zeros, cached result must be 0
            var row2K = sheet.getRow(2).getCell(10);
            assertThat(row2K.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(row2K.getCachedFormulaResultType()).isEqualTo(CellType.NUMERIC);
            assertThat(row2K.getNumericCellValue()).isEqualTo(0.0);

            // Total row: SUM formula with cached result
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue())
                    .isEqualTo("总计");
            var totalK = sheet.getRow(3).getCell(10);
            assertThat(totalK.getCellFormula()).isEqualTo("SUM(K2:K3)");
            assertThat(totalK.getCachedFormulaResultType()).isEqualTo(CellType.NUMERIC);
            assertThat(totalK.getNumericCellValue()).isEqualTo(176.25);

            // Verify D:J columns preserve non-half-hour precision
            assertThat(sheet.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(4.25);
            assertThat(sheet.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(3.125);
            assertThat(sheet.getRow(1).getCell(6).getNumericCellValue()).isEqualTo(0.5);
            assertThat(sheet.getRow(1).getCell(7).getNumericCellValue()).isEqualTo(0.25);
            assertThat(sheet.getRow(1).getCell(8).getNumericCellValue()).isEqualTo(1.75);
            assertThat(sheet.getRow(1).getCell(9).getNumericCellValue()).isEqualTo(0.125);
        }
    }

    @Test
    void absenceStatWorkbookBlanksZeroDaysAndOmitsZeroPeopleFromTitle() throws Exception {
        var page = new QueryPage(
                "absence-stat",
                "company-1",
                "2026-08-01",
                "2026-08-03",
                "proj-1",
                Instant.parse("2026-08-20T00:00:00Z"),
                "OPEN",
                List.of(),
                null,
                1,
                0,
                50,
                List.of(Map.of(
                        "employeeNumber", "SZST0007",
                        "employeeName", "钱七",
                        "department", "工程一部",
                        "absenceHours", 8.0,
                        "days", List.of(Map.of(
                                "date", LocalDate.of(2026, 8, 2),
                                "dayType", "WEEKDAY",
                                "hours", 8.0)))),
                List.of(),
                "a".repeat(64),
                "scope-1",
                List.of());
        var encoded = new QueryPageExcelEncoder().encode(page);
        assertThat(encoded.fileName()).isEqualTo("2026-08_旷工统计表.xlsx");
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("旷工统计表");
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue())
                    .isEqualTo("合计旷工");
            assertThat(sheet.getRow(2).getCell(2).getStringCellValue())
                    .isEqualTo("钱七");
            assertThat(sheet.getRow(2).getCell(3).getNumericCellValue())
                    .isEqualTo(8.0);
            assertThat(sheet.getRow(2).getCell(4).getStringCellValue())
                    .isEqualTo("");
            assertThat(sheet.getRow(2).getCell(5).getNumericCellValue())
                    .isEqualTo(8.0);
            assertThat(sheet.getRow(2).getCell(6).getStringCellValue())
                    .isEqualTo("");
        }
    }

    @Test
    void everyQuerySheetEncodesHeaders() throws Exception {
        var encoder = new QueryPageExcelEncoder();
        for (String sheet : List.of(
                "exceptions", "leave", "overtime", "makeup", "work-hours",
                "late", "missed-punch", "missed-punch-stat", "attendance-rate",
                "annual-leave", "annual-leave-stat", "time-off", "time-off-stat",
                "time-off-daily", "matrix", "finance-overtime",
                "overtime-fee-daily", "overtime-voluntary-daily",
                "overtime-comp-daily",
                "overtime-daily", "absence-stat", "leave-stat")) {
            var page = new QueryPage(
                    sheet,
                    "company-1",
                    "2026-08-01",
                    "2026-08-31",
                    "proj-1",
                    Instant.parse("2026-08-20T00:00:00Z"),
                    "OPEN",
                    List.of(),
                    "当前筛选条件下没有记录",
                    0,
                    0,
                    50,
                    List.of(),
                    List.of(),
                    "a".repeat(64),
                    "scope-1",
                    List.of());
            var encoded = encoder.encode(page);
            assertThat(encoded.content()[0]).isEqualTo((byte) 0x50);
            assertThat(encoded.fileName()).endsWith(".xlsx");
        }
    }

    @Test
    void annualLeaveStatWritesSlashForPreOpeningMonths() throws Exception {
        var page = new QueryPage(
                "annual-leave-stat",
                "company-1",
                "2026-01-01",
                "2026-12-31",
                "proj-1",
                Instant.parse("2026-08-20T00:00:00Z"),
                "OPEN",
                List.of(),
                null,
                1,
                0,
                50,
                List.of(java.util.Map.ofEntries(
                        java.util.Map.entry("sequence", 1),
                        java.util.Map.entry("levelOneDepartment", "服务中心"),
                        java.util.Map.entry("levelTwoDepartment", "工程一部"),
                        java.util.Map.entry("employeeName", "张三"),
                        java.util.Map.entry("hireDate", LocalDate.of(2020, 3, 1)),
                        java.util.Map.entry("companyTenureYears", 6.4),
                        java.util.Map.entry("priorTenureYears", 0),
                        java.util.Map.entry("cumulativeTenureYears", 6.4),
                        java.util.Map.entry("entitledDays", 5),
                        java.util.Map.entry("newHireCalendarDays", 0),
                        java.util.Map.entry("openingHours", 40),
                        java.util.Map.entry("remainingDays", 5),
                        java.util.Map.entry("remainingHours", 40),
                        java.util.Map.entry("usedMonth1", "/"),
                        java.util.Map.entry("usedMonth7", "/"),
                        java.util.Map.entry("usedMonth8", 0))),
                List.of(),
                "a".repeat(64),
                "scope-1",
                List.of());
        var encoded = new QueryPageExcelEncoder().encode(page);
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("年假统计表");
            assertThat(sheet.getRow(1).getCell(13).getStringCellValue()).isEqualTo("/");
            assertThat(sheet.getRow(0).getCell(9).getStringCellValue())
                    .contains("新员工");
            assertThat(sheet.getRow(0).getCell(10).getStringCellValue())
                    .isEqualTo("期初小时");
        }
    }

    @Test
    void missedPunchStatExportsMissFillAndMakeupText() throws Exception {
        var page = new QueryPage(
                "missed-punch-stat",
                "company-1",
                "2026-08-03",
                "2026-08-03",
                "proj-1",
                Instant.parse("2026-08-20T00:00:00Z"),
                "OPEN",
                List.of(),
                null,
                1,
                0,
                50,
                List.of(Map.of(
                        "sequence", 1,
                        "employeeNumber", "SZST0001",
                        "employeeName", "张三",
                        "department", "工程部",
                        "missedCount", 1,
                        "remark", "8月3日（上班）",
                        "days", List.of(Map.of(
                                "date", LocalDate.of(2026, 8, 3),
                                "morning", Map.of(
                                        "text", "漏刷",
                                        "tone", "MISSING_PUNCH"),
                                "afternoon", Map.of(
                                        "text", "补签08:18",
                                        "tone", "PUNCH_CORRECTION"))))),
                List.of(),
                "a".repeat(64),
                "scope-1",
                List.of());
        var encoded = new QueryPageExcelEncoder().encode(page);
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("忘打卡统计表");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("序号");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("部门");
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("姓名");
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue()).isEqualTo("次数");
            assertThat(sheet.getRow(0).getCell(4).getStringCellValue()).isEqualTo("备注");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("张三");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("1");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("8月3日（上班）");
        }
    }

    @Test
    void financeOvertimeClassifiesDayTypePerEmployee() throws Exception {
        // Bug reproduction: same date, different employees have different dayTypes
        // Employee A: 2026-08-10 is SUNDAY with 8 hours
        // Employee B: 2026-08-10 is WORKDAY with 8 hours
        // Each row's category subtotals must use that person's own classification
        var page = new QueryPage(
                "finance-overtime",
                "company-1",
                "2026-08-10",
                "2026-08-10",
                "proj-1",
                Instant.parse("2026-08-20T00:00:00Z"),
                "OPEN",
                List.of(),
                null,
                2,
                0,
                50,
                List.of(
                        Map.of(
                                "employeeNumber", "SZST0001",
                                "employeeName", "员工A",
                                "department", "工程一部",
                                "weekdayOvertimeHours", 0.0,
                                "weekendOvertimeHours", 8.0,
                                "holidayOvertimeHours", 0.0,
                                "days", List.of(Map.of(
                                        "date", LocalDate.of(2026, 8, 10),
                                        "dayType", "SUNDAY",
                                        "hours", 8.0))),
                        Map.of(
                                "employeeNumber", "SZST0002",
                                "employeeName", "员工B",
                                "department", "工程二部",
                                "weekdayOvertimeHours", 8.0,
                                "weekendOvertimeHours", 0.0,
                                "holidayOvertimeHours", 0.0,
                                "days", List.of(Map.of(
                                        "date", LocalDate.of(2026, 8, 10),
                                        "dayType", "WORKDAY",
                                        "hours", 8.0)))),
                List.of(),
                "a".repeat(64),
                "scope-1",
                List.of());
        var encoded = new QueryPageExcelEncoder().encode(page);
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var sheet = workbook.getSheetAt(0);
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateAll();

            // Row 2: Employee A has SUNDAY, so 8/10 should sum to weekend (col 4)
            var employeeA = sheet.getRow(2);
            assertThat(employeeA.getCell(2).getStringCellValue()).isEqualTo("员工A");
            assertThat(employeeA.getCell(3).getNumericCellValue()).isEqualTo(0.0); // weekday
            assertThat(employeeA.getCell(4).getNumericCellValue()).isEqualTo(8.0); // weekend
            assertThat(employeeA.getCell(5).getNumericCellValue()).isEqualTo(0.0); // holiday

            // Row 3: Employee B has WORKDAY, so 8/10 should sum to weekday (col 3)
            var employeeB = sheet.getRow(3);
            assertThat(employeeB.getCell(2).getStringCellValue()).isEqualTo("员工B");
            assertThat(employeeB.getCell(3).getNumericCellValue()).isEqualTo(8.0); // weekday
            assertThat(employeeB.getCell(4).getNumericCellValue()).isEqualTo(0.0); // weekend
            assertThat(employeeB.getCell(5).getNumericCellValue()).isEqualTo(0.0); // holiday
        }
    }
}
