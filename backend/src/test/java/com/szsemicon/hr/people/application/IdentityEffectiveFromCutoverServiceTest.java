package com.szsemicon.hr.people.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.identityaccess.application.AuditPersistence;
import com.szsemicon.hr.people.application.IdentityEffectiveFromCutoverModels.Blocked;
import com.szsemicon.hr.people.application.IdentityEffectiveFromCutoverModels.Candidate;
import com.szsemicon.hr.people.application.IdentityEffectiveFromCutoverMapper;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityEffectiveFromCutoverServiceTest {

    @Mock
    private CurrentCapabilityService capabilities;
    @Mock
    private CurrentPrincipalProvider principals;
    @Mock
    private IdentityEffectiveFromCutoverMapper mapper;
    @Mock
    private AuditPersistence audits;

    private IdentityEffectiveFromCutoverService service;

    @BeforeEach
    void setUp() {
        service = new IdentityEffectiveFromCutoverService(
                capabilities,
                principals,
                mapper,
                audits,
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC));
    }

    private void actor() {
        when(principals.currentPrincipalId())
                .thenReturn("20000000-0000-0000-0000-000000000001");
    }

    @Test
    void diagnoseListsMovableAndBlocked() {
        when(mapper.listMovable(LocalDate.of(2026, 1, 1)))
                .thenReturn(List.of(new Candidate(
                        "EMPLOYEE_VERSION", "v1", "e1", LocalDate.of(2026, 8, 4))));
        when(mapper.listAlreadyApplied(LocalDate.of(2026, 1, 1)))
                .thenReturn(List.of());
        when(mapper.listBlocked(LocalDate.of(2026, 1, 1)))
                .thenReturn(List.of());
        var diagnosis = service.diagnose();
        assertThat(diagnosis.safe()).isTrue();
        assertThat(diagnosis.movable()).hasSize(1);
    }

    @Test
    void executeStopsWhenBlocked() {
        actor();
        when(mapper.findRunStatusByRequest(any(), eq("req-1"))).thenReturn(null);
        when(mapper.listMovable(any())).thenReturn(List.of());
        when(mapper.listAlreadyApplied(any())).thenReturn(List.of());
        when(mapper.listBlocked(any())).thenReturn(List.of(
                new Blocked("WORK_CALENDAR_DAY", "c1", "cal", "CALENDAR_DAYS_MISSING_BEFORE_CUTOFF")));
        assertThatThrownBy(() -> service.execute("req-1", "前移生效日"))
                .isInstanceOf(ApiProblemException.class);
        verify(mapper, never()).moveEmployeeVersions(any());
    }

    @Test
    void secondRequestIsAlreadyApplied() {
        actor();
        when(mapper.findRunStatusByRequest(any(), eq("req-1")))
                .thenReturn("COMMITTED");
        var result = service.execute("req-1", "再跑一次");
        assertThat(result.status()).isEqualTo("ALREADY_APPLIED");
        verify(mapper, never()).moveEmployeeVersions(any());
    }

    @Test
    void executeMovesWhenSafe() {
        actor();
        when(mapper.findRunStatusByRequest(any(), eq("req-2"))).thenReturn(null);
        when(mapper.listMovable(any())).thenReturn(List.of(new Candidate(
                "EMPLOYEE_VERSION", "v1", "e1", LocalDate.of(2026, 8, 4))));
        when(mapper.listAlreadyApplied(any())).thenReturn(List.of());
        when(mapper.listBlocked(any())).thenReturn(List.of());
        when(mapper.moveEmployeeVersions(any())).thenReturn(1);
        when(mapper.moveAssignments(any())).thenReturn(0);
        when(mapper.moveOrganizationVersions(any())).thenReturn(0);
        when(mapper.moveGroupAssignments(any())).thenReturn(0);
        when(mapper.moveGroupRevisions(any())).thenReturn(0);
        when(mapper.moveCalendarVersions(any())).thenReturn(0);
        when(mapper.movePolicyBindings(any())).thenReturn(0);
        var result = service.execute("req-2", "前移到一月");
        assertThat(result.status()).isEqualTo("COMMITTED");
        assertThat(result.updatedCount()).isEqualTo(1);
        verify(mapper).moveEmployeeVersions(LocalDate.of(2026, 1, 1));
    }
}
