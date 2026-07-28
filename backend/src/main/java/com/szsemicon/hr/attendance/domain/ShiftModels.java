package com.szsemicon.hr.attendance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class ShiftModels {

    private ShiftModels() {
    }

    public enum SegmentType {
        WORK,
        BREAK,
        MEAL
    }

    public enum VersionStatus {
        DRAFT,
        PUBLISHED,
        INACTIVE
    }

    public record ShiftTemplate(
            String shiftId,
            String legalEntityId,
            String locationId,
            String code,
            String name,
            AttendanceGroupModels.LifecycleStatus status,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {
    }

    public record Segment(
            SegmentType segmentType,
            LocalTime startLocalTime,
            int startDayOffset,
            LocalTime endLocalTime,
            int endDayOffset) {

        public int normalizedStartMinute() {
            return startLocalTime.getHour() * 60
                    + startLocalTime.getMinute()
                    + startDayOffset * 24 * 60;
        }

        public int normalizedEndMinute() {
            return endLocalTime.getHour() * 60
                    + endLocalTime.getMinute()
                    + endDayOffset * 24 * 60;
        }
    }

    public record ShiftVersion(
            String shiftVersionId,
            String shiftId,
            int versionNumber,
            VersionStatus status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String timeZone,
            List<Segment> segments,
            String snapshotDigest,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            Instant publishedAt,
            String updatedBy,
            Instant updatedAt) {

        public ShiftVersion {
            segments = List.copyOf(segments);
        }
    }

    public record ShiftPublicationFact(
            String timelineId,
            String shiftId,
            String shiftVersionId,
            int eventSequence,
            VersionStatus state,
            LocalDate businessEffectiveFrom,
            String predecessorTimelineId,
            Instant recordedAt,
            String actorId,
            String requestId) {
    }
}
