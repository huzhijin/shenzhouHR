package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real SQL selection of current pins, with the source/calculation boundary mocked. */
class AttendanceReportAutoRecalcHistoryTest {
    private static final Instant NOW = Instant.parse("2026-10-15T04:15:00Z");
    private static final Instant REFRESHED = NOW.plusSeconds(7);
    private static final String COMPANY = "company-1";
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private JdbcTemplate jdbc;
    private RealtimeAttendanceReportSnapshotService snapshots;
    private ReportSlotCompletionListener slotListener;

    @BeforeEach
    void createDatabase() {
        var dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:auto-recalc-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE company (company_id VARCHAR(36), status VARCHAR(32))");
        jdbc.execute("""
                CREATE TABLE attendance_report_auto_recalc_slot (
                    slot_id VARCHAR(36), slot_start TIMESTAMP PRIMARY KEY, status VARCHAR(32),
                    deli_succeeded_at TIMESTAMP, oa_succeeded_at TIMESTAMP,
                    recalc_due_at TIMESTAMP, recalc_completed_at TIMESTAMP, last_error VARCHAR(500)
                )
                """);
        jdbc.execute("""
                CREATE TABLE attendance_report_projection (
                    attendance_report_projection_id VARCHAR(36) PRIMARY KEY,
                    company_id VARCHAR(36), period_start DATE, period_end_exclusive DATE,
                    period_state VARCHAR(32), status VARCHAR(32), published_at TIMESTAMP,
                    formula_catalog_version VARCHAR(128), source_versions_json VARCHAR(1024),
                    projection_version VARCHAR(128)
                )
                """);
        jdbc.update("INSERT INTO company VALUES (?, 'ACTIVE')", COMPANY);
        snapshots = mock(RealtimeAttendanceReportSnapshotService.class);
        slotListener = mock(ReportSlotCompletionListener.class);
    }

    @Test
    void refreshesExistingOpenHistoryBeforeComparingCutoffsAndRecalculatesWholeMonths() {
        seedMixedHistory();
        seedSlot(NOW);
        when(snapshots.refreshSources(eq(COMPANY), any(), eq(NOW))).thenReturn(REFRESHED);
        when(snapshots.sourcesNewerThanPin(any(), eq(REFRESHED))).thenReturn(true);

        serviceAt(NOW).processDueSlots();

        var ordered = inOrder(snapshots);
        ordered.verify(snapshots).refreshSources(COMPANY, AUGUST, NOW);
        ordered.verify(snapshots).sourcesNewerThanPin(
                org.mockito.ArgumentMatchers.argThat(pin -> pin.filter().period().equals(AUGUST)
                        && pin.dataAsOf().equals(REFRESHED)), eq(REFRESHED));
        ordered.verify(snapshots).materializeCompanyMonthWindow(COMPANY, AUGUST, RecalcWindow.MONTH, REFRESHED);
        ordered.verify(snapshots).refreshSources(COMPANY, OCTOBER, NOW);
        ordered.verify(snapshots).materializeCompanyMonthWindow(COMPANY, OCTOBER, RecalcWindow.MONTH, REFRESHED);
        ordered.verifyNoMoreInteractions();
        assertThat(slotStatus()).isEqualTo("COMPLETED");
        verify(slotListener).onReportSlotCompleted(COMPANY, 2026);
    }

