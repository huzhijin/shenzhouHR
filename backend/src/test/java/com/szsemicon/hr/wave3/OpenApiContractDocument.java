package com.szsemicon.hr.wave3;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

final class OpenApiContractDocument {

    private final Map<String, Object> root;

    private OpenApiContractDocument(Map<String, Object> root) {
        this.root = root;
    }

    static OpenApiContractDocument load(String repositoryPath) {
        Path fromBackend = Path.of("..", repositoryPath);
        Path fromRoot = Path.of(repositoryPath);
        Path path = Files.exists(fromBackend) ? fromBackend : fromRoot;
        try (InputStream input = Files.newInputStream(path)) {
            Object parsed = new Yaml().load(input);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IllegalStateException("OpenAPI root must be a mapping");
            }
            return new OpenApiContractDocument(stringMap(map));
        } catch (Exception exception) {
            throw new IllegalStateException(repositoryPath + " must be valid YAML", exception);
        }
    }

    Map<String, Object> root() {
        return root;
    }

    Map<String, Object> paths() {
        return map(root.get("paths"));
    }

    Map<String, Object> schemas() {
        return map(map(root.get("components")).get("schemas"));
    }

    Object resolve(String reference) {
        if (!reference.startsWith("#/")) {
            throw new IllegalArgumentException("Only local references are allowed: " + reference);
        }
        Object current = root;
        for (String encodedToken : reference.substring(2).split("/")) {
            String token = encodedToken.replace("~1", "/").replace("~0", "~");
            current = map(current).get(token);
            if (current == null) {
                throw new IllegalArgumentException("Unresolved reference: " + reference);
            }
        }
        return current;
    }

    List<String> unresolvedReferences() {
        List<String> unresolved = new ArrayList<>();
        walk(root, value -> {
            if (value instanceof Map<?, ?> candidate && candidate.get("$ref") instanceof String reference) {
                try {
                    resolve(reference);
                } catch (RuntimeException exception) {
                    unresolved.add(reference);
                }
            }
        });
        return unresolved;
    }

    List<String> validateSchema(String schemaName, Object instance) {
        List<String> errors = new ArrayList<>();
        validate(schemas().get(schemaName), instance, "$", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> candidate)) {
            throw new IllegalArgumentException("Expected mapping but got: " + value);
        }
        return (Map<String, Object>) candidate;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        if (!(value instanceof List<?> candidate)) {
            throw new IllegalArgumentException("Expected sequence but got: " + value);
        }
        return (List<Object>) candidate;
    }

    static Map<String, Object> object(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Object fixture requires key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            result.put((String) keyValues[index], keyValues[index + 1]);
        }
        return result;
    }

    private void validate(
            Object rawSchema, Object instance, String path, List<String> errors) {
        validate(rawSchema, instance, path, errors, Set.of(), false);
    }

    private void validate(
            Object rawSchema,
            Object instance,
            String path,
            List<String> errors,
            Set<String> inheritedProperties,
            boolean deferObjectEnvelope) {
        Map<String, Object> schema = map(rawSchema);
        if (schema.get("$ref") instanceof String reference) {
            validate(
                    resolve(reference),
                    instance,
                    path,
                    errors,
                    inheritedProperties,
                    deferObjectEnvelope);
            return;
        }
        if (schema.get("allOf") instanceof List<?> allOf) {
            ObjectEvaluation evaluation = evaluateObject(schema);
            Set<String> evaluatedProperties = new LinkedHashSet<>(inheritedProperties);
            evaluatedProperties.addAll(evaluation.properties());
            if (!deferObjectEnvelope && instance instanceof Map<?, ?> objectValue) {
                validateObjectEnvelope(
                        evaluation,
                        stringMap(objectValue),
                        path,
                        errors);
            }
            allOf.forEach(part -> validate(
                    part,
                    instance,
                    path,
                    errors,
                    evaluatedProperties,
                    true));
            inheritedProperties = evaluatedProperties;
            deferObjectEnvelope = true;
        }
        if (schema.get("anyOf") instanceof List<?> anyOf) {
            boolean matched = anyOf.stream().anyMatch(part -> {
                List<String> branchErrors = new ArrayList<>();
                validate(part, instance, path, branchErrors);
                return branchErrors.isEmpty();
            });
            if (!matched) {
                errors.add(path + " does not match anyOf");
            }
            return;
        }

        if (!matchesType(schema.get("type"), instance)) {
            errors.add(path + " has invalid type");
            return;
        }
        if (schema.containsKey("const") && !java.util.Objects.equals(schema.get("const"), instance)) {
            errors.add(path + " does not match const");
        }
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(instance)) {
            errors.add(path + " is outside enum");
        }
        if (instance instanceof String stringValue) {
            validateString(schema, stringValue, path, errors);
        } else if (instance instanceof Number numberValue) {
            validateNumber(schema, numberValue, path, errors);
        } else if (instance instanceof Map<?, ?> objectValue) {
            validateObject(
                    schema,
                    stringMap(objectValue),
                    path,
                    errors,
                    inheritedProperties,
                    deferObjectEnvelope);
        } else if (instance instanceof List<?> arrayValue) {
            validateArray(schema, arrayValue, path, errors);
        }
    }

    private void validateObject(
            Map<String, Object> schema,
            Map<String, Object> instance,
            String path,
            List<String> errors,
            Set<String> inheritedProperties,
            boolean deferObjectEnvelope) {
        Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?>
                ? map(schema.get("properties")) : Map.of();
        Set<String> evaluatedProperties = new LinkedHashSet<>(inheritedProperties);
        evaluatedProperties.addAll(properties.keySet());
        if (!deferObjectEnvelope && Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            instance.keySet().stream()
                    .filter(key -> !evaluatedProperties.contains(key))
                    .forEach(key -> errors.add(path + " has additional property " + key));
        }
        if (!deferObjectEnvelope && schema.get("required") instanceof List<?> required) {
            required.stream()
                    .map(String::valueOf)
                    .filter(key -> !instance.containsKey(key))
                    .forEach(key -> errors.add(path + " lacks required property " + key));
        }
        instance.forEach((key, value) -> {
            Object propertySchema = properties.get(key);
            if (propertySchema != null) {
                validate(propertySchema, value, path + "." + key, errors);
            }
        });
    }

    private void validateObjectEnvelope(
            ObjectEvaluation evaluation,
            Map<String, Object> instance,
            String path,
            List<String> errors) {
        if (evaluation.rejectsUnknown()) {
            instance.keySet().stream()
                    .filter(key -> !evaluation.properties().contains(key))
                    .forEach(key -> errors.add(path + " has additional property " + key));
        }
        evaluation.required().stream()
                .filter(key -> !instance.containsKey(key))
                .forEach(key -> errors.add(path + " lacks required property " + key));
    }

    private ObjectEvaluation evaluateObject(Object rawSchema) {
        Map<String, Object> schema = map(rawSchema);
        Set<String> properties = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>();
        boolean rejectsUnknown = Boolean.FALSE.equals(schema.get("additionalProperties"));
        if (schema.get("$ref") instanceof String reference) {
            ObjectEvaluation resolved = evaluateObject(resolve(reference));
            properties.addAll(resolved.properties());
            required.addAll(resolved.required());
            rejectsUnknown = rejectsUnknown || resolved.rejectsUnknown();
        }
        if (schema.get("properties") instanceof Map<?, ?> directProperties) {
            directProperties.keySet().stream()
                    .map(String::valueOf)
                    .forEach(properties::add);
        }
        if (schema.get("required") instanceof List<?> directRequired) {
            directRequired.stream()
                    .map(String::valueOf)
                    .forEach(required::add);
        }
        if (schema.get("allOf") instanceof List<?> allOf) {
            for (Object part : allOf) {
                ObjectEvaluation branch = evaluateObject(part);
                properties.addAll(branch.properties());
                required.addAll(branch.required());
                rejectsUnknown = rejectsUnknown || branch.rejectsUnknown();
            }
        }
        return new ObjectEvaluation(properties, required, rejectsUnknown);
    }

    private void validateArray(
            Map<String, Object> schema,
            List<?> instance,
            String path,
            List<String> errors) {
        if (schema.get("minItems") instanceof Number minimum
                && instance.size() < minimum.intValue()) {
            errors.add(path + " has fewer items than minItems");
        }
        if (schema.get("maxItems") instanceof Number maximum
                && instance.size() > maximum.intValue()) {
            errors.add(path + " has more items than maxItems");
        }
        Object itemSchema = schema.get("items");
        if (itemSchema != null) {
            for (int index = 0; index < instance.size(); index++) {
                validate(itemSchema, instance.get(index), path + "[" + index + "]", errors);
            }
        }
    }

    private static void validateString(
            Map<String, Object> schema,
            String instance,
            String path,
            List<String> errors) {
        if (schema.get("minLength") instanceof Number minimum
                && instance.length() < minimum.intValue()) {
            errors.add(path + " is shorter than minLength");
        }
        if (schema.get("maxLength") instanceof Number maximum
                && instance.length() > maximum.intValue()) {
            errors.add(path + " is longer than maxLength");
        }
        if (schema.get("pattern") instanceof String pattern
                && !Pattern.compile(pattern).matcher(instance).matches()) {
            errors.add(path + " does not match pattern");
        }
        if ("date".equals(schema.get("format"))) {
            try {
                java.time.LocalDate.parse(instance);
            } catch (java.time.format.DateTimeParseException exception) {
                errors.add(path + " is not an RFC3339 full-date");
            }
        }
        if ("date-time".equals(schema.get("format"))) {
            try {
                java.time.OffsetDateTime.parse(instance);
            } catch (java.time.format.DateTimeParseException exception) {
                errors.add(path + " is not an offset RFC3339 instant");
            }
        }
    }

    private static void validateNumber(
            Map<String, Object> schema,
            Number instance,
            String path,
            List<String> errors) {
        if (schema.get("minimum") instanceof Number minimum
                && instance.doubleValue() < minimum.doubleValue()) {
            errors.add(path + " is below minimum");
        }
        if (schema.get("maximum") instanceof Number maximum
                && instance.doubleValue() > maximum.doubleValue()) {
            errors.add(path + " is above maximum");
        }
    }

    private static boolean matchesType(Object type, Object instance) {
        if (type == null) {
            return true;
        }
        if (type instanceof List<?> alternatives) {
            return alternatives.stream().anyMatch(candidate -> matchesType(candidate, instance));
        }
        return switch (String.valueOf(type)) {
            case "null" -> instance == null;
            case "object" -> instance instanceof Map<?, ?>;
            case "array" -> instance instanceof List<?>;
            case "string" -> instance instanceof String;
            case "integer" -> instance instanceof Byte
                    || instance instanceof Short
                    || instance instanceof Integer
                    || instance instanceof Long;
            case "number" -> instance instanceof Number;
            case "boolean" -> instance instanceof Boolean;
            default -> false;
        };
    }

    private static void walk(Object value, java.util.function.Consumer<Object> consumer) {
        consumer.accept(value);
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(child -> walk(child, consumer));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> walk(child, consumer));
        }
    }

    private static Map<String, Object> stringMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private record ObjectEvaluation(
            Set<String> properties,
            Set<String> required,
            boolean rejectsUnknown) {
    }
}
