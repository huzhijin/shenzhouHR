package com.szsemicon.hr.audit.application;

import com.szsemicon.hr.identityaccess.application.AuditPersistence;
import com.szsemicon.hr.identityaccess.application.AuditPersistence.AuditFilter;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AuditRecord;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueryService {

    private final AuditPersistence repository;

    public AuditQueryService(AuditPersistence repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AuditPage list(
            String action,
            String result,
            String resourceType,
            String resourceId,
            String sort,
            int page,
            int size) {
        int boundedPage = Math.max(page, 0);
        int boundedSize = Math.min(Math.max(size, 1), 100);
        String normalizedResult = normalize(result);
        if (!normalizedResult.isEmpty()
                && !List.of("SUCCESS", "FAILURE", "DENIED").contains(normalizedResult)) {
            throw new IllegalArgumentException("invalid audit result");
        }
        AuditFilter filter = new AuditFilter(
                normalize(action),
                normalizedResult,
                normalize(resourceType),
                normalize(resourceId),
                "occurredAt,asc".equals(sort));
        List<AuditSummary> items = repository.listAudit(
                        filter,
                        boundedSize,
                        boundedPage * boundedSize)
                .stream()
                .map(AuditQueryService::summary)
                .toList();
        return new AuditPage(
                items,
                repository.countAudit(filter),
                boundedPage,
                boundedSize);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    @Transactional(readOnly = true)
    public AuditDetail get(String eventId) {
        AuditRecord event = repository.findAudit(eventId)
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_AVAILABLE",
                        "请求的资源不可用"));
        return new AuditDetail(
                event.eventId(),
                event.occurredAt(),
                event.actorDisplayName(),
                event.action(),
                event.resourceType(),
                event.resourceId(),
                event.result(),
                event.correlationId(),
                event.actorId(),
                event.reason(),
                event.requestId(),
                event.beforeDigest(),
                event.afterDigest());
    }

    private static AuditSummary summary(AuditRecord event) {
        return new AuditSummary(
                event.eventId(),
                event.occurredAt(),
                event.actorDisplayName(),
                event.action(),
                event.resourceType(),
                event.resourceId(),
                event.result(),
                event.correlationId());
    }

    public record AuditSummary(
            String eventId,
            java.time.Instant occurredAt,
            String actorDisplayName,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String correlationId) {
    }

    public record AuditDetail(
            String eventId,
            java.time.Instant occurredAt,
            String actorDisplayName,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String correlationId,
            String actorId,
            String reason,
            String requestId,
            String beforeDigest,
            String afterDigest) {
    }

    public record AuditPage(
            List<AuditSummary> items,
            long total,
            int page,
            int size) {
    }
}
