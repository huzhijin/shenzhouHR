package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.evidenceingestion.infrastructure.persistence.EmployeeDeliBindingMapper;
import com.szsemicon.hr.reporting.application.AttendanceReportQueryService;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DeliPunchReplayApplicationServiceTest {

    @Test
    void replayStaysDisabledUntilOperatorTurnsTheFlagOn() {
        var service = new DeliPunchReplayApplicationService(
                false,
                mock(CurrentCapabilityService.class),
                mock(CurrentPrincipalProvider.class),
                mock(EmployeeDeliBindingMapper.class),
                mock(EmployeeDeliBindingSeedService.class),
                mock(DeliPunchPageTransaction.class),
                List.of(),
                List.of(),
                List.of(),
                mock(AttendanceReportQueryService.class),
                Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.replay(
                        null, null, null, false, true, true))
                .isInstanceOf(ApiProblemException.class)
                .extracting(ex -> ((ApiProblemException) ex).code())
                .isEqualTo("DELI_REPLAY_DISABLED");
        assertThatThrownBy(() -> service.replay(
                        null, null, null, false, true, true))
                .extracting(ex -> ((ApiProblemException) ex).status())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void defaultWindowCoversAugustFirstHalf() {
        assertThat(DeliPunchReplayApplicationService.DEFAULT_FROM)
                .isEqualTo(LocalDate.parse("2026-08-01"));
        assertThat(DeliPunchReplayApplicationService.DEFAULT_TO)
                .isEqualTo(LocalDate.parse("2026-08-13"));
    }
}
