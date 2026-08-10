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

    private static final List<String> SATURDAY_DINNER_OVERRIDE_KEYS = List.of(
            "saturdayMealWindowStart",
            "saturdayMealWindowEnd",
            "saturdayDeductionMinutes",
            "saturdayTriggerMinutes");
    private static final List<String> SUNDAY_DINNER_OVERRIDE_KEYS = List.of(
            "sundayMealWindowStart",
            "sundayMealWindowEnd",
            "sundayDeductionMinutes",
            "sundayTriggerMinutes");
    private static final List<String> SATURDAY_LUNCH_KEYS = List.of(
            "saturdayLunchWindowStart",
            "saturdayLunchWindowEnd",
            "saturdayLunchDeductionMinutes",
            "saturdayLunchTriggerMinutes");
    private static final List<String> SUNDAY_LUNCH_KEYS = List.of(
            "sundayLunchWindowStart",
            "sundayLunchWindowEnd",
            "sundayLunchDeductionMinutes",
            "sundayLunchTriggerMinutes");
    private static final List<String> PUBLIC_HOLIDAY_LUNCH_KEYS = List.of(
            "publicHolidayLunchWindowStart",
            "publicHolidayLunchWindowEnd",
            "publicHolidayLunchDeductionMinutes",
            "publicHolidayLunchTriggerMinutes");
    private static final List<String> PUBLIC_HOLIDAY_DINNER_OVERRIDE_KEYS =
            List.of(
                    "publicHolidayMealWindowStart",
                    "publicHolidayMealWindowEnd",
                    "publicHolidayDeductionMinutes",
                    "publicHolidayTriggerMinutes");
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

        for (Map.Entry<String, Rule> declaredParameter : declared.entrySet()) {
            if (declaredParameter.getValue().required()
                    && !unique.containsKey(declaredParameter.getKey())) {
                issues.add(issue(
                        "POLICY_PARAMETER_REQUIRED",
                        "parameters." + declaredParameter.getKey(),
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
        validateWindow(
                parameters,
                "mealWindowStart",
                "mealWindowEnd",
                "晚餐窗口开始与结束时间不能相同",
                issues);
        validateOverrideGroup(
                parameters,
                SATURDAY_DINNER_OVERRIDE_KEYS,
                "saturdayMealWindowStart",
                "saturdayMealWindowEnd",
                "saturdayDinnerOverride",
                "周六覆盖参数必须全部填写或全部不填",
                "周六晚餐窗口开始与结束时间不能相同",
                issues);
        validateOverrideGroup(
                parameters,
                SUNDAY_DINNER_OVERRIDE_KEYS,
                "sundayMealWindowStart",
                "sundayMealWindowEnd",
                "sundayDinnerOverride",
                "周日覆盖参数必须全部填写或全部不填",
                "周日晚餐窗口开始与结束时间不能相同",
                issues);
        validateOverrideGroup(
                parameters,
                SATURDAY_LUNCH_KEYS,
                "saturdayLunchWindowStart",
                "saturdayLunchWindowEnd",
                "saturdayLunch",
                "周六午餐参数必须全部填写或全部不填",
                "周六午餐窗口开始与结束时间不能相同",
                issues);
        validateOverrideGroup(
                parameters,
                SUNDAY_LUNCH_KEYS,
                "sundayLunchWindowStart",
                "sundayLunchWindowEnd",
                "sundayLunch",
                "周日午餐参数必须全部填写或全部不填",
                "周日午餐窗口开始与结束时间不能相同",
                issues);
        validateOverrideGroup(
                parameters,
                PUBLIC_HOLIDAY_LUNCH_KEYS,
                "publicHolidayLunchWindowStart",
                "publicHolidayLunchWindowEnd",
                "publicHolidayLunch",
                "法定节假日午餐参数必须全部填写或全部不填",
                "法定节假日午餐窗口开始与结束时间不能相同",
                issues);
        validateOverrideGroup(
                parameters,
                PUBLIC_HOLIDAY_DINNER_OVERRIDE_KEYS,
                "publicHolidayMealWindowStart",
                "publicHolidayMealWindowEnd",
                "publicHolidayDinnerOverride",
                "法定节假日晚餐覆盖参数必须全部填写或全部不填",
                "法定节假日晚餐窗口开始与结束时间不能相同",
                issues);
    }

    private static void validateOverrideGroup(
            Map<String, ParameterValue> parameters,
            List<String> groupKeys,
            String startKey,
            String endKey,
            String groupField,
            String incompleteMessage,
            String equalWindowMessage,
            List<ValidationIssue> issues) {
        long supplied = groupKeys.stream().filter(parameters::containsKey).count();
        if (supplied == 0) {
            return;
        }
        if (supplied != groupKeys.size()) {
            issues.add(issue(
                    "POLICY_PARAMETER_GROUP_INCOMPLETE",
                    "parameters." + groupField,
                    incompleteMessage));
            return;
        }
        validateWindow(
                parameters, startKey, endKey, equalWindowMessage, issues);
    }

    private static void validateWindow(
            Map<String, ParameterValue> parameters,
            String startKey,
            String endKey,
            String equalWindowMessage,
            List<ValidationIssue> issues) {
        Object rawStart = value(parameters, startKey);
        Object rawEnd = value(parameters, endKey);
        if (!(rawStart instanceof String start)
                || !(rawEnd instanceof String end)) {
            return;
        }
        try {
            if (LocalTime.parse(start).equals(LocalTime.parse(end))) {
                issues.add(issue(
                        "POLICY_PARAMETER_FORMAT_INVALID",
                        "parameters." + endKey,
                        equalWindowMessage));
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
                                        "PUBLIC_HOLIDAY"),
                        "saturdayMealWindowStart", Rule.optionalLocalTime(),
                        "saturdayMealWindowEnd", Rule.optionalLocalTime(),
                        "saturdayDeductionMinutes", Rule.optionalInteger(0, 240),
                        "saturdayTriggerMinutes", Rule.optionalInteger(0, 1440),
                        "saturdayLunchWindowStart", Rule.optionalLocalTime(),
                        "saturdayLunchWindowEnd", Rule.optionalLocalTime(),
                        "saturdayLunchDeductionMinutes",
                                Rule.optionalInteger(0, 240),
                        "saturdayLunchTriggerMinutes",
                                Rule.optionalInteger(0, 1440),
                        "sundayMealWindowStart", Rule.optionalLocalTime(),
                        "sundayMealWindowEnd", Rule.optionalLocalTime(),
                        "sundayDeductionMinutes", Rule.optionalInteger(0, 240),
                        "sundayTriggerMinutes", Rule.optionalInteger(0, 1440),
                        "sundayLunchWindowStart", Rule.optionalLocalTime(),
                        "sundayLunchWindowEnd", Rule.optionalLocalTime(),
                        "sundayLunchDeductionMinutes",
                                Rule.optionalInteger(0, 240),
                        "sundayLunchTriggerMinutes",
                                Rule.optionalInteger(0, 1440),
                        "publicHolidayMealWindowStart",
                                Rule.optionalLocalTime(),
                        "publicHolidayMealWindowEnd",
                                Rule.optionalLocalTime(),
                        "publicHolidayDeductionMinutes",
                                Rule.optionalInteger(0, 240),
                        "publicHolidayTriggerMinutes",
                                Rule.optionalInteger(0, 1440),
                        "publicHolidayLunchWindowStart",
                                Rule.optionalLocalTime(),
                        "publicHolidayLunchWindowEnd",
                                Rule.optionalLocalTime(),
                        "publicHolidayLunchDeductionMinutes",
                                Rule.optionalInteger(0, 240),
                        "publicHolidayLunchTriggerMinutes",
                                Rule.optionalInteger(0, 1440)));
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
        // Management kinds: present → validate, absent → not blocking (handled in service).
        rules.put(
                PolicyKind.PUNCH_WINDOW,
                orderedRules(
                        "enabled", Rule.bool(),
                        "arrivalBeforeMinutes", Rule.integer(0, 720),
                        "arrivalAfterMinutes", Rule.integer(0, 720),
                        "departureBeforeMinutes", Rule.integer(0, 720),
                        "departureAfterMinutes", Rule.integer(0, 720)));
        rules.put(
                PolicyKind.PERIOD_CLOSE,
                orderedRules(
                        "enabled", Rule.bool(),
                        "closeDayOfNextMonth", Rule.integer(1, 28),
                        "reopenAllowed", Rule.bool(),
                        "reopenRequiresApproval", Rule.bool(),
                        "maxReopenCount", Rule.integer(0, 99)));
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
            Set<String> enumValues,
            boolean required) {

        static Rule bool() {
            return new Rule(ValueType.BOOLEAN, null, null, Set.of(), true);
        }

        static Rule integer(long minimum, long maximum) {
            return new Rule(
                    ValueType.INTEGER,
                    BigDecimal.valueOf(minimum),
                    BigDecimal.valueOf(maximum),
                    Set.of(),
                    true);
        }

        static Rule optionalInteger(long minimum, long maximum) {
            return new Rule(
                    ValueType.INTEGER,
                    BigDecimal.valueOf(minimum),
                    BigDecimal.valueOf(maximum),
                    Set.of(),
                    false);
        }

        static Rule localTime() {
            return new Rule(
                    ValueType.LOCAL_TIME, null, null, Set.of(), true);
        }

        static Rule optionalLocalTime() {
            return new Rule(
                    ValueType.LOCAL_TIME, null, null, Set.of(), false);
        }

        static Rule enumeration(String... values) {
            return new Rule(
                    ValueType.ENUM, null, null, Set.of(values), true);
        }

        static Rule enumerationList(String... values) {
            return new Rule(
                    ValueType.ENUM_LIST, null, null, Set.of(values), true);
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
