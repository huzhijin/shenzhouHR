package com.szsemicon.hr.attendance.domain;

import com.szsemicon.hr.attendance.domain.CalendarModels.DayType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Selects the effective meal-deduction values for one business day.
 *
 * <p>Natural Saturday/Sunday overrides are deliberately subordinate to the
 * authoritative work-calendar day type. A weekend date marked as a special
 * workday therefore uses only the base dinner, while a public holiday uses
 * its own optional lunch and dinner overrides. Older public-holiday policies
 * without the dinner override continue to use the base dinner; neither case
 * may inherit the natural Saturday/Sunday overrides.</p>
 */
public final class MealDeductionPolicyResolver {

    private static final List<String> SATURDAY_DINNER_KEYS = List.of(
            "saturdayMealWindowStart",
            "saturdayMealWindowEnd",
            "saturdayDeductionMinutes",
            "saturdayTriggerMinutes");
    private static final List<String> SUNDAY_DINNER_KEYS = List.of(
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
    private static final List<String> PUBLIC_HOLIDAY_DINNER_KEYS = List.of(
            "publicHolidayMealWindowStart",
            "publicHolidayMealWindowEnd",
            "publicHolidayDeductionMinutes",
            "publicHolidayTriggerMinutes");

    private MealDeductionPolicyResolver() {
    }

    public static ResolvedMealDeduction resolve(
            Map<String, Object> parameters,
            DayType calendarDayType,
            LocalDate businessDate) {
        return resolveAll(parameters, calendarDayType, businessDate).stream()
                .filter(value -> value.mealType() == MealType.DINNER)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "dinner meal deduction must always resolve"));
    }

    /**
     * Resolves the ordered, unique meal windows for a business day.
     *
     * <p>The required legacy fields continue to represent the base dinner.
     * Optional lunch groups add a second independently evaluated window. This
     * lets old published JSON keep exactly one dinner deduction while newer
     * weekend and public-holiday versions can deduct lunch and dinner once
     * each.</p>
     */
    public static List<ResolvedMealDeduction> resolveAll(
            Map<String, Object> parameters,
            DayType calendarDayType,
            LocalDate businessDate) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(calendarDayType, "calendarDayType");
        Objects.requireNonNull(businessDate, "businessDate");

        validateGroupShape(
                parameters, SATURDAY_DINNER_KEYS, "saturday dinner");
        validateGroupShape(
                parameters, SUNDAY_DINNER_KEYS, "sunday dinner");
        validateGroupShape(
                parameters, SATURDAY_LUNCH_KEYS, "saturday lunch");
        validateGroupShape(
                parameters, SUNDAY_LUNCH_KEYS, "sunday lunch");
        validateGroupShape(
                parameters, PUBLIC_HOLIDAY_LUNCH_KEYS, "public holiday lunch");
        validateGroupShape(
                parameters, PUBLIC_HOLIDAY_DINNER_KEYS, "public holiday dinner");

        ArrayList<ResolvedMealDeduction> resolved = new ArrayList<>(2);
        DayOfWeek naturalDayOfWeek = businessDate.getDayOfWeek();
        if (calendarDayType == DayType.WEEKEND) {
            if (naturalDayOfWeek == DayOfWeek.SATURDAY
                    && hasCompleteGroup(parameters, SATURDAY_LUNCH_KEYS)) {
                resolved.add(read(
                        parameters,
                        "SATURDAY_LUNCH",
                        MealType.LUNCH,
                        Source.SATURDAY_LUNCH,
                        "saturdayLunchWindowStart",
                        "saturdayLunchWindowEnd",
                        "saturdayLunchDeductionMinutes",
                        "saturdayLunchTriggerMinutes"));
            } else if (naturalDayOfWeek == DayOfWeek.SUNDAY
                    && hasCompleteGroup(parameters, SUNDAY_LUNCH_KEYS)) {
                resolved.add(read(
                        parameters,
                        "SUNDAY_LUNCH",
                        MealType.LUNCH,
                        Source.SUNDAY_LUNCH,
                        "sundayLunchWindowStart",
                        "sundayLunchWindowEnd",
                        "sundayLunchDeductionMinutes",
                        "sundayLunchTriggerMinutes"));
            }
        } else if (calendarDayType == DayType.PUBLIC_HOLIDAY
                && hasCompleteGroup(parameters, PUBLIC_HOLIDAY_LUNCH_KEYS)) {
            resolved.add(read(
                    parameters,
                    "PUBLIC_HOLIDAY_LUNCH",
                    MealType.LUNCH,
                    Source.PUBLIC_HOLIDAY_LUNCH,
                    "publicHolidayLunchWindowStart",
                    "publicHolidayLunchWindowEnd",
                    "publicHolidayLunchDeductionMinutes",
                    "publicHolidayLunchTriggerMinutes"));
        }

        Prefix dinnerPrefix = selectedDinnerPrefix(
                parameters, calendarDayType, businessDate.getDayOfWeek());
        resolved.add(read(
                parameters,
                dinnerPrefix.windowId(),
                MealType.DINNER,
                dinnerPrefix.source(),
                dinnerPrefix.key("mealWindowStart"),
                dinnerPrefix.key("mealWindowEnd"),
                dinnerPrefix.key("deductionMinutes"),
                dinnerPrefix.key("triggerMinutes")));
        return List.copyOf(resolved);
    }

    private static Prefix selectedDinnerPrefix(
            Map<String, Object> parameters,
            DayType calendarDayType,
            DayOfWeek naturalDayOfWeek) {
        if (calendarDayType == DayType.PUBLIC_HOLIDAY) {
            return hasCompleteGroup(parameters, PUBLIC_HOLIDAY_DINNER_KEYS)
                    ? Prefix.PUBLIC_HOLIDAY
                    : Prefix.BASE;
        }
        if (calendarDayType != DayType.WEEKEND) {
            return Prefix.BASE;
        }
        if (naturalDayOfWeek == DayOfWeek.SATURDAY
                && hasCompleteGroup(parameters, SATURDAY_DINNER_KEYS)) {
            return Prefix.SATURDAY;
        }
        if (naturalDayOfWeek == DayOfWeek.SUNDAY
                && hasCompleteGroup(parameters, SUNDAY_DINNER_KEYS)) {
            return Prefix.SUNDAY;
        }
        return Prefix.BASE;
    }

    private static ResolvedMealDeduction read(
            Map<String, Object> parameters,
            String windowId,
            MealType mealType,
            Source source,
            String startKey,
            String endKey,
            String deductionKey,
            String triggerKey) {
        return new ResolvedMealDeduction(
                windowId,
                mealType,
                localTime(parameters, startKey),
                localTime(parameters, endKey),
                integer(parameters, deductionKey),
                integer(parameters, triggerKey),
                source);
    }

    private static void validateGroupShape(
            Map<String, Object> parameters,
            List<String> groupKeys,
            String groupName) {
        long supplied = groupKeys.stream().filter(parameters::containsKey).count();
        if (supplied != 0 && supplied != groupKeys.size()) {
            throw new IllegalArgumentException(
                    groupName + " meal override must be absent or complete");
        }
    }

    private static boolean hasCompleteGroup(
            Map<String, Object> parameters, List<String> groupKeys) {
        return groupKeys.stream().allMatch(parameters::containsKey);
    }

    private static LocalTime localTime(
            Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a local time");
        }
        return LocalTime.parse(text);
    }

    private static int integer(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        try {
            return new java.math.BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer", exception);
        }
    }

    private enum Prefix {
        BASE("", "BASE_DINNER", Source.BASE),
        SATURDAY(
                "saturday",
                "SATURDAY_DINNER",
                Source.SATURDAY_OVERRIDE),
        SUNDAY("sunday", "SUNDAY_DINNER", Source.SUNDAY_OVERRIDE),
        PUBLIC_HOLIDAY(
                "publicHoliday",
                "PUBLIC_HOLIDAY_DINNER",
                Source.PUBLIC_HOLIDAY_OVERRIDE);

        private final String value;
        private final String windowId;
        private final Source source;

        Prefix(String value, String windowId, Source source) {
            this.value = value;
            this.windowId = windowId;
            this.source = source;
        }

        String key(String baseKey) {
            if (this == BASE) {
                return baseKey;
            }
            return value + Character.toUpperCase(baseKey.charAt(0))
                    + baseKey.substring(1);
        }

        Source source() {
            return source;
        }

        String windowId() {
            return windowId;
        }
    }

    public enum Source {
        BASE("基础参数"),
        SATURDAY_OVERRIDE("周六覆盖"),
        SUNDAY_OVERRIDE("周日覆盖"),
        SATURDAY_LUNCH("周六午餐"),
        SUNDAY_LUNCH("周日午餐"),
        PUBLIC_HOLIDAY_OVERRIDE("法定节假日覆盖"),
        PUBLIC_HOLIDAY_LUNCH("法定节假日午餐");

        private final String description;

        Source(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public enum MealType {
        LUNCH("午餐"),
        DINNER("晚餐");

        private final String description;

        MealType(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public record ResolvedMealDeduction(
            String windowId,
            MealType mealType,
            LocalTime mealWindowStart,
            LocalTime mealWindowEnd,
            int deductionMinutes,
            int triggerMinutes,
            Source source) {

        public ResolvedMealDeduction {
            if (windowId == null || windowId.isBlank()) {
                throw new IllegalArgumentException(
                        "meal window id must not be blank");
            }
            Objects.requireNonNull(mealType, "mealType");
            Objects.requireNonNull(mealWindowStart, "mealWindowStart");
            Objects.requireNonNull(mealWindowEnd, "mealWindowEnd");
            Objects.requireNonNull(source, "source");
            if (mealWindowStart.equals(mealWindowEnd)) {
                throw new IllegalArgumentException(
                        "meal window start and end must differ");
            }
            if (deductionMinutes < 0 || triggerMinutes < 0) {
                throw new IllegalArgumentException(
                        "meal deduction values must be non-negative");
            }
        }
    }
}
