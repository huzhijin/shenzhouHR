package com.szsemicon.hr.evidenceingestion.infrastructure.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.evidenceingestion.application.OaDocumentSyncApplicationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class OaAttendanceReportSourceRefreshAdapterTest {
    private static final String COMPANY = "company-1";
    private static final Instant REQUESTED = Instant.parse("2026-10-04T00:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 30);
    private static final LocalDate TO_EXCLUSIVE = LocalDate.of(2026, 10, 1);

    private final OaDocumentSyncApplicationService sync = mock(OaDocumentSyncApplicationService.class);
    private final OaAttendanceReportSourceRefreshAdapter adapter =
            new OaAttendanceReportSourceRefreshAdapter(sync);

    @Test
    void delegatesTheExactRangeWithoutExpandingToAMonth() {
        Instant completed = REQUESTED.plusSeconds(20);
        when(sync.refreshRangeForReport(COMPANY, FROM, TO_EXCLUSIVE, REQUESTED)).thenReturn(completed);

        assertThat(adapter.refreshRange(COMPANY, FROM, TO_EXCLUSIVE, REQUESTED)).isEqualTo(completed);

        verify(sync).refreshRangeForReport(COMPANY, FROM, TO_EXCLUSIVE, REQUESTED);
        verifyNoMoreInteractions(sync);
    }

    @Test
    void propagatesRangeRefreshFailureWithoutFallingBackToAMonth() {
        var failed = new IllegalStateException("source verification failed");
        when(sync.refreshRangeForReport(COMPANY, FROM, TO_EXCLUSIVE, REQUESTED)).thenThrow(failed);

        assertThatThrownBy(() -> adapter.refreshRange(COMPANY, FROM, TO_EXCLUSIVE, REQUESTED))
                .isSameAs(failed);

        verify(sync).refreshRangeForReport(COMPANY, FROM, TO_EXCLUSIVE, REQUESTED);
        verifyNoMoreInteractions(sync);
    }

    @Test
    void delegatesExplicitEmployeesWithoutChangingTheDependencyWindow() {
        var employees = java.util.List.of("employee-1");
        Instant completed = REQUESTED.plusSeconds(20);
        when(sync.refreshRangeForReport(COMPANY, FROM, TO_EXCLUSIVE, REQUESTED, employees))
                .thenReturn(completed);
        assertThat(adapter.refreshRange(COMPANY, FROM, TO_EXCLUSIVE, REQUESTED, employees))
                .isEqualTo(completed);
        verify(sync).refreshRangeForReport(COMPANY, FROM, TO_EXCLUSIVE, REQUESTED, employees);
        verifyNoMoreInteractions(sync);
    }

    @Test
    void retainsMonthRefreshDelegationForWholeMonthCallers() {
        var month = YearMonth.of(2026, 9);
        Instant completed = REQUESTED.plusSeconds(20);
        when(sync.refreshMonthForReport(COMPANY, month, REQUESTED)).thenReturn(completed);

        assertThat(adapter.refresh(COMPANY, month, REQUESTED)).isEqualTo(completed);

        verify(sync).refreshMonthForReport(COMPANY, month, REQUESTED);
        verifyNoMoreInteractions(sync);
    }
}
