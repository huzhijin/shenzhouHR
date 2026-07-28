package com.szsemicon.hr.audit.application;

import com.szsemicon.hr.identityaccess.application.AuditPersistence;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AuditRecord;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import com.szsemicon.hr.shared.web.CorrelationIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

@Service
public class AuditService {

    private final AuditPersistence repository;
    private final SecurityTokenService tokenService;
    private final Clock clock;

    public AuditService(
            AuditPersistence repository,
            SecurityTokenService tokenService,
            Clock clock) {
        this.repository = repository;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    @Transactional
    public void record(
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String reason) {
        append(
                actorId, action, resourceType, resourceId, result, reason,
                null, null, null);
    }

    @Transactional
    public void record(
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String reason,
            String beforeDigest,
            String afterDigest) {
        append(
                actorId, action, resourceType, resourceId, result, reason,
                beforeDigest, afterDigest, null);
    }

    @Transactional
    public void recordVersioned(
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String reason,
            long resourceVersion,
            String beforeDigest,
            String afterDigest) {
        if (resourceVersion < 0) {
            throw new IllegalArgumentException(
                    "audit resource version must be non-negative");
        }
        append(
                actorId, action, resourceType, resourceId, result, reason,
                beforeDigest, afterDigest, Long.toString(resourceVersion));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String reason) {
        append(
                actorId, action, resourceType, resourceId, result, reason,
                null, null, null);
    }

    private void append(
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String reason,
            String beforeDigest,
            String afterDigest,
            String resourceVersion) {
        String correlationId = currentCorrelationId();
        String requestId = correlationId;
        String eventId = UUID.randomUUID().toString();
        Instant occurredAt = clock.instant();
        String eventHash = tokenService.digest(String.join(
                "|",
                eventId,
                occurredAt.toString(),
                actorId == null ? "ANONYMOUS" : actorId,
                action,
                resourceId == null ? "" : resourceId,
                result,
                requestId));
        AuditRecord event = new AuditRecord(
                eventId,
                occurredAt,
                actorId == null ? "ANONYMOUS" : actorId,
                actorId == null ? "匿名请求" : actorId,
                action,
                resourceType,
                resourceId,
                result,
                reason,
                correlationId,
                requestId,
                beforeDigest,
                afterDigest,
                eventHash);
        if (resourceVersion == null) {
            repository.appendAudit(event);
        } else {
            repository.appendVersionedAudit(event, resourceVersion);
        }
    }

    public String currentCorrelationId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return UUID.randomUUID().toString();
        }
        Object value = attributes.getAttribute(
                CorrelationIdFilter.REQUEST_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        return value == null ? UUID.randomUUID().toString() : value.toString();
    }
}
