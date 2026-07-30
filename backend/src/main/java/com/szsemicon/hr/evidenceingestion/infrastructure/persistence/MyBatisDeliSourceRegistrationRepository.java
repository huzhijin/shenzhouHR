package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.application.DeliSourceRegistrationModels;
import com.szsemicon.hr.evidenceingestion.application.DeliSourceRegistrationRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisDeliSourceRegistrationRepository
        implements DeliSourceRegistrationRepository {

    private final AttendanceSourceSyncMapper mapper;

    public MyBatisDeliSourceRegistrationRepository(
            AttendanceSourceSyncMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RegistrationResult register(
            DeliSourceRegistrationModels.Command command,
            String principalId,
            String capability,
            String idempotencyKey,
            String requestDigest,
            String sourceId,
            String configurationRevisionId,
            String idempotencyId,
            String snapshotDigest,
            Instant at) {
        String authorizedEntity = mapper.lockAuthorizedCompany(
                command.companyId(), principalId, capability, at);
        if (authorizedEntity == null) {
            return RegistrationResult.of(
                    RegistrationState.RESOURCE_UNAVAILABLE);
        }
        var idempotency = mapper.findRegistrationIdempotency(
                principalId, command.sourceCode(), idempotencyKey);
        if (idempotency != null) {
            if (!Objects.equals(
                            requestDigest, idempotency.requestDigest())
                    || !"COMPLETED_SUCCESS".equals(idempotency.status())) {
                return RegistrationResult.of(
                        RegistrationState.IDEMPOTENCY_CONFLICT);
            }
            var replay = mapper.findDeliSourceByCode(
                    command.companyId(), command.sourceCode());
            return replay == null
                    ? RegistrationResult.of(
                            RegistrationState.IDEMPOTENCY_CONFLICT)
                    : RegistrationResult.source(
                            RegistrationState.REPLAYED, replay);
        }
        if (mapper.findDeliSourceByCode(
                        command.companyId(), command.sourceCode())
                != null) {
            return RegistrationResult.of(
                    RegistrationState.SOURCE_CODE_CONFLICT);
        }
        if (mapper.countActiveSourcesForCredentialReference(
                        command.secretReferenceName(), at)
                != 0) {
            return RegistrationResult.of(
                    RegistrationState.CREDENTIAL_REFERENCE_CONFLICT);
        }

        mapper.insertRegistrationIdempotency(
                idempotencyId,
                principalId,
                command.sourceCode(),
                idempotencyKey,
                requestDigest,
                at);
        mapper.insertDeliSource(
                sourceId, command, principalId, at);
        mapper.insertDeliSourceConfiguration(
                configurationRevisionId,
                sourceId,
                command,
                snapshotDigest,
                principalId,
                at);
        mapper.insertWatermarkIfAbsent(sourceId);
        if (mapper.completeRegistrationIdempotency(
                        idempotencyId, sourceId, at)
                != 1) {
            throw new IllegalStateException(
                    "Deli source idempotency completion failed");
        }
        var created = mapper.findDeliSourceByCode(
                command.companyId(), command.sourceCode());
        if (created == null) {
            throw new IllegalStateException(
                    "created Deli source is unavailable");
        }
        return RegistrationResult.source(
                RegistrationState.CREATED, created);
    }
}
