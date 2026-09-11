package com.szsemicon.hr.punchimport.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.application.RealtimeAttendanceReportSnapshotService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VendorMonthlyLiveReportContractTest {

    @Test
    void punchPublishDoesNotWriteReportProjectionOrSettlement() throws Exception {
        String command = Files.readString(Path.of(
                "src/main/java/com/szsemicon/hr/punchimport/application/PunchImportCommandService.java"),
                StandardCharsets.UTF_8);
        assertThat(command).doesNotContain("attendance_report_projection");
        assertThat(command).doesNotContain("AttendanceReportPublication");
        assertThat(command).doesNotContain("insertRecalculationIntent");
        assertThat(RealtimeAttendanceReportSnapshotService.class.getSimpleName())
                .isEqualTo("RealtimeAttendanceReportSnapshotService");
    }
}
