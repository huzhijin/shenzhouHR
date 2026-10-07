package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.infrastructure.persistence.HrPunchAdjustmentMapper;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HrPunchAdjustmentService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principals;
    private final HrPunchAdjustmentMapper mapper;
    private final AttendanceReportQueryService reports;
    private final Clock clock;

    public HrPunchAdjustmentService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principals,
            HrPunchAdjustmentMapper mapper,
            AttendanceReportQueryService reports,
            Clock clock) {
        this.capabilities = capabilities;
        this.principals = principals;
        this.mapper = mapper;
        this.reports = reports;
        this.clock = clock;
    }

    @Transactional
    public SavedAdjustment save(SaveCommand command) {
        capabilities.require(CapabilityCodes.ATTENDANCE_ADJUST_MANAGE);
        if (command == null) {
            throw invalid("裁定内容必填");
        }
        String employeeId = required(command.employeeId(), "员工");
        LocalDate businessDate = command.businessDate();
        if (businessDate == null) {
            throw invalid("考勤日期必填");
        }
        Instant onDutyAt = command.onDutyAt();
        Instant offDutyAt = command.offDutyAt();
        Integer overtimeMinutes = overtimeMinutes(command.overtimeHours());
        String cleared = clearedTypes(command.clearedExceptionTypes());
        String dayTypes = dayTypes(command.dayTypes());
        if (onDutyAt == null && offDutyAt == null
                && overtimeMinutes == null
                && (cleared == null || cleared.isBlank())
                && (dayTypes == null || dayTypes.isBlank())) {
            throw invalid("请填写上班或下班时刻、加班小时、当天类型或取消的异常");
        }
        String reason = required(command.reason(), "原因");
        if (reason.length() > 500) {
            throw invalid("原因长度超限");
        }
        String companyId = mapper.findEmployeeCompanyId(employeeId);
        if (companyId == null || companyId.isBlank()) {
            throw new ApiProblemException(
                    HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND", "员工不存在");
        }
        if (command.companyId() != null
                && !command.companyId().isBlank()
                && !companyId.equals(command.companyId())) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "ATTENDANCE_REPORT_SCOPE_NOT_AVAILABLE",
                    "员工不属于所选公司");
        }
        Instant now = clock.instant();
        Instant dataAsOf = now.truncatedTo(ChronoUnit.MICROS);
        if (dataAsOf.isBefore(now)) {
            dataAsOf = dataAsOf.plus(1, ChronoUnit.MICROS);
        }
        String adjustmentId = UUID.randomUUID().toString();
        mapper.insert(
                adjustmentId,
                companyId,
                employeeId,
                businessDate,
                onDutyAt,
                offDutyAt,
                reason,
                principals.currentPrincipalId(),
                now,
                overtimeMinutes,
                cleared,
                dayTypes);
        if (command.recalculate()) {
            reports.recalculateEmployeeDays(
                    YearMonth.from(businessDate),
                    companyId,
                    employeeId,
                    businessDate,
                    dataAsOf);
        }
        return new SavedAdjustment(adjustmentId, companyId, employeeId, businessDate);
    }

    @Transactional
    public List<SavedAdjustment> saveAll(List<SaveCommand> commands) {
        capabilities.require(CapabilityCodes.ATTENDANCE_ADJUST_MANAGE);
        if (commands == null || commands.isEmpty()) {
            throw invalid("裁定列表不能为空");
        }
        if (commands.size() > 300) {
            throw invalid("单次最多 300 条人事调整");
        }
        List<SavedAdjustment> saved = new ArrayList<>();
        for (SaveCommand command : commands) {
            saved.add(save(command.withoutRecalculate()));
        }
        return List.copyOf(saved);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw invalid(label + "必填");
        }
        return value.trim();
    }

    private static ApiProblemException invalid(String message) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static Integer overtimeMinutes(BigDecimal hours) {
        if (hours == null) {
            return null;
        }
        BigDecimal scaled = hours.setScale(1, RoundingMode.HALF_UP);
        if (scaled.compareTo(BigDecimal.ZERO) < 0
                || scaled.compareTo(BigDecimal.valueOf(24)) > 0) {
            throw invalid("加班小时须在 0 到 24 之间");
        }
        BigDecimal doubled = scaled.multiply(BigDecimal.valueOf(2));
        if (doubled.stripTrailingZeros().scale() > 0) {
            throw invalid("加班小时须按 0.5 小时填写");
        }
        return scaled.multiply(BigDecimal.valueOf(60)).intValue();
    }

    private static String clearedTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            return null;
        }
        Set<String> allowed = Set.of(
                "LATE",
                "EARLY_DEPARTURE",
                "MISSING_PUNCH",
                "ABSENCE",
                "OVERTIME_DOCUMENT_MISSING_OR_LATE",
                "OVERTIME_FORM_BEYOND_LAST_PUNCH",
                "LONG_PUNCH_SPAN_REVIEW");
        Set<String> cleaned = new LinkedHashSet<>();
        for (String type : types) {
            if (type == null || type.isBlank()) {
                continue;
            }
            String normalized = type.trim().toUpperCase(Locale.ROOT);
            if (!allowed.contains(normalized)) {
                throw invalid("不支持取消的异常类型");
            }
            cleaned.add(normalized);
        }
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    private static String dayTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            return null;
        }
        Set<String> allowed = Set.of(
                "OVERTIME",
                "TRIP",
                "OUTING",
                "TIME_OFF",
                "ANNUAL_LEAVE",
                "PERSONAL_LEAVE",
                "SICK_LEAVE",
                "MARRIAGE_LEAVE",
                "BEREAVEMENT_LEAVE",
                "MATERNITY_LEAVE",
                "PATERNITY_LEAVE",
                "BREASTFEEDING_LEAVE",
                "WORK_INJURY_LEAVE",
                "ABSENCE",
                "LATE",
                "EARLY_DEPARTURE",
                "MISSING_PUNCH");
        Set<String> cleaned = new LinkedHashSet<>();
        for (String type : types) {
            if (type == null || type.isBlank()) {
                continue;
            }
            String normalized = type.trim().toUpperCase(Locale.ROOT);
            if (!allowed.contains(normalized)) {
                throw invalid("不支持的当天类型");
            }
            cleaned.add(normalized);
        }
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    public record SaveCommand(
            String companyId,
            String employeeId,
            LocalDate businessDate,
            Instant onDutyAt,
            Instant offDutyAt,
            String reason,
            BigDecimal overtimeHours,
            List<String> clearedExceptionTypes,
            List<String> dayTypes,
            boolean recalculate) {
        public SaveCommand(
                String companyId,
                String employeeId,
                LocalDate businessDate,
                Instant onDutyAt,
                Instant offDutyAt,
                String reason,
                BigDecimal overtimeHours,
                List<String> clearedExceptionTypes) {
            this(
                    companyId,
                    employeeId,
                    businessDate,
                    onDutyAt,
                    offDutyAt,
                    reason,
                    overtimeHours,
                    clearedExceptionTypes,
                    List.of(),
                    true);
        }

        public SaveCommand(
                String companyId,
                String employeeId,
                LocalDate businessDate,
                Instant onDutyAt,
                Instant offDutyAt,
                String reason,
                BigDecimal overtimeHours,
                List<String> clearedExceptionTypes,
                List<String> dayTypes) {
            this(
                    companyId,
                    employeeId,
                    businessDate,
                    onDutyAt,
                    offDutyAt,
                    reason,
                    overtimeHours,
                    clearedExceptionTypes,
                    dayTypes,
                    true);
        }

        SaveCommand withoutRecalculate() {
            return new SaveCommand(
                    companyId,
                    employeeId,
                    businessDate,
                    onDutyAt,
                    offDutyAt,
                    reason,
                    overtimeHours,
                    clearedExceptionTypes,
                    dayTypes,
                    false);
        }
    }

    public record SavedAdjustment(
            String adjustmentId,
            String companyId,
            String employeeId,
            LocalDate businessDate) {
    }
}
