package com.szsemicon.hr.evidenceingestion.application;

import java.time.Instant;

public interface DeliSourceRegistrationRepository {

    RegistrationResult register(
            DeliSourceRegistrationModels.Command command,
            String principalId,
            String capability,
            String idempotencyKey,
            String requestDigest,
            String sourceId,
            String configurationRevisionId,
            String idempotencyId,
            String snapshotDigest,
            Instant at);

    enum RegistrationState {
        CREATED,
        REPLAYED,
        RESOURCE_UNAVAILABLE,
        IDEMPOTENCY_CONFLICT,
        SOURCE_CODE_CONFLICT,
        CREDENTIAL_REFERENCE_CONFLICT
    }

    record RegistrationResult(
            RegistrationState state,
            DeliSourceRegistrationModels.SourceView source) {

        public static RegistrationResult of(RegistrationState state) {
            return new RegistrationResult(state, null);
        }

        public static RegistrationResult source(
                RegistrationState state,
                DeliSourceRegistrationModels.SourceView source) {
            return new RegistrationResult(state, source);
        }
    }
}
