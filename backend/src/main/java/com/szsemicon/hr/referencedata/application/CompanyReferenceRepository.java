package com.szsemicon.hr.referencedata.application;

import java.util.List;

public interface CompanyReferenceRepository {

    List<CompanyReference> findActive();

    record CompanyReference(
            String companyId,
            String code,
            String name) {
    }
}
