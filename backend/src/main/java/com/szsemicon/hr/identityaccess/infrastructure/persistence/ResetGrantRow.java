package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import java.time.Instant;

record ResetGrantRow(
        String grantId,
        String accountId,
        Instant expiresAt,
        Instant usedAt,
        long rowVersion) {
}
