package com.szsemicon.hr.people.interfaces.rest;

import com.szsemicon.hr.people.application.PeopleCommands.AdjustPriorService;
import com.szsemicon.hr.people.application.PeopleCommands.CreateEmployee;
import com.szsemicon.hr.people.application.PeopleCommands.CreateEmployment;
import com.szsemicon.hr.people.application.PeopleCommands.CreateOrganization;
import com.szsemicon.hr.people.application.PeopleCommands.UpdateEmployee;
import com.szsemicon.hr.people.application.PeopleCommands.UpdateEmployment;
import com.szsemicon.hr.people.application.PeopleCommands.UpdateOrganization;
import com.szsemicon.hr.people.application.PeopleManagementService;
import com.szsemicon.hr.people.domain.PeopleModels.OrganizationVersion;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.EmployeeCreateRequest;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.EmployeeUpdateRequest;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.EmployeeVersionPage;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.EmployeeView;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.EmploymentCreateRequest;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.EmploymentPage;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.EmploymentUpdateRequest;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.EmploymentView;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.OrganizationCreateRequest;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.OrganizationUpdateRequest;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.OrganizationVersionPage;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.OrganizationView;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.PriorServiceAdjustmentRequest;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.PriorServiceRecordPage;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.PriorServiceRecordView;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.PriorServiceReplayView;
import com.szsemicon.hr.people.interfaces.rest.PeopleManagementDtos.ReasonRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PeopleManagementController {

    private final PeopleManagementService service;

    public PeopleManagementController(PeopleManagementService service) {
        this.service = service;
    }

    @PostMapping("/organization-units")
    @PreAuthorize("hasAuthority('ORGANIZATION:CREATE')")
    ResponseEntity<OrganizationView> createOrganization(
            @Valid @RequestBody OrganizationCreateRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        OrganizationVersion version = service.createOrganization(
                new CreateOrganization(
                        request.legalEntityId(), request.parentOrganizationId(),
                        request.code(), request.name(), request.organizationType(),
                        request.effectiveFrom(), request.reason()),
                IfMatchVersion.parse(ifMatch),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(version.rowVersion()))
                .body(PeopleManagementDtos.organization(version));
    }

    @GetMapping("/organization-units/{organizationId:[0-9a-fA-F-]{36}}")
    @PreAuthorize("hasAuthority('ORGANIZATION:READ')")
    ResponseEntity<OrganizationView> getOrganization(
            @PathVariable String organizationId,
            @RequestParam(required = false) LocalDate asOf) {
        OrganizationVersion version = service.getOrganization(organizationId, asOf);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(version.rowVersion()))
                .body(PeopleManagementDtos.organization(version));
    }

    @PatchMapping("/organization-units/{organizationId:[0-9a-fA-F-]{36}}")
    @PreAuthorize("hasAuthority('ORGANIZATION:EDIT')")
    ResponseEntity<OrganizationView> updateOrganization(
            @PathVariable String organizationId,
            @Valid @RequestBody OrganizationUpdateRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        OrganizationVersion version = service.updateOrganization(
                organizationId,
                new UpdateOrganization(
                        request.parentOrganizationId(), request.code(), request.name(),
                        request.organizationType(), request.status(), request.effectiveFrom(),
                        request.effectiveTo(), request.reason()),
                IfMatchVersion.parse(ifMatch),
                idempotencyKey);
        return versionedOrganization(version);
    }

    @GetMapping("/organization-units/{organizationId:[0-9a-fA-F-]{36}}/versions")
    @PreAuthorize("hasAuthority('ORGANIZATION:READ')")
    ResponseEntity<OrganizationVersionPage> listOrganizationVersions(
            @PathVariable String organizationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(PeopleManagementDtos.organizationPage(
                service.listOrganizationVersions(organizationId, page, size)));
    }

    @PostMapping("/employees")
    @PreAuthorize("hasAuthority('EMPLOYEE:CREATE')")
    ResponseEntity<EmployeeView> createEmployee(
            @Valid @RequestBody EmployeeCreateRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var detail = service.createEmployee(
                new CreateEmployee(
                        request.legalEntityId(), request.employeeNumber(),
                        request.displayName(), request.externalEmployeeId(),
                        request.effectiveFrom(), request.reason()),
                IfMatchVersion.parse(ifMatch),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(detail.employee().rowVersion()))
                .body(PeopleManagementDtos.employee(detail));
    }

    @GetMapping("/employees/{employeeId}")
    @PreAuthorize("hasAuthority('EMPLOYEE:READ')")
    ResponseEntity<EmployeeView> getEmployee(
            @PathVariable String employeeId,
            @RequestParam(required = false) LocalDate asOf) {
        var detail = service.getEmployee(employeeId, asOf);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(detail.employee().rowVersion()))
                .body(PeopleManagementDtos.employee(detail));
    }

    @PatchMapping("/employees/{employeeId}")
    @PreAuthorize("hasAuthority('EMPLOYEE:EDIT')")
    ResponseEntity<EmployeeView> updateEmployee(
            @PathVariable String employeeId,
            @Valid @RequestBody EmployeeUpdateRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var detail = service.updateEmployee(
                employeeId,
                new UpdateEmployee(
                        request.employeeNumber(), request.displayName(), request.status(),
                        request.effectiveFrom(), request.effectiveTo(), request.reason()),
                IfMatchVersion.parse(ifMatch),
                idempotencyKey);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(detail.employee().rowVersion()))
                .body(PeopleManagementDtos.employee(detail));
    }

    @GetMapping("/employees/{employeeId}/versions")
    @PreAuthorize("hasAuthority('EMPLOYEE:READ')")
    ResponseEntity<EmployeeVersionPage> listEmployeeVersions(
            @PathVariable String employeeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(PeopleManagementDtos.employeePage(
                service.listEmployeeVersions(employeeId, page, size)));
    }

    @GetMapping("/employees/{employeeId}/employment-periods")
    @PreAuthorize("hasAuthority('EMPLOYMENT:READ')")
    ResponseEntity<EmploymentPage> listEmploymentPeriods(
            @PathVariable String employeeId,
            @RequestParam(required = false) LocalDate asOf,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(PeopleManagementDtos.employmentPage(
                service.listEmploymentPeriods(employeeId, asOf, page, size)));
    }

    @PostMapping("/employees/{employeeId}/employment-periods")
    @PreAuthorize("hasAuthority('EMPLOYMENT:CREATE')")
    ResponseEntity<EmploymentView> createEmployment(
            @PathVariable String employeeId,
            @Valid @RequestBody EmploymentCreateRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var result = service.createEmployment(
                employeeId,
                new CreateEmployment(
                        request.organizationId(), request.positionId(), request.startDate(),
                        request.terminationDate(), request.reason()),
                IfMatchVersion.parse(ifMatch),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(result.period().rowVersion()))
                .body(PeopleManagementDtos.employment(result.period()));
    }

    @PatchMapping("/employees/{employeeId}/employment-periods/{employmentPeriodId}")
    @PreAuthorize("hasAuthority('EMPLOYMENT:EDIT')")
    ResponseEntity<EmploymentView> updateEmployment(
            @PathVariable String employeeId,
            @PathVariable String employmentPeriodId,
            @Valid @RequestBody EmploymentUpdateRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var result = service.updateEmployment(
                employeeId,
                employmentPeriodId,
                new UpdateEmployment(
                        request.organizationId(), request.positionId(), request.startDate(),
                        request.terminationDate(), request.reason()),
                IfMatchVersion.parse(ifMatch),
                idempotencyKey);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(result.period().rowVersion()))
                .body(PeopleManagementDtos.employment(result.period()));
    }

    @GetMapping("/employees/{employeeId}/prior-service-records")
    @PreAuthorize("hasAuthority('PRIOR_SERVICE:READ')")
    ResponseEntity<PriorServiceRecordPage> listPriorService(
            @PathVariable String employeeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(PeopleManagementDtos.priorServicePage(
                service.listPriorService(employeeId, page, size)));
    }

    @PostMapping("/employees/{employeeId}/prior-service-adjustments")
    @PreAuthorize("hasAuthority('PRIOR_SERVICE:ADJUST')")
    ResponseEntity<PriorServiceRecordView> adjustPriorService(
            @PathVariable String employeeId,
            @Valid @RequestBody PriorServiceAdjustmentRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var result = service.adjustPriorService(
                employeeId,
                new AdjustPriorService(
                        request.amountDays(), request.businessDate(), request.reason()),
                IfMatchVersion.parse(ifMatch),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(result.aggregateVersion()))
                .body(PeopleManagementDtos.priorService(result.record()));
    }

    @PostMapping("/employees/{employeeId}/prior-service/recalculate")
    @PreAuthorize("hasAuthority('PRIOR_SERVICE:ADJUST')")
    ResponseEntity<PriorServiceReplayView> recalculatePriorService(
            @PathVariable String employeeId,
            @Valid @RequestBody ReasonRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var result = service.recalculatePriorService(
                employeeId, request.reason(), IfMatchVersion.parse(ifMatch), idempotencyKey);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(result.aggregateVersion()))
                .body(PeopleManagementDtos.replay(result.replay()));
    }

    private static ResponseEntity<OrganizationView> versionedOrganization(
            OrganizationVersion version) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(version.rowVersion()))
                .body(PeopleManagementDtos.organization(version));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
