package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationInputSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;

public final class CanonicalAttendanceDigests {

    private CanonicalAttendanceDigests() {
    }

    public static String inputDigest(CalculationInputSnapshot snapshot) {
        throw new UnsupportedOperationException("RED: canonical input digest");
    }

    public static String resultDigest(DailyAttendanceResult result) {
        throw new UnsupportedOperationException("RED: semantic result digest");
    }
}
