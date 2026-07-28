package com.szsemicon.hr.identityaccess.application;

import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AuditRecord;
import java.util.List;
import java.util.Optional;

public interface AuditPersistence {

    void appendAudit(AuditRecord event);

    void appendVersionedAudit(AuditRecord event, String resourceVersion);

    List<AuditRecord> listAudit(AuditFilter filter, int limit, int offset);

    long countAudit(AuditFilter filter);

    Optional<AuditRecord> findAudit(String eventId);

    record AuditFilter(
            String action,
            String result,
            String resourceType,
            String resourceId,
            boolean ascending) {
    }
}
