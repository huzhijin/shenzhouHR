package com.szsemicon.hr.attendance.domain;

import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ParameterValue;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ValidationIssue;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Closed-world validation for the three attendance-owned policy kinds.
 *
 * <p>The public catalog intentionally exposes presentation metadata only. This
 * validator owns the executable contract: accepted keys, JSON value types,
 * ranges and enum members. Unknown keys are rejected instead of being silently
 * retained in a published snapshot.</p>
 */
public final class AttendancePolicyParameterValidator {

    private static final Map<PolicyKind, LinkedHashMap<String, Rule>> RULES =
            createRules();

    private AttendancePolicyParameterValidator() {
    }

    public static List<ValidationIssue> validate(
            PolicyKind policyKind, List<ParameterValue> parameters) {
        ArrayList<ValidationIssue> issues = new ArrayList<>();
        List<ParameterValue> supplied =
                parameters == null ? List.of() : parameters;
        if (supplied.isEmpty()) {
            issues.add(issue(
                    "POLICY_PARAMETERS_REQUIRED",
                    "parameters",
                    "策略参数不能为空"));
        }

        LinkedHashMap<String, Rule> declared = RULES.get(policyKind);
        if (declared == null) {
            issues.add(issue(
                    "POLICY_KIND_UNSUPPORTED",
                    "policyKind",
                    "策略类型未在考勤策略目录登记"));
            return List.copyOf(issues);
        }

        Map<String, ParameterValue> unique = new HashMap<>();
        for (ParameterValue parameter : supplied) {
            String key = parameter == null ? null : parameter.key();
            if (key == null || key.isBlank()) {
                issues.add(issue(
                        "POLICY_PARAMETER_KEY_REQUIRED",
                        "parameters",
                        "策略参数键不能为空"));
                continue;
            }
            if (unique.putIfAbsent(key, parameter) != null) {
                issues.add(issue(
                        "POLICY_PARAMETER_DUPLICATE",
                        "parameters." + key,
                        "策略参数键不能重复"));
                continue;
            }
            Rule rule = declared.get(key);
            if (rule == null) {
                issues.add(issue(
                        "POLICY_PARAMETER_UNKNOWN",
                        "parameters." + key,
                        "策略参数未在当前类型目录登记"));
                continue;
            }
            Object value = parameter.value();
            if (value == null) {
                issues.add(issue(
                        "POLICY_PARAMETER_VALUE_REQUIRED",
                        "parameters." + key,
                        "策略参数值不能为空"));
                continue;
            }
            rule.validate(key, value, issues);
        }

        for (String requiredKey : declared.keySet()) {
            if (!unique.containsKey(requiredKey)) {
                issues.add(issue(
                        "POLICY_PARAMETER_REQUIRED",
                        "parameters." + requiredKey,
                        "缺少必填策略参数"));
            }
        }
        validateMealWindow(policyKind, unique, issues);
        return List.copyOf(issues);
    }

    private static void validateMealWindow(
            PolicyKind policyKind,
            Map<String, ParameterValue> parameters,
            List<ValidationIssue> issues) {
        if (policyKind != PolicyKind.MEAL_DEDUCTION) {
            return;
        }
        Object rawStart = value(parameters, "mealWindowStart");
        Object rawEnd = value(parameters, "mealWindowEnd");
        if (!(rawStart instanceof String start)
                || !(rawEnd instanceof String end)) {
            return;
        }
        try {
            if (LocalTime.parse(start).equals(LocalTime.parse(end))) {
                issues.add(issue(
                        "POLICY_PARAMETER_FORMAT_INVALID",
                        "parameters.mealWindowEnd",
                        "晚餐窗口开始与结束时间不能相同"));
            }
        } catch (DateTimeParseException ignored) {
            // Individual field rules already report malformed local times.
        }
    }

    private static Object value(
            Map<String, ParameterValue> parameters, String key) {
        ParameterValue parameter = parameters.get(key);
        return parameter == null ? null : parameter.value();
    }

    private static Map<PolicyKind, LinkedHashMap<String, Rule>> createRules() {
        EnumMap<PolicyKind, LinkedHashMap<String, Rule>> rules =
                new EnumMap<>(PolicyKind.class);
        rules.put(
                PolicyKind.MEAL_DEDUCTION,
                orderedRules(
                        "enabled", Rule.bool(),
                        "mealWindowStart", Rule.localTime(),
                        "mealWindowEnd", Rule.localTime(),
                        "deductionMinutes", Rule.integer(0, 240),
                        "triggerMinutes", Rule.integer(0, 1440),
                        "applicableDayTypes",
                                Rule.enumerationList(
                                        "WORKDAY",
                                        "SPECIAL_WORKDAY",
                                        "WEEKEND",
                                        "PUBLIC_HOLIDAY")));
        rules.put(
                PolicyKind.LATE_GRACE,
                orderedRules(
                        "enabled", Rule.bool(),
                        "graceMinutes", Rule.integer(15, 15)));
        rules.put(
                PolicyKind.MONTHLY_LATE_EXEMPTION,
                orderedRules(
                        "enabled", Rule.bool(),
                        "graceMinutes", Rule.integer(15, 15),
                        "monthlyUses", Rule.integer(1, 1),
                        "resetOnGroupChange", Rule.bool()));
        return Map.copyOf(rules);
    }

