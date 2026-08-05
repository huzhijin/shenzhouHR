package com.szsemicon.hr.employee.interfaces.rest;

import com.szsemicon.hr.employee.application.EmployeeListQueryService;
import com.szsemicon.hr.employee.application.EmployeePage;
import com.szsemicon.hr.employee.domain.EmployeeSummary;
import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final EmployeeListQueryService queryService;

    public EmployeeController(EmployeeListQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    ResponseEntity<EmployeePageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) String companyId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate asOf,
            @RequestParam(required = false) String sort) {
        EmployeePage result = queryService.query(
                page, size, query, organizationId, companyId, status, asOf, sort);
        var response = new EmployeePageResponse(
                result.items().stream().map(this::toResponse).toList(),
                result.total(),
                result.page(),
                result.size());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    private EmployeeSummaryResponse toResponse(EmployeeSummary employee) {
        return new EmployeeSummaryResponse(
                employee.employeeId(),
                employee.employeeVersionId(),
                employee.employeeNumber(),
                employee.displayName(),
                employee.employmentStatus(),
                employee.organizationId(),
                employee.organizationName(),
                employee.organizationCode(),
                employee.seeyonOaCode(),
                employee.bindingStatus(),
                employee.assignmentEffectiveFrom(),
                employee.assignmentEffectiveTo(),
                employee.sourceAuthority(),
                employee.rowVersion());
    }
}
