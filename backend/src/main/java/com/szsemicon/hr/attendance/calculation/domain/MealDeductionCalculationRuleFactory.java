package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.MealDeductionRule;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.attendance.domain.CalendarModels.DayType;
import com.szsemicon.hr.attendance.domain.MealDeductionPolicyResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts one immutable attendance-group meal policy snapshot into the
 * calculation-domain rules for a specific business day.
 */
public final class MealDeductionCalculationRuleFactory {

    private MealDeductionCalculationRuleFactory() {
    }

    public static List<MealDeductionRule> resolve(
            Map<String, Object> parameters,
            DayType calendarDayType,
            LocalDate businessDate,
            ZoneId businessZone) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(calendarDayType, "calendarDayType");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(businessZone, "businessZone");
        if (!Boolean.TRUE.equals(parameters.get("enabled"))
                || !appliesTo(parameters, calendarDayType)) {
            return List.of();
        }
        return MealDeductionPolicyResolver.resolveAll(
                        parameters, calendarDayType, businessDate)
                .stream()
                .map(resolved -> new MealDeductionRule(
                        resolved.windowId(),
                        interval(
                                businessDate,
                                resolved.mealWindowStart(),
                                resolved.mealWindowEnd(),
                                businessZone),
                        resolved.deductionMinutes(),
                        resolved.triggerMinutes(),
                        true))
                .toList();
    }

    private static boolean appliesTo(
            Map<String, Object> parameters, DayType dayType) {
        Object value = parameters.get("applicableDayTypes");
        return value instanceof List<?> values
                && values.stream().allMatch(String.class::isInstance)
                && values.contains(dayType.name());
    }

    private static TimeInterval interval(
            LocalDate businessDate,
            java.time.LocalTime start,
            java.time.LocalTime end,
            ZoneId zone) {
        LocalDateTime from = businessDate.atTime(start);
        LocalDateTime to = businessDate.atTime(end);
        if (!to.isAfter(from)) {
            to = to.plusDays(1);
        }
        return new TimeInterval(
                resolveLocalInstant(from, zone, true),
                resolveLocalInstant(to, zone, false));
    }

    private static Instant resolveLocalInstant(
            LocalDateTime localDateTime,
            ZoneId zone,
            boolean startBoundary) {
        List<ZoneOffset> offsets =
                zone.getRules().getValidOffsets(localDateTime);
        if (offsets.size() == 1) {
            return localDateTime.toInstant(offsets.getFirst());
        }
        if (offsets.size() == 2) {
            Instant first = localDateTime.toInstant(offsets.get(0));
            Instant second = localDateTime.toInstant(offsets.get(1));
            return startBoundary
                    ? (first.isBefore(second) ? first : second)
                    : (first.isAfter(second) ? first : second);
        }
        ZoneOffsetTransition transition =
                zone.getRules().getTransition(localDateTime);
        if (transition == null || !transition.isGap()) {
            throw new IllegalArgumentException(
                    "meal window cannot be resolved in the business zone");
        }
        return transition.getInstant();
    }
}
