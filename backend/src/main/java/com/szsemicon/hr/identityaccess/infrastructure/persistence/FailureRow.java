package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import java.time.Instant;

record FailureRow(
        int failureCount,
        Instant windowStartedAt,
        Instant lastFailedAt,
        Instant lockedUntil,
        long rowVersion) {
}
