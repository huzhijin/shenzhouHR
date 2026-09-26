package com.szsemicon.hr.attendance.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.szsemicon.hr.attendance.application.AttendanceGroupCommands.LocationCommand;
import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AttendanceGroupServiceSharedLocationCatalogTest {

    @Test
    void production_profile_rejects_location_creation_before_any_write() {
        AttendanceGroupRepository repository =
                mock(AttendanceGroupRepository.class);
        AttendanceGroupService service = new AttendanceGroupService(
                mock(CurrentCapabilityService.class),
                mock(CurrentPrincipalProvider.class),
                mock(PeopleRepository.class),
                repository,
                mock(ShiftRepository.class),
                mock(CalendarRepository.class),
                mock(AttendancePolicyRepository.class),
                mock(AttendanceSetupIdempotencyService.class),
                mock(AuditService.class),
                new SecurityTokenService(),
                Clock.systemUTC(),
                new MockEnvironment().withProperty(
                        "spring.profiles.active", "prod"));

        LocationCommand command = new LocationCommand(
                "company-1",
                "NEW_LOCATION",
                "不应创建",
                "Asia/Shanghai",
                LocalDate.parse("2026-09-01"),
                null,
                "验证固定目录");

        assertThatThrownBy(() -> service.createLocation(command, "fixed-catalog"))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> org.assertj.core.api.Assertions
                                .assertThat(problem.code())
                                .isEqualTo("SHARED_LOCATION_CATALOG_FIXED"));
        verifyNoInteractions(repository);
    }
}
