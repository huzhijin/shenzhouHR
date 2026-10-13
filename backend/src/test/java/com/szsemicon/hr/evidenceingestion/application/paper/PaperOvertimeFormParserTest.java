package com.szsemicon.hr.evidenceingestion.application.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class PaperOvertimeFormParserTest {

    @Test
    void parsesHandwrittenSampleFields() {
        PaperOvertimeFormParser.Draft draft = PaperOvertimeFormParser.parse(
                """
                加班人员：陈士庆
                加班日期：8.18
                加班时间：17:40 至 22:00
                加班事由：写SOP文件
                ☑调休  加班费  义务加班
                """,
                LocalDate.of(2026, 8, 26));

        assertThat(draft.name()).isEqualTo("陈士庆");
        assertThat(draft.overtimeDate()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(draft.start()).isEqualTo(LocalTime.of(17, 40));
        assertThat(draft.end()).isEqualTo(LocalTime.of(22, 0));
        assertThat(draft.reason()).isEqualTo("写SOP文件");
        assertThat(draft.overtimeType()).isEqualTo(OvertimeType.COMPENSATORY);
    }

    @Test
    void twoCheckedBoxesLeaveTypeEmpty() {
        OvertimeType type = PaperOvertimeFormParser.parseType("☑加班费 ☑调休");
        assertThat(type).isNull();
    }

    @Test
    void parsesSzPaperFormWithChineseDateAndHourSuffix() {
        PaperOvertimeFormParser.Draft draft = PaperOvertimeFormParser.parse(
                """
                加班申请单
                加班人员：程康 共 1 人 部门：RD1 申请日期：2026.8.19
                加班原因：编写检测标准
                预计加班时间：8 月 15 日 9:30时至 8 月 15 日 18:00时 共7.5 小时
                实际加班时间 1.姓名程康：8月15日9:30时至8月15日18:00时 共7.5小时 ☑加班费
                """,
                LocalDate.of(2026, 8, 27));

        assertThat(draft.name()).isEqualTo("程康");
        assertThat(draft.department()).isEqualTo("RD1");
        assertThat(draft.overtimeDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(draft.start()).isEqualTo(LocalTime.of(9, 30));
        assertThat(draft.end()).isEqualTo(LocalTime.of(18, 0));
        assertThat(draft.overtimeType()).isEqualTo(OvertimeType.PAID);
    }

    @Test
    void unreadCheckboxLeavesTypeEmpty() {
        OvertimeType type = PaperOvertimeFormParser.parseType("加班费 调休 义务加班");
        assertThat(type).isNull();
    }

    @Test
    void parsesOcrNoiseFromSzPaperPhoto() {
        PaperOvertimeFormParser.Draft draft = PaperOvertimeFormParser.parse(
                """
                加班申请单
                加班人员：程 康 共 1 人
                部门：RD 1
                申请日期：2026.8.19
                加班原因：编写 MJ.Transition 检测标准
                预计加班时间：8月15日 9:30时至 8月15日 18:00时 共7.5小时
                实际加班时间
                1.姓名程康：8月15日9:30时至8月15日18:00时 共7.5小时 加班费 义务加班 调休
                """,
                LocalDate.of(2026, 8, 27));

        assertThat(draft.name()).isEqualTo("程康");
        assertThat(draft.department()).isEqualTo("RD1");
        assertThat(draft.overtimeDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(draft.start()).isEqualTo(LocalTime.of(9, 30));
        assertThat(draft.end()).isEqualTo(LocalTime.of(18, 0));
        assertThat(draft.overtimeType()).isEqualTo(OvertimeType.PAID);
    }
}
