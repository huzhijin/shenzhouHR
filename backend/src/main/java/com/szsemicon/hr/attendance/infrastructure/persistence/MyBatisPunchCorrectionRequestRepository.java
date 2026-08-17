package com.szsemicon.hr.attendance.infrastructure.persistence;

import com.szsemicon.hr.attendance.application.PunchCorrectionRequestRepository;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.PunchSide;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.Status;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisPunchCorrectionRequestRepository
        implements PunchCorrectionRequestRepository {

    private final PunchCorrectionRequestMapper mapper;

    MyBatisPunchCorrectionRequestRepository(
            PunchCorrectionRequestMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void lockEmployee(String employeeId) {
        if (mapper.lockEmployee(employeeId) == null) {
            throw new ApiProblemException(
                    HttpStatus.NOT_FOUND,
                    "EMPLOYEE_NOT_FOUND",
                    "员工不存在");
        }
    }

    @Override
    public int countQuotaConsuming(
            String employeeId, YearMonth month) {
        return mapper.countQuotaConsuming(
                employeeId, month.atDay(1));
    }

    @Override
    public boolean isScheduledWorkDay(
            String employeeId,
            LocalDate businessDate,
            Instant knowledgeAsOf) {
        return mapper.isScheduledWorkDay(
                employeeId, businessDate, knowledgeAsOf);
    }

    @Override
    public boolean isPunchExempt(
            String employeeId,
            LocalDate businessDate,
            Instant knowledgeAsOf) {
        return mapper.isPunchExempt(employeeId, knowledgeAsOf);
    }

    @Override
    public void insert(PunchCorrectionRequest request) {
        mapper.insert(row(request));
    }

    @Override
    public Optional<PunchCorrectionRequest> find(String requestId) {
        return Optional.ofNullable(mapper.find(requestId)).map(this::domain);
    }

    @Override
    public boolean approve(
            String requestId,
            String reviewerId,
            Instant reviewedAt,
            String reviewNotes) {
        return mapper.approve(
                requestId, reviewerId, reviewedAt, reviewNotes) == 1;
    }

    private PunchCorrectionRows.RequestRow row(
            PunchCorrectionRequest request) {
        return new PunchCorrectionRows.RequestRow(
                request.requestId(),
                request.employeeId(),
                request.requestMonth().atDay(1),
                request.businessDate(),
                request.punchSide().name(),
                request.reason(),
                request.status().name(),
                request.requestedAt(),
                request.requestedBy(),
                request.reviewedAt(),
                request.reviewedBy(),
                request.reviewNotes());
    }

    private PunchCorrectionRequest domain(
            PunchCorrectionRows.RequestRow row) {
        return new PunchCorrectionRequest(
                row.requestId(),
                row.employeeId(),
                YearMonth.from(row.requestMonth()),
                row.businessDate(),
                PunchSide.valueOf(row.punchSide()),
                row.correctionReason(),
                Status.valueOf(row.status()),
                row.requestedAt(),
                row.requestedBy(),
                row.reviewedAt(),
                row.reviewedBy(),
                row.reviewNotes());
    }
}
