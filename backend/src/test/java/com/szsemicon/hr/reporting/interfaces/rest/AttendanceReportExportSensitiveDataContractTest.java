package com.szsemicon.hr.reporting.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AttendanceReportExportSensitiveDataContractTest {

    @Test
    void requestStringRepresentationsRedactPurposeAndPassword() {
        String purpose = "董事会专项复核";
        String password = "Current#Password123";
        var create =
                new AttendanceReportExportController.CreateExportRequest(
                        ReportType.ATTENDANCE_DETAIL,
                        YearMonth.of(2026, 7),
                        "legal-1",
                        null,
                        null,
                        null,
                        purpose,
                        password);
        var download =
                new AttendanceReportExportController.ReauthenticationRequest(
                        password);

        assertThat(create.toString())
                .contains("purpose=<redacted>")
                .contains("currentPassword=<redacted>")
                .doesNotContain(purpose, password);
        assertThat(download.toString())
                .contains("currentPassword=<redacted>")
                .doesNotContain(password);
    }

    @Test
    void passwordIsWriteOnlyDuringStructuredSerialization()
            throws Exception {
        String password = "Current#Password123";
        var mapper = new ObjectMapper();
        var create =
                new AttendanceReportExportController.CreateExportRequest(
                        ReportType.ATTENDANCE_DETAIL,
                        null,
                        "legal-1",
                        null,
                        null,
                        null,
                        "月度薪资核对",
                        password);
        var download =
                new AttendanceReportExportController.ReauthenticationRequest(
                        password);

        assertThat(mapper.writeValueAsString(create))
                .doesNotContain("currentPassword", password);
        assertThat(mapper.writeValueAsString(download))
                .doesNotContain("currentPassword", password);
        assertThat(mapper.readValue(
                        "{\"currentPassword\":\""
                                + password
                                + "\"}",
                        AttendanceReportExportController
                                .ReauthenticationRequest.class)
                .currentPassword())
                .isEqualTo(password);
    }
}
