package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AttendanceDashboardWorkbenchMapperXmlTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceDashboardWorkbenchMapper.xml");

    @Test
    void punchExemptionQueryMatchesOfficialExecutiveAndStandingRoster()
            throws Exception {
        String mapper = Files.readString(MAPPER)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        assertThat(mapper).contains(
                "<select id=\"listpunchexemptemployeeids\"",
                "role.role_code = 'executive'",
                "assignment.source = 'standing_list'",
                "roster_employee.employee_number = listed_employee.employee_number",
                "roster_employee.company_id = #{companyid}",
                "assignment.valid_to &gt; #{asof}");
    }
}
