package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

public interface PunchCorrectionRequestRepository {

    void lockEmployee(String employeeId);

    int countQuotaConsuming(String employeeId, YearMonth month);

    boolean isScheduledWorkDay(
            String employeeId, LocalDate businessDate, Instant knowledgeAsOf);

    boolean isPunchExempt(
            String employeeId, LocalDate businessDate, Instant knowledgeAsOf);

    void insert(PunchCorrectionRequest request);

    Optional<PunchCorrectionRequest> find(String requestId);

    boolean approve(
            String requestId,
            String reviewerId,
            Instant reviewedAt,
            String reviewNotes);
}
