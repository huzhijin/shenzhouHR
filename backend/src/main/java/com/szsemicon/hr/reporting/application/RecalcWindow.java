package com.szsemicon.hr.reporting.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public enum RecalcWindow {
    LAST_3_DAYS,
    LAST_7_DAYS,
    MONTH;

    public static RecalcWindow from(String raw) {
        if (raw == null || raw.isBlank()) {
            return MONTH;
        }
        try {
            return RecalcWindow.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MONTH;
        }
    }

    public DateSpan resolve(LocalDate today, YearMonth selectedMonth) {
        YearMonth month = selectedMonth == null
                ? YearMonth.from(today)
                : selectedMonth;
        return switch (this) {
            case LAST_3_DAYS -> new DateSpan(
                    today.minusDays(2), today.plusDays(1));
            case LAST_7_DAYS -> new DateSpan(
                    today.minusDays(6), today.plusDays(1));
            case MONTH -> new DateSpan(
                    month.atDay(1), month.plusMonths(1).atDay(1));
        };
    }

    public List<YearMonth> months(DateSpan span) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth cursor = YearMonth.from(span.startInclusive());
        YearMonth last = YearMonth.from(span.endExclusive().minusDays(1));
        while (!cursor.isAfter(last)) {
            months.add(cursor);
            cursor = cursor.plusMonths(1);
        }
        return months;
    }

    public record DateSpan(LocalDate startInclusive, LocalDate endExclusive) {
        public DateSpan {
            if (!endExclusive.isAfter(startInclusive)) {
                throw new IllegalArgumentException("recalc window is empty");
            }
        }

        public LocalDate writeStart(YearMonth month) {
            LocalDate monthStart = month.atDay(1);
            return startInclusive.isBefore(monthStart)
                    ? monthStart
                    : startInclusive;
        }

        public LocalDate writeEndExclusive(YearMonth month) {
            LocalDate monthEnd = month.plusMonths(1).atDay(1);
            return endExclusive.isAfter(monthEnd) ? monthEnd : endExclusive;
        }
    }
}
