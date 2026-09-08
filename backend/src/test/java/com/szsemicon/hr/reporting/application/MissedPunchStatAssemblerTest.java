package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.infrastructure.persistence.QueryPageRows.DailyCellRow;
import com.szsemicon.hr.reporting.infrastructure.persistence.QueryPageRows.OaRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MissedPunchStatAssemblerTest {

    @Test
    void normalPunchesHaveTimesAndNoTone() {
        List<Map<String, Object>> days = MissedPunchStatAssembler.days(
                List.of(cell(
                        LocalDate.of(2026, 8, 3),
                        "WEEKDAY",
                        null,
                        0,
                        Instant.parse("2026-08-03T00:32:00Z"),
                        Instant.parse("2026-08-03T09:00:00Z"))),
                List.of());

        Map<String, Object> morning = slot(days.getFirst(), "morning");
        Map<String, Object> afternoon = slot(days.getFirst(), "afternoon");
        assertThat(morning.get("text")).isEqualTo("08:32");
        assertThat(morning.get("tone")).isNull();
        assertThat(afternoon.get("text")).isEqualTo("17:00");
        assertThat(afternoon.get("tone")).isNull();
        assertThat(MissedPunchStatAssembler.matchesFilters(days, null, null)).isFalse();
    }

    @Test
    void morningMissIsLabelledAndColoredOnlyOnThatSlot() {
        List<Map<String, Object>> days = MissedPunchStatAssembler.days(
                List.of(cell(
                        LocalDate.of(2026, 8, 4),
                        "WEEKDAY",
                        null,
                        1,
                        Instant.parse("2026-08-04T09:00:00Z"),
                        Instant.parse("2026-08-04T09:00:00Z"))),
                List.of());

        Map<String, Object> morning = slot(days.getFirst(), "morning");
        Map<String, Object> afternoon = slot(days.getFirst(), "afternoon");
        assertThat(morning.get("text")).isEqualTo("漏刷");
        assertThat(morning.get("tone")).isEqualTo("MISSING_PUNCH");
        assertThat(afternoon.get("text")).isEqualTo("17:00");
        assertThat(afternoon.get("tone")).isNull();
        assertThat(MissedPunchStatAssembler.matchesFilters(days, "MISSING_PUNCH", "MORNING"))
                .isTrue();
        assertThat(MissedPunchStatAssembler.matchesFilters(days, "MISSING_PUNCH", "AFTERNOON"))
                .isFalse();
        MissedPunchStatAssembler.Summary summary = MissedPunchStatAssembler.summary(days);
        assertThat(summary.missedCount()).isEqualTo(1);
        assertThat(summary.remark()).isEqualTo("8月4日（上班）");
    }

    @Test
    void makeupShowsRedTextWithoutMissFill() {
        Instant correction = Instant.parse("2026-08-05T00:18:00Z");
        List<Map<String, Object>> days = MissedPunchStatAssembler.days(
                List.of(cell(
                        LocalDate.of(2026, 8, 5),
                        "WEEKDAY",
                        null,
                        1,
                        null,
                        Instant.parse("2026-08-05T09:00:00Z"))),
                List.of(oa(
                        "PUNCH_CORRECTION",
                        null,
                        correction,
                        correction.plusSeconds(60),
                        "APPROVED")));

        Map<String, Object> morning = slot(days.getFirst(), "morning");
        assertThat(morning.get("text")).isEqualTo("补签08:18");
        assertThat(morning.get("tone")).isEqualTo("PUNCH_CORRECTION");
        assertThat(MissedPunchStatAssembler.matchesFilters(days, "PUNCH_CORRECTION", null))
                .isTrue();
        MissedPunchStatAssembler.Summary summary = MissedPunchStatAssembler.summary(days);
        assertThat(summary.missedCount()).isEqualTo(1);
        assertThat(summary.remark()).isEqualTo("8月5日（上班补签）");
    }

    @Test
    void restAndLeaveAreNotMissedPunch() {
        List<Map<String, Object>> rest = MissedPunchStatAssembler.days(
                List.of(cell(
                        LocalDate.of(2026, 8, 9),
                        "SUNDAY",
                        null,
                        1,
                        null,
                        null)),
                List.of());
        assertThat(slot(rest.getFirst(), "morning").get("text")).isEqualTo("");
        assertThat(slot(rest.getFirst(), "morning").get("tone")).isNull();

        List<Map<String, Object>> leave = MissedPunchStatAssembler.days(
                List.of(cell(
                        LocalDate.of(2026, 8, 6),
                        "WEEKDAY",
                        "ANNUAL",
                        1,
                        null,
                        null)),
                List.of());
        assertThat(slot(leave.getFirst(), "morning").get("tone")).isNull();
        assertThat(MissedPunchStatAssembler.matchesFilters(leave, null, null)).isFalse();
    }

    private static DailyCellRow cell(
            LocalDate date,
            String dayType,
            String leaveType,
            int missing,
            Instant first,
            Instant last) {
        return new DailyCellRow(
                "employee-1",
                date,
                dayType,
                leaveType,
                0,
                0,
                missing,
                first,
                last,
                0,
                0);
    }

    private static OaRow oa(
            String type,
            String leaveType,
            Instant start,
            Instant end,
            String status) {
        return new OaRow(
                "doc-1",
                "employee-1",
                "SZST0001",
                "张三",
                "org-1",
                "工程部",
                type,
                leaveType,
                start,
                end,
                0,
                status,
                "OA");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> slot(Map<String, Object> day, String side) {
        return (Map<String, Object>) day.get(side);
    }
}
