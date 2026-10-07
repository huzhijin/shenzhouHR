package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.application.PunchCorrectionQuotaService.QuotaStatus;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.PunchSide;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.Status;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PunchCorrectionApplicationService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final PunchCorrectionRequestRepository repository;
    private final PunchCorrectionQuotaService quotaService;
    private final CurrentPrincipalProvider principalProvider;
    private final PeopleRepository peopleRepository;
    private final Clock clock;

    public PunchCorrectionApplicationService(
            PunchCorrectionRequestRepository repository,
            PunchCorrectionQuotaService quotaService,
            CurrentPrincipalProvider principalProvider,
            PeopleRepository peopleRepository,
            Clock clock) {
        this.repository = repository;
        this.quotaService = quotaService;
        this.principalProvider = principalProvider;
        this.peopleRepository = peopleRepository;
        this.clock = clock;
    }

    @Transactional
    public PunchCorrectionRequest submit(SubmitCommand command) {
        if (command == null) {
            throw invalid("补卡申请必填");
        }
        String employeeId = required(command.employeeId(), "员工", 36);
        LocalDate businessDate = command.businessDate();
        if (businessDate == null) {
            throw invalid("补卡日期必填");
        }
        PunchSide punchSide = command.punchSide();
        if (punchSide == null) {
            throw invalid("补卡方向必填");
        }
        String reason = required(command.reason(), "补卡原因", 500);
        Instant now = clock.instant();
        validateDate(businessDate, now);
        String principalId = principalProvider.currentPrincipalId();
        authorizeEmployee(
                principalId,
                CapabilityCodes.ATTENDANCE_PUNCH_CORRECTION_CREATE,
                employeeId,
                businessDate,
                now);

        repository.lockEmployee(employeeId);
        if (repository.isPunchExempt(employeeId, businessDate, now)) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "PUNCH_NOT_REQUIRED",
                    "您的岗位无需打卡");
        }
        if (!repository.isScheduledWorkDay(employeeId, businessDate, now)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PUNCH_CORRECTION_NOT_WORKDAY",
                    "只能为有效工作日补卡");
        }
        if (!quotaService.canRequestCorrection(
                employeeId, YearMonth.from(businessDate))) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "PUNCH_CORRECTION_QUOTA_EXHAUSTED",
                    "本月补卡次数已用完");
        }

        PunchCorrectionRequest request = PunchCorrectionRequest.pending(
                UUID.randomUUID().toString(),
                employeeId,
                businessDate,
                punchSide,
                reason,
                now,
                principalId);
        repository.insert(request);
        return request;
    }

    @Transactional(readOnly = true)
    public QuotaStatus quota(String employeeId, YearMonth month) {
        String normalizedEmployeeId = required(employeeId, "员工", 36);
        if (month == null) {
            throw invalid("月份必填");
        }
        Instant now = clock.instant();
        authorizeEmployee(
                principalProvider.currentPrincipalId(),
                CapabilityCodes.ATTENDANCE_PUNCH_CORRECTION_READ,
                normalizedEmployeeId,
                month.atEndOfMonth(),
                now);
        return quotaService.quota(normalizedEmployeeId, month);
    }

    @Transactional
    public PunchCorrectionRequest approve(
            String requestId, String reviewNotes) {
        String normalizedId = required(requestId, "补卡申请", 36);
        PunchCorrectionRequest current = repository.find(normalizedId)
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.NOT_FOUND,
                        "PUNCH_CORRECTION_NOT_FOUND",
                        "补卡申请不存在"));
        String reviewerId = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        authorizeEmployee(
                reviewerId,
                CapabilityCodes.ATTENDANCE_PUNCH_CORRECTION_APPROVE,
                current.employeeId(),
                current.businessDate(),
                now);
        if (current.status() != Status.PENDING) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "PUNCH_CORRECTION_NOT_PENDING",
                    "只有待审批补卡申请可以批准");
        }
        String normalizedNotes = reviewNotes == null
                || reviewNotes.isBlank() ? null : reviewNotes.trim();
        if (normalizedNotes != null && normalizedNotes.length() > 500) {
            throw invalid("审批备注长度超限");
        }
        if (!repository.approve(
                normalizedId, reviewerId, now, normalizedNotes)) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "PUNCH_CORRECTION_CONCURRENT_REVIEW",
                    "补卡申请已被其他审批操作处理",
                    true);
        }
        return current.approve(reviewerId, now, normalizedNotes);
    }

    private void authorizeEmployee(
            String principalId,
            String capability,
            String employeeId,
            LocalDate businessDate,
            Instant authorizationTime) {
        if (!peopleRepository.canAccessEmployee(
                principalId,
                capability,
                employeeId,
                businessDate,
                authorizationTime)) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED",
                    "无权访问该员工补卡信息");
        }
    }

    private void validateDate(LocalDate businessDate, Instant now) {
        LocalDate today = now.atZone(BUSINESS_ZONE).toLocalDate();
        if (businessDate.isAfter(today)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PUNCH_CORRECTION_FUTURE_DATE",
                    "不能为未来日期补卡");
        }
        YearMonth earliest = YearMonth.from(today).minusMonths(1);
        YearMonth requested = YearMonth.from(businessDate);
        if (requested.isBefore(earliest)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PUNCH_CORRECTION_DATE_TOO_OLD",
                    "只能为本月和上月补卡");
        }
    }

    private static String required(
            String value, String label, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw invalid(label + "必填");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw invalid(label + "长度超限");
        }
        return normalized;
    }

    private static ApiProblemException invalid(String message) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public record SubmitCommand(
            String employeeId,
            LocalDate businessDate,
            PunchSide punchSide,
            String reason) {
    }
}
