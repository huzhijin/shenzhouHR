package com.szsemicon.hr.punchimport.infrastructure.excel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PunchWorkbookPolicy {

    public static final long DEFAULT_MAX_FILE_BYTES = 20L * 1024L * 1024L;
    public static final int DEFAULT_MAX_ROWS = 50_000;
    public static final int DEFAULT_MAX_ZIP_ENTRIES = 256;
    public static final long DEFAULT_MAX_UNCOMPRESSED_BYTES = 100L * 1024L * 1024L;
    public static final int DEFAULT_MAX_COMPRESSION_RATIO = 100;
    public static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public static final List<String> PUNCH_FIELDS = List.of(
            "employeeNumber",
            "devicePersonRef",
            "employeeNameForComparison",
            "departmentForComparison",
            "punchTime",
            "direction",
            "verificationMethod",
            "sourceRecordId",
            "sourceVersion",
            "eventCode",
            "deviceRef",
            "locationCode",
            "sourceTimeZone",
            "note");

    public static final List<String> DEVICE_MAPPING_FIELDS = List.of(
            "companyCode",
            "locationCode",
            "deviceRef",
            "deviceName",
            "devicePersonRef",
            "employeeNumber",
            "effectiveFrom",
            "effectiveTo",
            "sourceTimeZone");

    private static final Set<String> FORBIDDEN_RESULT_COLUMNS = Set.of(
            "lateminutes",
            "absence",
            "absenceminutes",
            "recognizedovertime",
            "exceptionresult",
            "dailyresult",
            "calculationresult");

    private static final Map<String, String> TRANSFORMS = Map.of(
            "column", "COLUMN",
            "date_parse", "DATE_PARSE",
            "time_parse", "TIME_PARSE",
            "timezone", "TIMEZONE",
            "trim", "TRIM",
            "enum_map", "ENUM_MAP");

    private PunchWorkbookPolicy() {
    }

    public static List<String> validateMappedHeaders(List<String> headers) {
        List<String> issues = new ArrayList<>();
        for (String header : headers) {
            String normalized = header.replaceAll("[\\s_\\-]", "")
                    .toLowerCase(Locale.ROOT);
            if (FORBIDDEN_RESULT_COLUMNS.contains(normalized)) {
                issues.add("FORBIDDEN_RESULT_COLUMN");
            }
        }
        return List.copyOf(issues);
    }

    public static String validateTransform(String transform) {
        if (transform == null) {
            throw new IllegalArgumentException("transform is required");
        }
        String canonical = TRANSFORMS.get(transform.toLowerCase(Locale.ROOT));
        if (canonical == null) {
            throw new IllegalArgumentException("unregistered mapping transform");
        }
        return canonical;
    }

    public static List<String> validateEnvelope(
            String filename,
            String contentType,
            byte[] prefix,
            long size) {
        List<String> issues = new ArrayList<>();
        if (size > DEFAULT_MAX_FILE_BYTES) {
            issues.add("FILE_TOO_LARGE");
        }
        boolean extension = filename != null
                && filename.toLowerCase(Locale.ROOT).endsWith(".xlsx");
        boolean zipMagic = prefix != null
                && prefix.length >= 4
                && prefix[0] == 'P'
                && prefix[1] == 'K'
                && prefix[2] == 3
                && prefix[3] == 4;
        boolean mimeAllowed = contentType == null
                || contentType.isBlank()
                || XLSX_CONTENT_TYPE.equalsIgnoreCase(contentType)
                || "application/octet-stream".equalsIgnoreCase(contentType);
        if (!extension || !zipMagic || !mimeAllowed) {
            issues.add("INVALID_XLSX_ENVELOPE");
        }
        return List.copyOf(issues);
    }

    public static void requireSafeZipEntryName(String entryName) {
        if (entryName == null
                || entryName.startsWith("/")
                || entryName.startsWith("\\")
                || entryName.contains("../")
                || entryName.contains("..\\")
                || entryName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("unsafe OOXML entry path");
        }
    }

    public static boolean containsForbiddenRelationship(byte[] xmlBytes) {
        String xml = new String(xmlBytes, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        return xml.contains("external")
                || xml.contains("oleobject")
                || xml.contains("embeddedpackage")
                || xml.contains("vbaproject")
                || xml.contains("dde");
    }
}
