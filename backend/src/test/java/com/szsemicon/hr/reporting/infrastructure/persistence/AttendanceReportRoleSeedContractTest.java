package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AttendanceReportRoleSeedContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V10__formal_attendance_reporting.sql");

    @Test
    void manufacturingSupervisorGetsReadButNeverExportCapabilities()
            throws Exception {
        String migration = Files.readString(MIGRATION);
        String roleSeed = between(
                migration,
                "INSERT INTO auth_role (",
                "INSERT INTO auth_capability (");
        String readGrant = between(
                migration,
                "INSERT INTO auth_role_capability (role_id, capability_id)",
                "INSERT INTO auth_role_capability (role_id, capability_id)");
        String exportGrant = migration.substring(
                migration.indexOf(
                        "INSERT INTO auth_role_capability"
                                + " (role_id, capability_id)",
                        migration.indexOf(
                                "INSERT INTO auth_role_capability"
                                        + " (role_id, capability_id)")
                                + 1));

        assertThat(roleSeed)
                .contains("'MANUFACTURING_CENTER_SUPERVISOR'")
                .contains("'制造中心主管'");
        assertThat(readGrant)
                .contains("'ATTENDANCE_DASHBOARD:READ'")
                .contains("'ATTENDANCE_REPORT:READ'")
                .contains("'MANUFACTURING_CENTER_SUPERVISOR'")
                .doesNotContain("EXPORT_CREATE")
                .doesNotContain("EXPORT_DOWNLOAD");
        assertThat(exportGrant)
                .contains("'ATTENDANCE_REPORT:EXPORT_CREATE'")
                .contains("'ATTENDANCE_REPORT:EXPORT_DOWNLOAD'")
                .contains("WHERE role.role_code = 'HR_ADMIN'")
                .doesNotContain("'MANUFACTURING_CENTER_SUPERVISOR'");
    }

    @Test
    void roleSeedDoesNotCreateOrInferAnyDataScope() throws Exception {
        String migration = Files.readString(MIGRATION);
        String roleSeed = between(
                migration,
                "INSERT INTO auth_role (",
                "INSERT INTO auth_capability (");

        assertThat(roleSeed)
                .doesNotContain("auth_data_scope")
                .doesNotContain("ORGANIZATION")
                .doesNotContain("include_descendants");
        assertThat(migration)
                .doesNotContain("INSERT INTO auth_data_scope")
                .doesNotContain(
                        "INSERT INTO auth_principal_role_assignment");
    }

    private static String between(String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(
                end, startIndex + start.length());
        return value.substring(startIndex, endIndex);
    }
}
