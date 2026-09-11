package com.szsemicon.hr.attendance.domain;

import java.util.List;

public final class AttendancePolicyCatalog {

    private AttendancePolicyCatalog() {
    }

    public static List<TemplateDefinition> templates() {
        return List.of(
                new TemplateDefinition(
                        "25000000-0000-0000-0000-000000000001",
                        AttendancePolicyModels.PolicyKind.MEAL_DEDUCTION,
                        "晚餐扣除",
                        List.of(
                                field("enabled", "是否启用", "BOOLEAN"),
                                field("mealWindowStart", "晚餐窗口开始", "LOCAL_TIME"),
                                field("mealWindowEnd", "晚餐窗口结束", "LOCAL_TIME"),
                                field("deductionMinutes", "扣除分钟", "INTEGER"),
                                field("triggerMinutes", "触发门槛分钟", "INTEGER"),
                                field("applicableDayTypes", "适用日期类型", "ENUM_LIST"),
                                optionalField(
                                        "saturdayMealWindowStart",
                                        "周六晚餐窗口开始",
                                        "LOCAL_TIME"),
                                optionalField(
                                        "saturdayMealWindowEnd",
                                        "周六晚餐窗口结束",
                                        "LOCAL_TIME"),
                                optionalField(
                                        "saturdayDeductionMinutes",
                                        "周六扣除分钟",
                                        "INTEGER"),
                                optionalField(
                                        "saturdayTriggerMinutes",
                                        "周六触发门槛分钟",
                                        "INTEGER"),
                                optionalField(
                                        "saturdayLunchWindowStart",
                                        "周六午餐窗口开始",
                                        "LOCAL_TIME"),
                                optionalField(
                                        "saturdayLunchWindowEnd",
                                        "周六午餐窗口结束",
                                        "LOCAL_TIME"),
                                optionalField(
                                        "saturdayLunchDeductionMinutes",
                                        "周六午餐扣除分钟",
                                        "INTEGER"),
                                optionalField(
                                        "saturdayLunchTriggerMinutes",
                                        "周六午餐触发门槛分钟",
                                        "INTEGER"),
                                optionalField(
                                        "sundayMealWindowStart",
                                        "周日晚餐窗口开始",
                                        "LOCAL_TIME"),
                                optionalField(
                                        "sundayMealWindowEnd",
                                        "周日晚餐窗口结束",
                                        "LOCAL_TIME"),
                                optionalField(
                                        "sundayDeductionMinutes",
                                        "周日扣除分钟",
                                        "INTEGER"),
                                optionalField(
                                        "sundayTriggerMinutes",
                                        "周日触发门槛分钟",
                                        "INTEGER"),
                                optionalField(
                                        "sundayLunchWindowStart",
                                        "周日午餐窗口开始",
                                        "LOCAL_TIME"),
                                optionalField(
                                        "sundayLunchWindowEnd",
                                        "周日午餐窗口结束",
                                        "LOCAL_TIME"),
                                optionalField(
                                        "sundayLunchDeductionMinutes",
                                        "周日午餐扣除分钟",
                                        "INTEGER"),
                                optionalField(
                                        "sundayLunchTriggerMinutes",
                                        "周日午餐触发门槛分钟",
                                        "INTEGER"),
                                optionalField(
                                        "publicHolidayMealWindowStart",
                                        "法定节假日晚餐窗口开始",
                                        "LOCAL_TIME"),
                                optionalField(
                                        "publicHolidayMealWindowEnd",
                                        "法定节假日晚餐窗口结束",
                                        "LOCAL_TIME"),
                                optionalField(
                                        "publicHolidayDeductionMinutes",
                                        "法定节假日晚餐扣除分钟",
                                        "INTEGER"),
                                optionalField(
                                        "publicHolidayTriggerMinutes",
                                        "法定节假日晚餐触发门槛分钟",
                                        "INTEGER"),
                                optionalField(
                                        "publicHolidayLunchWindowStart",
                                        "法定节假日午餐窗口开始",
                                        "LOCAL_TIME"),
                                optionalField(
                                        "publicHolidayLunchWindowEnd",
                                        "法定节假日午餐窗口结束",
                                        "LOCAL_TIME"),
                                optionalField(
                                        "publicHolidayLunchDeductionMinutes",
                                        "法定节假日午餐扣除分钟",
                                        "INTEGER"),
                                optionalField(
                                        "publicHolidayLunchTriggerMinutes",
                                        "法定节假日午餐触发门槛分钟",
                                        "INTEGER"))),
                new TemplateDefinition(
                        "25000000-0000-0000-0000-000000000002",
                        AttendancePolicyModels.PolicyKind.LATE_GRACE,
                        "迟到分钟宽限",
                        List.of(
                                field("enabled", "是否启用", "BOOLEAN"),
                                field("graceMinutes", "宽限分钟", "INTEGER"))),
                new TemplateDefinition(
                        "25000000-0000-0000-0000-000000000003",
                        AttendancePolicyModels.PolicyKind.MONTHLY_LATE_EXEMPTION,
                        "自然月迟到豁免",
                        List.of(
                                field("enabled", "是否启用", "BOOLEAN"),
                                field("graceMinutes", "宽限分钟", "INTEGER"),
                                field("monthlyUses", "自然月可用次数", "INTEGER"),
                                field("resetOnGroupChange", "换组是否重置", "BOOLEAN"))),
                new TemplateDefinition(
                        "25000000-0000-0000-0000-000000000009",
                        AttendancePolicyModels.PolicyKind.PUNCH_WINDOW,
                        "打卡取卡窗口",
                        List.of(
                                field("enabled", "是否启用", "BOOLEAN"),
                                field("arrivalBeforeMinutes", "上班窗口提前分钟", "INTEGER"),
                                field("arrivalAfterMinutes", "上班窗口延后分钟", "INTEGER"),
                                field("departureBeforeMinutes", "下班窗口提前分钟", "INTEGER"),
                                field("departureAfterMinutes", "下班窗口延后分钟", "INTEGER"))),
                new TemplateDefinition(
                        "25000000-0000-0000-0000-00000000000a",
                        AttendancePolicyModels.PolicyKind.PERIOD_CLOSE,
                        "月结封账",
                        List.of(
                                field("enabled", "是否启用", "BOOLEAN"),
                                field("closeDayOfNextMonth", "次月封账日", "INTEGER"),
                                field("reopenAllowed", "允许重开", "BOOLEAN"),
                                field("reopenRequiresApproval", "重开需要审批", "BOOLEAN"),
                                field("maxReopenCount", "重开次数上限（0=不限）", "INTEGER"))));
    }

    private static FieldDefinition field(String key, String label, String valueType) {
        return new FieldDefinition(key, label, valueType, true);
    }

    private static FieldDefinition optionalField(
            String key, String label, String valueType) {
        return new FieldDefinition(key, label, valueType, false);
    }

    public record TemplateDefinition(
            String templateId,
            AttendancePolicyModels.PolicyKind policyKind,
            String name,
            List<FieldDefinition> fields) {

        public TemplateDefinition {
            fields = List.copyOf(fields);
        }
    }

    public record FieldDefinition(
            String key, String label, String valueType, boolean required) {
    }
}
