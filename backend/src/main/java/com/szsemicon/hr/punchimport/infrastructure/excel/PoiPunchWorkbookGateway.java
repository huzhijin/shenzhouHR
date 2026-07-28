package com.szsemicon.hr.punchimport.infrastructure.excel;

import com.szsemicon.hr.punchimport.application.PunchImportExceptions.UnsafeWorkbookException;
import com.szsemicon.hr.punchimport.application.PunchWorkbookGateway;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
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
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class PoiPunchWorkbookGateway implements PunchWorkbookGateway {

    public static final String TEMPLATE_VERSION = "1.0.0";
    private static final Instant TEMPLATE_PUBLISHED_AT =
            Instant.parse("2026-07-28T00:00:00Z");
    private static final List<String> SHEETS = List.of(
            "导入说明",
            "考勤打卡导入",
            "设备人员映射（可选）",
            "字段说明",
            "枚举值",
            "示例数据");
    private static final Set<String> FORBIDDEN_PACKAGE_MARKERS = Set.of(
            "vbaproject", "macroenabled", "activex", "oleobject",
            "externalLink", "embeddedpackage");

    private final TemplateWorkbook template;

    public PoiPunchWorkbookGateway() {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(32L * 1024L * 1024L);
        ZipSecureFile.setMaxTextSize(8L * 1024L * 1024L);
        template = generateTemplate();
    }

    @Override
    public TemplateWorkbook currentTemplate() {
        return new TemplateWorkbook(
                template.templateVersion(),
                template.fieldContractDigest(),
                template.fileSha256(),
                template.filename(),
                template.content());
    }

    @Override
    public ParsedWorkbook parse(
            byte[] content,
            String filename,
            String contentType) {
        List<String> envelopeIssues = PunchWorkbookPolicy.validateEnvelope(
                filename, contentType, prefix(content), content.length);
        if (!envelopeIssues.isEmpty()) {
            throw unsafe(envelopeIssues.getFirst(), "文件不是允许的安全 .xlsx");
        }
        inspectZip(content);
        inspectPackage(content);
        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(content))) {
            if (!(workbook instanceof XSSFWorkbook)) {
                throw unsafe("INVALID_XLSX_ENVELOPE", "仅支持 OOXML .xlsx");
            }
            rejectFormulaCells(workbook);
            Sheet punchSheet = requiredSheet(workbook, "考勤打卡导入");
            Sheet mappingSheet = requiredSheet(workbook, "设备人员映射（可选）");
            List<Map<String, String>> punchRows = readRows(
                    punchSheet, PunchWorkbookPolicy.PUNCH_FIELDS);
            List<Map<String, String>> mappingRows = readRows(
                    mappingSheet, PunchWorkbookPolicy.DEVICE_MAPPING_FIELDS);
            if (punchRows.size() + mappingRows.size()
                    > PunchWorkbookPolicy.DEFAULT_MAX_ROWS) {
                throw unsafe("ROW_LIMIT_EXCEEDED", "数据行数超过 50000");
            }
            String version = customProperty(workbook, "templateVersion");
            String contract = customProperty(workbook, "fieldContractSha256");
            if (version == null) {
                version = "HETEROGENEOUS";
            }
            if (contract == null) {
                contract = fieldContractDigest();
            }
            return new ParsedWorkbook(
                    version,
                    contract,
                    punchRows,
                    mappingRows);
        } catch (UnsafeWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new UnsafeWorkbookException(
                    "XLSX_PARSE_FAILED",
                    "无法安全解析 .xlsx 工作簿",
                    exception);
        }
    }

    private static TemplateWorkbook generateTemplate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.getProperties().getCoreProperties()
                    .setTitle("神州 HR 原始打卡导入模板");
            workbook.getProperties().getCoreProperties().setCreator("ShenzhouHR");
            workbook.getProperties().getCoreProperties()
                    .setCreated(TEMPLATE_PUBLISHED_AT.toString());
            workbook.getProperties().getCoreProperties()
                    .setModified(TEMPLATE_PUBLISHED_AT.toString());
            workbook.getProperties().getCoreProperties()
                    .setLastModifiedByUser("ShenzhouHR");
            workbook.getProperties().getCustomProperties()
                    .addProperty("templateVersion", TEMPLATE_VERSION);
            workbook.getProperties().getCustomProperties()
                    .addProperty("fieldContractSha256", fieldContractDigest());

            var headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Sheet instructions = workbook.createSheet(SHEETS.get(0));
            instructions.createRow(0).createCell(0)
                    .setCellValue("仅导入原始打卡事实；不得填写迟到、旷工、认可加班或薪资结果。");
            instructions.createRow(1).createCell(0)
                    .setCellValue("姓名与部门仅用于核对，不作为员工唯一匹配键。");
            instructions.createRow(2).createCell(0)
                    .setCellValue("所有示例均为 SYNTHETIC 合成数据。");
            instructions.setColumnWidth(0, 96 * 256);

            Sheet punch = workbook.createSheet(SHEETS.get(1));
            writeHeader(punch, PunchWorkbookPolicy.PUNCH_FIELDS, headerStyle);
            punch.createFreezePane(0, 1);

            Sheet mappings = workbook.createSheet(SHEETS.get(2));
            writeHeader(mappings, PunchWorkbookPolicy.DEVICE_MAPPING_FIELDS, headerStyle);
            mappings.createFreezePane(0, 1);

            Sheet fieldDescriptions = workbook.createSheet(SHEETS.get(3));
            writeHeader(
                    fieldDescriptions,
                    List.of("field", "scope", "required", "description"),
                    headerStyle);
            int fieldRow = 1;
            for (String field : PunchWorkbookPolicy.PUNCH_FIELDS) {
                Row row = fieldDescriptions.createRow(fieldRow++);
                row.createCell(0).setCellValue(field);
                row.createCell(1).setCellValue("PUNCH");
                row.createCell(2).setCellValue(
                        Set.of("punchTime", "direction", "sourceTimeZone").contains(field));
                row.createCell(3).setCellValue("受控原始来源字段");
            }
            for (String field : PunchWorkbookPolicy.DEVICE_MAPPING_FIELDS) {
                Row row = fieldDescriptions.createRow(fieldRow++);
                row.createCell(0).setCellValue(field);
                row.createCell(1).setCellValue("DEVICE_PERSON_MAPPING");
                row.createCell(2).setCellValue(
                        !Set.of("deviceName", "effectiveTo").contains(field));
                row.createCell(3).setCellValue("受控设备人员绑定字段");
            }

            Sheet enums = workbook.createSheet(SHEETS.get(4));
            writeHeader(enums, List.of("field", "allowedValue"), headerStyle);
            int enumRow = 1;
            for (String direction : List.of("AUTO", "IN", "OUT")) {
                Row row = enums.createRow(enumRow++);
                row.createCell(0).setCellValue("direction");
                row.createCell(1).setCellValue(direction);
            }
            for (String method : List.of("CARD", "PIN", "MOBILE", "OTHER")) {
                Row row = enums.createRow(enumRow++);
                row.createCell(0).setCellValue("verificationMethod");
                row.createCell(1).setCellValue(method);
            }

            Sheet examples = workbook.createSheet(SHEETS.get(5));
            writeHeader(examples, PunchWorkbookPolicy.PUNCH_FIELDS, headerStyle);
            Row example = examples.createRow(1);
            List<String> exampleValues = List.of(
                    "SYNTHETIC-E001",
                    "SYNTHETIC-PERSON-001",
                    "合成员工甲",
                    "合成部门",
                    "2026-07-28 09:00:00",
                    "IN",
                    "CARD",
                    "922337203685477580812345",
                    "1",
                    "SYNTHETIC-PUNCH",
                    "SYNTHETIC-DEVICE-001",
                    "SYNTHETIC-LOCATION",
                    "Asia/Shanghai",
                    "SYNTHETIC ONLY");
            for (int index = 0; index < exampleValues.size(); index++) {
                example.createCell(index).setCellValue(exampleValues.get(index));
            }

            workbook.write(output);
            byte[] normalized = normalizeZip(output.toByteArray());
            String filename = "shenzhouhr-attendance-punch-template-"
                    + TEMPLATE_VERSION + ".xlsx";
            return new TemplateWorkbook(
                    TEMPLATE_VERSION,
                    fieldContractDigest(),
                    sha256(normalized),
                    filename,
                    normalized);
        } catch (Exception exception) {
            throw new IllegalStateException("unable to generate punch template", exception);
        }
    }

    private static List<Map<String, String>> readRows(
            Sheet sheet,
            List<String> allowedFields) {
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) {
            throw unsafe("MISSING_HEADER", "数据工作表缺少表头");
        }
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        Map<Integer, String> headers = new LinkedHashMap<>();
        for (Cell cell : header) {
            String value = formatter.formatCellValue(cell).trim();
            if (!value.isEmpty()) {
                headers.put(cell.getColumnIndex(), value);
            }
        }
        List<String> policyIssues = PunchWorkbookPolicy.validateMappedHeaders(
                List.copyOf(headers.values()));
        if (!policyIssues.isEmpty()) {
            throw unsafe(policyIssues.getFirst(), "工作簿包含禁止的计算结果列");
        }
        Set<String> allowed = Set.copyOf(allowedFields);
        List<Map<String, String>> result = new ArrayList<>();
        for (int rowIndex = header.getRowNum() + 1;
                rowIndex <= sheet.getLastRowNum();
                rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            boolean nonBlank = false;
            for (Map.Entry<Integer, String> entry : headers.entrySet()) {
                String field = entry.getValue();
                if (!allowed.contains(field)) {
                    continue;
                }
                Cell cell = row.getCell(entry.getKey());
                String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                values.put(field, value);
                nonBlank |= !value.isEmpty();
            }
            if (nonBlank) {
                values.put("_rowNumber", Integer.toString(rowIndex + 1));
                result.add(Map.copyOf(values));
            }
        }
        return List.copyOf(result);
    }

    private static void rejectFormulaCells(Workbook workbook) {
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.FORMULA) {
                        throw unsafe("FORMULA_FORBIDDEN", "工作簿不得包含公式");
                    }
                }
            }
        }
    }

    private static void inspectZip(byte[] content) {
        int entries = 0;
        long total = 0;
        try (ZipInputStream input =
                new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                PunchWorkbookPolicy.requireSafeZipEntryName(entry.getName());
                entries++;
                if (entries > PunchWorkbookPolicy.DEFAULT_MAX_ZIP_ENTRIES) {
                    throw unsafe("ZIP_ENTRY_LIMIT", "OOXML 条目数量超限");
                }
                long entryBytes = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > 32L * 1024L * 1024L
                            || total > PunchWorkbookPolicy.DEFAULT_MAX_UNCOMPRESSED_BYTES) {
                        throw unsafe("ZIP_EXPANSION_LIMIT", "OOXML 解压大小超限");
                    }
                }
                long compressed = entry.getCompressedSize();
                if (compressed > 0
                        && entryBytes / compressed
                        > PunchWorkbookPolicy.DEFAULT_MAX_COMPRESSION_RATIO) {
                    throw unsafe("ZIP_COMPRESSION_RATIO", "OOXML 压缩比异常");
                }
            }
        } catch (UnsafeWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new UnsafeWorkbookException(
                    "INVALID_ZIP",
                    "无法安全读取 OOXML 包",
                    exception);
        }
    }

    private static void inspectPackage(byte[] content) {
        try (OPCPackage packageFile = OPCPackage.open(
                new ByteArrayInputStream(content), true)) {
            for (PackageRelationship relationship : packageFile.getRelationships()) {
                rejectRelationship(relationship);
            }
            for (PackagePart part : packageFile.getParts()) {
                String descriptor = (
                        part.getPartName().getName() + " " + part.getContentType())
                        .toLowerCase(Locale.ROOT);
                if (FORBIDDEN_PACKAGE_MARKERS.stream()
                        .map(marker -> marker.toLowerCase(Locale.ROOT))
                        .anyMatch(descriptor::contains)) {
                    throw unsafe("ACTIVE_CONTENT_FORBIDDEN",
                            "工作簿包含宏、外链或嵌入对象");
                }
                if (part.isRelationshipPart()) {
                    continue;
                }
                for (PackageRelationship relationship : part.getRelationships()) {
                    rejectRelationship(relationship);
                }
            }
        } catch (UnsafeWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new UnsafeWorkbookException(
                    "INVALID_OOXML_PACKAGE",
                    "无法安全检查 OOXML 包",
                    exception);
        }
    }

    private static void rejectRelationship(PackageRelationship relationship) {
        String relationshipType =
                relationship.getRelationshipType().toLowerCase(Locale.ROOT);
        String descriptor = (
                relationshipType + " " + relationship.getTargetURI())
                .toLowerCase(Locale.ROOT);
        boolean embeddedPackage = relationshipType.contains("officedocument")
                && relationshipType.endsWith("/relationships/package");
        if (relationship.getTargetMode() == TargetMode.EXTERNAL
                || descriptor.contains("externallink")
                || descriptor.contains("oleobject")
                || embeddedPackage) {
            throw unsafe("EXTERNAL_CONTENT_FORBIDDEN",
                    "工作簿包含外部关系或嵌入对象");
        }
    }

    private static Sheet requiredSheet(Workbook workbook, String name) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) {
            throw unsafe("MISSING_REQUIRED_SHEET", "缺少工作表: " + name);
        }
        return sheet;
    }

    private static String customProperty(Workbook workbook, String name) {
        if (!(workbook instanceof XSSFWorkbook xssf)) {
            return null;
        }
        var property = xssf.getProperties().getCustomProperties().getProperty(name);
        return property == null ? null : property.getLpwstr();
    }

    private static void writeHeader(
            Sheet sheet,
            List<String> headers,
            org.apache.poi.ss.usermodel.CellStyle style) {
        Row row = sheet.createRow(0);
        for (int index = 0; index < headers.size(); index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(headers.get(index));
            cell.setCellStyle(style);
            sheet.setColumnWidth(index, 22 * 256);
        }
    }

    private static String fieldContractDigest() {
        String contract = String.join("\n", PunchWorkbookPolicy.PUNCH_FIELDS)
                + "\n--DEVICE--\n"
                + String.join("\n", PunchWorkbookPolicy.DEVICE_MAPPING_FIELDS);
        return sha256(contract.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] prefix(byte[] content) {
        return java.util.Arrays.copyOf(content, Math.min(content.length, 8));
    }

    private static byte[] normalizeZip(byte[] source) {
        try (ZipInputStream input =
                        new ZipInputStream(new ByteArrayInputStream(source));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream output = new ZipOutputStream(bytes)) {
            Map<String, byte[]> entries = new TreeMap<>();
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries.put(entry.getName(), input.readAllBytes());
            }
            for (Map.Entry<String, byte[]> value : entries.entrySet()) {
                ZipEntry normalized = new ZipEntry(value.getKey());
                normalized.setTime(0L);
                output.putNextEntry(normalized);
                output.write(value.getValue());
                output.closeEntry();
            }
            output.finish();
            return bytes.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("unable to normalize template zip", exception);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static UnsafeWorkbookException unsafe(String code, String message) {
        return new UnsafeWorkbookException(code, message);
    }
}
