package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PunchLocationSqlContractTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/PunchLocationMapper.xml");
    private static final Path QUERY_MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportQueryPageMapper.xml");
    private static final Path DASHBOARD = Path.of(
            "src/main/resources/mappers/AttendanceReportMapper.xml");
    private static final Path SELF_DASHBOARD = Path.of(
            "src/main/resources/mappers/SelfAttendanceDashboardMapper.xml");

    @Test
    void dayPunchListDoesNotSelectCoordinates() throws Exception {
        String xml = Files.readString(MAPPER);
        String list = between(xml, "<select id=\"listDayPunches\"", "</select>");
        assertThat(list)
                .contains("raw.verification_method")
                .contains("raw.raw_attendance_fact_id")
                .doesNotContain("longitude")
                .doesNotContain("latitude")
                .doesNotContain("map_longitude")
                .doesNotContain("map_latitude")
                .doesNotContain("location_summary");
    }

    @Test
    void dashboardsAndQuerySheetsStillOmitCoordinates() throws Exception {
        for (Path path : java.util.List.of(QUERY_MAPPER, DASHBOARD, SELF_DASHBOARD)) {
            if (!Files.exists(path)) {
                continue;
            }
            String xml = Files.readString(path);
            assertThat(xml)
                    .doesNotContain("map_longitude")
                    .doesNotContain("map_latitude");
        }
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from + start.length());
        if (from < 0 || to < 0) {
            throw new IllegalStateException("missing block " + start);
        }
        return text.substring(from, to);
    }
}
