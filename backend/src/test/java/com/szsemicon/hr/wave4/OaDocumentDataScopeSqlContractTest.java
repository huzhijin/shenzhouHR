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
                .contains("data_scope.scope_type = 'LEGAL_ENTITY'")
                .contains("data_scope.scope_type = 'ORGANIZATION'")
                .contains("data_scope.scope_type = 'SELF'")
                .contains("organization_current_closure closure")
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

    private static int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }

    private static String between(String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(end, startIndex);
        return value.substring(startIndex, endIndex + end.length());
    }
}
