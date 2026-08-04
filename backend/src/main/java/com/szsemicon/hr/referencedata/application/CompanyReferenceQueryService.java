package com.szsemicon.hr.referencedata.application;

import com.szsemicon.hr.referencedata.application.CompanyReferenceRepository.CompanyReference;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyReferenceQueryService {

    private final CompanyReferenceRepository repository;

    public CompanyReferenceQueryService(CompanyReferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<CompanyReference> listActiveCompanies() {
        return repository.findActive();
    }
}
