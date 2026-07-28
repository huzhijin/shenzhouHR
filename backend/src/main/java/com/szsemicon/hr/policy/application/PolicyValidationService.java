package com.szsemicon.hr.policy.application;

import com.szsemicon.hr.policy.domain.PolicyModels.FieldDefinition;
import com.szsemicon.hr.policy.domain.PolicyModels.ParameterValue;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyTemplate;
import com.szsemicon.hr.policy.domain.PolicyModels.PolicyVersion;
import com.szsemicon.hr.policy.domain.PolicyModels.ScopeBinding;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationIssue;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationResult;
import com.szsemicon.hr.policy.domain.PolicyModels.ValueType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public final class PolicyValidationService {

    private static final Pattern TEMPLATE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");
    private static final Pattern FIELD_KEY = Pattern.compile("[a-z][a-zA-Z0-9]*");
    private static final Set<String> EXECUTABLE_FIELD_NAMES =
            Set.of("script", "expression", "command", "executable");

    public List<ValidationIssue> validateTemplate(
            String code,
            String name,
            String description,
            List<FieldDefinition> fields) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (code == null || !TEMPLATE_CODE.matcher(code).matches()) {
            issues.add(issue("INVALID_TEMPLATE_CODE", "code", "模板编码格式无效"));
        }
        if (name == null || name.isBlank() || name.length() > 100) {
            issues.add(issue("INVALID_TEMPLATE_NAME", "name", "模板名称长度无效"));
        }
        if (description == null || description.length() > 500) {
            issues.add(issue("INVALID_TEMPLATE_DESCRIPTION", "description", "模板说明长度无效"));
        }
        if (fields == null || fields.isEmpty()) {
            issues.add(issue("FIELD_DEFINITION_REQUIRED", "fieldDefinitions", "至少需要一个受控字段"));
            return issues;
        }

        Set<String> keys = new HashSet<>();
        for (int index = 0; index < fields.size(); index++) {
            FieldDefinition field = fields.get(index);
            String path = "fieldDefinitions[" + index + "]";
            if (field.key() == null || !FIELD_KEY.matcher(field.key()).matches()) {
                issues.add(issue("INVALID_FIELD_KEY", path + ".key", "字段键格式无效"));
            } else {
                if (!keys.add(field.key())) {
                    issues.add(issue("DUPLICATE_FIELD_KEY", path + ".key", "字段键重复"));
                }
                if (EXECUTABLE_FIELD_NAMES.contains(field.key().toLowerCase())) {
                    issues.add(issue(
                            "EXECUTABLE_FIELD_FORBIDDEN",
                            path + ".key",
                            "规则不允许脚本、表达式或命令字段"));
                }
            }
            if (field.label() == null || field.label().isBlank() || field.label().length() > 100) {
                issues.add(issue("INVALID_FIELD_LABEL", path + ".label", "字段标签长度无效"));
            }
            if (field.valueType() == null) {
                issues.add(issue("VALUE_TYPE_REQUIRED", path + ".valueType", "字段类型必填"));
                continue;
            }
            if (field.valueType() == ValueType.ENUM && field.enumValues().isEmpty()) {
                issues.add(issue("ENUM_VALUES_REQUIRED", path + ".enumValues", "枚举字段必须声明可选值"));
            }
            if (new HashSet<>(field.enumValues()).size() != field.enumValues().size()) {
                issues.add(issue("DUPLICATE_ENUM_VALUE", path + ".enumValues", "枚举值不能重复"));
            }
            if (field.minimum() != null
                    && field.maximum() != null
                    && field.minimum().compareTo(field.maximum()) > 0) {
                issues.add(issue("INVALID_VALUE_RANGE", path, "字段最小值不能大于最大值"));
            }
        }
        return issues;
    }

    public ValidationResult validateVersion(
            PolicyTemplate template,
            PolicyVersion version,
            Instant validatedAt) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (version.effectiveFrom() == null) {
            issues.add(issue(
                    "EFFECTIVE_FROM_REQUIRED",
                    "effectiveFrom",
                    "生效日必填"));
        } else if (version.effectiveTo() != null
                && version.effectiveTo().isBefore(version.effectiveFrom())) {
            issues.add(issue(
                    "INVALID_EFFECTIVE_PERIOD",
                    "effectiveTo",
                    "失效日不能早于生效日"));
        }
        if (version.changeReason() == null
                || version.changeReason().isBlank()
                || version.changeReason().length() > 500) {
            issues.add(issue(
                    "INVALID_CHANGE_REASON",
                    "changeReason",
                    "变更原因长度无效"));
        }

        Map<String, FieldDefinition> definitions = new HashMap<>();
        for (FieldDefinition definition : template.fieldDefinitions()) {
            definitions.put(definition.key(), definition);
        }
        Set<String> supplied = new HashSet<>();
        for (int index = 0; index < version.parameters().size(); index++) {
            ParameterValue parameter = version.parameters().get(index);
            String path = "parameters[" + index + "]";
            if (parameter.key() == null || !supplied.add(parameter.key())) {
                issues.add(issue("DUPLICATE_PARAMETER", path + ".key", "参数键为空或重复"));
                continue;
            }
            FieldDefinition definition = definitions.get(parameter.key());
            if (definition == null) {
                issues.add(issue(
                        "UNKNOWN_POLICY_FIELD",
                        path + ".key",
                        "参数不属于模板受控字段"));
                continue;
            }
            validateValue(path + ".value", definition, parameter.value(), issues);
        }
        for (FieldDefinition definition : template.fieldDefinitions()) {
            if (definition.required() && !supplied.contains(definition.key())) {
                issues.add(issue(
                        "REQUIRED_PARAMETER_MISSING",
                        "parameters." + definition.key(),
                        "缺少必填参数"));
            }
        }
        if (version.scopeBindings().isEmpty()) {
            issues.add(issue("SCOPE_REQUIRED", "scopeBindings", "至少需要一个作用范围"));
        }
        Set<String> scopeKeys = new HashSet<>();
        for (int index = 0; index < version.scopeBindings().size(); index++) {
            ScopeBinding binding = version.scopeBindings().get(index);
            String path = "scopeBindings[" + index + "]";
            if (binding.scopeType() == null) {
                issues.add(issue("SCOPE_TYPE_REQUIRED", path + ".scopeType", "作用范围类型必填"));
            }
            if (binding.scopeResourceId() == null || binding.scopeResourceId().isBlank()) {
                issues.add(issue(
                        "SCOPE_RESOURCE_REQUIRED",
                        path + ".scopeResourceId",
                        "作用范围资源必填"));
            }
            if (binding.priority() < 0 || binding.priority() > 10000) {
                issues.add(issue("INVALID_PRIORITY", path + ".priority", "优先级必须在 0 到 10000"));
            }
            if (binding.effectiveFrom() == null) {
                issues.add(issue(
                        "SCOPE_EFFECTIVE_FROM_REQUIRED",
                        path + ".effectiveFrom",
                        "作用范围生效日必填"));
            } else if (binding.effectiveTo() != null
                    && binding.effectiveTo().isBefore(binding.effectiveFrom())) {
                issues.add(issue(
                        "INVALID_SCOPE_PERIOD",
                        path + ".effectiveTo",
                        "作用范围失效日不能早于生效日"));
            }
            if (binding.effectiveFrom() != null
                    && version.effectiveFrom() != null
                    && (binding.effectiveFrom().isBefore(version.effectiveFrom())
                    || (version.effectiveTo() != null
                    && (binding.effectiveTo() == null
                    || binding.effectiveTo().isAfter(version.effectiveTo()))))) {
                issues.add(issue(
                        "SCOPE_OUTSIDE_VERSION_PERIOD",
                        path + ".effectiveFrom",
                        "作用范围期间必须位于版本生效期间内"));
            }
            String scopeKey = binding.scopeType()
                    + "|" + binding.scopeResourceId()
                    + "|" + binding.priority()
                    + "|" + binding.effectiveFrom()
                    + "|" + binding.effectiveTo();
            if (!scopeKeys.add(scopeKey)) {
                issues.add(issue("DUPLICATE_SCOPE_BINDING", path, "作用范围绑定重复"));
            }
        }
        return new ValidationResult(issues.isEmpty(), issues, validatedAt);
    }

    public void validateSimulationInput(String sampleName, Map<String, Object> inputs) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (sampleName == null || sampleName.isBlank() || sampleName.length() > 100) {
            issues.add(issue("INVALID_SAMPLE_NAME", "sampleName", "样例名称长度无效"));
        }
        if (inputs == null) {
            issues.add(issue("SIMULATION_INPUT_REQUIRED", "inputs", "样例输入必填"));
        } else {
            inputs.forEach((key, value) -> {
                if (EXECUTABLE_FIELD_NAMES.contains(key.toLowerCase())) {
                    issues.add(issue(
                            "EXECUTABLE_INPUT_FORBIDDEN",
                            "inputs." + key,
                            "样例试算不执行脚本、表达式或命令"));
                }
                if (value instanceof Map<?, ?> || value instanceof List<?>) {
                    issues.add(issue(
                            "COMPLEX_INPUT_FORBIDDEN",
                            "inputs." + key,
                            "样例输入只接受受控标量"));
                }
            });
        }
        if (!issues.isEmpty()) {
            throw new PolicyExceptions.ValidationFailed(issues);
        }
    }

    private void validateValue(
            String path,
            FieldDefinition definition,
            Object value,
            List<ValidationIssue> issues) {
        if (value == null) {
            if (definition.required()) {
                issues.add(issue("NULL_REQUIRED_VALUE", path, "必填参数不能为 null"));
            }
            return;
        }
        try {
            switch (definition.valueType()) {
                case ENUM -> validateEnum(path, definition, value, issues);
                case BOOLEAN -> requireType(path, value, Boolean.class, issues);
                case INTEGER -> validateInteger(path, definition, value, issues);
                case DECIMAL -> validateDecimal(path, definition, value, issues);
                case DURATION -> Duration.parse(requireString(value));
                case TIME_WINDOW -> validateTimeWindow(requireString(value));
                case DATE -> LocalDate.parse(requireString(value));
                case TEXT -> {
                    String text = requireString(value);
                    if (text.length() > 1000) {
                        issues.add(issue("TEXT_TOO_LONG", path, "文本参数不能超过 1000 字符"));
                    }
                }
            }
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            issues.add(issue("INVALID_PARAMETER_VALUE", path, "参数值与受控类型不匹配"));
        }
    }

    private void validateEnum(
            String path,
            FieldDefinition definition,
            Object value,
            List<ValidationIssue> issues) {
        String candidate = requireString(value);
        if (!definition.enumValues().contains(candidate)) {
            issues.add(issue("ENUM_VALUE_NOT_ALLOWED", path, "参数值不在受控枚举中"));
        }
    }

    private void validateInteger(
            String path,
            FieldDefinition definition,
            Object value,
            List<ValidationIssue> issues) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("integer required");
        }
        BigDecimal decimal = new BigDecimal(number.toString());
        if (decimal.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("integral number required");
        }
        validateRange(path, definition, decimal, issues);
    }

    private void validateDecimal(
            String path,
            FieldDefinition definition,
            Object value,
            List<ValidationIssue> issues) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("decimal required");
        }
        validateRange(path, definition, new BigDecimal(number.toString()), issues);
    }

    private void validateRange(
            String path,
            FieldDefinition definition,
            BigDecimal value,
            List<ValidationIssue> issues) {
        if (definition.minimum() != null && value.compareTo(definition.minimum()) < 0) {
            issues.add(issue("VALUE_BELOW_MINIMUM", path, "参数值小于允许最小值"));
        }
        if (definition.maximum() != null && value.compareTo(definition.maximum()) > 0) {
            issues.add(issue("VALUE_ABOVE_MAXIMUM", path, "参数值大于允许最大值"));
        }
    }

    private void requireType(
            String path,
            Object value,
            Class<?> requiredType,
            List<ValidationIssue> issues) {
        if (!requiredType.isInstance(value)) {
            issues.add(issue("INVALID_PARAMETER_VALUE", path, "参数值与受控类型不匹配"));
        }
    }

    private String requireString(Object value) {
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalArgumentException("string required");
    }

    private void validateTimeWindow(String value) {
        String[] parts = value.split("-", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("time window required");
        }
        LocalTime.parse(parts[0]);
        LocalTime.parse(parts[1]);
    }

    private ValidationIssue issue(String code, String field, String message) {
        return new ValidationIssue(code, field, message);
    }

}
