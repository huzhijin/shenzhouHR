package com.szsemicon.hr.leavetimeaccount.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LeaveAccountOaRecalculateSqlContractTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/LeaveAccountOaRecalculateMapper.xml");

    @Test
    void listOaHoursCreditsTimeOffInLieuOvertime() throws Exception {
        String xml = Files.readString(MAPPER);
        int from = xml.indexOf("<select id=\"listOaHours\"");
        int to = xml.indexOf("</select>", from);
        String list = xml.substring(from, to);
        assertThat(list)
                .contains("oa_attendance_document_context")
                .contains("TIME_OFF_IN_LIEU")
                .contains("COMPENSATORY")
                .contains("%TIME_OFF%")
                .contains("%调休%");
    }

    @Test
    void incrementalSyncReversesOnlyPostCutoffOaSyncRequestIds() throws Exception {
        String xml = Files.readString(MAPPER);
        int from = xml.indexOf("<select id=\"listUnreversedOaSyncEntries\"");
        int to = xml.indexOf("</select>", from);
        String list = xml.substring(from, to);
        assertThat(list)
                .contains("entry.business_date &gt;= '2026-09-19'")
                .contains("oa-sync:%")
                .contains("oa-sync-rev:%")
                .contains("oa-sync-fix-%");
        assertThat(xml.indexOf("oa-sync-fix-%", to)).isGreaterThan(to);
    }
}
