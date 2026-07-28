package com.szsemicon.hr.identityaccess.application;

import java.time.Instant;
import java.util.List;

/**
 * Compatibility facade for services that coordinate authentication, account and
 * audit transactions. Each persistence responsibility is implemented separately.
 */
public interface IdentityAccessRepository
        extends AuthenticationPersistence, AccountPersistence, AuditPersistence {

    record AccountRecord(
            String accountId,
            String principalId,
            String username,
            String normalizedUsername,
            String displayName,
            String status,
            boolean firstPasswordChangeRequired,
            Instant lockedUntil,
            Instant lastLoginAt,
            long sessionEpoch,
            long rowVersion,
            String legalEntityId) {
    }

    record CredentialRecord(String accountId, String passwordHash, long rowVersion) {
    }

    record FailureRecord(
            int failureCount,
            Instant windowStartedAt,
            Instant lastFailedAt,
            Instant lockedUntil,
            long rowVersion) {

        public static FailureRecord empty() {
            return new FailureRecord(0, null, null, null, 0);
        }
    }

    record SessionRecord(
            String sessionId,
            String accountId,
            String tokenDigest,
            String status,
            Instant createdAt,
            Instant lastSeenAt,
            Instant idleExpiresAt,
            Instant absoluteExpiresAt,
            String requestId,
            long rowVersion) {
    }

    record ResetGrantRecord(
            String grantId,
            String accountId,
            Instant expiresAt,
            Instant usedAt,
            long rowVersion) {
    }

    record RoleAssignmentInput(
            String roleId,
            String scopeType,
            String scopeResourceId,
            Instant validFrom,
            Instant validTo) {
    }

    record RoleAssignmentRecord(
            String assignmentId,
            String roleId,
            String roleCode,
            String roleName,
            String scopeType,
            String scopeResourceId,
            Instant validFrom,
            Instant validTo) {
    }

    record RoleRecord(
            String roleId,
            String roleCode,
            String roleName,
            List<String> capabilities) {
    }

    record AuditRecord(
            String eventId,
            Instant occurredAt,
            String actorId,
            String actorDisplayName,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String reason,
            String correlationId,
            String requestId,
            String beforeDigest,
            String afterDigest,
            String eventHash) {
    }
}
