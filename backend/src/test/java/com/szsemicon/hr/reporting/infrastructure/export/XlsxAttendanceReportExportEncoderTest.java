package com.szsemicon.hr.reporting.infrastructure.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportColumn;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportDataSet;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportRow;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import java.io.ByteArrayInputStream;
import java.time.YearMonth;
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
    void userControlledFormulaLikeTextRemainsAStringCell()
            throws Exception {
        String formulaLikeValue =
                "=HYPERLINK(\"https://invalid.example\",\"click\")";
        var dataSet = new ReportDataSet(
                ReportType.ATTENDANCE_DETAIL,
                "考勤明细",
                List.of(new ReportColumn(ReportField.EMPLOYEE_NAME)),
                List.of(ReportField.EMPLOYEE_NAME),
                List.of(new ReportRow(
                        "row-1",
                        Map.of(
                                ReportField.EMPLOYEE_NAME,
                                formulaLikeValue),
                        null)),
                "formula-v1");

        var encoded = new XlsxAttendanceReportExportEncoder()
                .encode(dataSet, YearMonth.of(2026, 7));

        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(encoded.content()))) {
            var cell = workbook.getSheetAt(0).getRow(1).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue())
                    .isEqualTo(formulaLikeValue);
        }
    }
}
