package com.szsemicon.hr.reporting.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AttendanceReportOpenApiContractTest {

    private static final Path OPEN_API =
            Path.of("../api/openapi.yaml").toAbsolutePath().normalize();

    @Test
    void formalReportAndReauthenticatedExportRoutesAreCallableContracts()
            throws Exception {
        String contract = Files.readString(OPEN_API);

        assertThat(contract)
                .contains("  /attendance-reports:")
                .contains("operationId: getAttendanceReport")
                .contains("x-capability: ATTENDANCE_REPORT:READ")
                .contains("  /attendance-reports/companies:")
                .contains("operationId: listAttendanceReportCompanies")
                .contains(
                        "$ref: '#/components/schemas/"
                                + "AttendanceReportCompanyDirectory'")
                .contains("  /attendance-reports/exports:")
                .contains("operationId: createAttendanceReportExport")
                .contains(
                        "x-capability: ATTENDANCE_REPORT:EXPORT_CREATE")
                .contains(
                        "  /attendance-reports/exports/{exportId}/download:")
                .contains("operationId: downloadAttendanceReportExport")
                .contains(
                        "x-capability: ATTENDANCE_REPORT:EXPORT_DOWNLOAD")
                .contains("writeOnly: true")
                .contains("application/vnd.openxmlformats-officedocument"
                        + ".spreadsheetml.sheet")
                .doesNotContain(
                        "path: /attendance-report-exports, "
                                + "requestSchema: AttendanceReportExportRequest");
    }

    @Test
    void companySelectionIsBoundAcrossQueryResponseAndExport()
            throws Exception {
        String contract = Files.readString(OPEN_API);
        String query = between(
                contract,
                "  /attendance-reports:",
                "  /attendance-reports/companies:");
        String filters = between(
                contract,
                "    AttendanceReportFilters:",
                "    AttendanceReportColumn:");
        String create = between(
                contract,
                "    AttendanceReportExportCreateRequest:",
                "    ReportExportReauthenticationRequest:");
        String view = between(
                contract,
                "    AttendanceReportExportView:",
                "    FieldError:");

        assertThat(query)
                .contains("- name: companyId")
                .contains("maxLength: 36");
        assertThat(filters)
                .contains(
                        "required: [period, scopeReference, companyId]")
                .contains("companyId:")
                .contains("minLength: 1");
        assertThat(create)
                .contains("companyId:")
                .contains("多公司授权必须从授权公司目录显式选择");
        assertThat(view)
                .contains("- companyId")
                .contains("companyId:")
                .contains("minLength: 1");
    }

    @Test
    void formalReportTypeCatalogIncludesExceptionAndAllNineReports()
            throws Exception {
        String contract = Files.readString(OPEN_API);
        String schema = between(
                contract,
                "    AttendanceReportType:",
                "    AttendanceReportScope:");

        assertThat(schema)
                .contains(
                        "ATTENDANCE_DETAIL",
                        "LEAVE",
                        "OVERTIME",
                        "WORK_HOURS",
                        "EXCEPTIONS",
                        "LATE",
                        "MISSED_PUNCH",
                        "ATTENDANCE_RATE",
                        "ANNUAL_LEAVE");
    }

    private static String between(
            String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(end, startIndex + start.length());
        return value.substring(startIndex, endIndex);
    }
}
