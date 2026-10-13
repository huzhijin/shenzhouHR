package com.szsemicon.hr.attendance.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PunchCorrectionOpenApiContractTest {

    private static final Path OPEN_API =
            Path.of("../api/openapi.yaml").toAbsolutePath().normalize();

    @Test
    void contractRemainsValidYaml() throws Exception {
        Object contract = new Yaml().load(Files.readString(OPEN_API));

        assertThat(contract).isInstanceOf(Map.class);
    }

    @Test
    void versionedAndLegacyRoutesDeclareEmployeeAsOfDataScope()
            throws Exception {
        String contract = Files.readString(OPEN_API);
        String routes = between(
                contract,
                "  /attendance/punch-corrections:",
                "  /attendance-events/{eventId}/evidence:");

        assertThat(routes)
                .contains(
                        "operationId: submitPunchCorrection",
                        "operationId: submitPunchCorrectionAtApplyPath",
                        "operationId: getPunchCorrectionQuota",
                        "operationId: getPunchCorrectionQuotaByPath",
                        "operationId: approvePunchCorrection",
                        "operationId: submitPunchSupplement",
                        "operationId: submitPunchSupplementAtApplyPath",
                        "operationId: getPunchSupplementQuota",
                        "operationId: getPunchSupplementQuotaByPath",
                        "operationId: approvePunchSupplement");
        assertThat(count(routes, "      - url: /api/v1")).isEqualTo(10);
        assertThat(count(routes, "      - url: /api\n")).isEqualTo(10);
        assertThat(count(
                routes,
                "x-data-scope: PEOPLE:EMPLOYEE_AS_OF")).isEqualTo(10);
        assertThat(count(
                routes,
                "x-capability: ATTENDANCE_PUNCH_CORRECTION:CREATE"))
                .isEqualTo(4);
        assertThat(count(
                routes,
                "x-capability: ATTENDANCE_PUNCH_CORRECTION:READ"))
                .isEqualTo(4);
        assertThat(count(
                routes,
                "x-capability: ATTENDANCE_PUNCH_CORRECTION:APPROVE"))
                .isEqualTo(2);
    }

    @Test
    void routeDecisionNamesTheImplementedScopeEnforcementAndAsOfDates()
            throws Exception {
        String contract = Files.readString(OPEN_API);
        String decision = between(
                contract,
                "    punchCorrection:",
                "  authorization:");

        assertThat(decision)
                .contains(
                        "canonicalRuntimePath: /api/v1/attendance/punch-corrections",
                        "pathItemServers: [/api/v1, /api]",
                        "enforcement: PeopleRepository.canAccessEmployee",
                        "scopeTypes: [COMPANY, ORGANIZATION, SELF]",
                        "submitAsOf: businessDate",
                        "quotaAsOf: monthEnd",
                        "approveAsOf: requestBusinessDate",
                        "deniedResponse: 403 ACCESS_DENIED")
                .doesNotContain(
                        "NOT_DOCUMENTED_UNTIL_RUNTIME_SCOPE_ENFORCEMENT_EXISTS");
    }

    private static String between(
            String value,
            String start,
            String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(end, startIndex + start.length());
        assertThat(startIndex).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return value.substring(startIndex, endIndex);
    }

    private static int count(String value, String needle) {
        return value.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
