package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeliAttendanceSourceOpenApiContractTest {

    private static final String DOCUMENT = readDocument();

    @Test
    void registrationRunStatusAndRetryContractsAreExplicitAndScoped() {
        assertThat(DOCUMENT)
                .contains("operationId: registerDeliAttendanceSource")
                .contains("operationId: startDeliAttendanceSourceJob")
                .contains("operationId: getAttendanceSourceJob")
                .contains("operationId: retryAttendanceSourceJob")
                .contains("operationId: replayDeliPunchIdentity")
                .contains("DeliIdentityReplayRequest")
                .contains("DeliIdentityReplayView")
                .contains("x-capability: ATTENDANCE_SOURCE:CONFIGURE")
                .contains("x-capability: ATTENDANCE_SOURCE:RUN")
                .contains("x-capability: ATTENDANCE_SOURCE:READ")
                .contains("x-capability: ATTENDANCE_SOURCE:RETRY")
                .contains("x-data-scope: ATTENDANCE_SOURCE:COMPANY")
                .contains(
                        "$ref: '#/components/schemas/"
                                + "DeliSourceRegistrationRequest'")
                .contains(
                        "$ref: '#/components/schemas/"
                                + "DeliSourceSyncJobRequest'")
                .contains(
                        "$ref: '#/components/schemas/"
                                + "AttendanceSourceSyncJobView'");
    }

    @Test
    void formalRequestSchemasAcceptReferencesButNeverCredentialValues() {
        String registration = DOCUMENT.substring(
                DOCUMENT.indexOf("    DeliSourceRegistrationRequest:"),
                DOCUMENT.indexOf("    DeliSourceRegistrationView:"));

        assertThat(registration)
                .contains("secretReferenceName:")
                .contains("pageSize:")
                .contains("rateLimitPerMinute:")
                .contains("backoffSeconds:")
                .contains("sourceTimeZone:")
                .doesNotContain("appKey:")
                .doesNotContain("appSecret:")
                .doesNotContain("checkData:")
                .doesNotContain("check_data:");
    }

    private static String readDocument() {
        try {
            return Files.readString(
                    Path.of("../api/openapi.yaml")
                            .toAbsolutePath()
                            .normalize());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
