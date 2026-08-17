package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeliSyncLogSqlContractTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/DeliSyncLogMapper.xml");
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V48__business_rules_alignment_schema.sql");

    @Test
    void mapperPersistsTheRequiredDisplayFields() throws Exception {
        String sql = Files.readString(MAPPER);

        assertThat(sql)
                .contains("sync_started_at")
                .contains("sync_status")
                .contains("record_count")
                .contains("error_message")
                .contains("<select id=\"findLatestCompleted\"")
                .contains("sync_status IN ('SUCCESS', 'FAILED')");
    }

    @Test
    void failedRowsCannotAdvanceTheSuccessfulTimeMarker() throws Exception {
        String sql = Files.readString(MAPPER);
        String markerQuery = sql.substring(
                sql.indexOf("<select id=\"findLastSuccessfulSyncTime\""),
                sql.indexOf("</select>", sql.indexOf(
                        "<select id=\"findLastSuccessfulSyncTime\"")));

        assertThat(markerQuery)
                .contains("sync_time_range_end")
                .contains("sync_status = 'SUCCESS'")
                .doesNotContain("sync_status = 'FAILED'");
    }

    @Test
    void cleanupAndMigrationSupportThirtyDayRetention() throws Exception {
        String mapper = Files.readString(MAPPER);
        String migration = Files.readString(MIGRATION);

        assertThat(mapper)
                .contains("<delete id=\"deleteStartedBefore\"")
                .contains("sync_started_at &lt; #{cutoff}");
        assertThat(migration)
                .contains("CREATE TABLE deli_sync_log")
                .contains("sync_time_range_start")
                .contains("sync_time_range_end")
                .contains("execution_duration_ms");
    }
}
