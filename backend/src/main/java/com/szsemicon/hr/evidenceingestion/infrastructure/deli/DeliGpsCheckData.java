package com.szsemicon.hr.evidenceingestion.infrastructure.deli;

import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounded GPS/out-work fields from Deli {@code check_data}.
 * Location text keys: {@code location}, {@code address}, {@code addr}.
 * Latitude keys: {@code lat}, {@code latitude}.
 * Longitude keys: {@code lgt}, {@code lng}, {@code lon}, {@code longitude}.
 * Photo/wifi and any other keys are dropped.
 */
final class DeliGpsCheckData {

    private static final int MAX_LOCATION_SUMMARY = 191;
    private static final int MAX_COORDINATE = 64;
    private static final Set<String> LOCATION_KEYS = Set.of("location", "address", "addr");
    private static final Set<String> LATITUDE_KEYS = Set.of("lat", "latitude");
    private static final Set<String> LONGITUDE_KEYS = Set.of("lgt", "lng", "lon", "longitude");
    private static final Set<String> DROPPED_KEYS = Set.of("photo", "wifi", "image", "photo_url");

    private DeliGpsCheckData() {
    }

    record Parsed(
            String locationSummary,
            String longitudeRaw,
            String latitudeRaw,
            boolean forbiddenPayloadDropped) {
    }

    static boolean gpsOrOutWork(String checkType) {
        if (checkType == null) {
            return false;
        }
        String normalized = checkType.trim().toLowerCase(Locale.ROOT);
        return "gps".equals(normalized) || "out_work".equals(normalized);
    }

    static Parsed parse(JsonNode checkData, String checkType, ObjectMapper mapper) {
        JsonNode object = asObject(checkData, mapper);
        boolean dropped = object != null && dropsForbidden(object);
        if (!gpsOrOutWork(checkType) || object == null) {
            return new Parsed(null, null, null, dropped || (checkData != null && !checkData.isNull()));
        }
        return new Parsed(
                firstText(object, LOCATION_KEYS, MAX_LOCATION_SUMMARY),
                firstText(object, LONGITUDE_KEYS, MAX_COORDINATE),
                firstText(object, LATITUDE_KEYS, MAX_COORDINATE),
                dropped);
    }

    private static JsonNode asObject(JsonNode node, ObjectMapper mapper) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return node;
        }
        if (!node.isTextual()) {
            return null;
        }
        String raw = node.asText();
        if (raw == null || raw.isBlank() || !raw.trim().startsWith("{")) {
            return null;
        }
        try {
            JsonNode parsed = mapper.readTree(raw);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean dropsForbidden(JsonNode object) {
        for (String key : DROPPED_KEYS) {
            JsonNode value = object.get(key);
            if (value != null && !value.isNull()) {
                return true;
            }
        }
        return false;
    }

    private static String firstText(JsonNode object, Set<String> keys, int maxLength) {
        for (String key : keys) {
            JsonNode value = object.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.asText();
            if (text == null || text.isBlank()) {
                continue;
            }
            String trimmed = text.trim();
            if (trimmed.length() > maxLength || hasControlCharacter(trimmed)) {
                continue;
            }
            return trimmed;
        }
        return null;
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
