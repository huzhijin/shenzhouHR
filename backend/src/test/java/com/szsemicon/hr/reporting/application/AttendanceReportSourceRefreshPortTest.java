package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class AttendanceReportSourceRefreshPortTest {
    private static final String COMPANY = "company-1";
    private static final Instant REQUESTED = Instant.parse("2026-10-04T00:00:00Z");
    private static final LocalDate SEPTEMBER_30 = LocalDate.of(2026, 9, 30);
    private static final LocalDate OCTOBER_1 = LocalDate.of(2026, 10, 1);

    private final AttendanceReportSourceRefreshPort monthly =
            mock(AttendanceReportSourceRefreshPort.class);
    // Exercise the real interface default while recording only its monthly calls.
    private final AttendanceReportSourceRefreshPort port = monthly::refresh;

    @Test
    void singleDayFallbackRefreshesItsMonthAndExcludesTheEndBoundaryMonth() {
        var september = YearMonth.of(2026, 9);
        when(monthly.refresh(COMPANY, september, REQUESTED)).thenReturn(REQUESTED);

        assertThat(port.refreshRange(COMPANY, SEPTEMBER_30, OCTOBER_1, REQUESTED))
                .isEqualTo(REQUESTED);

        verify(monthly).refresh(COMPANY, september, REQUESTED);
        verifyNoMoreInteractions(monthly);
    }

    @Test
    void unsupportedEmployeeScopeStillRefreshesAllSourceDependencies() {
        var september = YearMonth.of(2026, 9);
        when(monthly.refresh(COMPANY, september, REQUESTED)).thenReturn(REQUESTED);
        assertThat(port.refreshRange(COMPANY, SEPTEMBER_30, OCTOBER_1, REQUESTED,
                java.util.List.of("employee-1"))).isEqualTo(REQUESTED);
        verify(monthly).refresh(COMPANY, september, REQUESTED);
        verifyNoMoreInteractions(monthly);
    }

    @Test
    void crossMonthFallbackRefreshesBothMonthsAndReturnsTheMaximumCutoff() {
        var september = YearMonth.of(2026, 9);
        var october = YearMonth.of(2026, 10);
        Instant latest = REQUESTED.plusSeconds(30);
        when(monthly.refresh(COMPANY, september, REQUESTED)).thenReturn(latest);
        when(monthly.refresh(COMPANY, october, REQUESTED)).thenReturn(REQUESTED.plusSeconds(10));

        assertThat(port.refreshRange(COMPANY, SEPTEMBER_30, OCTOBER_1.plusDays(1), REQUESTED))
                .isEqualTo(latest);

        var order = inOrder(monthly);
        order.verify(monthly).refresh(COMPANY, september, REQUESTED);
        order.verify(monthly).refresh(COMPANY, october, REQUESTED);
        verifyNoMoreInteractions(monthly);
    }

    @ParameterizedTest
    @CsvSource({"2026-09-30,2026-09-30", "2026-10-01,2026-09-30"})
    void rejectsEmptyOrReversedRangeBeforeRefreshing(LocalDate from, LocalDate to) {
        assertThatThrownBy(() -> port.refreshRange(COMPANY, from, to, REQUESTED))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(monthly);
    }

    @ParameterizedTest
    @MethodSource("nullRangeArguments")
    void rejectsNullRangeArgumentsBeforeRefreshing(LocalDate from, LocalDate to, Instant requestedAt) {
        assertThatThrownBy(() -> port.refreshRange(COMPANY, from, to, requestedAt))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(monthly);
    }

    @ParameterizedTest
    @MethodSource("invalidCutoffs")
    void rejectsMissingOrOlderCutoffWithoutContinuingToTheNextMonth(Instant invalidCutoff) {
        var september = YearMonth.of(2026, 9);
        when(monthly.refresh(COMPANY, september, REQUESTED)).thenReturn(invalidCutoff);

        assertThatThrownBy(() -> port.refreshRange(
                COMPANY, SEPTEMBER_30, OCTOBER_1.plusDays(1), REQUESTED))
                .isInstanceOf(IllegalStateException.class);

        verify(monthly).refresh(COMPANY, september, REQUESTED);
        verifyNoMoreInteractions(monthly);
    }

    private static Stream<Arguments> nullRangeArguments() {
        return Stream.of(
                Arguments.of(null, OCTOBER_1, REQUESTED),
                Arguments.of(SEPTEMBER_30, null, REQUESTED),
                Arguments.of(SEPTEMBER_30, OCTOBER_1, null));
    }

    private static Stream<Arguments> invalidCutoffs() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(REQUESTED.minusSeconds(1)));
    }
}