    private static LinkedHashMap<String, Rule> orderedRules(Object... entries) {
        LinkedHashMap<String, Rule> rules = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            rules.put((String) entries[index], (Rule) entries[index + 1]);
        }
        return rules;
    }

    private static ValidationIssue issue(
            String code, String field, String message) {
        return new ValidationIssue(code, field, message);
    }

    private record Rule(
            ValueType type,
            BigDecimal minimum,
            BigDecimal maximum,
            Set<String> enumValues) {

        static Rule bool() {
            return new Rule(ValueType.BOOLEAN, null, null, Set.of());
        }

        static Rule integer(long minimum, long maximum) {
            return new Rule(
                    ValueType.INTEGER,
                    BigDecimal.valueOf(minimum),
                    BigDecimal.valueOf(maximum),
                    Set.of());
        }

        static Rule localTime() {
            return new Rule(ValueType.LOCAL_TIME, null, null, Set.of());
        }

        static Rule enumeration(String... values) {
            return new Rule(ValueType.ENUM, null, null, Set.of(values));
        }

        static Rule enumerationList(String... values) {
            return new Rule(ValueType.ENUM_LIST, null, null, Set.of(values));
        }

        void validate(
                String key, Object value, List<ValidationIssue> issues) {
            switch (type) {
                case BOOLEAN -> validateBoolean(key, value, issues);
                case INTEGER -> validateInteger(key, value, issues);
                case LOCAL_TIME -> validateLocalTime(key, value, issues);
                case ENUM -> validateEnum(key, value, issues);
                case ENUM_LIST -> validateEnumList(key, value, issues);
            }
        }

        private void validateBoolean(
                String key, Object value, List<ValidationIssue> issues) {
            if (!(value instanceof Boolean)) {
                issues.add(typeIssue(key, "BOOLEAN"));
            }
        }

        private void validateInteger(
                String key, Object value, List<ValidationIssue> issues) {
            BigDecimal number;
            try {
                if (!(value instanceof Number)) {
                    throw new NumberFormatException();
                }
                number = new BigDecimal(value.toString());
            } catch (NumberFormatException exception) {
                issues.add(typeIssue(key, "INTEGER"));
                return;
            }
            if (number.stripTrailingZeros().scale() > 0) {
                issues.add(typeIssue(key, "INTEGER"));
                return;
            }
            if (number.compareTo(minimum) < 0
                    || number.compareTo(maximum) > 0) {
                issues.add(issue(
                        "POLICY_PARAMETER_OUT_OF_RANGE",
                        "parameters." + key,
                        "策略参数超出允许范围"));
            }
        }

        private void validateLocalTime(
                String key, Object value, List<ValidationIssue> issues) {
            if (!(value instanceof String text)) {
                issues.add(typeIssue(key, "LOCAL_TIME"));
                return;
            }
            try {
                LocalTime.parse(text);
            } catch (DateTimeParseException exception) {
                issues.add(formatIssue(key));
            }
        }

        private void validateEnum(
                String key, Object value, List<ValidationIssue> issues) {
            if (!(value instanceof String text)) {
                issues.add(typeIssue(key, "ENUM"));
            } else if (!enumValues.contains(text)) {
                issues.add(issue(
                        "POLICY_PARAMETER_ENUM_INVALID",
                        "parameters." + key,
                        "策略参数不在允许枚举中"));
            }
        }

        private void validateEnumList(
                String key, Object value, List<ValidationIssue> issues) {
            if (!(value instanceof List<?> values)) {
                issues.add(typeIssue(key, "ENUM_LIST"));
                return;
            }
            if (values.isEmpty()) {
                issues.add(issue(
                        "POLICY_PARAMETER_ENUM_INVALID",
                        "parameters." + key,
                        "策略参数枚举列表不能为空"));
                return;
            }
            Set<Object> unique = new java.util.HashSet<>();
            for (Object item : values) {
                if (!(item instanceof String text)
                        || !enumValues.contains(text)
                        || !unique.add(item)) {
                    issues.add(issue(
                            "POLICY_PARAMETER_ENUM_INVALID",
                            "parameters." + key,
                            "策略参数包含重复或不允许的枚举值"));
                    return;
                }
            }
        }

        private ValidationIssue typeIssue(String key, String expectedType) {
            return issue(
                    "POLICY_PARAMETER_TYPE_INVALID",
                    "parameters." + key,
                    "策略参数类型必须为 " + expectedType);
        }

        private ValidationIssue formatIssue(String key) {
            return issue(
                    "POLICY_PARAMETER_FORMAT_INVALID",
                    "parameters." + key,
                    "时间必须为 HH:mm 格式");
        }
    }

    private enum ValueType {
        BOOLEAN,
        INTEGER,
        LOCAL_TIME,
        ENUM,
        ENUM_LIST
    }
}
