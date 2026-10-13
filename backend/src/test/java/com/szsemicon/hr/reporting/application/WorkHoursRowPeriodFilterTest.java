package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.reporting.application.QueryPageRows.EmployeeDailyAggregateRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.OaRow;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for workHoursRow period-filter, dataAsOf cap, and
 * cross-projection dedup logic.
 *
 * Real-data motivation:
 *   唐勇 SZST0026, Oct 2026 pin: dataAsOf = 2026-10-04T16:31:25Z (Shanghai
 *   2026-10-05 00:31:25). Daily facts cover only 2026-10-01–05. The SICK leave
 *   doc spans Sep 17→Oct 16; without the dataAsOf cap the Oct 8–16 future days
 *   (not yet calculated) would still be subtracted, yielding actualHours < 0.
 */
class WorkHoursRowPeriodFilterTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private static EmployeeDailyAggregateRow aggregate(long scheduledMinutes) {
        return new EmployeeDailyAggregateRow(
                "emp-1", "SZST0026", "唐勇", "org-1", "神州科技",
                0, 0, 0, 0,
                scheduledMinutes,
                0, 0, 0,
                0, 0, 0, 0,
                scheduledMinutes / 480,
                BigDecimal.ZERO,
                null);
    }

    /** OaRow with byDate JSON, projection window, and a specific dataAsOf. */
    private static OaRow oaRow(
            String docId,
            LocalDate projStart,
            LocalDate projEnd,
            long recognizedMinutes,
            String byDateJson,
            Instant dataAsOf) {
        return new OaRow(
                docId, "emp-1", "SZST0026", "唐勇", "org-1", "神州科技",
                "LEAVE", "SICK",
                projStart.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                projEnd.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                recognizedMinutes,
                "APPROVED", "OA",
                projStart, projEnd,
                byDateJson,
                dataAsOf);
    }

    // ── dataAsOf cap tests ────────────────────────────────────────────────────

    @Test
    void dataAsOfCapExcludesFutureSickLeaveDays() {
        // Oct projection: dataAsOf = 2026-10-04T16:31:25Z
        // → Shanghai 2026-10-05 00:31:25 → cap day = 2026-10-05 (exclusive)
        // dayMap has Oct 8 and Oct 9, which are AFTER the cap → must be excluded.
        // dayMap also has Oct 1–4, which are within cap → must be included.
        // 唐勇 scenario: scheduled = 0 (only holidays 10/1-7), dataAsOf covers 10/1-4.
        Instant dataAsOf = Instant.parse("2026-10-04T16:31:25Z"); // 2026-10-05 00:31 CST
        String byDateJson = "{"
                + "\"2026-10-01\":0,"   // PUBLIC_HOLIDAY → 0 min (legal zero)
                + "\"2026-10-08\":480," // future — must be excluded by dataAsOf cap
                + "\"2026-10-09\":480"  // future — must be excluded by dataAsOf cap
                + "}";
        // Total declared = 960 (only the two non-zero Oct future days)
        var doc = oaRow("sick-oct", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1),
                960L, byDateJson, dataAsOf);

        var row = AttendanceReportQueryPageService.workHoursRow(
                aggregate(0L), List.of(doc),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1));

        // Oct 1 has 0 minutes; Oct 8/9 are after the dataAsOf cap → total = 0
        assertThat(row.get("leaveHours")).isEqualTo(0.0d);
        // actual = 0 scheduled + 0 paid OT + 0 vol OT − 0 leave = 0 (not negative)
        assertThat(row.get("actualHours")).isEqualTo(0.0d);
    }

    @Test
    void dataAsOfCapIncludesDayEqualsDataAsOfShanghai() {
        // dataAsOf = 2026-10-04T16:31:25Z = 2026-10-05 00:31 CST → shanghaiDate = Oct 5
        // cap (exclusive upper bound) = shanghaiDate + 1 = Oct 6
        // Oct 4 < Oct 6 → included; Oct 5 < Oct 6 → included; Oct 6 >= Oct 6 → excluded.
        Instant dataAsOf = Instant.parse("2026-10-04T16:31:25Z");
        String byDateJson = "{\"2026-10-04\":480,\"2026-10-05\":480,\"2026-10-06\":480}";
        var doc = oaRow("sick-cap-boundary",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1),
                1440L, byDateJson, dataAsOf);

        var row = AttendanceReportQueryPageService.workHoursRow(
                aggregate(960L), List.of(doc),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1));

        // Oct 4 + Oct 5 within cap, Oct 6 excluded → 2 × 480 = 16h
        assertThat(row.get("leaveHours")).isEqualTo(16.0d);
    }

    @Test
    void dataAsOfCapCrossesUtcMidnightShanghaiStillPreviousDay() {
        // dataAsOf = 2026-10-04T15:59:00Z = 2026-10-04 23:59 CST → shanghaiDate = Oct 4
        // cap = Oct 5 (exclusive). Oct 4 included; Oct 5 excluded.
        Instant dataAsOf = Instant.parse("2026-10-04T15:59:00Z"); // 23:59 CST, still Oct 4
        String byDateJson = "{\"2026-10-04\":480,\"2026-10-05\":480}";
        var doc = oaRow("sick-utc-midnight",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1),
                960L, byDateJson, dataAsOf);

        var row = AttendanceReportQueryPageService.workHoursRow(
                aggregate(480L), List.of(doc),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1));

        // Oct 4 within [Oct 1, Oct 5); Oct 5 >= Oct 5 → excluded → 480 min = 8h
        assertThat(row.get("leaveHours")).isEqualTo(8.0d);
    }

    @Test
    void dataAsOfCapCrossesUtcMidnightJustIntoNextShanghaiDay() {
        // dataAsOf = 2026-10-04T16:01:00Z = 2026-10-05 00:01 CST → shanghaiDate = Oct 5
        // cap = Oct 6 (exclusive) → Oct 5 is now included, Oct 6 excluded.
        Instant dataAsOf = Instant.parse("2026-10-04T16:01:00Z"); // 00:01 CST Oct 5
        String byDateJson = "{\"2026-10-04\":480,\"2026-10-05\":480,\"2026-10-06\":480}";
        var doc = oaRow("sick-utc-next-day",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1),
                1440L, byDateJson, dataAsOf);

        var row = AttendanceReportQueryPageService.workHoursRow(
                aggregate(960L), List.of(doc),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1));

        // Oct 4 + Oct 5 included; Oct 6 excluded → 2 × 480 = 16h
        assertThat(row.get("leaveHours")).isEqualTo(16.0d);
    }

    // ── Sep full month unaffected by Oct dataAsOf ─────────────────────────────

    @Test
    void septemberProjectionIsNotCapByOctoberDataAsOf() {
        // The Sep projection has its own dataAsOf; Oct's cap must not bleed into it.
        // Sep doc: 22 workdays × 480 = 10560 min = 176 h, dataAsOf = 2026-10-04T16:00:00Z.
        // All Sep days are before Oct 5 anyway, so the cap makes no difference;
        // this test confirms the Sep result is still exactly 176h.
        Instant sepDataAsOf = Instant.parse("2026-10-04T16:00:00Z");
        String byDateJson = "{" +
                "\"2026-09-01\":480,\"2026-09-02\":480,\"2026-09-03\":480," +
                "\"2026-09-04\":480,\"2026-09-07\":480,\"2026-09-08\":480," +
                "\"2026-09-09\":480,\"2026-09-10\":480,\"2026-09-11\":480," +
                "\"2026-09-14\":480,\"2026-09-15\":480,\"2026-09-16\":480," +
                "\"2026-09-17\":480,\"2026-09-18\":480,\"2026-09-20\":480," +
                "\"2026-09-21\":480,\"2026-09-22\":480,\"2026-09-23\":480," +
                "\"2026-09-24\":480,\"2026-09-28\":480,\"2026-09-29\":480," +
                "\"2026-09-30\":480}"; // 22 entries × 480 = 10560
        var doc = oaRow("sick-sep",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                10560L, byDateJson, sepDataAsOf);

        var row = AttendanceReportQueryPageService.workHoursRow(
                aggregate(10560L), List.of(doc),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));

        assertThat(row.get("leaveHours")).isEqualTo(176.0d);
        assertThat(row.get("actualHours")).isEqualTo(0.0d);
    }

    // ── Cross-projection same-doc/day dedup ────────────────────────────────────

    @Test
    void sameDocDaySameValueInTwoProjectionRowsCountsOnce() {
        // The same OA document appears in both Sep and Oct projection rows.
        // Sep row covers Sep 29–30; Oct row covers Oct 8–9.
        // Both carry the full 4-day dayMap. Without dedup, Sep 29+30 would be
        // counted twice. The seen map must deduplicate them.
        String dayMap = "{\"2026-09-29\":480,\"2026-09-30\":480," +
                "\"2026-10-08\":480,\"2026-10-09\":480}";
        Instant dataAsOf = Instant.parse("2026-10-10T16:00:00Z");
        var sepDoc = oaRow("cross-doc", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                1920L, dayMap, dataAsOf);
        var octDoc = oaRow("cross-doc", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1),
                1920L, dayMap, dataAsOf);

        // Querying Sep 1 → Nov 1 means both projections are in scope.
        var row = AttendanceReportQueryPageService.workHoursRow(
                aggregate(22080L), List.of(sepDoc, octDoc),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 1));

        // Sep 29+30 from sepDoc (960) + Oct 8+9 from octDoc (960) = 1920 min = 32h.
        // Without dedup this would be 64h.
        assertThat(row.get("leaveHours")).isEqualTo(32.0d);
    }

    @Test
    void sameDocDayDifferentValueInTwoProjectionRowsThrows503() {
        // Same document ID, same business date, but two projection rows carry
        // different per-day minute values for that date. This indicates a corrupt
        // or inconsistently published fact and must surface as 503.
        String dayMapV1 = "{\"2026-09-29\":480}";
        String dayMapV2 = "{\"2026-09-29\":240}"; // different value for same day
        Instant dataAsOf = Instant.parse("2026-10-10T16:00:00Z");
        var row1 = oaRow("dup-doc", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                480L, dayMapV1, dataAsOf);
        var row2 = oaRow("dup-doc", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                240L, dayMapV2, dataAsOf);

        assertThatThrownBy(() ->
                AttendanceReportQueryPageService.workHoursRow(
                        aggregate(11040L), List.of(row1, row2),
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void twoDistinctDocsSameDayDifferentValuesAreNotFlaggedAsDuplicate() {
        // Two *different* OA documents with leave on the same day but different
        // minutes are perfectly legal (e.g. half-day AM + half-day PM leave).
        // The dedup key includes documentId so these must NOT trigger 503.
        String dayMapA = "{\"2026-09-15\":240}"; // half-day doc A
        String dayMapB = "{\"2026-09-15\":240}"; // half-day doc B
        Instant dataAsOf = Instant.parse("2026-10-01T00:00:00Z");
        var docA = oaRow("doc-a", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                240L, dayMapA, dataAsOf);
        var docB = new OaRow(
                "doc-b", "emp-1", "SZST0026", "唐勇", "org-1", "神州科技",
                "LEAVE", "PERSONAL",
                LocalDate.of(2026, 9, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                LocalDate.of(2026, 10, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                240L, "APPROVED", "OA",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                dayMapB,
                dataAsOf);

        var row = AttendanceReportQueryPageService.workHoursRow(
                aggregate(11040L), List.of(docA, docB),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));

        // Both half-days are different documents → 240 + 240 = 480 min = 8h
        assertThat(row.get("leaveHours")).isEqualTo(8.0d);
    }
}
