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
                .contains("  /attendance-reports/month-matrix:")
                .contains("operationId: getAttendanceMonthMatrix")
                .contains(
                        "$ref: '#/components/schemas/"
                                + "AttendanceMonthMatrixPage'")
                .contains("  /attendance-reports/companies:")
                .contains("operationId: listAttendanceReportCompanies")
                .contains(
                        "$ref: '#/components/schemas/"
                                + "AttendanceReportCompanyDirectory'")
                .contains(
                        "method: GET, path: /attendance-reports, "
                                + "responseSchema: AttendanceReportPage, "
                                + "errors: [400, 401, 403, 409]")
                .contains(
                        "method: GET, path: /attendance-reports/"
                                + "month-matrix, responseSchema: "
                                + "AttendanceMonthMatrixPage, "
                                + "errors: [400, 401, 403, 409]")
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
    void publicationRouteRequiresRefreshAndCompanyScopedAuthorization()
            throws Exception {
        String contract = Files.readString(OPEN_API);
        String route = between(
                contract,
                "  /attendance-reports/publications:",
                "  /attendance-reports/exports:");
        String request = between(
                contract,
                "    AttendanceReportPublicationRequest:",
                "    AttendanceReportPublicationView:");
        String view = between(
                contract,
                "    AttendanceReportPublicationView:",
                "    AttendanceReportExportCreateRequest:");

        assertThat(route)
                .contains("operationId: publishAttendanceReport")
                .contains("x-capability: ATTENDANCE_REPORT:REFRESH")
                .contains(
                        "$ref: '#/components/parameters/CsrfToken'")
                .contains(
                        "$ref: '#/components/schemas/"
                                + "AttendanceReportPublicationRequest'")
                .contains(
                        "$ref: '#/components/schemas/"
                                + "AttendanceReportPublicationView'")
                .contains("'200':", "'201':", "'403':", "'503':")
                .contains("跨公司或已过期授权均返回 403")
                .contains("不会触发核算或发布");
        assertThat(request)
                .contains(
                        "required: [companyId, period, periodState, reason]",
                        "maxLength: 36",
                        "enum: [OPEN, FROZEN, CLOSED, REOPENED]",
                        "minLength: 2",
                        "maxLength: 500",
                        "writeOnly: true");
        assertThat(view)
                .contains(
                        "- projectionId",
                        "- projectionVersion",
                        "- projectionDigest",
                        "- dataAsOf",
                        "- publishedAt",
                        "- created",
                        "pattern: '^[a-f0-9]{64}$'");
    }

    @Test
    void recalculateIsARefreshCapabilityWriteAndNotTheDefaultGet()
            throws Exception {
        String contract = Files.readString(OPEN_API);
        String route = between(
                contract,
                "  /attendance-reports/recalculate:",
                "  /attendance-reports/publications:");
        String request = between(
                contract,
                "    AttendanceReportRecalculateRequest:",
                "    AttendanceReportRecalculateView:");
        String view = between(
                contract,
                "    AttendanceReportRecalculateView:",
                "    AttendanceReportFilters:");

        assertThat(route)
                .contains("operationId: recalculateAttendanceReport")
                .contains("x-capability: ATTENDANCE_REPORT:REFRESH")
                .contains("$ref: '#/components/parameters/CsrfToken'")
                .contains("HR 管理员或系统管理员")
                .contains("普通 GET、页面刷新和高管/部门经理/员工不得调用")
                .contains("'403'");
        assertThat(request)
                .contains("required: [companyId, period]");
        assertThat(view)
                .contains("projectionVersion")
                .contains("sourcesNewerThanPin");
    }

    @Test
    void monthMatrixDefinesSemanticBadgesWithoutServerColors()
            throws Exception {
        String contract = Files.readString(OPEN_API);
        String schema = between(
                contract,
                "    AttendanceMonthMatrixBadgeCode:",
                "    AttendanceReportExportCreateRequest:");

        assertThat(schema)
                .contains(
                        "LATE",
                        "EARLY_DEPARTURE",
                        "MISSING_PUNCH",
                        "RECOGNIZED_OVERTIME",
                        "TIME_OFF",
                        "OUTING",
                        "TRIP",
                        "PERSONAL_LEAVE",
                        "SICK_LEAVE",
                        "ANNUAL_LEAVE",
                        "PUNCH_CORRECTION",
                        "ABSENCE",
                        "REST_DAY")
                .doesNotContain("color:");
    }

    @Test
    void reportPagesBindToOneOpaqueRealtimeSnapshotToken()
            throws Exception {
        String contract = Files.readString(OPEN_API);
        String queryRoutes = between(
                contract,
                "  /attendance-reports:",
                "  /attendance-reports/companies:");
        String parameter = between(
                contract,
                "    ExpectedProjectionVersion:",
                "    Page:");

        assertThat(queryRoutes)
                .containsOnlyOnce("operationId: getAttendanceReport")
                .containsOnlyOnce("operationId: getAttendanceMonthMatrix")
                .contains(
                        "$ref: '#/components/parameters/"
                                + "ExpectedProjectionVersion'")
                .contains(
                        "$ref: '#/components/responses/"
                                + "AttendanceReportRealtimeConflict'");
        assertThat(parameter)
                .contains("name: expectedProjectionVersion")
                .contains("in: query")
                .contains("required: false")
                .contains("minLength: 1")
                .contains("maxLength: 128")
                .contains("实时输入快照令牌")
                .contains("不是持久化报表发布版本")
                .contains("ATTENDANCE_REPORT_SNAPSHOT_CHANGED")
                .contains("不披露新令牌");
    }

    @Test
    void reportGetRoutesCalculateRealtimeWithoutPublicationPrerequisite()
            throws Exception {
        String contract = Files.readString(OPEN_API);
        String reportRoutes = between(
                contract,
                "  /attendance-reports:",
                "  /attendance-reports/publications:");
        String metadata = between(
                contract,
                "    AttendanceReportProjectionMetadata:",
                "    AttendanceReportCompanyOption:");
        String realtimeConflict = between(
                contract,
                "    AttendanceReportRealtimeConflict:",
                "    StaleVersion:");

        assertThat(reportRoutes)
                .contains("已钉住的完整核算快照")
                .contains("查询不要求人工发布")
                .contains("不要求人工发布")
                .contains("普通 GET 不重算")
                .doesNotContain("且存在已发布正式投影的公司安全名称");
        assertThat(metadata)
                .contains("钉住报表快照元数据")
                .contains("并非人工发布版本")
                .contains("SOURCE.DELI_CLOUD:<ISO date-time|UNSYNCED>:<64hex>")
                .contains("SOURCE.OA_ATTENDANCE:<ISO date-time|UNSYNCED>:<64hex>")
                .contains("同类来源可以有零项或多项")
                .contains("任一来源未同步时显示“部分未同步”")
                .contains("items: { type: string, minLength: 1, maxLength: 128 }")
                .contains("不是得力或 OA 各自的截止时间");
        assertThat(realtimeConflict)
                .contains("ATTENDANCE_REPORT_SNAPSHOT_CHANGED")
                .contains("ATTENDANCE_REPORT_REALTIME_CALCULATION_UNAVAILABLE")
                .contains("不会返回伪造零值");
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
                "    AttendanceReportExportView:");
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
                .contains("filters:")
                .contains(
                        "$ref: '#/components/schemas/"
                                + "AttendanceReportFilters'")
                .doesNotContain("companyId:");
        assertThat(view)
                .contains("- companyId")
                .contains("companyId:")
                .contains("minLength: 1");
    }

    @Test
    void exportCreationRequiresTheCurrentProjectionAndFieldSelection()
            throws Exception {
        String contract = Files.readString(OPEN_API);
        String create = between(
                contract,
                "    AttendanceReportExportCreateRequest:",
                "    AttendanceReportExportView:");

        assertThat(create)
                .contains(
                        "- projectionVersion",
                        "- queryFingerprint",
                        "- scopeReference",
                        "- filters",
                        "- selectedFields",
                        "pattern: '^[a-f0-9]{64}$'",
                        "uniqueItems: true")
                .doesNotContain(
                        "period:",
                        "organizationId:",
                        "employeeId:",
                        "currentPassword:");
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
