package com.szsemicon.hr.reporting.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Only immutable business dates and recognised minutes; null means legacy/unavailable. */
public final class OaRecognizedDayMinutes {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private OaRecognizedDayMinutes() {}

    /** Exact date-key membership in the validated immutable JSON map. Bound as
     * a SQL parameter, not interpolated SQL, and portable across MySQL/H2.
     * Zero shares do not make a leave occur on that date. */
    public static String positiveDatePattern(LocalDate from, LocalDate endExclusive) {
        if (from == null || endExclusive == null || !from.isBefore(endExclusive)
                || java.time.temporal.ChronoUnit.DAYS.between(from, endExclusive) > 366) {
            throw new IllegalArgumentException("invalid OA query date window");
        }
        String dates = from.datesUntil(endExclusive).map(LocalDate::toString)
                .collect(java.util.stream.Collectors.joining("|"));
        return "\"(?:" + dates + ")\"\\s*:\\s*[1-9][0-9]*\\s*[,}]";
    }

    /** Date-key membership including explicit 0 minutes (for REST44 SICK coverage).
     * Matches any non-negative integer value for the date key. */
    public static String anyDatePattern(LocalDate from, LocalDate endExclusive) {
        if (from == null || endExclusive == null || !from.isBefore(endExclusive)
                || java.time.temporal.ChronoUnit.DAYS.between(from, endExclusive) > 366) {
            throw new IllegalArgumentException("invalid OA query date window");
        }
        String dates = from.datesUntil(endExclusive).map(LocalDate::toString)
                .collect(java.util.stream.Collectors.joining("|"));
        return "\"(?:" + dates + ")\"\\s*:\\s*[0-9]+\\s*[,}]";
    }

    public static Map<LocalDate, Long> validated(Map<LocalDate, Long> values, long total) {
        if (values == null) return null;
        Map<LocalDate, Long> sorted = new TreeMap<>();
        long sum = 0;
        for (var entry : values.entrySet()) {
            LocalDate date = entry.getKey();
            Long minutes = entry.getValue();
            if (date == null || date.getYear() < 1970 || date.getYear() > 9999
                    || minutes == null || minutes < 0 || minutes > 4_294_967_295L) {
                throw new IllegalArgumentException("invalid OA daily recognized minutes");
            }
            sum = Math.addExact(sum, minutes);
            // Keep 0 minutes for REST_DAY SICK display coverage
            sorted.put(date, minutes);
        }
        if (sum != total) throw new IllegalArgumentException("OA daily minutes do not equal form total");
        return Collections.unmodifiableMap(sorted);
    }

    public static String encode(Map<LocalDate, Long> values) {
        if (values == null) return null;
        StringBuilder json = new StringBuilder("{");
        new TreeMap<>(values).forEach((day, minutes) -> {
            if (json.length() > 1) json.append(',');
            json.append('"').append(day).append("\":").append(minutes);
        });
        return json.append('}').toString();
    }

    public static Map<LocalDate, Long> decode(String json, long total) {
        if (json == null) return null;
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("expected date map");
            Map<LocalDate, Long> values = new TreeMap<>();
            var fields = root.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                LocalDate day = LocalDate.parse(field.getKey());
                JsonNode minutes = field.getValue();
                if (!day.toString().equals(field.getKey()) || !minutes.isIntegralNumber()
                        || !minutes.canConvertToLong()) throw new IllegalArgumentException("invalid date minutes");
                values.put(day, minutes.longValue());
            }
            return validated(values, total);
        } catch (Exception ex) {
            // Do not expose source JSON or parser details to users/logs.
            throw new IllegalArgumentException("invalid OA daily recognized minutes payload");
        }
    }
}
