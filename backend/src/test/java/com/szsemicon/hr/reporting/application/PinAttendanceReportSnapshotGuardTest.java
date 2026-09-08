package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.interfaces.rest.AttendanceReportController;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

class PinAttendanceReportSnapshotGuardTest {

    @Test
    void refreshSqlStaysOnHrAndSystemAdminRoles() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V14__attendance_report_projection_refresh.sql"));

        assertThat(sql)
                .contains("ATTENDANCE_REPORT:REFRESH")
                .contains("SYSTEM_ADMIN")
                .contains("HR_ADMIN")
                .doesNotContain("EXECUTIVE")
                .doesNotContain("DEPARTMENT_HEAD")
                .doesNotContain("EMPLOYEE_SELF")
                .doesNotContain("MANUFACTURING_CENTER_SUPERVISOR");
    }

    @Test
    void autoSyncCronDefaultsStayIncrementalAndJobsDoNotRecalculateReports()
            throws Exception {
        String application = Files.readString(Path.of(
                "src/main/resources/application.yml"));
        String deliJob = Files.readString(Path.of(
                "src/main/java/com/szsemicon/hr/evidenceingestion/"
                        + "infrastructure/scheduler/DeliAutoSyncJob.java"));
        String oaJob = Files.readString(Path.of(
                "src/main/java/com/szsemicon/hr/evidenceingestion/"
                        + "infrastructure/scheduler/OaAutoSyncJob.java"));

        assertThat(application)
                .contains("auto-sync-cron: ${SHENZHOUHR_DELI_AUTO_SYNC_CRON:0 0 0,8,12,18 * * ?}")
                .contains("auto-sync-cron: ${SHENZHOUHR_OA_AUTO_SYNC_CRON:0 0 * * * ?}");
        assertThat(deliJob)
                .contains("0 0 0,8,12,18 * * ?")
                .doesNotContain("recalculate");
        assertThat(oaJob)
                .contains("0 0 * * * ?")
                .doesNotContain("recalculate");
    }

    @Test
    void recalculateEndpointRequiresRefreshCapability() throws Exception {
        var method = java.util.Arrays.stream(
                        AttendanceReportController.class.getDeclaredMethods())
                .filter(candidate -> "recalculate".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        PreAuthorize authorize = method.getAnnotation(PreAuthorize.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);

        assertThat(mapping.value()).contains("/recalculate");
        assertThat(authorize.value())
                .contains("ATTENDANCE_REPORT:REFRESH");
    }
}
