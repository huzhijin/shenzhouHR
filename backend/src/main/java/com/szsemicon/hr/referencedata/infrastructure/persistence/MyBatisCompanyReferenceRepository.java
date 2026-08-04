package com.szsemicon.hr.referencedata.infrastructure.persistence;

import com.szsemicon.hr.referencedata.application.CompanyReferenceRepository;
import com.szsemicon.hr.referencedata.application.CompanyReferenceRepository.CompanyReference;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisCompanyReferenceRepository
        implements CompanyReferenceRepository {

    private final CompanyReferenceMapper mapper;

    public MyBatisCompanyReferenceRepository(CompanyReferenceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<CompanyReference> findActive() {
        return mapper.findActive().stream()
                .map(row -> new CompanyReference(
                        row.companyId(),
                        row.code(),
                        row.name()))
                .toList();
    }
}
