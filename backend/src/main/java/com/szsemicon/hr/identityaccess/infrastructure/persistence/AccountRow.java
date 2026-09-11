package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import java.time.Instant;

record AccountRow(
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
        String companyId) {
}
