package com.szsemicon.hr.developmentlog.interfaces.rest;

import com.szsemicon.hr.developmentlog.application.DevelopmentLogQueryService;
import com.szsemicon.hr.developmentlog.application.DevelopmentLogQueryService.DeploymentEntry;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/access/development-log")
public class DevelopmentLogController {

    private final DevelopmentLogQueryService queryService;

    public DevelopmentLogController(DevelopmentLogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<DevelopmentLogResponse> list(
            @RequestParam(required = false) @Size(max = 200) String search
    ) {
        List<DeploymentEntry> entries = queryService.listDeployments(search);
        return entries.stream()
                .map(this::toResponse)
                .toList();
    }

    private DevelopmentLogResponse toResponse(DeploymentEntry entry) {
        return new DevelopmentLogResponse(
                entry.deploymentId(),
                formatInstant(entry.deployedAt()),
                entry.version(),
                entry.title(),
                entry.description(),
                entry.details(),
                entry.isCurrent()
        );
    }

    private String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : "";
    }

    public record DevelopmentLogResponse(
            String deploymentId,
            String date,
            String version,
            String title,
            String description,
            String details,
            boolean isCurrent
    ) {}
}
