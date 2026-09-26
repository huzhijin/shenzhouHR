package com.szsemicon.hr.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.application.PunchCorrectionApplicationService.SubmitCommand;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.PunchSide;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.Status;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PunchCorrectionApplicationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-17T01:00:00Z");

    private InMemoryRepository repository;
    private PeopleRepository peopleRepository;
    private PunchCorrectionApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepository();
        PunchCorrectionQuotaService quota =
                new PunchCorrectionQuotaService(repository);
        peopleRepository = mock(PeopleRepository.class);
        when(peopleRepository.canAccessEmployee(
                any(), any(), any(), any(), any())).thenReturn(true);
        service = new PunchCorrectionApplicationService(
                repository,
                quota,
                () -> "principal-001",
                peopleRepository,
                Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void firstCorrectionInMonthIsAllowedAndPendingConsumesQuota() {
        PunchCorrectionRequest created = service.submit(command(
                LocalDate.parse("2026-08-15"), PunchSide.ENTRY));

        assertThat(created.status()).isEqualTo(Status.PENDING);
        assertThat(created.requestMonth()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(service.quota(
                "employee-001", YearMonth.of(2026, 8)))
                .satisfies(quota -> {
                    assertThat(quota.usedQuota()).isEqualTo(1);
                    assertThat(quota.remainingQuota()).isZero();
                    assertThat(quota.totalQuota()).isEqualTo(1);
                });
    }

    @Test
    void secondPendingOrApprovedCorrectionInSameMonthIsRejected() {
        service.submit(command(
                LocalDate.parse("2026-08-14"), PunchSide.ENTRY));

        assertThatThrownBy(() -> service.submit(command(
                LocalDate.parse("2026-08-15"), PunchSide.EXIT)))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.code())
                            .isEqualTo("PUNCH_CORRECTION_QUOTA_EXHAUSTED");
                    assertThat(problem.getMessage())
                            .isEqualTo("本月补卡次数已用完");
                });
    }

    @Test
    void quotaResetsForEachNaturalCalendarMonth() {
        PunchCorrectionRequest july = service.submit(command(
                LocalDate.parse("2026-07-28"), PunchSide.BOTH));
        PunchCorrectionRequest august = service.submit(command(
                LocalDate.parse("2026-08-15"), PunchSide.BOTH));

        assertThat(july.requestMonth()).isEqualTo(YearMonth.of(2026, 7));
        assertThat(august.requestMonth()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(repository.requests).hasSize(2);
    }

    @Test
    void rejectedRequestDoesNotConsumeQuota() {
        repository.requests.add(new PunchCorrectionRequest(
                "rejected-request",
                "employee-001",
                YearMonth.of(2026, 8),
                LocalDate.parse("2026-08-14"),
                PunchSide.ENTRY,
                "忘记打卡",
                Status.REJECTED,
                NOW.minusSeconds(7200),
                "principal-001",
                NOW.minusSeconds(3600),
                "hr-001",
                "不符合要求"));

        PunchCorrectionRequest retry = service.submit(command(
                LocalDate.parse("2026-08-15"), PunchSide.EXIT));

        assertThat(retry.status()).isEqualTo(Status.PENDING);
    }

    @Test
    void quotaIsCalendarMonthScopedAndRejectedRequestsLeaveThatMonthAvailable() {
        PunchCorrectionQuotaService quota =
                new PunchCorrectionQuotaService(repository);
        repository.requests.add(new PunchCorrectionRequest(
                "august-rejected-request",
                "employee-001",
                YearMonth.of(2026, 8),
                LocalDate.parse("2026-08-14"),
                PunchSide.BOTH,
                "设备故障",
                Status.REJECTED,
                NOW.minusSeconds(7200),
                "principal-001",
                NOW.minusSeconds(3600),
                "hr-001",
                "材料不完整"));

        assertThat(quota.canRequestCorrection(
                "employee-001", YearMonth.of(2026, 8))).isTrue();
        repository.requests.add(PunchCorrectionRequest.pending(
                "august-pending-request",
                "employee-001",
                LocalDate.parse("2026-08-15"),
                PunchSide.ENTRY,
                "忘记打卡",
                NOW,
                "principal-001"));

        assertThat(quota.canRequestCorrection(
                "employee-001", YearMonth.of(2026, 8))).isFalse();
        assertThat(quota.canRequestCorrection(
                "employee-001", YearMonth.of(2026, 9))).isTrue();
    }

    @Test
    void approvalTurnsRequestIntoSyntheticApprovedEvidenceState() {
        PunchCorrectionRequest pending = service.submit(command(
                LocalDate.parse("2026-08-15"), PunchSide.BOTH));

        PunchCorrectionRequest approved = service.approve(
                pending.requestId(), "同意补卡");

        assertThat(approved.status()).isEqualTo(Status.APPROVED);
        assertThat(approved.punchSide()).isEqualTo(PunchSide.BOTH);
        assertThat(approved.reviewedBy()).isEqualTo("principal-001");
        assertThat(repository.find(pending.requestId()))
                .get()
                .extracting(PunchCorrectionRequest::status)
                .isEqualTo(Status.APPROVED);
    }

    @Test
    void futureAndOlderThanPreviousMonthDatesAreRejected() {
        assertThatThrownBy(() -> service.submit(command(
                LocalDate.parse("2026-08-18"), PunchSide.ENTRY)))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.getMessage())
                                .isEqualTo("不能为未来日期补卡"));
        assertThatThrownBy(() -> service.submit(command(
                LocalDate.parse("2026-06-30"), PunchSide.ENTRY)))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.getMessage())
                                .isEqualTo("只能为本月和上月补卡"));
    }

    @Test
    void nonWorkdayAndPunchExemptRoleAreRejected() {
        repository.workDay = false;
        assertThatThrownBy(() -> service.submit(command(
                LocalDate.parse("2026-08-15"), PunchSide.ENTRY)))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code())
                                .isEqualTo("PUNCH_CORRECTION_NOT_WORKDAY"));

        repository.workDay = true;
        repository.punchExempt = true;
        assertThatThrownBy(() -> service.submit(command(
                LocalDate.parse("2026-08-15"), PunchSide.ENTRY)))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.getMessage())
                                .isEqualTo("您的岗位无需打卡"));
    }

    @Test
    void employeeOutsidePrincipalDataScopeIsRejectedBeforeMutation() {
        when(peopleRepository.canAccessEmployee(
                any(), any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.submit(command(
                LocalDate.parse("2026-08-15"), PunchSide.ENTRY)))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.status()).isEqualTo(
                            org.springframework.http.HttpStatus.FORBIDDEN);
                    assertThat(problem.code()).isEqualTo("ACCESS_DENIED");
                });
        assertThat(repository.requests).isEmpty();
        verify(peopleRepository).canAccessEmployee(
                "principal-001",
                CapabilityCodes.ATTENDANCE_PUNCH_CORRECTION_CREATE,
                "employee-001",
                LocalDate.parse("2026-08-15"),
                NOW);
    }

    @Test
    void quotaUsesReadScopeAtTargetMonthEnd() {
        when(peopleRepository.canAccessEmployee(
                any(), any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.quota(
                "employee-001", YearMonth.of(2026, 8)))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.status()).isEqualTo(
                            org.springframework.http.HttpStatus.FORBIDDEN);
                    assertThat(problem.code()).isEqualTo("ACCESS_DENIED");
                });
        verify(peopleRepository).canAccessEmployee(
                "principal-001",
                CapabilityCodes.ATTENDANCE_PUNCH_CORRECTION_READ,
                "employee-001",
                LocalDate.parse("2026-08-31"),
                NOW);
    }

    @Test
    void approvalUsesApproveScopeAtRequestBusinessDateBeforeMutation() {
        PunchCorrectionRequest pending = service.submit(command(
                LocalDate.parse("2026-08-15"), PunchSide.BOTH));
        when(peopleRepository.canAccessEmployee(
                any(), any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.approve(
                pending.requestId(), "同意补卡"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> {
                    assertThat(problem.status()).isEqualTo(
                            org.springframework.http.HttpStatus.FORBIDDEN);
                    assertThat(problem.code()).isEqualTo("ACCESS_DENIED");
                });
        assertThat(repository.find(pending.requestId()))
                .get()
                .extracting(PunchCorrectionRequest::status)
                .isEqualTo(Status.PENDING);
        verify(peopleRepository).canAccessEmployee(
                "principal-001",
                CapabilityCodes.ATTENDANCE_PUNCH_CORRECTION_APPROVE,
                "employee-001",
                LocalDate.parse("2026-08-15"),
                NOW);
    }

    private SubmitCommand command(
            LocalDate businessDate, PunchSide punchSide) {
        return new SubmitCommand(
                "employee-001",
                businessDate,
                punchSide,
                "忘记打卡");
    }

    private static final class InMemoryRepository
            implements PunchCorrectionRequestRepository {

        private final List<PunchCorrectionRequest> requests =
                new ArrayList<>();
        private boolean workDay = true;
        private boolean punchExempt;

        @Override
        public void lockEmployee(String employeeId) {
        }

        @Override
        public int countQuotaConsuming(
                String employeeId, YearMonth month) {
            return (int) requests.stream()
                    .filter(value -> value.employeeId().equals(employeeId))
                    .filter(value -> value.requestMonth().equals(month))
                    .filter(value -> value.status().consumesQuota())
                    .count();
        }

        @Override
        public boolean isScheduledWorkDay(
                String employeeId,
                LocalDate businessDate,
                Instant knowledgeAsOf) {
            return workDay;
        }

        @Override
        public boolean isPunchExempt(
                String employeeId,
                LocalDate businessDate,
                Instant knowledgeAsOf) {
            return punchExempt;
        }

        @Override
        public void insert(PunchCorrectionRequest request) {
            requests.add(request);
        }

        @Override
        public Optional<PunchCorrectionRequest> find(String requestId) {
            return requests.stream()
                    .filter(value -> value.requestId().equals(requestId))
                    .findFirst();
        }

        @Override
        public boolean approve(
                String requestId,
                String reviewerId,
                Instant reviewedAt,
                String reviewNotes) {
            for (int index = 0; index < requests.size(); index++) {
                PunchCorrectionRequest current = requests.get(index);
                if (current.requestId().equals(requestId)
                        && current.status() == Status.PENDING) {
                    requests.set(index, current.approve(
                            reviewerId, reviewedAt, reviewNotes));
                    return true;
                }
            }
            return false;
        }
    }
}
