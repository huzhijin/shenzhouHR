package com.szsemicon.hr.attendance.calculation.application;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.AttendanceCloseSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.ClosePrecheckReport;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodStateSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.AttendanceResultDifference;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.RecalculationBatchRequest;
import java.time.Instant;
import java.util.List;

public final class AttendanceCalculationUseCases {

    private AttendanceCalculationUseCases() {
    }

    public interface CalculateAttendanceDay {

        DailyAttendanceResult calculate(
                RecalculationBatchRequest request,
                String employeeId,
                java.time.LocalDate businessDate,
                Instant knowledgeCutoff);
    }

    public interface CompareCalculationVersions {

        AttendanceResultDifference compare(
                String oldCalculationVersionId,
                String newCalculationVersionId,
                List<String> causalReferences);
    }

    public interface PrecheckAttendancePeriod {

        ClosePrecheckReport precheck(
                String periodId, long expectedVersion, String requestId);
    }

    public interface CloseAttendancePeriod {

        AttendanceCloseSnapshot close(
                String periodId,
                long expectedVersion,
                String precheckToken,
                String reason,
                String idempotencyKey);
    }

    public interface ReopenAttendancePeriod {

        PeriodStateSnapshot reopen(
                String periodId,
                long expectedVersion,
                String approvalReference,
                String reason,
                String idempotencyKey);
    }
}
