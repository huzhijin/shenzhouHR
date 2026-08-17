package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceReportDataScopeSqlContractTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportMapper.xml");
    private static final List<String> FACT_QUERY_IDS = List.of(
            "listAuthorizedDailyFacts",
            "listAuthorizedOaFacts",
            "listAuthorizedExceptionFacts",
            "listAuthorizedTimeAccountFacts",
            "listAuthorizedDepartmentAttendanceRates",
            "listAuthorizedEmployeeSickLeaveDays",
            "listAuthorizedEmployeeDepartmentAttendancePeriods");
    private static final List<String> EMPLOYEE_FILTERED_FACT_QUERY_IDS = List.of(
            "listAuthorizedDailyFacts",
            "listAuthorizedOaFacts",
            "listAuthorizedExceptionFacts",
            "listAuthorizedTimeAccountFacts",
            "listAuthorizedEmployeeSickLeaveDays",
            "listAuthorizedEmployeeDepartmentAttendancePeriods");

    @Test
    void everyFactQueryUsesTheSameFailClosedVisibilityPredicate()
            throws Exception {
        String xml = Files.readString(MAPPER);
        String visibility = between(
                xml,
                "<sql id=\"reportFactVisibility\">",
                "</sql>");

        assertThat(xml)
                .contains("<sql id=\"reportFactVisibility\">")
                .contains("capability.capability_code = #{capabilityCode}")
                .contains(
                        "capability.capability_code = "
                                + "'ATTENDANCE_REPORT:READ'")
                .doesNotContain("${")
                .doesNotContain("SYSTEM_ADMIN")
                .doesNotContain("role_code")
                .doesNotContain(
                        "MANUFACTURING_CENTER_SUPERVISOR");
        assertThat(visibility)
                .contains("principal.status = 'ACTIVE'")
                .contains("role_assignment.valid_from")
                .contains("role_assignment.valid_to")
                .contains("data_scope.valid_from")
                .contains("data_scope.valid_to")
                .contains("projection.status = 'PUBLISHED'")
                .contains(
                        "projection.company_id = fact.company_id")
                .contains(
                        "fact_organization.organization_id ="
                                + System.lineSeparator()
                                + "                        fact.organization_id")
                .contains(
                        "fact_organization.company_id ="
                                + System.lineSeparator()
                                + "                        fact.company_id")
                .contains("fact_employee.employee_id = fact.employee_id")
                .contains(
                        "fact_employee.company_id ="
                                + System.lineSeparator()
                                + "                        fact.company_id")
                .contains("data_scope.scope_type = 'COMPANY'")
                .contains("data_scope.scope_type = 'ORGANIZATION'")
                .contains("data_scope.scope_type = 'SELF'")
                .contains("data_scope.include_descendants = FALSE")
                .contains("data_scope.include_descendants = TRUE")
                .contains("employment_assignment current_assignment")
                .contains(
                        "current_assignment_organization.identity_status =")
                .contains(
                        "organization_current_projection"
                                + System.lineSeparator()
                                + "                                "
                                + "current_assignment_projection")
                .contains(
                        "current_assignment_version.status = 'ACTIVE'")
                .contains(
                        "current_assignment_version.effective_from")
                .contains(
                        "current_assignment_version.effective_to")
                .contains(
                        "current_assignment.employee_id ="
                                + System.lineSeparator()
                                + "                                "
                                + "fact.employee_id")
                .contains(
                        "current_assignment.record_status = 'ACTIVE'")
                .contains("current_assignment.version_valid_to IS NULL")
                .contains(
                        "current_assignment.effective_from"
                                + System.lineSeparator()
                                + "                                  "
                                + "&lt;= #{authorizationTime}")
                .contains("organization_current_closure closure")
                .contains(
                        "closure.descendant_organization_id ="
                                + System.lineSeparator()
                                + "                                                    "
                                + "current_assignment.organization_id")
                .contains("principal.employee_id = fact.employee_id");

        assertThat(count(xml, "<include refid=\"reportFactVisibility\"/>"))
                .as("all enumerated fact reads must share one scope predicate")
                .isEqualTo(FACT_QUERY_IDS.size());
        for (String queryId : FACT_QUERY_IDS) {
            assertThat(select(xml, queryId))
                    .as("fact query %s", queryId)
                    .contains("<include refid=\"reportFactVisibility\"/>")
                    .contains("projection.company_id = fact.company_id")
                    .contains(
                            "fact.attendance_report_projection_id ="
                                    + " #{projectionId}")
                    .contains("fact.company_id = #{companyId}")
                    .contains("fact.organization_id = #{organizationId}")
                    .doesNotContain(" OFFSET ")
                    .doesNotContain(" COUNT(");
        }
        for (String queryId : EMPLOYEE_FILTERED_FACT_QUERY_IDS) {
            assertThat(select(xml, queryId))
                    .as("employee-filtered fact query %s", queryId)
                    .contains("fact.employee_id = #{employeeId}");
        }
        for (String queryId : List.of(
                "listAuthorizedDepartmentAttendanceRates",
                "listAuthorizedEmployeeDepartmentAttendancePeriods")) {
            assertThat(select(xml, queryId))
                    .as("aggregate fact query %s", queryId)
                    .containsPattern(
                            "projection\\.attendance_report_projection_id"
                                    + "\\s*=\\s*#\\{projectionId}")
                    .contains("projection.company_id = #{companyId}");
        }
    }

    @Test
    void projectionReadSelectsTheLatestPublishedAuthorizedMonth()
            throws Exception {
        String projectionQuery = select(
                Files.readString(MAPPER),
                "listLatestAuthorizedProjections");

        assertThat(projectionQuery)
                .contains("projection.period_start = #{periodStart}")
                .contains(
                        "projection.period_end_exclusive ="
                                + " #{periodEndExclusive}")
                .contains("projection.status = 'PUBLISHED'")
                .contains("projection.published_at &lt;= #{authorizationTime}")
                .contains("#{companyId} IS NULL")
                .contains(
                        "projection.company_id = #{companyId}")
                .contains("<include refid=\"reportProjectionVisibility\"/>")
                .contains("NOT EXISTS (")
                .contains("newer_projection.company_id =")
                .contains("ORDER BY projection.company_id")
                .contains(
                        "projection.attendance_report_projection_id DESC")
                .contains("LIMIT 2")
                .doesNotContain("LIMIT 1");
    }

    @Test
    void companyDirectoryCannotEnumerateUnpublishedOrUnauthorizedCompanies()
            throws Exception {
        String directory = select(
                Files.readString(MAPPER),
                "listAuthorizedCompanies");

        assertThat(directory)
                .contains("SELECT DISTINCT projection.company_id")
                .contains("company.name AS company_name")
                .contains("company.status = 'ACTIVE'")
                .contains("projection.period_start = #{periodStart}")
                .contains(
                        "projection.period_end_exclusive ="
                                + " #{periodEndExclusive}")
                .contains("projection.status = 'PUBLISHED'")
                .contains(
                        "projection.published_at &lt;= #{authorizationTime}")
                .contains("<include refid=\"reportProjectionVisibility\"/>")
                .doesNotContain("LIMIT")
                .doesNotContain("${");
    }

    @Test
    void inactiveOrUnprojectedOrganizationScopesCannotAuthorizeReports()
            throws Exception {
        String xml = Files.readString(MAPPER);
        String projectionVisibility = between(
                xml,
                "<sql id=\"reportProjectionVisibility\">",
                "</sql>");
        String factVisibility = between(
                xml,
                "<sql id=\"reportFactVisibility\">",
                "</sql>");
        String scopeQuery = select(xml, "listAuthorizedScopes");

        for (String authorizationSql :
                List.of(projectionVisibility, factVisibility, scopeQuery)) {
            assertThat(authorizationSql)
                    .contains("data_scope.scope_type = 'ORGANIZATION'")
                    .contains("scoped_organization.identity_status")
                    .contains("'ACTIVE'")
                    .contains("organization_current_projection")
                    .contains("scoped_projection")
                    .contains("organization_version scoped_version")
                    .contains("scoped_version.status = 'ACTIVE'")
                    .contains(
                            "scoped_version.effective_from"
                                    + System.lineSeparator())
                    .contains("scoped_version.effective_to");
        }
        assertThat(factVisibility)
                .contains(
                        "current_assignment_organization.identity_status")
                .contains("current_assignment_projection.current_version_id")
                .contains(
                        "current_assignment_version.status = 'ACTIVE'")
                .contains(
                        "current_assignment_version.effective_from")
                .contains(
                        "current_assignment_version.effective_to");
    }

    @Test
    void oaReadUsesHalfOpenIntervalOverlapSoCrossMonthDocumentsRemainVisible()
            throws Exception {
        String oaQuery =
                select(Files.readString(MAPPER), "listAuthorizedOaFacts");

        assertThat(oaQuery)
                .contains(
                        "COALESCE(fact.interval_start, fact.point_instant)"
                                + System.lineSeparator()
                                + "              &lt; #{periodEndExclusiveAt}")
                .contains(
                        "TIMESTAMPADD(MICROSECOND, 1, fact.point_instant)")
                .contains(") &gt; #{periodStartAt}")
                .doesNotContain(
                        "COALESCE(fact.interval_start, fact.point_instant)"
                                + System.lineSeparator()
                                + "              &gt;= #{periodStartAt}");
    }

    private static int count(String value, String token) {
        return (value.length() - value.replace(token, "").length())
                / token.length();
    }

    private static String select(String xml, String id) {
        return between(xml, "<select id=\"" + id + "\"", "</select>");
    }

    private static String between(String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(end, startIndex);
        return value.substring(startIndex, endIndex + end.length());
    }
}
