package com.szsemicon.hr.referencedata.application;

import com.szsemicon.hr.referencedata.application.CompanyReferenceRepository.CompanyReference;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyReferenceQueryService {

    private final CompanyReferenceRepository repository;
    private final CurrentPrincipalProvider principalProvider;
    private final Clock clock;

    public CompanyReferenceQueryService(
            CompanyReferenceRepository repository,
            CurrentPrincipalProvider principalProvider,
            Clock clock) {
        this.repository = repository;
        this.principalProvider = principalProvider;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CompanyReference> listActiveCompanies() {
        return repository.findVisibleActive(
                principalProvider.currentPrincipalId(),
                clock.instant());
    }
}
