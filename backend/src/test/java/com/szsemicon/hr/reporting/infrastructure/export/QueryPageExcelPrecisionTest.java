package com.szsemicon.hr.reporting.infrastructure.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.application.AttendanceReportQueryPageService.QueryPage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QueryPageExcelPrecisionTest {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    @ParameterizedTest
    @CsvSource({"0.1,0.1", "0.2,0.2", "0,0", "8,8", "0.5,0.5", "2.5,2.5"})
    void dailyJournalDoesNotRoundQueryHoursAgain(String raw, String expected) throws Exception {
        var row = Map.<String, Object>of("employeeNumber", "SZJN0012",
                "employeeName", "员工", "department", "部门",
                "lateHours", new BigDecimal(raw), "earlyHours", new BigDecimal(raw));
        try (var book = workbook("daily-journal", row)) {
            assertThat(book.getSheetAt(0).getRow(1).getCell(8).getStringCellValue())
                    .isEqualTo(expected);
            assertThat(book.getSheetAt(0).getRow(1).getCell(9).getStringCellValue())
                    .isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @CsvSource({"0.06,0.06", "14.75,14.75", "-0.44,-0.44", "0,0", "8,8", "0.5,0.5"})
    void RemainingDaysKeepTheExactQueryPrecision(String raw, String expected) throws Exception {
        var row = Map.<String, Object>of("employeeNumber", "SZST0028",
                "employeeName", "员工", "department", "部门",
                "remainingDays", Double.parseDouble(raw));
        for (String sheet : List.of("annual-leave", "time-off")) {
            try (var book = workbook(sheet, row)) {
                assertThat(book.getSheetAt(0).getRow(1).getCell(9).getStringCellValue())
                        .as(sheet).isEqualTo(expected);
            }
        }
        for (String sheet : List.of("annual-leave-stat", "time-off-stat")) {
            try (var book = workbook(sheet, row)) {
                int column = "annual-leave-stat".equals(sheet) ? 11 : 6;
                assertThat(book.getSheetAt(0).getRow(1).getCell(column).getStringCellValue())
                        .as(sheet).isEqualTo(expected);
            }
        }
    }

    @Test
    void attendancePercentageStillUsesOneDecimalPlace() throws Exception {
        try (var book = workbook("attendance-rate", Map.of("attendanceRate", 98.765))) {
            assertThat(book.getSheetAt(0).getRow(1).getCell(5).getStringCellValue())
                    .isEqualTo("98.8%");
        }
    }

    private static XSSFWorkbook workbook(String sheet, Map<String, Object> row) throws Exception {
        var page = new QueryPage(sheet, "company-1", "2026-09-01", "2026-09-30",
                "proj-1", Instant.parse("2026-10-06T00:00:00Z"), "OPEN", List.of(), null,
                1, 0, 50, List.of(row), List.of(), "a".repeat(64), "scope-1", List.of());
        return new XSSFWorkbook(new ByteArrayInputStream(new QueryPageExcelEncoder().encode(page).content()));
    }
}
