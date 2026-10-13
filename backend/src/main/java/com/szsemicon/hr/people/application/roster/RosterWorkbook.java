package com.szsemicon.hr.people.application.roster;

import com.szsemicon.hr.people.application.PeopleWorkbookException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class RosterWorkbook {

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            DateTimeFormatter.ofPattern("yyyy年M月d日")
    };

    private RosterWorkbook() {
    }

    public static byte[] template() {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("花名册");
            Row header = sheet.createRow(0);
            for (int index = 0; index < RosterNames.HEADERS.size(); index++) {
                header.createCell(index).setCellValue(RosterNames.HEADERS.get(index));
                sheet.setColumnWidth(index, 18 * 256);
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new PeopleWorkbookException("无法生成花名册导入模板", exception);
        }
    }

    public static List<RosterRow> parse(byte[] content) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() < 1) {
                throw new PeopleWorkbookException("工作簿没有工作表");
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            int headerRowIndex = findHeaderRow(sheet, formatter);
            List<RosterRow> rows = new ArrayList<>();
            for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || rowIsBlank(row, formatter)) {
                    continue;
                }
                LocalDate hireDate = readDate(row.getCell(9), formatter, rowIndex + 1);
                rows.add(new RosterRow(
                        rowIndex + 1,
                        text(row.getCell(0), formatter),
                        text(row.getCell(1), formatter),
                        text(row.getCell(2), formatter),
                        text(row.getCell(3), formatter),
                        text(row.getCell(4), formatter),
                        text(row.getCell(5), formatter),
                        text(row.getCell(6), formatter),
                        text(row.getCell(7), formatter),
                        text(row.getCell(8), formatter),
                        hireDate));
            }
            if (rows.isEmpty()) {
                throw new PeopleWorkbookException("花名册没有数据行");
            }
            return List.copyOf(rows);
        } catch (PeopleWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PeopleWorkbookException("无法解析花名册 .xlsx", exception);
        }
    }

    private static int findHeaderRow(Sheet sheet, DataFormatter formatter) {
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (int column = 0; column < RosterNames.HEADERS.size(); column++) {
                cells.add(text(row.getCell(column), formatter));
            }
            if (RosterNames.headerEquals(cells)) {
                return rowIndex;
            }
        }
        throw new PeopleWorkbookException("未找到花名册表头");
    }

    private static boolean rowIsBlank(Row row, DataFormatter formatter) {
        for (int column = 0; column < RosterNames.HEADERS.size(); column++) {
            if (!text(row.getCell(column), formatter).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String text(Cell cell, DataFormatter formatter) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            throw new PeopleWorkbookException("工作簿不得包含公式");
        }
        return formatter.formatCellValue(cell).strip();
    }

    private static LocalDate readDate(Cell cell, DataFormatter formatter, int rowNumber) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            Date date = cell.getDateCellValue();
            return date.toInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return LocalDate.of(1899, 12, 30).plusDays((long) cell.getNumericCellValue());
        }
        String text = text(cell, formatter);
        if (text.isBlank()) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(text.replace(" ", ""), format);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        throw new PeopleWorkbookException("第 " + rowNumber + " 行入职日期无法解析: " + text);
    }
}
