package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionSeverity;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HrAttendanceOverrideTest {

    @Test
    void overrideWeekdayOvertimeToThreeHours() {
        DailyFact original = daily(150, 12, 0);
        DailyFact overridden = HrAttendanceOverride.apply(
                original, 180, Set.of());
        assertThat(overridden.recognizedOvertimeMinutes()).isEqualTo(180L);
        assertThat(overridden.actualWorkMinutes())
                .isEqualTo(original.confirmedScheduledWorkMinutes() + 180);
    }

    @Test
    void clearLateRemovesLateException() {
        ExceptionFact late = new ExceptionFact(
                "case-1",
                "employee-1",
                "SZST0001",
                "张三",
                "org-1",
                "工程部",
                LocalDate.of(2026, 8, 4),
                "LATE",
                ExceptionSeverity.WARNING,
                ExceptionState.OPEN,
                12,
                "迟到",
                "v1");
        List<ExceptionFact> kept = HrAttendanceOverride.filter(
                List.of(late), Set.of("LATE"));
        assertThat(kept).isEmpty();
        DailyFact cleared = HrAttendanceOverride.apply(
                daily(150, 12, 0), null, Set.of("LATE"));
        assertThat(cleared.lateMinutes()).isZero();
        assertThat(cleared.penalizedLateMinutes()).isZero();
    }

    @Test
    void clearLongSpanRemovesReviewException() {
        ExceptionFact longSpan = new ExceptionFact(
                "case-long",
                "employee-1",
                "SZST0001",
                "张三",
                "org-1",
                "工程部",
                LocalDate.of(2026, 8, 11),
                "LONG_PUNCH_SPAN_REVIEW",
                ExceptionSeverity.WARNING,
                ExceptionState.PENDING_REVIEW,
                948,
                "长时在岗待审",
                "v1");
        assertThat(HrAttendanceOverride.filter(
                        List.of(longSpan),
                        Set.of("LONG_PUNCH_SPAN_REVIEW")))
                .isEmpty();
    }

    private static DailyFact daily(long overtime, long late, int missing) {
        return new DailyFact(
                "fact-1",
                "company-1",
                "employee-1",
                "SZST0001",
                "张三",
                "org-1",
                "org-v-1",
                "工程部",
                LocalDate.of(2026, 8, 4),
                DayType.WEEKDAY,
                "白班",
                480,
                480,
                overtime,
                overtime,
                0,
                0,
                overtime,
                0,
                0,
                480 + overtime,
                1,
                1,
                late,
                late,
                0,
                missing,
                null,
                null,
                "calc-v1",
                "digest-v1",
                null);
    }
}
