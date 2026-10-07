package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository;
import java.time.Instant;
import java.time.LocalDate;

final class DashboardRows {

    private DashboardRows() {
    }

    record CompanyRow(String companyId, String companyName) {

        AttendanceDashboardRepository.CompanyOption toDomain() {
            return new AttendanceDashboardRepository.CompanyOption(
                    companyId, companyName);
        }
    }

    record ProjectionRow(
            String projectionId,
            String companyId,
            String projectionVersion,
            String periodState,
            String sourceVersionsJson,
            Instant dataAsOf) {
    }

    record ScopeRow(
            String scopeId,
            String scopeType,
            String companyId,
            String organizationId,
            boolean includeDescendants,
            String principalEmployeeId) {
    }

    record SummaryRow(
            long unresolvedCount,
            long affectedEmployeeCount,
            long blockingCount) {

        AttendanceDashboardRepository.ExceptionSummary toDomain() {
            return new AttendanceDashboardRepository.ExceptionSummary(
                    unresolvedCount,
                    affectedEmployeeCount,
                    blockingCount);
        }
    }

    record DailyTrendRow(
            LocalDate businessDate,
            long exceptionCount,
            long blockingCount,
            long affectedEmployeeCount) {

        AttendanceDashboardRepository.DailyTrendPoint toDomain() {
            return new AttendanceDashboardRepository.DailyTrendPoint(
                    businessDate,
                    exceptionCount,
                    blockingCount,
                    affectedEmployeeCount);
        }
    }

    record SeverityDistributionRow(String severity, long count) {

        AttendanceDashboardRepository.SeverityDistributionItem toDomain() {
            return new AttendanceDashboardRepository
                    .SeverityDistributionItem(severity, count);
        }
    }

    record TypeDistributionRow(String exceptionType, long count) {

        AttendanceDashboardRepository.TypeDistributionItem toDomain() {
            return new AttendanceDashboardRepository.TypeDistributionItem(
                    exceptionType, count);
        }
    }

    record OrganizationRankingRow(
            String organizationName,
            long exceptionCount,
            long blockingCount) {

        AttendanceDashboardRepository.OrganizationRankingItem toDomain() {
            return new AttendanceDashboardRepository
                    .OrganizationRankingItem(
                            organizationName,
                            exceptionCount,
                            blockingCount);
        }
    }

    record ExceptionRow(
            String exceptionReference,
            String employeeNumber,
            String employeeName,
            String organizationName,
            LocalDate businessDate,
            String exceptionType,
            String severity,
            String state,
            long exceptionMinutes,
            String evidenceSummary) {

        AttendanceDashboardRepository.ExceptionItem toDomain() {
            return new AttendanceDashboardRepository.ExceptionItem(
                    exceptionReference,
                    employeeNumber,
                    employeeName,
                    organizationName,
                    businessDate,
                    exceptionType,
                    severity,
                    state,
                    exceptionMinutes,
                    evidenceSummary);
        }
    }
}
