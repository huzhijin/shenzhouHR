package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RoleGrantLegalEntityLockSqlContractTest {

    private static final Path ADAPTER = Path.of(
            "src/main/java/com/szsemicon/hr/identityaccess/infrastructure/persistence/"
                    + "AccountPersistenceAdapter.java");
    private static final Path SERVICE = Path.of(
            "src/main/java/com/szsemicon/hr/identityaccess/application/"
                    + "AccountAccessService.java");
    private static final Path PEOPLE_MAPPER =
            Path.of("src/main/resources/mappers/PeopleMapper.xml");

    @Test
    void roleGrantAndPeoplePublicationCoordinateOnTheSameLegalEntityRowLock()
            throws Exception {
        String adapter = Files.readString(ADAPTER);
        String peopleMapper = Files.readString(PEOPLE_MAPPER);

        assertThat(adapter)
                .contains("SortedSet<String> targetLegalEntityIds = new TreeSet<>()")
                .contains("for (String legalEntityId : targetLegalEntityIds)");
        assertThat(normalizeWhitespace(adapter))
                .contains(
                        "SELECT legal_entity_id FROM legal_entity "
                                + "WHERE legal_entity_id = ? "
                                + "AND status = 'ACTIVE' FOR UPDATE");
        assertThat(normalizeWhitespace(peopleMapper))
                .contains(
                        "<select id=\"lockLegalEntity\" resultType=\"string\"> "
                                + "SELECT legal_entity_id FROM legal_entity "
                                + "WHERE legal_entity_id = #{legalEntityId} "
                                + "FOR UPDATE </select>");
    }

    @Test
    void targetLegalEntitiesAreLockedBeforeActorAssignmentsAndScopeChecks()
            throws Exception {
        String service = Files.readString(SERVICE);

        int targetLock =
                service.indexOf("accountPersistence.lockRoleGrantTargetLegalEntities(");
        int actorAssignmentLock =
                service.indexOf("accountPersistence.lockCurrentRoleGrantAuthority(");
        int scopeResolution = service.indexOf(
                "accountPersistence.resolveAuthorizedRoleAssignmentScopes(");

        assertThat(targetLock).isGreaterThanOrEqualTo(0);
        assertThat(actorAssignmentLock).isGreaterThan(targetLock);
        assertThat(scopeResolution).isGreaterThan(actorAssignmentLock);
    }

    private static String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
