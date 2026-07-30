package com.szsemicon.hr.attendance.calculation;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.AdjustmentFact;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationInputSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationPolicy;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.GraceConsumptionSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.IntervalEvidence;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchDirection;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchEvent;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ScheduledWorkSegment;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.SegmentKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SyntheticAttendanceFixtures {

    static final LocalDate BUSINESS_DATE = LocalDate.parse("2026-07-15");
    static final Instant KNOWLEDGE_CUTOFF =
            Instant.parse("2026-07-23T00:00:00Z");

    private SyntheticAttendanceFixtures() {
    }

    static CalculationInputSnapshot mixedDay(boolean reverseCollections) {
        List<ScheduledWorkSegment> segments = new ArrayList<>(List.of(
                segment(
                        "synthetic-segment-am",
                        "2026-07-15T01:00:00Z",
                        "2026-07-15T05:00:00Z"),
                segment(
                        "synthetic-segment-pm",
                        "2026-07-15T06:00:00Z",
                        "2026-07-15T10:00:00Z")));
        List<PunchEvent> punches = new ArrayList<>(List.of(
                punch(
                        "synthetic-punch-in",
                        "2026-07-15T01:00:00Z",
                        PunchDirection.ENTRY),
                punch(
                        "synthetic-punch-out",
                        "2026-07-15T05:00:00Z",
                        PunchDirection.EXIT)));
        List<IntervalEvidence> evidence = new ArrayList<>(List.of(
                new IntervalEvidence(
                        "synthetic-leave-approved",
                        EvidenceKind.LEAVE,
                        interval(
                                "2026-07-15T06:00:00Z",
                                "2026-07-15T10:00:00Z"),
                        "synthetic-oa-document",
                        Instant.parse("2026-07-14T00:00:00Z"),
                        true)));
        List<AdjustmentFact> adjustments = new ArrayList<>();
        if (reverseCollections) {
            Collections.reverse(segments);
            Collections.reverse(punches);
            Collections.reverse(evidence);
        }
        return snapshot(
                segments,
                punches,
                evidence,
                adjustments,
                defaultPolicy(),
                KNOWLEDGE_CUTOFF);
    }

    static CalculationInputSnapshot snapshot(
            List<ScheduledWorkSegment> segments,
            List<PunchEvent> punches,
            List<IntervalEvidence> evidence,
            List<AdjustmentFact> adjustments,
            CalculationPolicy policy,
            Instant knowledgeCutoff) {
        return new CalculationInputSnapshot(
                "synthetic-company",
                "synthetic-employee-001",
                "synthetic-employment-period",
                BUSINESS_DATE,
                ZoneId.of("Asia/Shanghai"),
                knowledgeCutoff,
                segments,
                punches,
                evidence,
                adjustments,
                new GraceConsumptionSnapshot(
                        "synthetic-employee-001",
                        YearMonth.from(BUSINESS_DATE),
                        0,
                        "synthetic-grace-digest"),
                policy,
                "synthetic-config-snapshot",
                "synthetic-config-digest",
                "synthetic-evidence-snapshot",
                "synthetic-evidence-digest",
                "synthetic-adjustment-digest",
                "synthetic-period",
                1,
                "synthetic-open-token-v1",
                "w5-domain-v1",
                "synthetic-request",
                "synthetic-correlation");
    }

    static CalculationPolicy defaultPolicy() {
        return new CalculationPolicy(
                15,
                1,
                Instant.parse("2026-07-23T15:59:59Z"),
                false,
                null,
                48 * 60,
                List.of());
    }

    static ScheduledWorkSegment segment(
            String id, String start, String end) {
        TimeInterval interval = interval(start, end);
        return new ScheduledWorkSegment(
                id,
                BUSINESS_DATE,
                interval,
                interval,
                interval,
                SegmentKind.SCHEDULED_WORK);
    }

    static PunchEvent punch(
            String id, String instant, PunchDirection direction) {
        return new PunchEvent(
                id,
                Instant.parse(instant),
                direction,
                "synthetic-evidence-" + id);
    }

    static TimeInterval interval(String start, String end) {
        return new TimeInterval(Instant.parse(start), Instant.parse(end));
    }
}
