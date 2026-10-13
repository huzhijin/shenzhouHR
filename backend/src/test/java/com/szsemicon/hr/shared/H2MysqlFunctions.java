package com.szsemicon.hr.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class H2MysqlFunctions {
    private H2MysqlFunctions() {}

    public static String format(BigDecimal value, Integer decimals) {
        if (value == null || decimals == null) {
            return null;
        }
        BigDecimal scaled = value.setScale(decimals, RoundingMode.HALF_UP);
        DecimalFormat df = new DecimalFormat();
        df.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.US));
        df.setMinimumFractionDigits(decimals);
        df.setMaximumFractionDigits(decimals);
        df.setGroupingUsed(true);
        return df.format(scaled);
    }
}
