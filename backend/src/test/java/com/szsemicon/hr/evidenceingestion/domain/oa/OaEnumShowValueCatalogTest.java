package com.szsemicon.hr.evidenceingestion.domain.oa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Locks the OA data-dictionary enum mappings confirmed on 2026-08-08.
 *
 * <p>These assertions are the contract between the OA {@code ctp_enum_item}
 * display values and the internal leave / overtime codes. Changing a mapping
 * requires re-confirming the OA dictionary with the customer.</p>
 */
class OaEnumShowValueCatalogTest {

    @Test
    @DisplayName("每个确认的中文假别都能解析为内部假别代码")
    void resolvesEveryConfirmedLeaveShowValue() {
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("年假"))
                .isEqualTo("ANNUAL_LEAVE");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("病假"))
                .isEqualTo("SICK_LEAVE");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("事假"))
                .isEqualTo("PERSONAL_LEAVE");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("调休"))
                .isEqualTo("TIME_OFF");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("婚假"))
                .isEqualTo("MARRIAGE_LEAVE");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("产假"))
                .isEqualTo("MATERNITY_LEAVE");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("陪产假"))
                .isEqualTo("PATERNITY_LEAVE");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("丧假"))
                .isEqualTo("BEREAVEMENT_LEAVE");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("工伤"))
                .isEqualTo("WORK_INJURY_LEAVE");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("护理假"))
                .isEqualTo("NURSING_LEAVE");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("哺乳时间"))
                .isEqualTo("BREASTFEEDING_TIME");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("产检时间"))
                .isEqualTo("PRENATAL_EXAM_TIME");
    }

    @Test
    @DisplayName("常见别名也能解析")
    void resolvesAlternateLabels() {
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("年休假"))
                .isEqualTo("ANNUAL_LEAVE");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("结婚假"))
                .isEqualTo("MARRIAGE_LEAVE");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("工伤假"))
                .isEqualTo("WORK_INJURY_LEAVE");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("哺乳假"))
                .isEqualTo("BREASTFEEDING_TIME");
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("孕检假"))
                .isEqualTo("PRENATAL_EXAM_TIME");
    }

    @Test
    @DisplayName("前后空白被裁剪后仍能解析")
    void stripsSurroundingWhitespace() {
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("  年假  "))
                .isEqualTo("ANNUAL_LEAVE");
        assertThat(OaOvertimeTypeCatalog.resolveOvertimeCode(" 调休 "))
                .isEqualTo("TIME_OFF_IN_LIEU");
    }

    @Test
    @DisplayName("未知或空的枚举值返回 null 而不是猜测")
    void returnsNullForUnknownValues() {
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode(null)).isNull();
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("")).isNull();
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("   ")).isNull();
        assertThat(OaLeaveTypeShowValueCatalog.resolveLeaveCode("探亲假"))
                .isNull();
        assertThat(OaOvertimeTypeCatalog.resolveOvertimeCode("双倍加班"))
                .isNull();
        assertThat(OaOvertimeTypeCatalog.resolveOvertimeCode(null)).isNull();
    }

    @Test
    @DisplayName("加班类别只有三种：加班费/调休/义务加班")
    void mapsExactlyThreeOvertimeCategories() {
        assertThat(OaOvertimeTypeCatalog.allMappings()).hasSize(3);
        assertThat(OaOvertimeTypeCatalog.resolveOvertimeCode("加班费"))
                .isEqualTo("COMPENSATED_OVERTIME");
        assertThat(OaOvertimeTypeCatalog.resolveOvertimeCode("调休"))
                .isEqualTo("TIME_OFF_IN_LIEU");
        assertThat(OaOvertimeTypeCatalog.resolveOvertimeCode("义务加班"))
                .isEqualTo("OBLIGATORY_OVERTIME");
    }

    @Test
    @DisplayName("只有加班费产生工资项，只有调休入调休余额")
    void classifiesOvertimeConsequences() {
        assertThat(OaOvertimeTypeCatalog.generatesPayrollItem(
                "COMPENSATED_OVERTIME")).isTrue();
        assertThat(OaOvertimeTypeCatalog.generatesPayrollItem(
                "TIME_OFF_IN_LIEU")).isFalse();
        assertThat(OaOvertimeTypeCatalog.generatesPayrollItem(
                "OBLIGATORY_OVERTIME")).isFalse();

        assertThat(OaOvertimeTypeCatalog.creditsTimeOffBalance(
                "TIME_OFF_IN_LIEU")).isTrue();
        assertThat(OaOvertimeTypeCatalog.creditsTimeOffBalance(
                "COMPENSATED_OVERTIME")).isFalse();
        assertThat(OaOvertimeTypeCatalog.creditsTimeOffBalance(
                "OBLIGATORY_OVERTIME")).isFalse();
    }

    @Test
    @DisplayName("病假和事假不计入带薪出勤，其余带薪假计入")
    void excludesUnpaidLeaveFromPaidAttendance() {
        assertThat(OaLeaveTypeShowValueCatalog.isPaidAttendance("SICK_LEAVE"))
                .isFalse();
        assertThat(OaLeaveTypeShowValueCatalog.isPaidAttendance(
                "PERSONAL_LEAVE")).isFalse();
        assertThat(OaLeaveTypeShowValueCatalog.isPaidAttendance(null))
                .isFalse();

        Set<String> paid = Set.of(
                "ANNUAL_LEAVE",
                "TIME_OFF",
                "MARRIAGE_LEAVE",
                "MATERNITY_LEAVE",
                "PATERNITY_LEAVE",
                "BEREAVEMENT_LEAVE",
                "WORK_INJURY_LEAVE",
                "NURSING_LEAVE",
                "BREASTFEEDING_TIME",
                "PRENATAL_EXAM_TIME");
        for (String code : paid) {
            assertThat(OaLeaveTypeShowValueCatalog.isPaidAttendance(code))
                    .as("%s 应计入带薪出勤", code)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("目录中的假别代码与 V33 迁移后的 leave_type 集合一致")
    void coversEveryPersistedLeaveCode() {
        // Mirrors leave_type.leave_code after V33__missing_leave_types.sql.
        Set<String> persisted = Set.of(
                "ANNUAL_LEAVE",
                "BEREAVEMENT_LEAVE",
                "BREASTFEEDING_TIME",
                "FAMILY_PLANNING_LEAVE",
                "MARRIAGE_LEAVE",
                "MATERNITY_LEAVE",
                "NURSING_LEAVE",
                "OTHER_LEAVE",
                "PATERNITY_LEAVE",
                "PERSONAL_LEAVE",
                "PRENATAL_EXAM_TIME",
                "SICK_LEAVE",
                "TIME_OFF",
                "WORK_INJURY_LEAVE");
        assertThat(OaLeaveTypeShowValueCatalog.allMappings().values())
                .as("目录不得引用未落库的假别代码")
                .allSatisfy(code -> assertThat(persisted).contains(code));
        assertThat(Set.copyOf(
                OaLeaveTypeShowValueCatalog.allMappings().values()))
                .as("每个已落库假别都必须有中文映射")
                .containsExactlyInAnyOrderElementsOf(persisted);
    }
}
