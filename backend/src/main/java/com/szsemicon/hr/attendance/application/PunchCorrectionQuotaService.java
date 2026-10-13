package com.szsemicon.hr.attendance.application;

import java.time.YearMonth;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PunchCorrectionQuotaService {

    public static final int MONTHLY_QUOTA = 1;

    private final PunchCorrectionRequestRepository repository;

    public PunchCorrectionQuotaService(
            PunchCorrectionRequestRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public boolean canRequestCorrection(String employeeId, YearMonth month) {
        return quota(employeeId, month).remainingQuota() > 0;
    }

    @Transactional(readOnly = true)
    public QuotaStatus quota(String employeeId, YearMonth month) {
        requireEmployee(employeeId);
        Objects.requireNonNull(month, "month");
        int used = Math.min(
                MONTHLY_QUOTA,
                Math.max(0, repository.countQuotaConsuming(
                        employeeId.trim(), month)));
        return new QuotaStatus(MONTHLY_QUOTA - used, used, MONTHLY_QUOTA);
    }

    private static void requireEmployee(String employeeId) {
        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("employeeId must not be blank");
        }
    }

    public record QuotaStatus(
            int remainingQuota, int usedQuota, int totalQuota) {

        public QuotaStatus {
            if (remainingQuota < 0
                    || usedQuota < 0
                    || totalQuota < 1
                    || remainingQuota + usedQuota != totalQuota) {
                throw new IllegalArgumentException("invalid quota status");
            }
        }
    }
}
