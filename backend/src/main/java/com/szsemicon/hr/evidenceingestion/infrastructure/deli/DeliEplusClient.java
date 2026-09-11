package com.szsemicon.hr.evidenceingestion.infrastructure.deli;

import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Direction;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort.DeliEplusDepartment;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class DeliEplusClient implements DeliPunchSourcePort {

    private static final Logger log = LoggerFactory.getLogger(DeliEplusClient.class);

    static final String API_PATH = "/v2.0/cloudappapi";
    static final String EMPLOYEE_PATH = "/v2.0/employee/query";
    static final String DEPARTMENT_PATH = "/v2.0/department/query";
    static final int MAX_PAGE_SIZE = 500;
    static final int MAX_EMPLOYEE_PAGE_SIZE = 100;
    static final int MAX_DEPARTMENT_PAGE_SIZE = 100;

    private static final int MAX_CURSOR_DIGITS = 128;
    private static final int MAX_SOURCE_RECORD_ID_LENGTH = 191;
    private static final int MAX_EXTERNAL_PERSON_REF_LENGTH = 128;
    private static final int MAX_TERMINAL_ID_LENGTH = 191;
    private static final int MAX_CHECK_TYPE_LENGTH = 64;
    private static final int MAX_MEMBER_NAME_LENGTH = 100;

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
     * Fetches the complete Deli employee-directory id to employee-number map.
     *
     * <p>Directory {@code id} is not short CHECKIN {@code user_id}
     * (directory 387 is 彭伟, CHECKIN user_id 387 is 周步新). Snowflake-length
     * person ids may replace a colliding device empno. Request or response
     * failure is still propagated so synchronization cannot continue with a
     * partial directory snapshot.
     */
    @Override
    public Map<String, String> fetchEmployeeDirectory(String sourceId) {
        Map<String, String> result = new HashMap<>();
        int offset = 0;
        while (true) {
            EmployeePage page = queryEmployees(
                    offset, MAX_EMPLOYEE_PAGE_SIZE);
            for (EmployeeDirectoryEntry entry : page.records()) {
                if (entry.userId() != null
                        && !entry.userId().isBlank()
                        && entry.employeeNum() != null
                        && !entry.employeeNum().isBlank()) {
                    result.put(entry.userId(), entry.employeeNum());
                }
            }
            if (page.returnedRowCount() < MAX_EMPLOYEE_PAGE_SIZE) {
                break;
            }
            offset = Math.addExact(offset, page.returnedRowCount());
        }
        return Map.copyOf(result);
    }

    @Override
    public List<DeliPunchSourcePort.EmployeeDirectoryPerson>
            fetchEmployeeDirectoryPeople(String sourceId) {
        List<DeliPunchSourcePort.EmployeeDirectoryPerson> result =
                new ArrayList<>();
        int offset = 0;
        while (true) {
            EmployeePage page = queryEmployees(
                    offset, MAX_EMPLOYEE_PAGE_SIZE);
            for (EmployeeDirectoryEntry entry : page.records()) {
                if (entry.userId() == null || entry.userId().isBlank()) {
                    continue;
                }
                result.add(new DeliPunchSourcePort.EmployeeDirectoryPerson(
                        entry.userId(),
                        entry.employeeNum(),
                        entry.displayName()));
            }
            if (page.returnedRowCount() < MAX_EMPLOYEE_PAGE_SIZE) {
                break;
            }
            offset = Math.addExact(offset, page.returnedRowCount());
        }
        return List.copyOf(result);
    }

    /**
     * Fetches the complete Deli department directory. Used to confirm the
     * live org tree (customer: 55 departments) before punch paging. Punch
     * identity still binds through {@code employee_num}, not department id.
     */
    @Override
    public List<DeliEplusDepartment> fetchDepartmentDirectory(String sourceId) {
        List<DeliEplusDepartment> result = new ArrayList<>();
        int offset = 0;
        while (true) {
            DepartmentPage page = queryDepartments(
                    offset, MAX_DEPARTMENT_PAGE_SIZE);
            for (DepartmentDirectoryEntry entry : page.records()) {
                result.add(new DeliEplusDepartment(
                        entry.departmentId(),
                        entry.name(),
                        entry.parentId()));
            }
            if (page.returnedRowCount() < MAX_DEPARTMENT_PAGE_SIZE) {
                break;
            }
            offset = Math.addExact(offset, page.returnedRowCount());
        }
        return List.copyOf(result);
    }

    public DepartmentPage queryDepartments(int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException(
                    "Department query offset must be >= 0");
        }
        if (limit < 1 || limit > MAX_DEPARTMENT_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Department query limit must be between 1 and 100");
        }
        String timestamp = Long.toString(clock.millis());
        String signature = signer.sign(
                DEPARTMENT_PATH,
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
        URI departmentEndpoint = properties.getBaseUrl().resolve(DEPARTMENT_PATH);
        DeliEplusHttpRequest request = new DeliEplusHttpRequest(
                departmentEndpoint,
                headers,
                writePayload(payload));
        DeliEplusHttpResponse response = transport.post(request);
        if (response.statusCode() != 200) {
            throw new DeliEplusClientException(
                    "DELI_HTTP_FAILURE",
                    "Deli E+ department request failed with HTTP status "
                            + response.statusCode(),
                    response.statusCode() == 429 || response.statusCode() >= 500);
        }
        return parseDepartmentResponse(response.body(), offset, limit);
    }

    public CheckinPage queryCheckins(String nextId) {
        return queryCheckins(nextId, properties.getPageSize());
    }

    public CheckinPage queryCheckins(String nextId, int pageSize) {
        return queryCheckins(
                DeliPunchSourcePort.FetchSettings.MODULE_CHECKIN,
                nextId,
                pageSize);
    }

    public CheckinPage queryCheckins(
            String apiModule, String nextId, int pageSize) {
        return queryCheckins(apiModule, nextId, pageSize, false);
    }

    public CheckinPage queryCheckins(
            String apiModule,
            String nextId,
            int pageSize,
            boolean skipUnreadableRecords) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Deli E+ page_size must be between 1 and 500");
        }
        String module = normalizeModule(apiModule);
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
        headers.put("Api-Module", module);
        headers.put("Api-Cmd", "checkin_query");

        Map<String, Object> payload = new LinkedHashMap<>();
        // Deli E+ next_id fits in a signed 64-bit integer for real data, but
        // the test fixtures use values larger than Long.MAX_VALUE to exercise
        // the cursor-passing logic.  Serialize as a BigInteger so Jackson emits
        // a plain JSON integer regardless of magnitude.
        payload.put("next_id", new java.math.BigInteger(inputNextId));
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
        return parseResponse(
                response.body(),
                module,
                inputNextId,
                pageSize,
                skipUnreadableRecords);
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
                settings.apiModule(),
                committedCursor,
                settings.pageSize(),
                settings.skipUnreadableRecords());
        List<DeliPunchRecord> records = page.records().stream()
                .map(record -> toPortRecord(
                        record,
                        settings.sourceTimeZone(),
                        settings.employeeDirectory()))
                .filter(record -> retainForIngest(settings.apiModule(), record))
                .toList();
        return new DeliPage(
                records,
                page.inputNextId(),
                page.nextId(),
                pageDigest(sourceId, settings.apiModule(), page, records));
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
            String apiModule,
            String inputNextId,
            int requestedPageSize,
            boolean skipUnreadableRecords) {
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
                try {
                    records.add(parseRecord(row, apiModule));
                } catch (DeliEplusClientException exception) {
                    if (!skipUnreadableRecords
                            || !"DELI_INVALID_RESPONSE".equals(
                                    exception.safeCode())) {
                        throw exception;
                    }
                    log.warn("Skipping unreadable Deli {} punch row", apiModule);
                }
            }
            return new CheckinPage(inputNextId, nextId, records);
        } catch (DeliEplusClientException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse("Deli E+ returned an invalid response");
        }
    }

    /**
     * Parses an employee query response. The official response uses
     * {@code data.rows}; the older {@code data.data} and direct {@code data}
     * array envelopes remain accepted for backwards compatibility. Rows
     * without a usable {@code employee_num} are skipped, while the raw row
     * count is retained for correct offset pagination.
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
            JsonNode rows = employeeRows(root.get("data"));
            if (rows.size() > limit) {
                throw invalidResponse(
                        "Deli E+ returned too many employee records");
            }
            List<EmployeeDirectoryEntry> records = new ArrayList<>();
            for (JsonNode row : rows) {
                try {
                    EmployeeDirectoryEntry entry = parseEmployeeRow(row);
                    if (entry != null) {
                        records.add(entry);
                    }
                } catch (DeliEplusClientException exception) {
                    if (!"DELI_INVALID_RESPONSE".equals(exception.safeCode())) {
                        throw exception;
                    }
                }
            }
            return new EmployeePage(offset, rows.size(), records);
        } catch (DeliEplusClientException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse("Deli E+ returned an invalid response");
        }
    }

    private static JsonNode employeeRows(JsonNode data) {
        if (data == null) {
            throw invalidResponse("Deli E+ returned an invalid response");
        }
        if (data.isArray()) {
            return data;
        }
        if (!data.isObject()) {
            throw invalidResponse("Deli E+ returned an invalid response");
        }
        JsonNode rows = data.get("rows");
        if (rows == null) {
            rows = data.get("data");
        }
        if (rows == null || !rows.isArray()) {
            throw invalidResponse("Deli E+ returned an invalid response");
        }
        return rows;
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
        return new EmployeeDirectoryEntry(
                userId, employeeNum, optionalDisplayName(row));
    }

    /**
     * Names are optional for punch matching. A vendor name object, over-long
     * value or control character must not abort the whole directory page.
     */
    private static String optionalDisplayName(JsonNode row) {
        JsonNode node = firstNonNull(
                row, "name", "real_name", "member_name", "emp_name");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() && !node.isIntegralNumber()) {
            return null;
        }
        String value = node.asText();
        if (value.isBlank()
                || value.length() > 100
                || hasControlCharacter(value)) {
            return null;
        }
        return value;
    }

    private DepartmentPage parseDepartmentResponse(
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
                        "Deli E+ rejected the department query (code "
                                + code + ")",
                        code == 109 || code == 110);
            }
            JsonNode rows = employeeRows(root.get("data"));
            if (rows.size() > limit) {
                throw invalidResponse(
                        "Deli E+ returned too many department records");
            }
            List<DepartmentDirectoryEntry> records = new ArrayList<>();
            for (JsonNode row : rows) {
                DepartmentDirectoryEntry entry = parseDepartmentRow(row);
                if (entry != null) {
                    records.add(entry);
                }
            }
            return new DepartmentPage(offset, rows.size(), records);
        } catch (DeliEplusClientException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse("Deli E+ returned an invalid response");
        }
    }

    private DepartmentDirectoryEntry parseDepartmentRow(JsonNode row) {
        if (row == null || !row.isObject()) {
            return null;
        }
        String departmentId = optionalIdentifier(
                row.get("id"), "id", MAX_EXTERNAL_PERSON_REF_LENGTH);
        if (departmentId == null) {
            return null;
        }
        String name = optionalIdentifier(
                firstNonNull(row, "name", "dept_name", "department_name"),
                "name",
                200);
        String parentId = optionalIdentifier(
                firstNonNull(row, "parent_id", "pid", "parentId"),
                "parent_id",
                MAX_EXTERNAL_PERSON_REF_LENGTH);
        return new DepartmentDirectoryEntry(departmentId, name, parentId);
    }

    private static JsonNode firstNonNull(JsonNode row, String... names) {
        for (String name : names) {
            JsonNode value = row.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private CheckinRecord parseRecord(JsonNode row, String apiModule) {
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
        String terminalId = optionalIdentifier(
                row.get("terminal_id"),
                "terminal_id",
                MAX_TERMINAL_ID_LENGTH);
        String deviceSn = parseDeviceSn(row.get("check_data"));
        if (terminalId == null) {
            terminalId = deviceSn;
        }
        if (terminalId == null
                && DeliPunchSourcePort.FetchSettings.MODULE_KQ.equals(apiModule)) {
            String checkTypeHint = optionalIdentifier(
                    row.get("check_type"),
                    "check_type",
                    MAX_CHECK_TYPE_LENGTH);
            terminalId = checkTypeHint == null ? "KQ" : "KQ:" + checkTypeHint;
        }
        if (terminalId == null) {
            throw invalidResponse("Deli E+ terminal_id is invalid");
        }
        String checkType = requiredIdentifier(
                row.get("check_type"),
                "check_type",
                MAX_CHECK_TYPE_LENGTH);
        long checkTime = requiredEpochSecond(row.get("check_time"));
        String checkDataDigest = checkDataDigest(row.get("check_data"));
        // KQ returns empno on the row. CHECKIN embeds employee_num in
        // check_data JSON. Either value is used for HR matching.
        String employeeNum = optionalIdentifier(
                row.get("empno"),
                "empno",
                MAX_EXTERNAL_PERSON_REF_LENGTH);
        if (employeeNum == null) {
            employeeNum = checkDataField(
                    row.get("check_data"),
                    "employee_num",
                    MAX_EXTERNAL_PERSON_REF_LENGTH);
        }
        String memberName = optionalIdentifier(
                firstNonNull(row, "name", "member_name", "user_name"),
                "member_name",
                MAX_MEMBER_NAME_LENGTH);
        if (memberName == null) {
            memberName = checkDataField(
                    row.get("check_data"),
                    "member_name",
                    MAX_MEMBER_NAME_LENGTH);
        }
        return new CheckinRecord(
                id,
                userId,
                extId,
                terminalId,
                checkType,
                checkTime,
                checkDataDigest,
                checkDataDigest != null,
                employeeNum,
                memberName);
    }

    private DeliPunchRecord toPortRecord(
            CheckinRecord record,
            java.time.ZoneId sourceTimeZone,
            Map<String, String> employeeDirectory) {
        boolean hasExtId = record.extId() != null;
        // Punch identity is empno unless the person ref is a Deli snowflake.
        // Short CHECKIN user_id 387 is 周步新 while directory id 387 is 彭伟,
        // so short ids never overwrite empno. Unique member_name is applied
        // later by EvidenceResolutionPolicy against the HR roster.
        String personRef = hasExtId ? record.extId() : record.userId();
        String employeeNumber = record.checkDataEmployeeNum();
        if (isSnowflakePersonId(personRef) && employeeDirectory != null) {
            String directoryNumber = employeeDirectory.get(personRef);
            if (directoryNumber != null && !directoryNumber.isBlank()) {
                employeeNumber = directoryNumber;
            }
        }
        return new DeliPunchRecord(
                record.id(),
                recordDigest(record),
                personRef,
                hasExtId
                        ? ConfirmedBindingKind.DELI_EXT_ID
                        : ConfirmedBindingKind.DELI_USER_ID,
                employeeNumber,
                record.memberName(),
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

    private static boolean isSnowflakePersonId(String personRef) {
        if (personRef == null || personRef.length() < 16) {
            return false;
        }
        for (int index = 0; index < personRef.length(); index++) {
            if (!Character.isDigit(personRef.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean retainForIngest(String apiModule, DeliPunchRecord record) {
        Instant notBefore = properties.getKqIngestNotBefore();
        if (!DeliPunchSourcePort.FetchSettings.MODULE_KQ.equals(apiModule)
                || notBefore == null) {
            return true;
        }
        return !record.punchInstant().isBefore(notBefore);
    }

    private static String normalizeModule(String apiModule) {
        String value = apiModule == null || apiModule.isBlank()
                ? DeliPunchSourcePort.FetchSettings.MODULE_CHECKIN
                : apiModule.trim();
        if (!DeliPunchSourcePort.FetchSettings.MODULE_CHECKIN.equals(value)
                && !DeliPunchSourcePort.FetchSettings.MODULE_KQ.equals(value)) {
            throw new IllegalArgumentException(
                    "Deli E+ api module must be CHECKIN or KQ");
        }
        return value;
    }

    private static String parseDeviceSn(JsonNode checkData) {
        if (checkData == null || checkData.isNull()) {
            return null;
        }
        String raw = checkData.isTextual()
                ? checkData.asText()
                : checkData.toString();
        if (raw == null || raw.isBlank() || !raw.contains("|")) {
            return null;
        }
        String sn = raw.split("\\|", 2)[0].trim();
        if (sn.isBlank()
                || sn.length() > MAX_TERMINAL_ID_LENGTH
                || hasControlCharacter(sn)) {
            return null;
        }
        return sn;
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
        // CHECKIN returns next_id as a JSON number. KQ returns it as a
        // decimal string to keep snowflake-sized ids intact.
        if (node == null
                || (!node.isIntegralNumber() && !node.isTextual())) {
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

    private String checkDataField(JsonNode node, String field, int maxLength) {
        if (node == null || node.isNull() || field == null) {
            return null;
        }
        try {
            JsonNode parsed;
            if (node.isObject()) {
                parsed = node;
            } else {
                String raw = node.asText();
                if (raw == null || raw.isBlank()) {
                    return null;
                }
                parsed = objectMapper.readTree(raw);
            }
            JsonNode valueNode = parsed.get(field);
            if (valueNode == null || valueNode.isNull()) {
                return null;
            }
            String value = valueNode.asText();
            if (value == null || value.isBlank()
                    || value.length() > maxLength
                    || hasControlCharacter(value)) {
                return null;
            }
            return value;
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
            String apiModule,
            CheckinPage page,
            List<DeliPunchRecord> records) {
        List<String> fields = new ArrayList<>();
        fields.add(sourceId);
        fields.add(apiModule);
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
    public record DepartmentPage(
            int offset,
            int returnedRowCount,
            List<DepartmentDirectoryEntry> records) {

        public DepartmentPage {
            records = List.copyOf(records);
        }
    }

    public record DepartmentDirectoryEntry(
            String departmentId, String name, String parentId) {

        @Override
        public String toString() {
            return "DepartmentDirectoryEntry[departmentId=<redacted>"
                    + ", name=<redacted>, parentId=<redacted>]";
        }
    }

    public record EmployeeDirectoryEntry(
            String userId, String employeeNum, String displayName) {

        public EmployeeDirectoryEntry(String userId, String employeeNum) {
            this(userId, employeeNum, null);
        }

        @Override
        public String toString() {
            return "EmployeeDirectoryEntry[userId=<redacted>"
                    + ", employeeNum=<redacted>, displayName=<redacted>]";
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
            String checkDataEmployeeNum,
            String memberName) {

        @Override
        public String toString() {
            return "CheckinRecord[id=<redacted>, userId=<redacted>, extId=<redacted>"
                    + ", terminalId=<redacted>, checkType=<redacted>"
                    + ", checkTime=<redacted>, checkDataDigest=<redacted>"
                    + ", checkDataPresent=" + checkDataPresent + "]";
        }
    }
}
