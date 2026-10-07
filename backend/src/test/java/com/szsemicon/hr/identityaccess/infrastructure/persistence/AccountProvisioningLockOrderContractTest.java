package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AccountProvisioningLockOrderContractTest {

    private static final Path SERVICE = Path.of(
            "src/main/java/com/szsemicon/hr/identityaccess/application/"
                    + "AccountAccessService.java");
    private static final Path ADAPTER = Path.of(
            "src/main/java/com/szsemicon/hr/identityaccess/infrastructure/persistence/"
                    + "AccountPersistenceAdapter.java");
    private static final Path PEOPLE_MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V5__people_initial_import_and_versioning.sql");

    @Test
    void firstClaimOccursOnlyAfterTargetsAndActorAreLocked()
            throws Exception {
        String service = Files.readString(SERVICE);
        String migration = Files.readString(PEOPLE_MIGRATION);
        int method = service.indexOf("private BulkAccountCreationResult "
                + "createEmployeeAccountBatch(");
        int nextMethod = service.indexOf("private BulkAccountCreationResult "
                + "recoverEmployeeAccountBatch(", method);
        String body = service.substring(method, nextMethod);

        assertThat(service)
                .contains("TransactionDefinition.ISOLATION_READ_COMMITTED");
        assertThat(normalizeWhitespace(migration))
                .contains("CONSTRAINT fk_people_idempotency_actor "
                        + "FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id)");
        assertThat(body.indexOf("lockEmployeeProvisioningTargets(employeeIds)"))
                .isLessThan(body.indexOf("lockBulkProvisioningAuthority(actorId, now)"));
        assertThat(body.indexOf("lockBulkProvisioningAuthority(actorId, now)"))
                .isLessThan(body.indexOf("accountPersistence.findIdempotency("));
        assertThat(body.indexOf("accountPersistence.findIdempotency("))
                .isLessThan(body.indexOf("accountPersistence.claimIdempotency("));
        assertThat(body.indexOf("accountPersistence.claimIdempotency("))
                .isLessThan(body.indexOf("requireProvisioningCandidates("));
    }

    @Test
    void replayRollsBackTheTargetFirstRaceBeforeTakingAnAccountLock()
            throws Exception {
        String service = Files.readString(SERVICE);
        int publicMethod = service.indexOf(
                "public BulkAccountCreationResult createEmployeeAccounts(");
        int firstAttempt = service.indexOf("private BulkAccountCreationResult "
                + "createEmployeeAccountBatch(", publicMethod);
        String dispatch = service.substring(publicMethod, firstAttempt);
        int replayMethod = service.indexOf("private BulkAccountCreationResult "
                + "recoverEmployeeAccountBatch(", firstAttempt);
        int runnerMethod = service.indexOf("private BulkAccountCreationResult "
                + "runProvisioningTransaction(", replayMethod);
        String replay = service.substring(replayMethod, runnerMethod);

        assertThat(dispatch)
                .contains("catch (ProvisioningReplayRaceException exception)")
                .contains("runProvisioningTransaction(() -> recoverEmployeeAccountBatch(")
                .doesNotContain("accountPersistence.claimIdempotency(");
        assertThat(replay.indexOf("accountPersistence.lockIdempotency("))
                .isLessThan(replay.indexOf("lockProvisionedAccounts("));
        assertThat(replay.indexOf("lockProvisionedAccounts("))
                .isLessThan(replay.indexOf(
                        "lockEmployeeProvisioningTargets(employeeIds)"));
        assertThat(replay.indexOf("lockEmployeeProvisioningTargets(employeeIds)"))
                .isLessThan(replay.indexOf(
                        "lockBulkProvisioningAuthority(actorId, now)"));
    }

    @Test
    void replayAccountLockDoesNotImplicitlyLockEmployeeBeforeCompany()
            throws Exception {
        String adapter = Files.readString(ADAPTER);
        int method = adapter.indexOf(
                "public Optional<AccountRecord> lockProvisionedAccount(");
        int nextMethod = adapter.indexOf("\n    @Override", method + 1);
        String body = normalizeWhitespace(adapter.substring(method, nextMethod));

        assertThat(body)
                .contains("FROM local_account account")
                .contains("WHERE account.account_id = ? FOR UPDATE")
                .doesNotContain("JOIN employee")
                .doesNotContain("JOIN auth_principal");
    }

    @Test
    void existingIdempotencyLockTouchesOnlyTheChildRecord()
            throws Exception {
        String adapter = Files.readString(ADAPTER);
        int method = adapter.indexOf("private Optional<IdempotencyClaim> readIdempotency(");
        int nextMethod = adapter.indexOf("\n    @Override", method + 1);
        String body = normalizeWhitespace(adapter.substring(method, nextMethod));

        assertThat(body)
                .contains("FROM people_idempotency_record")
                .contains("(lock ? \" FOR UPDATE\" : \"\")")
                .doesNotContain("JOIN auth_principal")
                .doesNotContain("INSERT INTO people_idempotency_record");
    }

    @Test
    void targetLocksUseCompanyThenEmployeeWithDeterministicOrdering()
            throws Exception {
        String adapter = Files.readString(ADAPTER);
        int method = adapter.indexOf(
                "public void lockEmployeeProvisioningTargets(");
        int nextMethod = adapter.indexOf("\n    @Override", method + 1);
        String body = normalizeWhitespace(adapter.substring(method, nextMethod));

        assertThat(body)
                .contains("SortedSet<String> companyIds = new TreeSet<>()")
                .contains("SortedSet<String> employeeIds = new TreeSet<>()");
        assertThat(body.indexOf("for (String companyId : companyIds)"))
                .isLessThan(body.indexOf("for (String employeeId : employeeIds)"));
        assertThat(body)
                .contains("SELECT company_id FROM company WHERE company_id = ? FOR UPDATE")
                .contains("SELECT employee_id FROM employee WHERE employee_id = ? FOR UPDATE");
    }

    private static String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
