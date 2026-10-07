package com.szsemicon.hr.employee.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.authorization.application.CapabilityRepository;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EmployeeListQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void passesCurrentPrincipalCapabilityAndPagingToScopedRepository() {
        CurrentPrincipalProvider principalProvider = () -> "principal-1";
        CapabilityRepository capabilityRepository = (principalId, at) -> Set.of(CapabilityCodes.MASTER_DATA_READ);
        CurrentCapabilityService capabilityService = new CurrentCapabilityService(
                principalProvider,
                capabilityRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicReference<String> capturedPrincipal = new AtomicReference<>();
        EmployeeReadRepository repository = (principalId, capabilityCode, at, page, size) -> {
            capturedPrincipal.set(principalId);
            return new EmployeePage(List.of(), 0, page, size);
        };
        EmployeeListQueryService service = new EmployeeListQueryService(
                capabilityService,
                principalProvider,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        EmployeePage result = service.query(0, 50);

        assertThat(capturedPrincipal).hasValue("principal-1");
        assertThat(result.items()).isEmpty();
        assertThat(result.size()).isEqualTo(50);
    }

    @Test
    void rejectsUnboundedPageSizeBeforeCallingRepository() {
        CurrentPrincipalProvider principalProvider = () -> "principal-1";
        CurrentCapabilityService capabilityService = new CurrentCapabilityService(
                principalProvider,
                (principalId, at) -> Set.of(CapabilityCodes.MASTER_DATA_READ),
                Clock.fixed(NOW, ZoneOffset.UTC));
        EmployeeReadRepository repository = (principalId, capabilityCode, at, page, size) -> {
            throw new AssertionError("repository must not be called");
        };
        EmployeeListQueryService service = new EmployeeListQueryService(
                capabilityService,
                principalProvider,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.query(0, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passesOptionalCompanyFilterWithoutChangingTheExistingAuthorizationInputs() {
        CurrentPrincipalProvider principalProvider = () -> "principal-1";
        CurrentCapabilityService capabilityService = new CurrentCapabilityService(
                principalProvider,
                (principalId, at) -> Set.of(CapabilityCodes.MASTER_DATA_READ),
                Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicReference<String> capturedCompanyId = new AtomicReference<>();
        EmployeeReadRepository repository = new EmployeeReadRepository() {
            @Override
            public EmployeePage findVisibleTo(
                    String principalId,
                    String capabilityCode,
                    Instant at,
                    int page,
                    int size) {
                throw new AssertionError("filtered repository overload must be called");
            }

            @Override
            public EmployeePage findVisibleTo(
                    String principalId,
                    String capabilityCode,
                    Instant at,
                    String query,
                    String organizationId,
                    String companyId,
                    String status,
                    String sort,
                    int page,
                    int size) {
                assertThat(principalId).isEqualTo("principal-1");
                assertThat(capabilityCode).isEqualTo(CapabilityCodes.MASTER_DATA_READ);
                capturedCompanyId.set(companyId);
                return new EmployeePage(List.of(), 0, page, size);
            }
        };
        EmployeeListQueryService service = new EmployeeListQueryService(
                capabilityService,
                principalProvider,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.query(
                0, 50, null, null, "company-1", null, null, null);

        assertThat(capturedCompanyId).hasValue("company-1");
    }

    @Test
    void forwardsTheExplicitOrganizationDescendantFilter() {
        CurrentPrincipalProvider principalProvider = () -> "principal-1";
        CurrentCapabilityService capabilityService = new CurrentCapabilityService(
                principalProvider,
                (principalId, at) -> Set.of(CapabilityCodes.MASTER_DATA_READ),
                Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicReference<Boolean> capturedIncludeDescendants = new AtomicReference<>();
        EmployeeReadRepository repository = new EmployeeReadRepository() {
            @Override
            public EmployeePage findVisibleTo(
                    String principalId,
                    String capabilityCode,
                    Instant at,
                    int page,
                    int size) {
                throw new AssertionError("filtered repository overload must be called");
            }

            @Override
            public EmployeePage findVisibleTo(
                    String principalId,
                    String capabilityCode,
                    Instant at,
                    String query,
                    String organizationId,
                    boolean includeDescendants,
                    String companyId,
                    String status,
                    String sort,
                    int page,
                    int size) {
                assertThat(organizationId).isEqualTo("organization-1");
                capturedIncludeDescendants.set(includeDescendants);
                return new EmployeePage(List.of(), 0, page, size);
            }
        };
        EmployeeListQueryService service = new EmployeeListQueryService(
                capabilityService,
                principalProvider,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.query(
                0, 50, null, "organization-1", true,
                null, null, null, null);

        assertThat(capturedIncludeDescendants).hasValue(true);
    }
}
