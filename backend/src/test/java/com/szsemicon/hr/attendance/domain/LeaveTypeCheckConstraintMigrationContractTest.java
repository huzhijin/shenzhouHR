package com.szsemicon.hr.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LeaveTypeCheckConstraintMigrationContractTest {

    @Test
    void v65AllowsEveryLeaveTypeEnumName() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V65__widen_leave_type_checks.sql"));
        String ddl = sql.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(ddl)
                .contains("DROP CHECK ck_oa_document_leave_type")
                .contains("DROP CHECK ck_att_report_daily_leave_type")
                .contains("ck_oa_document_leave_type")
                .contains("ck_att_report_daily_leave_type")
                .contains("FAMILY_PLANNING")
                .contains("LIKE '%FAMILY_PLANNING%'");
        for (LeaveType type : LeaveType.values()) {
            assertThat(ddl).contains("'" + type.name() + "'");
        }
    }
}
