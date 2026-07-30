package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AttendanceRuntimeAuthoritySqlContractTest {

    private static final Path CONFIGURATION_MAPPER = Path.of(
            "src/main/resources/mappers/"
                    + "AttendanceConfigurationAuthorityMapper.xml");
    private static final Path PERIOD_MAPPER = Path.of(
            "src/main/resources/mappers/"
                    + "AttendancePeriodProtectionMapper.xml");

    @Test
    void configurationAuthorityIsExactTemporalAndCrossEntityClosed()
            throws Exception {
        String sql = normalize(Files.readString(CONFIGURATION_MAPPER));

        assertThat(sql)
                .contains("company.company_id = #{companyId}")
                .contains("employee.employee_id = #{employeeId}")
                .contains("employee_version.status = 'ACTIVE'")
                .contains("employment.record_status = 'ACTIVE'")
                .contains("employment.version_valid_to IS NULL")
                .contains("organization.identity_status = 'ACTIVE'")
                .contains("assignment_timeline.state = 'ACTIVE'")
                .contains("group_timeline.state = 'ACTIVE'")
                .contains("location_timeline.state = 'ACTIVE'")
                .contains("calendar_publication.state = 'PUBLISHED'")
                .contains("shift_publication.state = 'PUBLISHED'")
                .contains("calendar_day.business_date = #{businessDate}")
                .contains("newer_assignment.event_sequence "
                        + "&gt; assignment_timeline.event_sequence")
                .contains("newer_group.event_sequence "
                        + "&gt; group_timeline.event_sequence")
                .contains("newer_location.event_sequence "
                        + "&gt; location_timeline.event_sequence")
                .contains("LIMIT 2 FOR SHARE");
        assertThat(sql.toUpperCase(Locale.ROOT))
                .doesNotContain("${")
                .doesNotContain("LOWER(")
                .doesNotContain("TRIM(");
    }

    @Test
    void periodAuthorityUsesOnlyLatestPublishedProjection() throws Exception {
        String sql = normalize(Files.readString(PERIOD_MAPPER));

        assertThat(sql)
                .contains("latest.company_id = #{companyId}")
                .contains("projection.company_id = #{companyId}")
                .contains("employee.employee_id = #{employeeId}")
                .contains("latest.status = 'PUBLISHED'")
                .contains("projection.status = 'PUBLISHED'")
                .contains("latest.published_at IS NOT NULL")
                .contains("ORDER BY latest.published_at DESC, "
                        + "latest.attendance_report_projection_id DESC "
                        + "LIMIT 1")
                .contains("employment.version_valid_to IS NULL")
                .contains("employment.record_status = 'ACTIVE'")
                .contains("LIMIT 2 FOR SHARE");
        assertThat(sql.toUpperCase(Locale.ROOT))
                .doesNotContain("${")
                .doesNotContain("CURRENT_DATE")
                .doesNotContain("CURRENT_TIMESTAMP")
                .doesNotContain("COALESCE(PROJECTION.PERIOD_STATE, 'OPEN')");
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