    @Test
    void unchangedHistoryIsSkippedOnlyAfterSourceRefresh() {
        seedMixedHistory();
        seedSlot(NOW);
        when(snapshots.refreshSources(eq(COMPANY), any(), eq(NOW))).thenReturn(REFRESHED);
        when(snapshots.sourcesNewerThanPin(any(), eq(REFRESHED))).thenReturn(false);

        serviceAt(NOW).processDueSlots();

        var ordered = inOrder(snapshots);
        ordered.verify(snapshots).refreshSources(COMPANY, AUGUST, NOW);
        ordered.verify(snapshots).sourcesNewerThanPin(any(), eq(REFRESHED));
        ordered.verify(snapshots).refreshSources(COMPANY, OCTOBER, NOW);
        ordered.verify(snapshots).materializeCompanyMonthWindow(COMPANY, OCTOBER, RecalcWindow.MONTH, REFRESHED);
        ordered.verifyNoMoreInteractions();
        verify(snapshots, never()).materializeCompanyMonthWindow(eq(COMPANY), eq(AUGUST), any(), any());
        assertThat(slotStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void firstThreeDaysIncludeThePreviousMonthWithoutCreatingArbitraryOlderReports() {
        var early = Instant.parse("2026-10-02T04:15:00Z");
        seedSlot(early);
        when(snapshots.refreshSources(eq(COMPANY), any(), eq(early))).thenReturn(early.plusSeconds(7));

        serviceAt(early).processDueSlots();

        var ordered = inOrder(snapshots);
        ordered.verify(snapshots).refreshSources(COMPANY, OCTOBER.minusMonths(1), early);
        ordered.verify(snapshots).materializeCompanyMonthWindow(COMPANY, OCTOBER.minusMonths(1), RecalcWindow.MONTH, early.plusSeconds(7));
        ordered.verify(snapshots).refreshSources(COMPANY, OCTOBER, early);
        ordered.verify(snapshots).materializeCompanyMonthWindow(COMPANY, OCTOBER, RecalcWindow.MONTH, early.plusSeconds(7));
        ordered.verifyNoMoreInteractions();
    }

    @Test
    void latestClosedCurrentMonthCannotBeReopenedEvenWhenItsFormulaIsOlder() {
        pin("current-open", COMPANY, OCTOBER, "OPEN", "PUBLISHED", 20);
        pin("current-closed", COMPANY, OCTOBER, "CLOSED", "PUBLISHED", 10);
        jdbc.update("UPDATE attendance_report_projection SET formula_catalog_version='OLD_FORMULA' WHERE attendance_report_projection_id='current-closed'");
        seedSlot(NOW);

        serviceAt(NOW).processDueSlots();

        verifyNoInteractions(snapshots);
        assertThat(slotStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void failedSeptemberRefreshStillProcessesOctoberAndOtherCompaniesButFailsTheSlot() {
        var september = YearMonth.of(2026, 9);
        pin("sep-open", COMPANY, september, "OPEN", "PUBLISHED", 20);
        jdbc.update("INSERT INTO company VALUES ('company-2', 'ACTIVE')");
        seedSlot(NOW);
        when(snapshots.refreshSources(COMPANY, september, NOW))
                .thenThrow(new IllegalStateException("OA_HISTORY_REFRESH_FAILED"));
        when(snapshots.refreshSources(COMPANY, OCTOBER, NOW)).thenReturn(REFRESHED);
        when(snapshots.refreshSources("company-2", OCTOBER, NOW)).thenReturn(REFRESHED);

        serviceAt(NOW).processDueSlots();

        verify(snapshots, never()).sourcesNewerThanPin(any(), any());
        verify(snapshots, never()).materializeCompanyMonthWindow(eq(COMPANY), eq(september), any(), any());
        verify(snapshots).materializeCompanyMonthWindow(COMPANY, OCTOBER, RecalcWindow.MONTH, REFRESHED);
        verify(snapshots).materializeCompanyMonthWindow("company-2", OCTOBER, RecalcWindow.MONTH, REFRESHED);
        verifyNoInteractions(slotListener);
        assertThat(slotStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM attendance_report_auto_recalc_slot", String.class))
                .isEqualTo("company=company-1 month=2026-09: OA_HISTORY_REFRESH_FAILED");
        assertThat(jdbc.queryForObject("SELECT recalc_completed_at FROM attendance_report_auto_recalc_slot", Timestamp.class))
                .isNull();
    }

    @Test
    void pinWithoutSourceVersionsIsRebuiltAfterRefreshRatherThanPermanentlySkipped() {
        pin("current-empty", COMPANY, OCTOBER, "OPEN", "PUBLISHED", 20);
        jdbc.update("UPDATE attendance_report_projection SET source_versions_json='[]'");
        seedSlot(NOW);
        when(snapshots.refreshSources(COMPANY, OCTOBER, NOW)).thenReturn(REFRESHED);

        serviceAt(NOW).processDueSlots();

        verify(snapshots).materializeCompanyMonthWindow(COMPANY, OCTOBER, RecalcWindow.MONTH, REFRESHED);
        verify(snapshots, never()).sourcesNewerThanPin(any(), any());
    }

    private void seedMixedHistory() {
        pin("aug-open", COMPANY, AUGUST, "OPEN", "PUBLISHED", 20);
        pin("sep-old-open", COMPANY, YearMonth.of(2026, 9), "OPEN", "PUBLISHED", 30);
        pin("sep-closed", COMPANY, YearMonth.of(2026, 9), "CLOSED", "PUBLISHED", 10);
        pin("jul-old-open", COMPANY, YearMonth.of(2026, 7), "OPEN", "PUBLISHED", 30);
        pin("jul-frozen", COMPANY, YearMonth.of(2026, 7), "FROZEN", "PUBLISHED", 10);
        pin("jun-draft", COMPANY, YearMonth.of(2026, 6), "OPEN", "DRAFT", 10);
        pin("foreign-open", "company-other", YearMonth.of(2026, 5), "OPEN", "PUBLISHED", 10);
        pin("future-open", COMPANY, YearMonth.of(2026, 11), "OPEN", "PUBLISHED", 10);
    }

    private AttendanceReportAutoRecalcService serviceAt(Instant now) {
        return new AttendanceReportAutoRecalcService(jdbc, snapshots,
                Clock.fixed(now, ZoneOffset.UTC), true, Duration.ofMinutes(10), java.util.List.of(slotListener));
    }

    private void seedSlot(Instant now) {
        jdbc.update("""
                INSERT INTO attendance_report_auto_recalc_slot
                    (slot_id, slot_start, status, deli_succeeded_at, oa_succeeded_at, recalc_due_at)
                VALUES ('slot-1', ?, 'SCHEDULED', ?, ?, ?)
                """, Timestamp.from(now.minusSeconds(900)), Timestamp.from(now.minusSeconds(900)),
                Timestamp.from(now.minusSeconds(600)), Timestamp.from(now));
    }

    private void pin(String id, String company, YearMonth month, String state, String status, int ageSeconds) {
        jdbc.update("""
                INSERT INTO attendance_report_projection VALUES (?, ?, ?, ?, ?, ?, ?,
                    'FULL_CALCULATION_OA_FORM_HOURS_V8', '["OA:old-cutoff"]', ?)
                """, id, company, Date.valueOf(month.atDay(1)), Date.valueOf(month.plusMonths(1).atDay(1)),
                state, status, Timestamp.from(NOW.minusSeconds(ageSeconds)), "ARP1-" + "a".repeat(64));
    }

    private String slotStatus() {
        return jdbc.queryForObject("SELECT status FROM attendance_report_auto_recalc_slot", String.class);
    }
}
