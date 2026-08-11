package com.szsemicon.hr.evidenceingestion.infrastructure.deli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Direction;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.ConfirmedBindingKind;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DeliEplusClientTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1710000000123L),
            ZoneOffset.UTC);
    private static final String APP_KEY = "example-app-key";
    private static final String APP_SECRET = "example-app-secret";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void postsCanonicalSignedQueryAndParsesEveryRequiredCheckinField() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        transport.enqueue(okResponse("""
                {
                  "code": 0,
                  "msg": "",
                  "data": {
                    "next_id": 922337203685477580812399,
                    "data": [{
                      "id": 922337203685477580812345,
                      "user_id": "DEMO-USER-001",
                      "ext_id": "DEMO-EMP-001",
                      "terminal_id": "DEMO-TERMINAL-001",
                      "check_type": "fp",
                      "check_time": 1710000000,
                      "check_data": {
                        "device_name": "示例考勤机",
                        "location": "demo-location"
                      }
                    }]
                  }
                }
                """));
        DeliEplusClient client = client(transport);

        DeliEplusClient.CheckinPage page = client.queryCheckins(
                "922337203685477580812300",
                500);

        DeliEplusHttpRequest request = transport.requests().getFirst();
        assertThat(request.uri())
                .isEqualTo(URI.create("https://v2-api.delicloud.com/v2.0/cloudappapi"));
        assertThat(request.headers())
                .containsEntry("Content-Type", "application/json; charset=UTF-8")
                .containsEntry("App-Key", APP_KEY)
                .containsEntry("App-Timestamp", "1710000000123")
                .containsEntry("App-Sig", "99e1a2aae86fc44b0b138cf0d931655d")
                .containsEntry("Api-Module", "CHECKIN")
                .containsEntry("Api-Cmd", "checkin_query");
        assertThat(request.headers().values()).doesNotContain("checkin_query_init");

        JsonNode payload = objectMapper.readTree(request.body());
        assertThat(payload.get("next_id").asText())
                .isEqualTo("922337203685477580812300");
        assertThat(payload.get("page_size").asText()).isEqualTo("500");
        assertThat(page.inputNextId()).isEqualTo("922337203685477580812300");
        assertThat(page.nextId()).isEqualTo("922337203685477580812399");
        assertThat(page.records()).hasSize(1);

        DeliEplusClient.CheckinRecord record = page.records().getFirst();
        assertThat(record.id()).isEqualTo("922337203685477580812345");
        assertThat(record.userId()).isEqualTo("DEMO-USER-001");
        assertThat(record.extId()).isEqualTo("DEMO-EMP-001");
        assertThat(record.terminalId()).isEqualTo("DEMO-TERMINAL-001");
        assertThat(record.checkType()).isEqualTo("fp");
        assertThat(record.checkTime()).isEqualTo(1710000000L);
        assertThat(record.checkDataDigest()).matches("[0-9a-f]{64}");
        assertThat(record.checkDataPresent()).isTrue();
        assertThat(record.toString())
                .doesNotContain(
                        record.id(),
                        record.userId(),
                        record.extId(),
                        record.terminalId(),
                        record.checkType(),
                        "示例考勤机",
                        "demo-location");
        assertThat(request.toString())
                .doesNotContain(APP_KEY, APP_SECRET, request.body());
    }

    @Test
    void sendsReturnedNextIdUnchangedOnTheFollowingPage() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        String nextId = "922337203685477580812345";
        transport.enqueue(okResponse(emptyPage(nextId)));
        transport.enqueue(okResponse(emptyPage(nextId)));
        DeliEplusClient client = client(transport);

        DeliEplusClient.CheckinPage firstPage = client.queryCheckins(null, 1);
        client.queryCheckins(firstPage.nextId(), 1);

        JsonNode firstPayload = objectMapper.readTree(transport.requests().get(0).body());
        JsonNode secondPayload = objectMapper.readTree(transport.requests().get(1).body());
        assertThat(firstPayload.get("next_id").asText()).isEqualTo("0");
        assertThat(secondPayload.get("next_id").asText()).isEqualTo(nextId);
        assertThat(secondPayload.get("page_size").asInt()).isEqualTo(1);
    }

    @Test
    void employeeDirectoryUsesOfficialRowsAndRawRowCountForPagination()
            throws Exception {
        CapturingTransport transport = new CapturingTransport();
        transport.enqueue(okResponse(employeeRowsPage(0, 100, 37)));
        transport.enqueue(okResponse(employeeRowsPage(100, 1, -1)));

        Map<String, String> directory = client(transport)
                .fetchEmployeeDirectory("source-demo-deli");

        assertThat(directory)
                .hasSize(100)
                .doesNotContainKey("DEMO-USER-037")
                .containsEntry("DEMO-USER-100", "DEMO-EMP-100");
        assertThat(transport.requests()).hasSize(2);
        DeliEplusHttpRequest firstRequest = transport.requests().get(0);
        DeliEplusHttpRequest secondRequest = transport.requests().get(1);
        assertThat(firstRequest.uri()).isEqualTo(
                URI.create("https://v2-api.delicloud.com/v2.0/employee/query"));
        assertThat(firstRequest.headers())
                .doesNotContainKeys("Api-Module", "Api-Cmd");
        JsonNode firstPayload = objectMapper.readTree(firstRequest.body());
        JsonNode secondPayload = objectMapper.readTree(secondRequest.body());
        assertThat(firstPayload.get("offset").asInt()).isZero();
        assertThat(firstPayload.get("limit").asInt()).isEqualTo(100);
        assertThat(secondPayload.get("offset").asInt()).isEqualTo(100);
        assertThat(secondPayload.get("limit").asInt()).isEqualTo(100);
    }

    @Test
    void employeeDirectoryFailureDoesNotReturnPartialResults() {
        CapturingTransport transport = new CapturingTransport();
        transport.enqueue(okResponse(employeeRowsPage(0, 100, -1)));
        transport.enqueue(okResponse("""
                {
                  "code": 0,
                  "msg": "must-not-appear-in-errors",
                  "data": {"rows": "invalid-sensitive-directory"}
                }
                """));

        DeliEplusClientException exception = catchClientException(
                () -> client(transport)
                        .fetchEmployeeDirectory("source-demo-deli"));

        assertThat(exception.safeCode()).isEqualTo("DELI_INVALID_RESPONSE");
        assertThat(exception.getMessage())
                .isEqualTo("Deli E+ returned an invalid response")
                .doesNotContain(
                        "must-not-appear-in-errors",
                        "invalid-sensitive-directory",
                        APP_KEY,
                        APP_SECRET);
        assertThat(transport.requests()).hasSize(2);
    }

    @Test
    void employeeQueryKeepsLegacyRowEnvelopesCompatible() {
        CapturingTransport transport = new CapturingTransport();
        transport.enqueue(okResponse("""
                {
                  "code": 0,
                  "data": {
                    "data": [{
                      "id": "DEMO-USER-LEGACY-1",
                      "employee_num": "DEMO-EMP-LEGACY-1"
                    }]
                  }
                }
                """));
        transport.enqueue(okResponse("""
                {
                  "code": 0,
                  "data": [{
                    "id": "DEMO-USER-LEGACY-2",
                    "employee_num": "DEMO-EMP-LEGACY-2"
                  }]
                }
                """));
        DeliEplusClient client = client(transport);

        assertThat(client.queryEmployees(0, 1).records().getFirst())
                .isEqualTo(new DeliEplusClient.EmployeeDirectoryEntry(
                        "DEMO-USER-LEGACY-1", "DEMO-EMP-LEGACY-1"));
        assertThat(client.queryEmployees(1, 1).records().getFirst())
                .isEqualTo(new DeliEplusClient.EmployeeDirectoryEntry(
                        "DEMO-USER-LEGACY-2", "DEMO-EMP-LEGACY-2"));
    }

    @Test
    void enforcesPageSizeAndCursorBoundariesBeforeTransport() {
        CapturingTransport transport = new CapturingTransport();
        DeliEplusClient client = client(transport);

        assertThatThrownBy(() -> client.queryCheckins("0", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 500");
        assertThatThrownBy(() -> client.queryCheckins("0", 501))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 500");
        assertThatThrownBy(() -> client.queryCheckins("-1", 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsigned integer");
        assertThatThrownBy(() -> client.queryCheckins("cursor+1", 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsigned integer");
        assertThatThrownBy(() -> client.queryCheckins("001", 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsigned integer");
        assertThat(transport.requests()).isEmpty();
    }

    @Test
    void doesNotInventOrderingForVendorCursorAndRejectsMalformedRecords() {
        CapturingTransport opaque = new CapturingTransport();
        opaque.enqueue(okResponse(emptyPage("99")));
        DeliEplusClient opaqueClient = client(opaque);

        assertThat(opaqueClient.queryCheckins("100", 50).nextId())
                .isEqualTo("99");

        CapturingTransport malformed = new CapturingTransport();
        malformed.enqueue(okResponse("""
                {
                  "code": 0,
                  "msg": "",
                  "data": {
                    "next_id": 1,
                    "data": [{
                      "id": "DEMO-ID",
                      "user_id": "DEMO-USER",
                      "ext_id": "DEMO-EMP",
                      "check_type": "fp",
                      "check_time": 1710000000,
                      "check_data": "{}"
                    }]
                  }
                }
                """));

        assertThatThrownBy(() -> client(malformed).queryCheckins("0", 50))
                .isInstanceOf(DeliEplusClientException.class)
                .hasMessage("Deli E+ terminal_id is invalid");
    }

    @Test
    void failsWholePageOnAnyOverlongIdentifierWithoutEchoingVendorValues() {
        List<OverlongIdentifier> cases = List.of(
                new OverlongIdentifier("id", 192),
                new OverlongIdentifier("user_id", 129),
                new OverlongIdentifier("ext_id", 129),
                new OverlongIdentifier("terminal_id", 192),
                new OverlongIdentifier("check_type", 65));

        for (OverlongIdentifier testCase : cases) {
            String overlong = "x".repeat(testCase.length());
            String id = "id".equals(testCase.field()) ? overlong : "RECORD-1";
            String userId =
                    "user_id".equals(testCase.field()) ? overlong : "USER-1";
            String extId =
                    "ext_id".equals(testCase.field()) ? overlong : "EMP-1";
            String terminalId = "terminal_id".equals(testCase.field())
                    ? overlong
                    : "TERMINAL-1";
            String checkType = "check_type".equals(testCase.field())
                    ? overlong
                    : "fp";
            CapturingTransport transport = new CapturingTransport();
            transport.enqueue(okResponse("""
                    {
                      "code": 0,
                      "data": {
                        "next_id": 1,
                        "data": [{
                          "id": "%s",
                          "user_id": "%s",
                          "ext_id": "%s",
                          "terminal_id": "%s",
                          "check_type": "%s",
                          "check_time": 1710000000,
                          "check_data": "must-not-appear-in-errors"
                        }]
                      }
                    }
                    """.formatted(
                            id, userId, extId, terminalId, checkType)));

            DeliEplusClientException exception = catchClientException(
                    () -> client(transport).queryCheckins("0", 50));

            assertThat(exception.safeCode()).isEqualTo("DELI_INVALID_RESPONSE");
            assertThat(exception.getMessage())
                    .isEqualTo(
                            "Deli E+ " + testCase.field() + " is invalid")
                    .doesNotContain(overlong, "must-not-appear-in-errors");
        }
    }

    @Test
    void rejectsAResponseThatExceedsTheRequestedRecordCount() {
        CapturingTransport transport = new CapturingTransport();
        transport.enqueue(okResponse("""
                {
                  "code": 0,
                  "data": {
                    "next_id": 2,
                    "data": [
                      {
                        "id": "RECORD-1",
                        "user_id": "USER-1",
                        "terminal_id": "TERMINAL-1",
                        "check_type": "fp",
                        "check_time": 1710000000
                      },
                      {
                        "id": "RECORD-2",
                        "user_id": "USER-2",
                        "terminal_id": "TERMINAL-1",
                        "check_type": "fp",
                        "check_time": 1710000001
                      }
                    ]
                  }
                }
                """));

        assertThatThrownBy(() -> client(transport).queryCheckins("0", 1))
                .isInstanceOf(DeliEplusClientException.class)
                .hasMessage("Deli E+ returned too many check-in records");
    }

    @Test
    void keepsHttpVendorAndJsonErrorsFreeOfCredentialsAndResponseDetails() {
        CapturingTransport httpFailure = new CapturingTransport();
        httpFailure.enqueue(new DeliEplusHttpResponse(
                401,
                "unauthorized " + APP_KEY + " " + APP_SECRET));
        DeliEplusClientException httpException = catchClientException(
                () -> client(httpFailure).queryCheckins("0", 50));
        assertThat(httpException.safeCode()).isEqualTo("DELI_HTTP_FAILURE");
        assertThat(httpException.retryable()).isFalse();
        assertThat(httpException.getMessage())
                .contains("HTTP status 401")
                .doesNotContain(APP_KEY, APP_SECRET, "unauthorized");
        assertThat(httpException.getCause()).isNull();

        CapturingTransport vendorFailure = new CapturingTransport();
        vendorFailure.enqueue(okResponse("""
                {
                  "code": 103,
                  "msg": "signature rejected for example-app-key/example-app-secret",
                  "data": null
                }
                """));
        DeliEplusClientException vendorException = catchClientException(
                () -> client(vendorFailure).queryCheckins("0", 50));
        assertThat(vendorException.safeCode()).isEqualTo("DELI_VENDOR_FAILURE");
        assertThat(vendorException.retryable()).isFalse();
        assertThat(vendorException.getMessage())
                .isEqualTo("Deli E+ rejected the request (code 103)")
                .doesNotContain(APP_KEY, APP_SECRET, "signature rejected");
        assertThat(vendorException.getCause()).isNull();

        CapturingTransport invalidJson = new CapturingTransport();
        invalidJson.enqueue(okResponse(
                "{\"secret\":\"" + APP_SECRET + "\",\"data\":"));
        DeliEplusClientException jsonException = catchClientException(
                () -> client(invalidJson).queryCheckins("0", 50));
        assertThat(jsonException.safeCode()).isEqualTo("DELI_INVALID_RESPONSE");
        assertThat(jsonException.retryable()).isFalse();
        assertThat(jsonException.getMessage())
                .isEqualTo("Deli E+ returned an invalid response")
                .doesNotContain(APP_KEY, APP_SECRET);
        assertThat(jsonException.getCause()).isNull();
    }

    @Test
    void classifiesOnlyDocumentedTransientFailuresAsRetryable() {
        CapturingTransport httpUnavailable = new CapturingTransport();
        httpUnavailable.enqueue(new DeliEplusHttpResponse(503, "temporary"));
        assertThat(catchClientException(
                        () -> client(httpUnavailable).queryCheckins("0", 50))
                        .retryable())
                .isTrue();

        CapturingTransport vendorLimited = new CapturingTransport();
        vendorLimited.enqueue(okResponse("""
                {
                  "code": 110,
                  "msg": "rate limited",
                  "data": null
                }
                """));
        assertThat(catchClientException(
                        () -> client(vendorLimited).queryCheckins("0", 50))
                        .retryable())
                .isTrue();
    }

    @Test
    void mapsToTheExistingPortWhileDroppingRawCheckData() {
        CapturingTransport transport = new CapturingTransport();
        transport.enqueue(okResponse("""
                {
                  "code": 0,
                  "msg": "",
                  "data": {
                    "next_id": 2,
                    "data": [{
                      "id": "DEMO-RECORD-001",
                      "user_id": "DEMO-USER-001",
                      "ext_id": "DEMO-EMP-001",
                      "terminal_id": "DEMO-TERMINAL-001",
                      "check_type": "gps",
                      "check_time": 1710000000,
                      "check_data": {
                        "location": "sensitive-demo-location",
                        "photo": "fake-photo-reference"
                      }
                    }]
                  }
                }
                """));

        var page = client(transport).fetchPage("source-demo-deli", null);

        assertThat(page.inputCursor()).isEqualTo("0");
        assertThat(page.nextCursor()).isEqualTo("2");
        assertThat(page.pageDigest()).matches("[0-9a-f]{64}");
        assertThat(page.records()).hasSize(1);
        var record = page.records().getFirst();
        assertThat(record.sourceRecordId()).isEqualTo("DEMO-RECORD-001");
        assertThat(record.sourceVersion()).matches("[0-9a-f]{64}");
        assertThat(record.externalPersonRef()).isEqualTo("DEMO-EMP-001");
        assertThat(record.externalPersonRefKind())
                .isEqualTo(ConfirmedBindingKind.DELI_EXT_ID);
        assertThat(record.employeeNumber()).isNull();
        assertThat(record.punchInstant()).isEqualTo(Instant.ofEpochSecond(1710000000L));
        assertThat(record.direction()).isEqualTo(Direction.AUTO);
        assertThat(record.verificationMethod()).isEqualTo("gps");
        assertThat(record.deviceRef()).isEqualTo("DEMO-TERMINAL-001");
        assertThat(record.locationSummary()).isNull();
        assertThat(record.forbiddenPayloadDropped()).isTrue();
        assertThat(record.toString())
                .doesNotContain("sensitive-demo-location", "fake-photo-reference");
    }

    @Test
    void missingExtIdCanOnlyUseConfirmedUserIdBinding() {
        CapturingTransport transport = new CapturingTransport();
        transport.enqueue(okResponse("""
                {
                  "code": 0,
                  "msg": "",
                  "data": {
                    "next_id": 2,
                    "data": [{
                      "id": "DEMO-RECORD-002",
                      "user_id": "DEMO-USER-002",
                      "terminal_id": "DEMO-TERMINAL-001",
                      "check_type": "card",
                      "check_time": 1710000000
                    }]
                  }
                }
                """));

        var record = client(transport)
                .fetchPage("source-demo-deli", null)
                .records()
                .getFirst();

        assertThat(record.externalPersonRef())
                .isEqualTo("DEMO-USER-002");
        assertThat(record.externalPersonRefKind())
                .isEqualTo(ConfirmedBindingKind.DELI_USER_ID);
        assertThat(record.employeeNumber()).isNull();
    }

    @Test
    void runtimeSourceSettingsOverrideBootstrapPageSizeAndTimeZone()
            throws Exception {
        CapturingTransport transport = new CapturingTransport();
        transport.enqueue(okResponse("""
                {
                  "code": 0,
                  "data": {
                    "next_id": 2,
                    "data": [{
                      "id": "DEMO-RECORD-003",
                      "user_id": "DEMO-USER-003",
                      "terminal_id": "DEMO-TERMINAL-001",
                      "check_type": "card",
                      "check_time": 1710000000
                    }]
                  }
                }
                """));

        var page = client(transport).fetchPage(
                "source-demo-deli",
                null,
                new DeliPunchSourcePort.FetchSettings(
                        37, ZoneId.of("UTC")));

        JsonNode payload = objectMapper.readTree(
                transport.requests().getFirst().body());
        assertThat(payload.get("page_size").asInt()).isEqualTo(37);
        assertThat(page.records().getFirst().sourceTimeZone())
                .isEqualTo("UTC");
    }

    private DeliEplusClient client(DeliEplusHttpTransport transport) {
        DeliEplusProperties properties = new DeliEplusProperties();
        properties.setEnabled(true);
        properties.setAppKey(APP_KEY);
        properties.setAppSecret(APP_SECRET);
        return new DeliEplusClient(
                properties,
                new DeliEplusSigner(),
                transport,
                objectMapper,
                FIXED_CLOCK);
    }

    private static DeliEplusHttpResponse okResponse(String body) {
        return new DeliEplusHttpResponse(200, body);
    }

    private static String emptyPage(String nextId) {
        return """
                {
                  "code": 0,
                  "msg": "",
                  "data": {
                    "next_id": %s,
                    "data": []
                  }
                }
                """.formatted(nextId);
    }

    private static String employeeRowsPage(
            int firstIndex, int rowCount, int missingEmployeeNumberIndex) {
        List<String> rows = new ArrayList<>();
        for (int index = firstIndex;
                index < firstIndex + rowCount;
                index++) {
            String employeeNumber = index == missingEmployeeNumberIndex
                    ? ""
                    : "DEMO-EMP-%03d".formatted(index);
            rows.add("""
                    {
                      "id": "DEMO-USER-%03d",
                      "employee_num": "%s"
                    }
                    """.formatted(index, employeeNumber));
        }
        return """
                {
                  "code": 0,
                  "msg": "",
                  "data": {"rows": [%s]}
                }
                """.formatted(String.join(",", rows));
    }

    private static DeliEplusClientException catchClientException(
            Runnable invocation) {
        try {
            invocation.run();
        } catch (DeliEplusClientException exception) {
            return exception;
        }
        throw new AssertionError("Expected DeliEplusClientException");
    }

    private record OverlongIdentifier(String field, int length) {
    }

    private static final class CapturingTransport implements DeliEplusHttpTransport {

        private final Deque<DeliEplusHttpResponse> responses = new ArrayDeque<>();
        private final List<DeliEplusHttpRequest> requests = new ArrayList<>();

        void enqueue(DeliEplusHttpResponse response) {
            responses.addLast(response);
        }

        List<DeliEplusHttpRequest> requests() {
            return List.copyOf(requests);
        }

        @Override
        public DeliEplusHttpResponse post(DeliEplusHttpRequest request) {
            requests.add(request);
            DeliEplusHttpResponse response = responses.pollFirst();
            if (response == null) {
                throw new AssertionError("No fake Deli E+ response was queued");
            }
            return response;
        }
    }
}
