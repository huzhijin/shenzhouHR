package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AttendanceDashboardSqlContractTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportMapper.xml");
    private static final Path REPORTING_MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V10__formal_attendance_reporting.sql");

    @Test
    void dashboardReadsRequireExactCapabilityAndNeverInferSystemRole()
            throws Exception {
        String xml = Files.readString(MAPPER);
        String projectionVisibility = between(
                xml,
                "<sql id=\"dashboardProjectionVisibility\">",
                "</sql>");
        String factVisibility = between(
                xml,
                "<sql id=\"dashboardFactVisibility\">",
                "</sql>");

        for (String visibility :
                java.util.List.of(
                        projectionVisibility, factVisibility)) {
            assertThat(visibility)
                    .contains(
                            "capability.capability_code ="
                                    + " #{capabilityCode}")
                    .contains("'ATTENDANCE_DASHBOARD:READ'")
                    .contains("principal.status = 'ACTIVE'")
                    .contains("role_assignment.valid_from")
                    .contains("role_assignment.valid_to")
                    .contains("data_scope.valid_from")
                    .contains("data_scope.valid_to")
                    .contains("data_scope.scope_type = 'COMPANY'")
                    .contains("data_scope.scope_type = 'ORGANIZATION'")
                    .contains("data_scope.scope_type = 'SELF'")
                    .doesNotContain("SYSTEM_ADMIN")
                    .doesNotContain("role_code")
                    .doesNotContain("${");
        }
        assertThat(factVisibility)
                .contains("projection.status = 'PUBLISHED'")
                .contains("data_scope.include_descendants = FALSE")
                .contains("data_scope.include_descendants = TRUE")
                .contains("organization_current_closure")
                .contains("principal.employee_id = fact.employee_id");
    }

    @Test
    void summaryAndTopTenUseTheSameDayUnresolvedAndScopePredicate()
            throws Exception {
        String xml = Files.readString(MAPPER);
        String summary = select(xml, "summarizeDashboardExceptions");
        String exceptions = select(xml, "listDashboardExceptions");

        for (String query : java.util.List.of(summary, exceptions)) {
            assertThat(query)
                    .contains(
                            "fact.business_date = #{businessDate}")
                    .contains(
                            "'OPEN', 'PENDING_EVIDENCE',"
                                    + " 'PENDING_REVIEW'")
                    .contains(
                            "<include refid=\"dashboardFactVisibility\"/>")
                    .doesNotContain("'RESOLVED'")
                    .doesNotContain("${");
        }
        assertThat(summary)
                .contains("COUNT(*) AS unresolved_count")
                .contains(
                        "COUNT(DISTINCT fact.employee_id)"
                                + " AS affected_employee_count")
                .contains(
                        "fact.severity = 'ERROR'")
                .contains("AS blocking_count");
        assertThat(exceptions)
                .contains(
                        "fact.exception_case_id"
                                + " AS exception_reference")
                .contains("fact.safe_evidence_summary AS evidence_summary")
                .contains("LIMIT 10")
                .doesNotContain("fact.employee_id,")
                .doesNotContain("raw_punch")
                .doesNotContain("device")
                .doesNotContain("latitude")
                .doesNotContain("longitude")
                .doesNotContain("payroll");
    }

    @Test
    void analyticsAggregateFullAuthorizedFactsWithStableWindowsAndLimits()
            throws Exception {
        String xml = Files.readString(MAPPER);
        String daily = select(xml, "listDashboardDailyTrend");
        String severity =
                select(xml, "listDashboardSeverityDistribution");
        String type = select(xml, "listDashboardTypeDistribution");
        String organization =
                select(xml, "listDashboardOrganizationRanking");

        for (String query : java.util.List.of(
                daily, severity, type, organization)) {
            assertThat(query)
                    .contains(
                            "fact.attendance_report_projection_id"
                                    + " = #{projectionId}")
                    .contains("fact.company_id = #{companyId}")
                    .contains(
                            "'OPEN', 'PENDING_EVIDENCE',"
                                    + " 'PENDING_REVIEW'")
                    .contains(
                            "<include refid=\"dashboardFactVisibility\"/>")
                    .doesNotContain("'RESOLVED'")
                    .doesNotContain("LIMIT 10</select>")
                    .doesNotContain("${");
        }
        assertThat(daily)
                .contains(
                        "fact.business_date &gt;= #{trendStart}")
                .contains(
                        "fact.business_date &lt;= #{businessDate}")
                .contains("GROUP BY fact.business_date")
                .contains("ORDER BY fact.business_date")
                .contains("COUNT(DISTINCT fact.employee_id)")
                .contains("AS affected_employee_count");
        assertThat(severity)
                .contains(
                        "fact.business_date = #{businessDate}")
                .contains("GROUP BY fact.severity")
                .contains("WHEN 'INFO' THEN 0")
                .contains("WHEN 'WARNING' THEN 1");
        assertThat(type)
                .contains(
                        "fact.business_date = #{businessDate}")
                .contains("GROUP BY fact.exception_type")
                .contains(
                        "ORDER BY count DESC, fact.exception_type")
                .contains("LIMIT 10");
        assertThat(organization)
                .contains(
                        "fact.business_date = #{businessDate}")
                .contains(
                        "organization_version.organization_version_id")
                .contains("fact.organization_version_id")
                .contains("GROUP BY organization_version.name")
                .contains("ORDER BY exception_count DESC")
                .contains("blocking_count DESC")
                .contains("LIMIT 5")
                .doesNotContain("employee_version")
                .doesNotContain("safe_evidence_summary");
    }

    @Test
    void dashboardUsesOnlyLatestPublishedAuthorizedCompanyProjection()
            throws Exception {
        String xml = Files.readString(MAPPER);
        String companies =
                select(xml, "listDashboardAuthorizedCompanies");
        String projection = select(
                xml, "listLatestDashboardAuthorizedProjections");

        assertThat(companies)
                .contains("SELECT DISTINCT projection.company_id")
                .contains("company.status = 'ACTIVE'")
                .contains("projection.status = 'PUBLISHED'")
                .contains(
                        "<include refid=\"dashboardProjectionVisibility\"/>");
        assertThat(projection)
                .contains(
                        "projection.company_id = #{companyId}")
                .contains("projection.status = 'PUBLISHED'")
                .contains("NOT EXISTS (")
                .contains("newer_projection.published_at")
                .contains("LIMIT 1")
                .contains(
                        "<include refid=\"dashboardProjectionVisibility\"/>");
    }

    @Test
    void systemAdministratorDoesNotReceiveDashboardBusinessAccessByRole()
            throws Exception {
        String migration = Files.readString(REPORTING_MIGRATION);
        String firstGrant = between(
                migration,
                "INSERT INTO auth_role_capability (role_id, capability_id)",
                "INSERT INTO auth_role_capability (role_id, capability_id)");

        assertThat(firstGrant)
                .contains("'ATTENDANCE_DASHBOARD:READ'")
                .doesNotContain("'SYSTEM_ADMIN'");
    }

    private static String select(String xml, String id) {
        return between(xml, "<select id=\"" + id + "\"", "</select>");
    }

    private static String between(
            String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(
                end, startIndex + start.length());
        return value.substring(startIndex, endIndex + end.length());
    }
}
