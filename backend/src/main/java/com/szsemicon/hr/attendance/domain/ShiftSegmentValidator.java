package com.szsemicon.hr.attendance.domain;

import com.szsemicon.hr.attendance.domain.ShiftModels.Segment;
import com.szsemicon.hr.attendance.domain.ShiftModels.SegmentType;
import java.util.Comparator;
import java.util.List;

public final class ShiftSegmentValidator {

    private ShiftSegmentValidator() {
    }

    public static List<Segment> validateAndNormalize(List<Segment> segments) {
        if (segments == null || segments.isEmpty() || segments.size() > 24) {
            throw new IllegalArgumentException("班次必须包含 1 至 24 个受控工作段");
        }
        if (segments.stream().noneMatch(segment -> segment.segmentType() == SegmentType.WORK)) {
            throw new IllegalArgumentException("班次至少需要一个 WORK 工作段");
        }
        for (Segment segment : segments) {
            if (segment.startDayOffset() < 0 || segment.startDayOffset() > 1
                    || segment.endDayOffset() < 0 || segment.endDayOffset() > 1) {
                throw new IllegalArgumentException(
                        "startDayOffset 和 endDayOffset 只能是 0 或 1");
            }
            if (segment.endDayOffset() < segment.startDayOffset()) {
                throw new IllegalArgumentException("工作段结束日偏移不得早于开始日偏移");
            }
        }
        List<Segment> normalized = segments.stream()
                .sorted(Comparator
                        .comparingInt(Segment::normalizedStartMinute)
                        .thenComparingInt(Segment::normalizedEndMinute)
                        .thenComparing(Segment::segmentType))
                .toList();
        for (int index = 0; index < normalized.size(); index++) {
            Segment segment = normalized.get(index);
            if (segment.normalizedEndMinute() <= segment.normalizedStartMinute()) {
                throw new IllegalArgumentException("工作段必须具有正时长");
            }
            if (index > 0
                    && normalized.get(index - 1).normalizedEndMinute()
                    > segment.normalizedStartMinute()) {
                throw new IllegalArgumentException("工作段时间不得重叠");
            }
        }
        return normalized;
    }
}
