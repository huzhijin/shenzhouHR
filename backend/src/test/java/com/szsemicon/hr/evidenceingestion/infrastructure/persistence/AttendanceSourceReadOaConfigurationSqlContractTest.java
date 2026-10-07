package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AttendanceSourceReadOaConfigurationSqlContractTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceSourceReadMapper.xml");

    @Test
    void oaSourceExposesItsValidatedShanghaiTimeZoneWithoutDeliConfig() throws Exception {
        String sql = Files.readString(MAPPER);

        assertThat(sql)
                .contains("WHEN source.source_type = 'OA_ATTENDANCE'")
                .contains("THEN 'Asia/Shanghai'")
                .contains("ELSE COALESCE(configuration.source_time_zone, 'UNKNOWN')");
    }
}
