package com.szsemicon.hr.referencedata.application;

import java.util.List;
import java.time.Instant;

public interface CompanyReferenceRepository {

    List<CompanyReference> findVisibleActive(String principalId, Instant at);

    record CompanyReference(
            String companyId,
            String code,
            String name) {
    }
}
