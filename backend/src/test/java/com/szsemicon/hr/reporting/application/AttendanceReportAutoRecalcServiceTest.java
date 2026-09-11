package com.szsemicon.hr.reporting.application;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class AttendanceReportAutoRecalcServiceTest {

    private static final Instant NOON =
            Instant.parse("2026-08-12T04:00:00Z");

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private RealtimeAttendanceReportSnapshotService snapshots;

    private AttendanceReportAutoRecalcService service;

    @BeforeEach
    void setUp() {
        service = new AttendanceReportAutoRecalcService(
                jdbc,
                snapshots,
                Clock.fixed(NOON, ZoneOffset.UTC),
                true,
                Duration.ofMinutes(10));
        org.mockito.Mockito.lenient()
                .doReturn(1)
                .when(jdbc)
                .update(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    void oneSourceSuccessDoesNotMaterializeUntilDue() {
        service.onScheduledDeliSuccess();
        verify(snapshots, never()).materializeCompanyMonthWindow(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void oaSuccessAloneSchedulesWithoutWaitingForDeli() {
        service.onScheduledOaSuccess();
        org.mockito.ArgumentCaptor<String> sql =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeast(2)).update(
                sql.capture(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThat(sql.getAllValues().getFirst())
                .contains("oa_succeeded_at")
                .contains("'SCHEDULED'");
        org.assertj.core.api.Assertions.assertThat(sql.getAllValues().get(1))
                .contains("deli_succeeded_at IS NOT NULL")
                .contains("oa_succeeded_at IS NOT NULL");
    }

    @Test
    void dueOpenMonthIsMaterialized() {
        Instant slot = Instant.parse("2026-08-12T04:00:00Z");
        when(jdbc.query(
                org.mockito.ArgumentMatchers.contains("SCHEDULED"),
                org.mockito.ArgumentMatchers.any(RowMapper.class),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(slot));
        when(jdbc.query(
                org.mockito.ArgumentMatchers.contains("company"),
                org.mockito.ArgumentMatchers.any(RowMapper.class)))
                .thenReturn(List.of("company-a"));
        when(jdbc.query(
                org.mockito.ArgumentMatchers.contains("period_state"),
                org.mockito.ArgumentMatchers.any(RowMapper.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        service.processDueSlots();

        verify(snapshots).materializeCompanyMonthWindow(
                "company-a",
                YearMonth.of(2026, 8),
                RecalcWindow.MONTH,
                NOON);
    }

    @Test
    void closedPinIsSkipped() {
        Instant slot = Instant.parse("2026-08-12T04:00:00Z");
        when(jdbc.query(
                org.mockito.ArgumentMatchers.contains("SCHEDULED"),
                org.mockito.ArgumentMatchers.any(RowMapper.class),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(slot));
        when(jdbc.query(
                org.mockito.ArgumentMatchers.contains("company"),
                org.mockito.ArgumentMatchers.any(RowMapper.class)))
                .thenReturn(List.of("company-a"));
        when(jdbc.query(
                org.mockito.ArgumentMatchers.contains("period_state"),
                org.mockito.ArgumentMatchers.any(RowMapper.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                    when(rs.getString("period_state")).thenReturn("CLOSED");
                    when(rs.getString("source_versions_json")).thenReturn("[]");
                    when(rs.getString("projection_version"))
                            .thenReturn("ARP1-" + "a".repeat(64));
                    return List.of(mapper.mapRow(rs, 0));
                });

        service.processDueSlots();

        verify(snapshots, never()).materializeCompanyMonthWindow(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
