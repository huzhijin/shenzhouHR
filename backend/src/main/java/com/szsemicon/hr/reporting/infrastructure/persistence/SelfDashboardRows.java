package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository;
import java.time.Instant;
import java.time.LocalDate;

final class SelfDashboardRows {

    private SelfDashboardRows() {
    }

    record AuthorizationRow(String employeeId, String companyId) {

        SelfAttendanceDashboardRepository.AuthorizedSelf toDomain() {
            return new SelfAttendanceDashboardRepository.AuthorizedSelf(
                    employeeId, companyId);
        }
    }

    record ProjectionRow(
            String projectionId,
            String companyId,
            String projectionVersion,
            String sourceVersionsJson,
            Instant dataAsOf,
            String periodState) {
    }

    record DailyRow(
            LocalDate businessDate,
            String shiftLabel,
            long scheduledMinutes,
            long confirmedMinutes,
            long recognizedOvertimeMinutes,
            long leaveMinutes,
            Instant firstPunchAt,
            Instant lastPunchAt) {

        SelfAttendanceDashboardRepository.DailyFact toDomain() {
            return new SelfAttendanceDashboardRepository.DailyFact(
                    businessDate,
                    shiftLabel,
                    scheduledMinutes,
                    confirmedMinutes,
                    recognizedOvertimeMinutes,
                    leaveMinutes,
                    firstPunchAt,
                    lastPunchAt);
        }
    }

    record DailyIssueCountRow(
            LocalDate businessDate, long issueCount) {

        SelfAttendanceDashboardRepository.DailyIssueCount toDomain() {
            return new SelfAttendanceDashboardRepository.DailyIssueCount(
                    businessDate, issueCount);
        }
    }

    record TodayIssueLabelRow(String issueLabel) {
    }

    record ExceptionTypeCountRow(String type, long count) {

        SelfAttendanceDashboardRepository.ExceptionTypeCount toDomain() {
            return new SelfAttendanceDashboardRepository
                    .ExceptionTypeCount(type, count);
        }
    }

    record RecentExceptionRow(
            LocalDate businessDate,
            String type,
            String severity,
            String state,
            long minutes,
            String safeEvidenceSummary) {

        SelfAttendanceDashboardRepository.RecentException toDomain() {
            return new SelfAttendanceDashboardRepository.RecentException(
                    businessDate,
                    type,
                    severity,
                    state,
                    minutes,
                    safeEvidenceSummary);
        }
    }
}
