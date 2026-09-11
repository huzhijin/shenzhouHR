package com.szsemicon.hr.referencedata.infrastructure.persistence;

import com.szsemicon.hr.referencedata.application.CompanyReferenceRepository;
import com.szsemicon.hr.referencedata.application.CompanyReferenceRepository.CompanyReference;
import java.util.List;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisCompanyReferenceRepository
        implements CompanyReferenceRepository {

    private final CompanyReferenceMapper mapper;

    public MyBatisCompanyReferenceRepository(CompanyReferenceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<CompanyReference> findVisibleActive(
            String principalId,
            Instant at) {
        return mapper.findVisibleActive(principalId, at).stream()
                .map(row -> new CompanyReference(
                        row.companyId(),
                        row.code(),
                        row.name()))
                .toList();
    }
}
