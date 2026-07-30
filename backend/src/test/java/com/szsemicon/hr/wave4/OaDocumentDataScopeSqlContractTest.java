package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OaDocumentDataScopeSqlContractTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceSourceReadMapper.xml");

    @Test
    void countAndRowsUseTheSameEmployeeFactScopeBeforePagination() throws Exception {
        String xml = Files.readString(MAPPER);

        assertThat(xml)
                .contains("<sql id=\"oaDocumentVisibility\">")
                .contains("data_scope.scope_type = 'COMPANY'")
                .contains("data_scope.scope_type = 'ORGANIZATION'")
                .contains("data_scope.scope_type = 'SELF'")
                .contains("organization_current_closure closure")
                .contains("employment_assignment current_employment")
                .contains("current_employment.employee_id =")
                .contains("match_decision.employee_id")
                .contains("current_employment.version_valid_to IS NULL")
                .contains("current_employment.record_status = 'ACTIVE'")
                .contains("current_employment.effective_from &lt;= #{at}")
                .contains("current_employment.effective_to &gt; #{at}")
                .contains("assigned_organization.company_id =")
                .contains("employee.company_id")
                .contains("assigned_organization.identity_status =")
                .contains("assigned_projection.current_version_id")
                .contains("assigned_version.status = 'ACTIVE'")
                .contains("scoped_organization.company_id =")
                .contains("source.company_id")
                .contains("scoped_organization.identity_status = 'ACTIVE'")
                .contains("scoped_projection.current_version_id")
                .contains("scoped_version.status = 'ACTIVE'")
                .contains("data_scope.include_descendants = FALSE")
                .contains("data_scope.include_descendants = TRUE")
                .contains("principal.employee_id = match_decision.employee_id")
                .contains("match_decision.match_status = 'MATCHED'")
                .contains("employee.employee_number")
                .doesNotContain("NULL AS employee_number");

        assertThat(count(xml, "<include refid=\"oaDocumentVisibility\"/>"))
                .as("count and list must share the exact scope predicate")
                .isEqualTo(2);
    }

    @Test
    void unmatchedRowsCannotFallThroughToOrdinaryOrganizationReaders() throws Exception {
        String xml = Files.readString(MAPPER);
        String countQuery = between(
                xml,
                "<select id=\"countOaDocuments\"",
                "</select>");
        String listQuery = between(
                xml,
                "<select id=\"listOaDocuments\"",
                "</select>");

        assertThat(countQuery)
                .contains("JOIN employee_match_decision match_decision")
                .contains("match_decision.match_status = 'MATCHED'")
                .contains("<include refid=\"oaDocumentVisibility\"/>");
        assertThat(listQuery)
                .contains("JOIN employee_match_decision match_decision")
                .contains("match_decision.match_status = 'MATCHED'")
                .contains("<include refid=\"oaDocumentVisibility\"/>");
    }

    @Test
    void historicalMatchedEmploymentIsFactOnlyAndCannotAuthorizeFormerDepartment()
            throws Exception {
        String visibility = between(
                Files.readString(MAPPER),
                "<sql id=\"oaDocumentVisibility\">",
                "</sql>");

        assertThat(visibility)
                .contains("employment_assignment current_employment")
                .contains("current_employment.organization_id")
                .doesNotContain(
                        "data_scope.organization_id =\n"
                                + "                                    employment.organization_id",
                        "closure.descendant_organization_id =\n"
                                + "                                            employment.organization_id");
    }

    private static int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }

    private static String between(String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(end, startIndex);
        return value.substring(startIndex, endIndex + end.length());
    }
}
