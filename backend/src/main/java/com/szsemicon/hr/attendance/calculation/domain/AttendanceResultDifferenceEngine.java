package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.AttendanceResultDifference;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.CalculationVersion;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.DifferenceCategory;
import java.util.List;

public final class AttendanceResultDifferenceEngine {

    public AttendanceResultDifference compare(
            String differenceId,
            CalculationVersion oldVersion,
            CalculationVersion newVersion,
            List<DifferenceCategory> categories,
            List<String> causalReferences) {
        throw new UnsupportedOperationException(
                "RED: attendance result difference");
    }
}
