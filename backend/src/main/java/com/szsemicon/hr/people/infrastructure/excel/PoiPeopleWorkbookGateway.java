package com.szsemicon.hr.people.infrastructure.excel;

import com.szsemicon.hr.people.application.PeopleWorkbookException;
import com.szsemicon.hr.people.application.PeopleWorkbookGateway;
import com.szsemicon.hr.people.domain.PeopleModels.ImportIssue;
import com.szsemicon.hr.people.domain.PeopleModels.MappingEntry;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateField;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateType;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateVersion;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class PoiPeopleWorkbookGateway implements PeopleWorkbookGateway {

    private static final String VERSION = "1.0.0";
    private static final Instant PUBLISHED_AT = Instant.parse("2026-07-25T00:00:00Z");
    private static final Set<String> MACRO_CONTENT_MARKERS =
            Set.of("vbaproject", "macroenabled", "activex", "oleobject");
    private static final int MAX_ZIP_ENTRIES = 2_048;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 128L * 1024 * 1024;
    private static final Set<String> EXACT_TEXT_FIELDS = Set.of(
            "organizationCode",
            "parentOrganizationCode",
            "employeeNumber",
            "externalEmployeeId");

    private final int maxRows;
    private final int maxSheets;
    private final Map<TemplateType, TemplateWorkbook> templates;

    public PoiPeopleWorkbookGateway(
            @Value("${shenzhouhr.people-import.max-rows:50000}") int maxRows,
            @Value("${shenzhouhr.people-import.max-sheets:8}") int maxSheets) {
        this.maxRows = maxRows;
        this.maxSheets = maxSheets;
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(32L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(8L * 1024 * 1024);
        Map<TemplateType, TemplateWorkbook> generated = new EnumMap<>(TemplateType.class);
        for (TemplateType type : TemplateType.values()) {
            generated.put(type, createTemplate(type));
        }
        this.templates = Map.copyOf(generated);
    }

    @Override
    public List<TemplateVersion> listTemplates(TemplateType type) {
        if (type == null) {
            return templates.values().stream()
                    .map(TemplateWorkbook::metadata)
                    .sorted((left, right) ->
                            left.templateType().compareTo(right.templateType()))
                    .toList();
        }
        TemplateWorkbook workbook = templates.get(type);
        return workbook == null ? List.of() : List.of(workbook.metadata());
    }

    @Override
    public TemplateWorkbook getTemplate(TemplateType type, String version) {
        TemplateWorkbook workbook = templates.get(type);
        if (workbook == null || !VERSION.equals(version)) {
            return null;
        }
        return new TemplateWorkbook(workbook.metadata(), workbook.content().clone());
    }

    @Override
    public ParsedWorkbook parse(
            byte[] content, TemplateType type, List<MappingEntry> mapping) {
        inspectZipEnvelope(content);
        inspectPackage(content);
        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(content), null)) {
            if (!(workbook instanceof XSSFWorkbook)) {
                throw new PeopleWorkbookException("仅支持 OOXML .xlsx 工作簿");
            }
            if (workbook.getNumberOfSheets() < 1 || workbook.getNumberOfSheets() > maxSheets) {
                throw new PeopleWorkbookException("工作表数量超出允许范围");
            }
            assertNoFormulaCells(workbook);
            Sheet data = workbook.getSheetAt(0);
            Row headerRow = data.getRow(data.getFirstRowNum());
            if (headerRow == null) {
                throw new PeopleWorkbookException("数据工作表缺少表头");
            }
            if (headerRow.getLastCellNum() > 100) {
                throw new PeopleWorkbookException("数据工作表列数超过允许上限");
            }
            Map<String, Integer> headerIndexes = headerIndexes(headerRow);
            Map<String, String> targetsBySource = new LinkedHashMap<>();
            Set<String> targetFields = new java.util.HashSet<>();
            for (MappingEntry entry : mapping) {
                if (!headerIndexes.containsKey(entry.sourceColumn())) {
                    throw new PeopleWorkbookException(
                            "字段映射引用了不存在的源列: " + entry.sourceColumn());
                }
                if (targetsBySource.put(entry.sourceColumn(), entry.targetField()) != null) {
                    throw new PeopleWorkbookException(
                            "字段映射包含重复源列: " + entry.sourceColumn());
                }
                if (!targetFields.add(entry.targetField())) {
                    throw new PeopleWorkbookException(
                            "字段映射包含重复目标字段: " + entry.targetField());
                }
            }
            Set<String> allowedTargets = fields(type).stream()
                    .map(TemplateField::key)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!allowedTargets.containsAll(targetsBySource.values())) {
                throw new PeopleWorkbookException("字段映射包含当前模板未定义的目标字段");
            }
            DataFormatter formatter = new DataFormatter();
            List<Map<String, Object>> rows = new ArrayList<>();
            int scanned = 0;
            for (int rowIndex = headerRow.getRowNum() + 1;
                    rowIndex <= data.getLastRowNum();
                    rowIndex++) {
                Row row = data.getRow(rowIndex);
                if (row == null || rowIsBlank(row, formatter)) {
                    continue;
                }
                scanned++;
                if (scanned > maxRows) {
                    throw new PeopleWorkbookException("数据行数超过允许上限");
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("_rowNumber", rowIndex + 1);
                for (Map.Entry<String, String> entry : targetsBySource.entrySet()) {
                    Cell cell = row.getCell(headerIndexes.get(entry.getKey()));
                    values.put(
                            entry.getValue(),
                            readCell(cell, formatter, entry.getValue(), rowIndex + 1));
                }
                rows.add(Map.copyOf(values));
            }
            return new ParsedWorkbook(List.copyOf(rows));
        } catch (PeopleWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PeopleWorkbookException("无法安全解析 .xlsx 工作簿", exception);
        }
    }

    @Override
    public byte[] createErrorReport(List<ImportIssue> issues) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.getProperties().getCoreProperties().setCreator("ShenzhouHR");
            workbook.getProperties().getCoreProperties().setCreated(PUBLISHED_AT.toString());
            workbook.getProperties().getCoreProperties().setModified(PUBLISHED_AT.toString());
            workbook.getProperties().getCoreProperties().setLastModifiedByUser("ShenzhouHR");
            Sheet sheet = workbook.createSheet("Errors");
            String[] headers = {
                "Row Number", "Field", "Code", "Severity", "Message", "Candidate Employee IDs"
            };
            Row header = sheet.createRow(0);
            Font font = workbook.createFont();
            font.setBold(true);
            var style = workbook.createCellStyle();
            style.setFont(font);
            for (int index = 0; index < headers.length; index++) {
                Cell cell = header.createCell(index);
                cell.setCellValue(headers[index]);
                cell.setCellStyle(style);
            }
            int rowIndex = 1;
            for (ImportIssue issue : issues) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(issue.rowNumber());
                row.createCell(1).setCellValue(nullToEmpty(issue.field()));
                row.createCell(2).setCellValue(issue.code());
                row.createCell(3).setCellValue(issue.severity().name());
                row.createCell(4).setCellValue(issue.message());
                row.createCell(5).setCellValue(String.join(",", issue.candidateEmployeeIds()));
            }
            setColumnWidths(sheet, 12, 24, 24, 12, 56, 36);
            workbook.write(output);
            return normalizeZip(output.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成错误行报告", exception);
        }
    }

    private TemplateWorkbook createTemplate(TemplateType type) {
        List<TemplateField> fields = fields(type);
        String fileName = "shenzhouhr-" + type.name().toLowerCase().replace('_', '-')
                + "-initial-import-" + VERSION + ".xlsx";
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.getProperties().getCoreProperties().setTitle(fileName);
            workbook.getProperties().getCoreProperties().setCreator("ShenzhouHR");
            workbook.getProperties().getCoreProperties().setCreated(PUBLISHED_AT.toString());
            workbook.getProperties().getCoreProperties().setModified(PUBLISHED_AT.toString());
            workbook.getProperties().getCoreProperties().setLastModifiedByUser("ShenzhouHR");
            Sheet data = workbook.createSheet("Data");
            Row header = data.createRow(0);
            Font font = workbook.createFont();
            font.setBold(true);
            var headerStyle = workbook.createCellStyle();
            headerStyle.setFont(font);
            var textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            for (int index = 0; index < fields.size(); index++) {
                Cell cell = header.createCell(index);
                cell.setCellValue(fields.get(index).label());
                cell.setCellStyle(headerStyle);
                data.setColumnWidth(index, 22 * 256);
                if (EXACT_TEXT_FIELDS.contains(fields.get(index).key())) {
                    data.setDefaultColumnStyle(index, textStyle);
                }
            }
            data.createFreezePane(0, 1);

            Sheet description = workbook.createSheet("Field Instructions");
            String[] descriptionHeaders = {
                "Key", "Label", "Required", "Value Type", "Match Key", "Description",
                "Allowed Values"
            };
            Row descriptionHeader = description.createRow(0);
            for (int index = 0; index < descriptionHeaders.length; index++) {
                Cell cell = descriptionHeader.createCell(index);
                cell.setCellValue(descriptionHeaders[index]);
                cell.setCellStyle(headerStyle);
            }
            int rowIndex = 1;
            for (TemplateField field : fields) {
                Row row = description.createRow(rowIndex++);
                row.createCell(0).setCellValue(field.key());
                row.createCell(1).setCellValue(field.label());
                row.createCell(2).setCellValue(field.required());
                row.createCell(3).setCellValue(field.valueType());
                row.createCell(4).setCellValue(field.matchKey());
                row.createCell(5).setCellValue(field.description());
                row.createCell(6).setCellValue(String.join(",", field.enumValues()));
            }
            setColumnWidths(description, 24, 24, 12, 16, 12, 72, 48);
            workbook.write(output);
            byte[] bytes = normalizeZip(output.toByteArray());
            return new TemplateWorkbook(
                    new TemplateVersion(
                            type, VERSION, fileName, sha256(bytes), PUBLISHED_AT, fields),
                    bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("无法构建版本化 Excel 模板", exception);
        }
    }

    private static List<TemplateField> fields(TemplateType type) {
        return switch (type) {
            case ORGANIZATION -> List.of(
                    field("organizationCode", "组织编码", true, "TEXT", true,
                            "法人主体内稳定且唯一；必须使用文本单元格以保留前导零和精确值"),
                    field("name", "组织名称", true, "TEXT", false,
                            "组织显示名称，不作为唯一匹配键"),
                    field("parentOrganizationCode", "上级组织编码", false, "TEXT", false,
                            "空值表示根组织；非空值必须使用文本单元格"),
                    enumField("organizationType", "组织类型", true,
                            List.of("COMPANY", "DEPARTMENT", "TEAM")),
                    field("effectiveFrom", "生效日期", true, "DATE", false,
                            "ISO 8601 日期，格式 YYYY-MM-DD"));
            case EMPLOYEE -> List.of(
                    field("employeeNumber", "员工编号", true, "TEXT", true,
                            "法人主体内稳定且唯一；必须使用文本单元格以保留前导零和精确值"),
                    field("externalEmployeeId", "外部精确员工ID", false, "PRECISE_ID", true,
                            "可选精确匹配键；必须使用文本单元格，姓名和部门永远不是唯一键"),
                    field("displayName", "姓名", true, "TEXT", false,
                            "员工显示姓名，不作为唯一匹配键"),
                    field("effectiveFrom", "生效日期", true, "DATE", false,
                            "ISO 8601 日期，格式 YYYY-MM-DD"));
            case EMPLOYMENT -> List.of(
                    field("employeeNumber", "员工编号", true, "TEXT", true,
                            "通过员工编号精确匹配员工；必须使用文本单元格"),
                    field("organizationCode", "组织编码", true, "TEXT", true,
                            "通过法人主体内组织编码精确匹配组织；必须使用文本单元格"),
                    field("startDate", "任职开始日", true, "DATE", false,
                            "半开区间起点，格式 YYYY-MM-DD"),
                    field("terminationDate", "业务离职日", false, "DATE", false,
                            "该日仍属于任职期，服务端转换为次日的 endExclusive"));
            case PRIOR_SERVICE -> List.of(
                    field("employeeNumber", "员工编号", true, "TEXT", true,
                            "通过员工编号精确匹配员工；必须使用文本单元格"),
                    field("amountDays", "发生天数", true, "INTEGER", false,
                            "入职前累计工龄发生额，范围 -36500 至 36500"),
                    field("businessDate", "业务日期", true, "DATE", false,
                            "发生额所属业务日期"),
                    field("reason", "原因", true, "TEXT", false,
                            "可追溯业务原因"));
        };
    }

    private static TemplateField field(
            String key, String label, boolean required, String valueType,
            boolean matchKey, String description) {
        return new TemplateField(
                key, label, required, valueType, matchKey, description, List.of());
    }

    private static TemplateField enumField(
            String key, String label, boolean required, List<String> allowed) {
        return new TemplateField(
                key, label, required, "ENUM", false, "仅允许字段说明中的枚举值", allowed);
    }

    private static void setColumnWidths(Sheet sheet, int... characterWidths) {
        for (int index = 0; index < characterWidths.length; index++) {
            sheet.setColumnWidth(index, characterWidths[index] * 256);
        }
    }

    private static void inspectPackage(byte[] content) {
        try (OPCPackage packageFile = OPCPackage.open(
                new ByteArrayInputStream(content), true)) {
            for (PackageRelationship relationship : packageFile.getRelationships()) {
                rejectExternalRelationship(relationship);
            }
            for (PackagePart part : packageFile.getParts()) {
                String descriptor = (
                        part.getPartName().getName() + " " + part.getContentType())
                        .toLowerCase(Locale.ROOT);
                if (MACRO_CONTENT_MARKERS.stream().anyMatch(descriptor::contains)) {
                    throw new PeopleWorkbookException("工作簿不得包含宏或嵌入式可执行对象");
                }
                if (part.isRelationshipPart()) {
                    continue;
                }
                for (PackageRelationship relationship : part.getRelationships()) {
                    rejectExternalRelationship(relationship);
                }
            }
        } catch (PeopleWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PeopleWorkbookException("文件不是有效且安全的 .xlsx 工作簿", exception);
        }
    }

    private static void inspectZipEnvelope(byte[] content) {
        Set<String> names = new java.util.HashSet<>();
        byte[] buffer = new byte[8 * 1024];
        int entryCount = 0;
        long totalBytes = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw new PeopleWorkbookException("工作簿 ZIP 条目数量超出允许上限");
                }
                String name = entry.getName();
                String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
                if (normalized.isBlank()
                        || normalized.startsWith("/")
                        || normalized.contains("\\")
                        || normalized.contains("../")
                        || normalized.contains("//")
                        || !names.add(normalized)) {
                    throw new PeopleWorkbookException("工作簿 ZIP 包含异常或重复条目");
                }
                int read;
                while ((read = input.read(buffer)) != -1) {
                    totalBytes = Math.addExact(totalBytes, read);
                    if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        throw new PeopleWorkbookException("工作簿 ZIP 累计解压大小超出允许上限");
                    }
                }
                input.closeEntry();
            }
            if (entryCount == 0) {
                throw new PeopleWorkbookException("文件不是有效的 .xlsx ZIP 容器");
            }
        } catch (PeopleWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PeopleWorkbookException("无法安全读取 .xlsx ZIP 容器", exception);
        }
    }

    private static void rejectExternalRelationship(PackageRelationship relationship) {
        if (relationship.getTargetMode() == TargetMode.EXTERNAL) {
            throw new PeopleWorkbookException("工作簿不得包含外部链接");
        }
    }

    private void assertNoFormulaCells(Workbook workbook) {
        long inspectedCells = 0;
        long maxInspectedCells = Math.multiplyExact((long) maxRows, 16L);
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    inspectedCells++;
                    if (inspectedCells > maxInspectedCells) {
                        throw new PeopleWorkbookException("工作簿单元格数量超出允许上限");
                    }
                    if (cell.getCellType() == CellType.FORMULA) {
                        throw new PeopleWorkbookException("工作簿不得包含公式");
                    }
                }
            }
        }
    }

    private static byte[] normalizeZip(byte[] source) {
        try {
            Map<String, byte[]> entries = new TreeMap<>();
            try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(source))) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        entries.put(entry.getName(), input.readAllBytes());
                    }
                }
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                    ZipOutputStream zip = new ZipOutputStream(output)) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    ZipEntry normalized = new ZipEntry(entry.getKey());
                    normalized.setTime(0L);
                    zip.putNextEntry(normalized);
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
                zip.finish();
                return output.toByteArray();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("无法规范化 Excel ZIP 容器", exception);
        }
    }

    private static Map<String, Integer> headerIndexes(Row row) {
        DataFormatter formatter = new DataFormatter();
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (Cell cell : row) {
            String header = formatter.formatCellValue(cell).trim();
            if (!header.isEmpty() && indexes.put(header, cell.getColumnIndex()) != null) {
                throw new PeopleWorkbookException("数据工作表包含重复表头: " + header);
            }
        }
        return Map.copyOf(indexes);
    }

    private static boolean rowIsBlank(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static Object readCell(
            Cell cell, DataFormatter formatter, String targetField, int rowNumber) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        if (EXACT_TEXT_FIELDS.contains(targetField)
                && cell.getCellType() != CellType.STRING) {
            throw new PeopleWorkbookException(
                    "第 " + rowNumber + " 行字段 " + targetField
                            + " 必须使用文本单元格，禁止数值精度或前导零丢失");
        }
        if (cell.getCellType() == CellType.NUMERIC
                && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (Math.rint(value) == value) {
                return Long.toString((long) value);
            }
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
