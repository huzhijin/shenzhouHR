package com.szsemicon.hr.evidenceingestion.infrastructure.deli;

import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Direction;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.ConfirmedBindingKind;
import java.math.BigInteger;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class DeliEplusClient implements DeliPunchSourcePort {

    static final String API_PATH = "/v2.0/cloudappapi";
    static final int MAX_PAGE_SIZE = 500;

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
        payload.put("next_id", new BigInteger(inputNextId));
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
        List<DeliPunchRecord> records = page.records().stream()
                .map(record -> toPortRecord(
                        record, settings.sourceTimeZone()))
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
        return new CheckinRecord(
                id,
                userId,
                extId,
                terminalId,
                checkType,
                checkTime,
                checkDataDigest,
                checkDataDigest != null);
    }

    private DeliPunchRecord toPortRecord(
            CheckinRecord record, java.time.ZoneId sourceTimeZone) {
        boolean hasExtId = record.extId() != null;
        return new DeliPunchRecord(
                record.id(),
                recordDigest(record),
                hasExtId ? record.extId() : record.userId(),
                hasExtId
                        ? ConfirmedBindingKind.DELI_EXT_ID
                        : ConfirmedBindingKind.DELI_USER_ID,
                null,
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
            boolean checkDataPresent) {

        @Override
        public String toString() {
            return "CheckinRecord[id=<redacted>, userId=<redacted>, extId=<redacted>"
                    + ", terminalId=<redacted>, checkType=<redacted>"
                    + ", checkTime=<redacted>, checkDataDigest=<redacted>"
                    + ", checkDataPresent=" + checkDataPresent + "]";
        }
    }
}
