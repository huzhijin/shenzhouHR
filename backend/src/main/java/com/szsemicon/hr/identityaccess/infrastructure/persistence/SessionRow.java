package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import java.time.Instant;

record SessionRow(
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
