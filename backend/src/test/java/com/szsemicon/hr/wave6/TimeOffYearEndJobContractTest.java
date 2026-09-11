package com.szsemicon.hr.wave6;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TimeOffYearEndJobContractTest {

    private static final Path V43 = Path.of(
            "src/main/resources/db/migration/V43__time_off_year_end_job.sql");

    @Test
    void migrationRetainsClusterLeaseRunSummaryAndPerEmployeeEvidence()
            throws Exception {
        String sql = Files.readString(V43);

        assertThat(sql).contains(
                "CREATE TABLE time_off_year_end_lock",
                "CREATE TABLE time_off_year_end_run",
                "CREATE TABLE time_off_year_end_run_item",
                "lock_expires_at",
                "success_count",
                "failure_count",
                "skipped_count",
                "employee_number",
                "source_event_id",
                "payload_digest",
                "item_status IN ('SUCCESS', 'FAILED', 'SKIPPED')",
                "ix_time_account_type_year_id");
    }

    @Test
    void productionConfigurationKeepsJobOffByDefaultAndSupportsReplay()
            throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        String job = Files.readString(Path.of(
                "src/main/java/com/szsemicon/hr/leavetimeaccount/"
                        + "infrastructure/scheduler/TimeOffYearEndJob.java"));
        String replay = Files.readString(Path.of(
                "src/main/java/com/szsemicon/hr/leavetimeaccount/"
                        + "infrastructure/scheduler/TimeOffYearEndReplayRunner.java"));
        String repository = Files.readString(Path.of(
                "src/main/java/com/szsemicon/hr/leavetimeaccount/"
                        + "infrastructure/persistence/JdbcTimeOffYearEndRepository.java"));
        String gateway = Files.readString(Path.of(
                "src/main/java/com/szsemicon/hr/leavetimeaccount/"
                        + "infrastructure/persistence/JdbcTimeOffYearEndProcedureGateway.java"));

        assertThat(yaml).contains(
                "SHENZHOUHR_TIME_OFF_YEAR_END_ENABLED:false",
                "SHENZHOUHR_TIME_OFF_YEAR_END_LOCK_LEASE:PT30M",
                "SHENZHOUHR_TIME_OFF_YEAR_END_ZONE:Asia/Shanghai");
        assertThat(job).contains(
                "matchIfMissing = false",
                "service.runPreviousYear(TriggerType.SCHEDULED)");
        assertThat(replay).contains(
                "name = \"replay-year\"",
                "service.runYear(replayYear, TriggerType.MANUAL)",
                "summary.status() == RunStatus.SKIPPED_LOCKED",
                "SZSC_YEAR_END_LOCK_HELD_BY_ANOTHER_NODE");
        assertThat(repository).contains(
                "CURRENT_TIMESTAMP(6)",
                "lock_expires_at > CURRENT_TIMESTAMP(6)")
                .doesNotContain("Timestamp.from(expiresAt)");
        assertThat(gateway).contains(
                "statement.setQueryTimeout(queryTimeoutSeconds)",
                "settings.lockLease().minusSeconds(5)");
    }
}
