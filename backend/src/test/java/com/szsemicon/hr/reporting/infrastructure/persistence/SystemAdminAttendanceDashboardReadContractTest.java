package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SystemAdminAttendanceDashboardReadContractTest {

    @Test
    void grantsDashboardReadToSystemAdminWithoutWideningSql()
            throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V56__system_admin_attendance_dashboard_read.sql"));

        assertThat(migration)
                .contains("ATTENDANCE_DASHBOARD:READ")
                .contains("SYSTEM_ADMIN")
                .contains("NOT EXISTS")
                .doesNotContain("auth_data_scope")
                .doesNotContain("role_code IN");
    }
}
