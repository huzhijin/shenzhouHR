package com.szsemicon.hr.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Field clocks for overnight-return OT. 金玉亮 punches are from the 2026-08
 * Deli dump. 王凯祥 8/5–7 were not in that dump; the four-punch series is the
 * synthetic home-and-return pattern used when only two clocks exist.
 *
 * 8/10 is same-calendar evening off. Overnight leaving is 8/12 00:14
 * attributed to 8/11.
 */
public final class OvernightReturnFixtures {

    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    public static final String JIN_YULIANG = "SZST0398";
    public static final String WANG_KAIXIANG = "SZST0431";

    public static final LocalDate AUG_10 = LocalDate.of(2026, 8, 10);
    public static final LocalDate AUG_11 = LocalDate.of(2026, 8, 11);
    public static final LocalDate AUG_12 = LocalDate.of(2026, 8, 12);
    public static final LocalDate AUG_5 = LocalDate.of(2026, 8, 5);
    public static final LocalDate AUG_6 = LocalDate.of(2026, 8, 6);

    private OvernightReturnFixtures() {
    }

    public static Instant jinAug10On() {
        return shanghai(AUG_10.atTime(8, 26, 42));
    }

    public static Instant jinAug10Off() {
        return shanghai(AUG_10.atTime(19, 1, 30));
    }

    public static Instant jinAug11On() {
        return shanghai(AUG_11.atTime(8, 26, 46));
    }

    public static Instant jinAug12OvernightOff() {
        return shanghai(AUG_12.atTime(0, 14, 58));
    }

    public static Instant jinAug12On() {
        return shanghai(AUG_12.atTime(8, 24, 48));
    }

    public static Instant wangDayOn() {
        return shanghai(AUG_5.atTime(8, 30));
    }

    public static Instant wangGoHome() {
        return shanghai(AUG_5.atTime(17, 30));
    }

    public static Instant wangReturn() {
        return shanghai(AUG_5.atTime(21, 0));
    }

    public static Instant wangOvernightOff() {
        return shanghai(AUG_6.atTime(2, 0));
    }

    public static Instant shanghai(LocalDateTime dateTime) {
        return dateTime.atZone(SHANGHAI).toInstant();
    }
}
