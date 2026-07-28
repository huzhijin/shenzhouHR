package com.szsemicon.hr.organization.interfaces.rest;

import com.szsemicon.hr.organization.application.CurrentOrganizationQueryService;
import com.szsemicon.hr.organization.application.OrganizationTreeNode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organization-units")
public class OrganizationController {

    private final CurrentOrganizationQueryService queryService;

    public OrganizationController(CurrentOrganizationQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    ResponseEntity<List<OrganizationNodeResponse>> currentTree(
            @RequestParam(required = false) LocalDate asOf,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        List<OrganizationNodeResponse> response =
                queryService.query(asOf, includeInactive).stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    private OrganizationNodeResponse toResponse(OrganizationTreeNode node) {
        return new OrganizationNodeResponse(
                node.organizationId(),
                node.organizationVersionId(),
                node.code(),
                node.name(),
                node.organizationType(),
                node.status(),
                node.sourceOrganizationId(),
                node.effectiveFrom(),
                node.effectiveTo(),
                node.sourceAuthority(),
                node.rowVersion(),
                node.children().stream().map(this::toResponse).toList());
    }
}
