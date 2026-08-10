package com.szsemicon.hr.evidenceingestion.infrastructure.deli;

import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Direction;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.ConfirmedBindingKind;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class DeliEplusClient implements DeliPunchSourcePort {

    static final String API_PATH = "/v2.0/cloudappapi";
    static final String EMPLOYEE_PATH = "/v2.0/employee/query";
    static final int MAX_PAGE_SIZE = 500;
    static final int MAX_EMPLOYEE_PAGE_SIZE = 100;

    private static final int MAX_CURSOR_DIGITS = 128;
    private static final int MAX_SOURCE_RECORD_ID_LENGTH = 191;
    private static final int MAX_EXTERNAL_PERSON_REF_LENGTH = 128;
    private static final int MAX_TERMINAL_ID_LENGTH = 191;
    private static final int MAX_CHECK_TYPE_LENGTH = 64;

    private final DeliEplusProperties properties;
    private final DeliEplusSigner signer;
    private final DeliEplusHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    DeliEplusClient(
            DeliEplusProperties properties,
            DeliEplusSigner signer,
            DeliEplusHttpTransport transport,
            ObjectMapper objectMapper,
            Clock clock) {
        properties.validateForEnabledClient();
        this.properties = properties;
        this.signer = Objects.requireNonNull(signer, "signer");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Fetches one page of the Deli E+ employee directory.
     *
     * <p>The employee query endpoint uses the same MD5 signing scheme as the
     * check-in query but has a different path and no {@code Api-Module} /
     * {@code Api-Cmd} headers.</p>
     *
     * @param offset zero-based row offset
     * @param limit  rows per page, 1–100
     * @return parsed employee page
     */
    public EmployeePage queryEmployees(int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("Employee query offset must be >= 0");
        }
        if (limit < 1 || limit > MAX_EMPLOYEE_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Employee query limit must be between 1 and 100");
        }
        String timestamp = Long.toString(clock.millis());
        String signature = signer.sign(
                EMPLOYEE_PATH,
                timestamp,
                properties.getAppKey(),
                properties.getAppSecret());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=UTF-8");
        headers.put("App-Key", properties.getAppKey());
        headers.put("App-Timestamp", timestamp);
        headers.put("App-Sig", signature);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offset", offset);
        payload.put("limit", limit);
        URI employeeEndpoint = properties.getBaseUrl().resolve(EMPLOYEE_PATH);
        DeliEplusHttpRequest request = new DeliEplusHttpRequest(
                employeeEndpoint,
                headers,
                writePayload(payload));
        DeliEplusHttpResponse response = transport.post(request);
        if (response.statusCode() != 200) {
            throw new DeliEplusClientException(
                    "DELI_HTTP_FAILURE",
                    "Deli E+ employee request failed with HTTP status "
                            + response.statusCode(),
                    response.statusCode() == 429 || response.statusCode() >= 500);
        }
        return parseEmployeeResponse(response.body(), offset, limit);
    }

    /**
     * Fetches the complete userId-to-employeeNumber directory by paging
     * through all employees. Returns an empty map on error so that the
     * caller falls back to confirmed-binding resolution.
     */
    @Override
    public Map<String, String> fetchEmployeeDirectory(String sourceId) {
        Map<String, String> result = new HashMap<>();
        int offset = 0;
        while (true) {
            EmployeePage page;
            try {
                page = queryEmployees(offset, MAX_EMPLOYEE_PAGE_SIZE);
            } catch (DeliEplusClientException exception) {
                break;
            }
            for (EmployeeDirectoryEntry entry : page.records()) {
                if (entry.userId() != null
                        && !entry.userId().isBlank()
                        && entry.employeeNum() != null
                        && !entry.employeeNum().isBlank()) {
                    result.put(entry.userId(), entry.employeeNum());
                }
            }
            if (page.records().size() < MAX_EMPLOYEE_PAGE_SIZE) {
                break;
            }
            offset += MAX_EMPLOYEE_PAGE_SIZE;
        }
        return Map.copyOf(result);
    }

    public CheckinPage queryCheckins(String nextId) {
        return queryCheckins(nextId, properties.getPageSize());
    }

    public CheckinPage queryCheckins(String nextId, int pageSize) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Deli E+ page_size must be between 1 and 500");
        }
        String inputNextId = normalizeCursor(nextId);
        String timestamp = Long.toString(clock.millis());
        String signature = signer.sign(
                API_PATH,
                timestamp,
                properties.getAppKey(),
                properties.getAppSecret());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=UTF-8");
        headers.put("App-Key", properties.getAppKey());
        headers.put("App-Timestamp", timestamp);
        headers.put("App-Sig", signature);
        headers.put("Api-Module", "CHECKIN");
        headers.put("Api-Cmd", "checkin_query");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("next_id", Long.parseLong(inputNextId));
        payload.put("page_size", pageSize);
        DeliEplusHttpRequest request = new DeliEplusHttpRequest(
                endpoint(),
                headers,
                writePayload(payload));
        DeliEplusHttpResponse response = transport.post(request);
        if (response.statusCode() != 200) {
            throw new DeliEplusClientException(
                    "DELI_HTTP_FAILURE",
                    "Deli E+ request failed with HTTP status " + response.statusCode(),
                    response.statusCode() == 429 || response.statusCode() >= 500);
        }
        return parseResponse(response.body(), inputNextId, pageSize);
    }

    @Override
    public boolean productionIntegration() {
        return true;
    }

    @Override
    public String credentialReferenceName() {
        return properties.getCredentialReferenceName();
    }

    @Override
    public DeliPage fetchPage(String sourceId, String committedCursor) {
        return fetchPage(
                sourceId,
                committedCursor,
                new FetchSettings(
                        properties.getPageSize(),
                        properties.getSourceTimeZone()));
    }

    @Override
    public DeliPage fetchPage(
            String sourceId,
            String committedCursor,
            FetchSettings settings) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId is required");
        }
        Objects.requireNonNull(settings, "settings");
        CheckinPage page = queryCheckins(
                committedCursor, settings.pageSize());
        Map<String, String> directory = settings.employeeDirectory();
        List<DeliPunchRecord> records = page.records().stream()
                .map(record -> toPortRecord(
                        record, settings.sourceTimeZone(), directory))
                .toList();
        return new DeliPage(
                records,
                page.inputNextId(),
                page.nextId(),
                pageDigest(sourceId, page, records));
    }

    private URI endpoint() {
        return properties.getBaseUrl().resolve(API_PATH);
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw invalidResponse("Deli E+ request payload could not be encoded");
        }
    }

    private CheckinPage parseResponse(
            String responseBody,
            String inputNextId,
            int requestedPageSize) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root == null || !root.isObject()) {
                throw invalidResponse("Deli E+ returned an invalid response");
            }
            int code = requiredInteger(root.get("code"));
            if (code != 0) {
                throw new DeliEplusClientException(
                        "DELI_VENDOR_FAILURE",
                        "Deli E+ rejected the request (code " + code + ")",
                        code == 109 || code == 110);
            }
            JsonNode data = root.get("data");
            if (data == null || !data.isObject()) {
                throw invalidResponse("Deli E+ returned an invalid response");
            }
            String nextId = requiredCursorInteger(data.get("next_id"));
            JsonNode rows = data.get("data");
            if (rows == null || !rows.isArray()) {
                throw invalidResponse("Deli E+ returned an invalid response");
            }
            if (rows.size() > requestedPageSize) {
                throw invalidResponse("Deli E+ returned too many check-in records");
            }
            List<CheckinRecord> records = new ArrayList<>();
            for (JsonNode row : rows) {
                records.add(parseRecord(row));
            }
            return new CheckinPage(inputNextId, nextId, records);
        } catch (DeliEplusClientException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse("Deli E+ returned an invalid response");
        }
    }

    /**
     * Parses an employee query response. The vendor wraps rows either in
     * {@code data.data} or directly in {@code data}, so both shapes are
     * accepted. Rows without a usable {@code employee_num} are skipped
     * rather than failing the page, because a partially bound directory is
     * still better than none.
     */
    private EmployeePage parseEmployeeResponse(
            String responseBody, int offset, int limit) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root == null || !root.isObject()) {
                throw invalidResponse("Deli E+ returned an invalid response");
            }
            int code = requiredInteger(root.get("code"));
            if (code != 0) {
                throw new DeliEplusClientException(
                        "DELI_VENDOR_FAILURE",
                        "Deli E+ rejected the employee query (code "
                                + code + ")",
                        code == 109 || code == 110);
            }
            JsonNode data = root.get("data");
            if (data == null) {
                throw invalidResponse("Deli E+ returned an invalid response");
            }
            JsonNode rows = data.isArray() ? data : data.get("data");
            if (rows == null || !rows.isArray()) {
                throw invalidResponse("Deli E+ returned an invalid response");
            }
            if (rows.size() > limit) {
                throw invalidResponse(
                        "Deli E+ returned too many employee records");
            }
            List<EmployeeDirectoryEntry> records = new ArrayList<>();
            for (JsonNode row : rows) {
                EmployeeDirectoryEntry entry = parseEmployeeRow(row);
                if (entry != null) {
                    records.add(entry);
                }
            }
            return new EmployeePage(offset, rows.size(), records);
        } catch (DeliEplusClientException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse("Deli E+ returned an invalid response");
        }
    }

    private EmployeeDirectoryEntry parseEmployeeRow(JsonNode row) {
        if (row == null || !row.isObject()) {
            return null;
        }
        String userId = optionalIdentifier(
                row.get("id"), "id", MAX_EXTERNAL_PERSON_REF_LENGTH);
        String employeeNum = optionalIdentifier(
                row.get("employee_num"),
                "employee_num",
                MAX_EXTERNAL_PERSON_REF_LENGTH);
        if (userId == null || employeeNum == null) {
            return null;
        }
        return new EmployeeDirectoryEntry(userId, employeeNum);
    }

    private CheckinRecord parseRecord(JsonNode row) {
        if (row == null || !row.isObject()) {
            throw invalidResponse("Deli E+ returned an invalid check-in record");
        }
        String id = requiredIdentifier(
                row.get("id"), "id", MAX_SOURCE_RECORD_ID_LENGTH);
        String userId = requiredIdentifier(
                row.get("user_id"),
                "user_id",
                MAX_EXTERNAL_PERSON_REF_LENGTH);
        String extId = optionalIdentifier(
                row.get("ext_id"),
                "ext_id",
                MAX_EXTERNAL_PERSON_REF_LENGTH);
        String terminalId = requiredIdentifier(
                row.get("terminal_id"),
                "terminal_id",
                MAX_TERMINAL_ID_LENGTH);
        String checkType = requiredIdentifier(
                row.get("check_type"),
                "check_type",
                MAX_CHECK_TYPE_LENGTH);
        long checkTime = requiredEpochSecond(row.get("check_time"));
        String checkDataDigest = checkDataDigest(row.get("check_data"));
        // Extract employee_num from check_data JSON string — Deli embeds the HR
        // employee number in check_data so it can be used for matching even when
        // no ext_id binding has been configured.
        String checkDataEmployeeNum = checkDataEmployeeNum(row.get("check_data"));
        return new CheckinRecord(
                id,
                userId,
                extId,
                terminalId,
                checkType,
                checkTime,
                checkDataDigest,
                checkDataDigest != null,
                checkDataEmployeeNum);
    }

    private DeliPunchRecord toPortRecord(
            CheckinRecord record,
            java.time.ZoneId sourceTimeZone,
            Map<String, String> employeeDirectory) {
        boolean hasExtId = record.extId() != null;
        // Resolve employee number: prefer directory lookup (Deli userId → empNum),
        // then check_data.employee_num, then fall through to null (will quarantine).
        String employeeNumber = employeeDirectory.get(record.userId());
        if (employeeNumber == null) {
            employeeNumber = record.checkDataEmployeeNum();
        }
        return new DeliPunchRecord(
                record.id(),
                recordDigest(record),
                hasExtId ? record.extId() : record.userId(),
                hasExtId
                        ? ConfirmedBindingKind.DELI_EXT_ID
                        : ConfirmedBindingKind.DELI_USER_ID,
                employeeNumber,
                Instant.ofEpochSecond(record.checkTime()),
                Long.toString(record.checkTime()),
                sourceTimeZone.getId(),
                Direction.AUTO,
                record.checkType(),
                record.terminalId(),
                null,
                "UNKNOWN",
                record.checkDataPresent());
    }

    private static String normalizeCursor(String nextId) {
        String value = nextId == null ? "0" : nextId;
        if (value.isBlank()
                || value.length() > MAX_CURSOR_DIGITS
                || !value.chars().allMatch(Character::isDigit)
                || hasNonCanonicalLeadingZero(value)) {
            throw new IllegalArgumentException(
                    "Deli E+ next_id must be an unsigned integer");
        }
        return value;
    }

    private static String requiredCursorInteger(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) {
            throw invalidResponse("Deli E+ next_id is invalid");
        }
        String value = node.asText();
        if (value.isBlank()
                || value.length() > MAX_CURSOR_DIGITS
                || !value.chars().allMatch(Character::isDigit)
                || hasNonCanonicalLeadingZero(value)) {
            throw invalidResponse("Deli E+ next_id is invalid");
        }
        return value;
    }

    private static int requiredInteger(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) {
            throw invalidResponse("Deli E+ response code is invalid");
        }
        try {
            return Integer.parseInt(node.asText());
        } catch (NumberFormatException exception) {
            throw invalidResponse("Deli E+ response code is invalid");
        }
    }

    private static String requiredUnsignedInteger(JsonNode node, String field) {
        if (node == null
                || (!node.isIntegralNumber() && !node.isTextual())) {
            throw invalidResponse("Deli E+ " + field + " is invalid");
        }
        String value = node.asText();
        if (value.isBlank()
                || value.length() > MAX_CURSOR_DIGITS
                || !value.chars().allMatch(Character::isDigit)) {
            throw invalidResponse("Deli E+ " + field + " is invalid");
        }
        return value;
    }

    private static String requiredIdentifier(
            JsonNode node,
            String field,
            int maximumLength) {
        if (node == null
                || (!node.isIntegralNumber() && !node.isTextual())) {
            throw invalidResponse("Deli E+ " + field + " is invalid");
        }
        String value = node.asText();
        if (value.isBlank()
                || value.length() > maximumLength
                || hasControlCharacter(value)) {
            throw invalidResponse("Deli E+ " + field + " is invalid");
        }
        return value;
    }

    private static String optionalIdentifier(
            JsonNode node,
            String field,
            int maximumLength) {
        if (node == null || node.isNull()) {
            return null;
        }
        // Deli returns empty string "" for absent ext_id — treat as absent.
        if (node.isTextual() && node.asText().isBlank()) {
            return null;
        }
        return requiredIdentifier(node, field, maximumLength);
    }

    private static long requiredEpochSecond(JsonNode node) {
        String value = requiredUnsignedInteger(node, "check_time");
        try {
            long epochSecond = Long.parseLong(value);
            Instant.ofEpochSecond(epochSecond);
            return epochSecond;
        } catch (NumberFormatException | DateTimeException exception) {
            throw invalidResponse("Deli E+ check_time is invalid");
        }
    }

    private String checkDataEmployeeNum(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            // check_data is a JSON-encoded string like
            // "{\"employee_num\":\"SZST0487\",\"member_name\":\"赵俊杰\",...}"
            String raw = node.asText();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            JsonNode parsed = objectMapper.readTree(raw);
            JsonNode empNumNode = parsed.get("employee_num");
            if (empNumNode == null || empNumNode.isNull()) {
                return null;
            }
            String empNum = empNumNode.asText();
            if (empNum == null || empNum.isBlank()
                    || empNum.length() > MAX_EXTERNAL_PERSON_REF_LENGTH
                    || hasControlCharacter(empNum)) {
                return null;
            }
            return empNum;
        } catch (Exception exception) {
            return null; // check_data is optional; don't fail the record
        }
    }

    private String checkDataDigest(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        MessageDigest digest = newSha256();
        try (DigestOutputStream output = new DigestOutputStream(
                java.io.OutputStream.nullOutputStream(),
                digest)) {
            objectMapper.writeValue(output, node);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw invalidResponse("Deli E+ check_data is invalid");
        }
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static boolean hasNonCanonicalLeadingZero(String value) {
        return value.length() > 1 && value.charAt(0) == '0';
    }

    private static String recordDigest(CheckinRecord record) {
        return sha256(List.of(
                record.id(),
                record.userId(),
                nullable(record.extId()),
                record.terminalId(),
                record.checkType(),
                Long.toString(record.checkTime()),
                nullable(record.checkDataDigest())));
    }

    private static String pageDigest(
            String sourceId,
            CheckinPage page,
            List<DeliPunchRecord> records) {
        List<String> fields = new ArrayList<>();
        fields.add(sourceId);
        fields.add(page.inputNextId());
        fields.add(page.nextId());
        records.forEach(record -> {
            fields.add(record.sourceRecordId());
            fields.add(record.sourceVersion());
        });
        return sha256(fields);
    }

    private static String sha256(List<String> fields) {
        MessageDigest digest = newSha256();
        for (String field : fields) {
            byte[] value = field.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(value.length)
                    .array());
            digest.update(value);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required digest algorithm is unavailable");
        }
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static DeliEplusClientException invalidResponse(String safeMessage) {
        return new DeliEplusClientException(
                "DELI_INVALID_RESPONSE", safeMessage, false);
    }

    /**
     * One page of the Deli E+ employee directory. {@code returnedRowCount} is
     * the raw row count before unusable rows were skipped, so the caller can
     * detect the last page even when some rows carried no employee number.
     */
    public record EmployeePage(
            int offset,
            int returnedRowCount,
            List<EmployeeDirectoryEntry> records) {

        public EmployeePage {
            records = List.copyOf(records);
        }

        @Override
        public String toString() {
            return "EmployeePage[offset=" + offset
                    + ", returnedRowCount=" + returnedRowCount
                    + ", recordCount=" + records.size() + "]";
        }
    }

    /**
     * A Deli userId to employee-number pair. Both values are personal
     * identifiers, so {@code toString} redacts them.
     */
    public record EmployeeDirectoryEntry(
            String userId, String employeeNum) {

        @Override
        public String toString() {
            return "EmployeeDirectoryEntry[userId=<redacted>"
                    + ", employeeNum=<redacted>]";
        }
    }

    public record CheckinPage(
            String inputNextId,
            String nextId,
            List<CheckinRecord> records) {

        public CheckinPage {
            records = List.copyOf(records);
        }

        @Override
        public String toString() {
            return "CheckinPage[inputNextId=<redacted>"
                    + ", nextId=<redacted>"
                    + ", recordCount=" + records.size() + "]";
        }
    }

    public record CheckinRecord(
            String id,
            String userId,
            String extId,
            String terminalId,
            String checkType,
            long checkTime,
            String checkDataDigest,
            boolean checkDataPresent,
            String checkDataEmployeeNum) {

        @Override
        public String toString() {
            return "CheckinRecord[id=<redacted>, userId=<redacted>, extId=<redacted>"
                    + ", terminalId=<redacted>, checkType=<redacted>"
                    + ", checkTime=<redacted>, checkDataDigest=<redacted>"
                    + ", checkDataPresent=" + checkDataPresent + "]";
        }
    }
}
