package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationInputSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;

public final class DeterministicAttendanceCalculator {

    public DailyAttendanceResult calculate(
            String calculationVersionId,
            CalculationInputSnapshot snapshot) {
        throw new UnsupportedOperationException(
                "RED: deterministic attendance calculation");
    }
}
