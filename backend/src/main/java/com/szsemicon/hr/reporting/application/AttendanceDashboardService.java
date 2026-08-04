package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.CompanyOption;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.DashboardSnapshot;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceDashboardService {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principalProvider;
    private final AttendanceDashboardRepository repository;
    private final Clock clock;

    public AttendanceDashboardService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceDashboardRepository repository,
            Clock clock) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public QueryResult query(String companyId) {
        String requestedCompanyId = normalizeCompanyId(companyId);
        capabilities.require(CapabilityCodes.ATTENDANCE_DASHBOARD_READ);

        var authorizationTime = clock.instant();
        LocalDate businessDate =
                authorizationTime.atZone(BUSINESS_ZONE).toLocalDate();
        YearMonth period = YearMonth.from(businessDate);
        String principalId = principalProvider.currentPrincipalId();
        List<CompanyOption> companies =
                repository.listAuthorizedCompanies(
                        principalId, period, authorizationTime);
        if (companies.isEmpty()) {
            throw projectionNotReady();
        }
        if (requestedCompanyId == null && companies.size() > 1) {
            return new CompanySelection(businessDate, companies);
        }

        CompanyOption selected = requestedCompanyId == null
                ? companies.getFirst()
                : companies.stream()
                        .filter(company -> company.companyId()
                                .equals(requestedCompanyId))
                        .findFirst()
                        .orElseThrow(
                                AttendanceDashboardService
                                        ::projectionNotReady);
        DashboardSnapshot snapshot =
                repository.loadAuthorizedToday(
                                principalId,
                                selected.companyId(),
                                businessDate,
                                authorizationTime)
                        .orElseThrow(
                                AttendanceDashboardService
                                        ::projectionNotReady);
        if (!selected.companyId().equals(snapshot.companyId())) {
            throw projectionNotReady();
        }
        var businessDayStart =
                businessDate.atStartOfDay(BUSINESS_ZONE).toInstant();
        if (snapshot.dataAsOf().isBefore(businessDayStart)) {
            throw projectionNotReady();
        }
        List<String> allowedActions = capabilities.currentCapabilities()
                .contains(CapabilityCodes.ATTENDANCE_REPORT_READ)
                        ? List.of("DASHBOARD_DRILL_DOWN")
                        : List.of();
        return new Ready(
                businessDate,
                selected,
                companies,
                snapshot,
                allowedActions);
    }

    private static String normalizeCompanyId(String companyId) {
        if (companyId == null) {
            return null;
        }
        String normalized = companyId.trim();
        if (normalized.isEmpty()
                || normalized.length() > 36
                || !normalized.equals(companyId)) {
            throw new IllegalArgumentException(
                    "companyId must be a trimmed non-blank value"
                            + " of at most 36 characters");
        }
        return normalized;
    }

    private static ApiProblemException projectionNotReady() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "ATTENDANCE_DASHBOARD_PROJECTION_NOT_READY",
                "今日考勤结果尚未生成或发布",
                true);
    }

    public sealed interface QueryResult
            permits Ready, CompanySelection {

        LocalDate businessDate();

        List<CompanyOption> companies();
    }

    public record Ready(
            LocalDate businessDate,
            CompanyOption selectedCompany,
            List<CompanyOption> companies,
            DashboardSnapshot snapshot,
            List<String> allowedActions)
            implements QueryResult {

        public Ready {
            Objects.requireNonNull(businessDate, "businessDate");
            Objects.requireNonNull(
                    selectedCompany, "selectedCompany");
            companies = List.copyOf(
                    Objects.requireNonNull(companies, "companies"));
            Objects.requireNonNull(snapshot, "snapshot");
            allowedActions = List.copyOf(Objects.requireNonNull(
                    allowedActions, "allowedActions"));
        }
    }

    public record CompanySelection(
            LocalDate businessDate,
            List<CompanyOption> companies)
            implements QueryResult {

        public CompanySelection {
            Objects.requireNonNull(businessDate, "businessDate");
            companies = List.copyOf(
                    Objects.requireNonNull(companies, "companies"));
            if (companies.size() < 2) {
                throw new IllegalArgumentException(
                        "company selection requires multiple companies");
            }
        }
    }
}
