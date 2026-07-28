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
                                field("applicableDayTypes", "适用日期类型", "ENUM_LIST"))),
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
                                field("resetOnGroupChange", "换组是否重置", "BOOLEAN"))));
    }

    private static FieldDefinition field(String key, String label, String valueType) {
        return new FieldDefinition(key, label, valueType, true);
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
