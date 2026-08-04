package com.szsemicon.hr.referencedata.interfaces.rest;

import com.szsemicon.hr.referencedata.application.CompanyReferenceQueryService;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reference-data")
public class ReferenceDataController {

    private static final String MANAGEMENT_READ_AUTHORIZATION = """
            hasAnyAuthority(
                'ACCOUNT:READ',
                'ROLE:READ',
                'MASTER_DATA:READ',
                'ORGANIZATION:READ',
                'EMPLOYEE:READ',
                'ATTENDANCE_SETUP:READ'
            )
            """;

    private final CompanyReferenceQueryService queryService;

    public ReferenceDataController(CompanyReferenceQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/companies")
    @PreAuthorize(MANAGEMENT_READ_AUTHORIZATION)
    ResponseEntity<List<CompanyReferenceResponse>> companies() {
        List<CompanyReferenceResponse> response = queryService
                .listActiveCompanies()
                .stream()
                .map(company -> new CompanyReferenceResponse(
                        company.companyId(),
                        company.code(),
                        company.name()))
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    record CompanyReferenceResponse(
            String companyId,
            String code,
            String name) {
    }
}
