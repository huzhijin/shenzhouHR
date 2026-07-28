package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.SimulationInput;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.SimulationResult;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendancePolicySimulationService {

    private final AttendanceConfigurationService configurationService;
    private final AttendancePolicyService policyService;
    private final AttendanceMonthlyExemptionUsageProvider usageProvider;
    private final CurrentCapabilityService capabilityService;
    private final Clock clock;

    public AttendancePolicySimulationService(
            AttendanceConfigurationService configurationService,
            AttendancePolicyService policyService,
            AttendanceMonthlyExemptionUsageProvider usageProvider,
            CurrentCapabilityService capabilityService,
            Clock clock) {
        this.configurationService = configurationService;
        this.policyService = policyService;
        this.usageProvider = usageProvider;
        this.capabilityService = capabilityService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SimulationResult> simulate(SimulationInput input) {
        capabilityService.require(CapabilityCodes.ATTENDANCE_SETUP_MANAGE_POLICY);
        Instant requestTime = clock.instant();
        if (input == null
                || input.employeeId() == null
                || input.employeeId().isBlank()
                || input.businessDate() == null
                || input.correctionAsOf() == null
                || input.correctionAsOf().isAfter(requestTime)) {
            throw AttendanceSetupRules.invalid(
                    "correctionAsOf 必须位于业务日开始与请求处理时刻之间");
        }
        var configuration = configurationService.resolve(
                input.employeeId(), input.businessDate(), input.correctionAsOf());
        if (!"RESOLVED".equals(configuration.status())
                || configuration.shiftVersion() == null) {
            throw AttendanceSetupRules.conflict(
                    configuration.status(),
                    configuration.explanation());
        }
        ZoneId zone = ZoneId.of(configuration.shiftVersion().timeZone());
        var businessDayStart = input.businessDate().atStartOfDay(zone).toInstant();
        if (input.correctionAsOf().isBefore(businessDayStart)) {
            throw AttendanceSetupRules.invalid(
                    "correctionAsOf 必须位于业务日开始与请求处理时刻之间");
        }
        YearMonth naturalMonth = YearMonth.from(input.businessDate());
        var usage = usageProvider.findUsage(
                input.employeeId(), naturalMonth,
                input.correctionAsOf());
        if (usage == null
                || !input.employeeId().equals(usage.employeeId())
                || !naturalMonth.equals(usage.naturalMonth())
                || usage.usedCount() < 0
                || usage.provenance() == null
                || usage.provenance().isBlank()
                || usage.knowledgeTime() == null) {
            throw AttendanceSetupRules.conflict(
                    "USAGE_PROJECTION_INVALID",
                    "权威月度使用投影身份、计数或来源无效");
        }
        if (usage.knowledgeTime().isAfter(input.correctionAsOf())) {
            throw AttendanceSetupRules.conflict(
                    "USAGE_KNOWLEDGE_AFTER_CORRECTION",
                    "权威月度使用投影晚于 correctionAsOf");
        }
        return policyService.simulateResolved(input, configuration, usage);
    }
}
