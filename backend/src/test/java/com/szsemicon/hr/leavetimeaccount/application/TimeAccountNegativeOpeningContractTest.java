package com.szsemicon.hr.leavetimeaccount.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TimeAccountNegativeOpeningContractTest {

    @Test
    void v57DropsNonNegativeBalanceCheck() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V57__allow_negative_time_account_balance.sql"));
        assertThat(sql)
                .contains("DROP CHECK ck_time_account_balance")
                .doesNotContain("balance_hours >= 0");
    }

    @Test
    void v64AllowsNegativeReportPinOpeningHours() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V64__allow_negative_report_time_account_opening.sql"));
        String ddl = sql.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(ddl)
                .contains("attendance_report_time_account_fact")
                .contains("MODIFY opening_hours DECIMAL(16,2) NOT NULL")
                .doesNotContain("UNSIGNED");
    }
}
