package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaDocumentRow;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class OaDocumentConverterGridTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void snapsLeaveEndpointsBeforeCalculation() {
        var row = new OaDocumentRow(
                "leave-odd",
                "LEAVE",
                null,
                com.szsemicon.hr.attendance.domain.LeaveType.PERSONAL,
                "E001",
                shanghai("2026-08-17T09:17:00"),
                shanghai("2026-08-17T12:00:00"),
                "Asia/Shanghai",
                shanghai("2026-08-17T09:17:00"),
                true);

        var evidence = OaDocumentConverter.toIntervalEvidence(List.of(row));

        assertThat(evidence).singleElement().satisfies(value -> {
            assertThat(value.kind()).isEqualTo(EvidenceKind.LEAVE);
            assertThat(value.interval().start())
                    .isEqualTo(shanghai("2026-08-17T09:00:00"));
            assertThat(value.interval().end())
                    .isEqualTo(shanghai("2026-08-17T12:00:00"));
        });
    }

    @Test
    void breastfeedingLeaveExpandsToOneHourWorkdaySlices() {
        var row = new OaDocumentRow(
                "breast-month",
                "LEAVE",
                null,
                com.szsemicon.hr.attendance.domain.LeaveType.BREASTFEEDING,
                "E001",
                shanghai("2026-08-03T08:30:00"),
                shanghai("2026-08-09T09:30:00"),
                "Asia/Shanghai",
                shanghai("2026-08-03T08:30:00"),
                true);

        var evidence = OaDocumentConverter.toIntervalEvidence(List.of(row));

        assertThat(evidence).hasSize(5);
        assertThat(evidence.getFirst().interval().start())
                .isEqualTo(shanghai("2026-08-03T08:30:00"));
        assertThat(evidence.getFirst().interval().end())
                .isEqualTo(shanghai("2026-08-03T09:30:00"));
        assertThat(evidence.get(1).interval().start())
                .isEqualTo(shanghai("2026-08-04T08:30:00"));
        assertThat(evidence.getLast().interval().start())
                .isEqualTo(shanghai("2026-08-07T08:30:00"));
        assertThat(evidence)
                .noneMatch(value -> {
                    var weekday = value.interval().start()
                            .atZone(SHANGHAI)
                            .getDayOfWeek();
                    return weekday == java.time.DayOfWeek.SATURDAY
                            || weekday == java.time.DayOfWeek.SUNDAY;
                });
    }

    @Test
    void breastfeedingStartClockFollowsTheDocumentEachWorkday() {
        var row = new OaDocumentRow(
                "breast-nine",
                "LEAVE",
                null,
                com.szsemicon.hr.attendance.domain.LeaveType.BREASTFEEDING,
                "E001",
                shanghai("2026-08-03T09:00:00"),
                shanghai("2026-08-04T10:00:00"),
                "Asia/Shanghai",
                shanghai("2026-08-03T09:00:00"),
                true);

        var evidence = OaDocumentConverter.toIntervalEvidence(List.of(row));

        assertThat(evidence).hasSize(2);
        assertThat(evidence.getFirst().interval().start())
                .isEqualTo(shanghai("2026-08-03T09:00:00"));
        assertThat(evidence.getFirst().interval().end())
                .isEqualTo(shanghai("2026-08-03T10:00:00"));
        assertThat(evidence.getLast().interval().start())
                .isEqualTo(shanghai("2026-08-04T09:00:00"));
        assertThat(evidence.getLast().interval().end())
                .isEqualTo(shanghai("2026-08-04T10:00:00"));
    }

    @Test
    void dropsDegenerateSnappedIntervalsFromCalculationEvidence() {
        var row = new OaDocumentRow(
                "leave-short",
                "LEAVE",
                null,
                com.szsemicon.hr.attendance.domain.LeaveType.PERSONAL,
                "E001",
                shanghai("2026-08-17T09:00:00"),
                shanghai("2026-08-17T09:17:00"),
                "Asia/Shanghai",
                shanghai("2026-08-17T09:00:00"),
                true);

        assertThat(OaDocumentConverter.toIntervalEvidence(List.of(row))).isEmpty();
    }

    private static Instant shanghai(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SHANGHAI).toInstant();
    }
}
