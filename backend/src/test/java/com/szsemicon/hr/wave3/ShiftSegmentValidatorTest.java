package com.szsemicon.hr.wave3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.attendance.domain.ShiftModels.Segment;
import com.szsemicon.hr.attendance.domain.ShiftModels.SegmentType;
import com.szsemicon.hr.attendance.domain.ShiftSegmentValidator;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShiftSegmentValidatorTest {

    @Test
    void accepts_the_cross_midnight_golden_shift_with_explicit_start_and_end_offsets() {
        List<Segment> result = ShiftSegmentValidator.validateAndNormalize(List.of(
                segment(SegmentType.WORK, "20:00", 0, "01:00", 1),
                segment(SegmentType.BREAK, "01:00", 1, "01:15", 1),
                segment(SegmentType.WORK, "01:15", 1, "04:00", 1)));

        assertThat(result).extracting(Segment::segmentType)
                .containsExactly(
                        SegmentType.WORK, SegmentType.BREAK, SegmentType.WORK);
        assertThat(result).extracting(
                        Segment::normalizedStartMinute,
                        Segment::normalizedEndMinute)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(20 * 60, 25 * 60),
                        org.assertj.core.groups.Tuple.tuple(25 * 60, 25 * 60 + 15),
                        org.assertj.core.groups.Tuple.tuple(25 * 60 + 15, 28 * 60));
    }

    @Test
    void rejects_overlap_and_missing_work_segments() {
        assertThatThrownBy(() -> ShiftSegmentValidator.validateAndNormalize(List.of(
                segment(SegmentType.WORK, "09:00", 0, "18:00", 0),
                segment(SegmentType.MEAL, "12:00", 0, "13:00", 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("工作段时间不得重叠");

        assertThatThrownBy(() -> ShiftSegmentValidator.validateAndNormalize(List.of(
                segment(SegmentType.MEAL, "12:00", 0, "13:00", 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("班次至少需要一个 WORK 工作段");

        assertThatThrownBy(() -> ShiftSegmentValidator.validateAndNormalize(List.of(
                segment(SegmentType.WORK, "20:00", 0, "01:00", 1),
                segment(SegmentType.BREAK, "00:59", 1, "01:15", 1),
                segment(SegmentType.WORK, "01:15", 1, "04:00", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("工作段时间不得重叠");

        assertThatThrownBy(() -> ShiftSegmentValidator.validateAndNormalize(List.of(
                segment(SegmentType.WORK, "20:00", 0, "20:00", 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("工作段必须具有正时长");
    }

    private Segment segment(
            SegmentType type,
            String start,
            int startDayOffset,
            String end,
            int endDayOffset) {
        return new Segment(
                type,
                LocalTime.parse(start),
                startDayOffset,
                LocalTime.parse(end),
                endDayOffset);
    }
}
