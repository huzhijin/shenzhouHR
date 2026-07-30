package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RoleGrantCompanyLockSqlContractTest {

    private static final Path ADAPTER = Path.of(
            "src/main/java/com/szsemicon/hr/identityaccess/infrastructure/persistence/"
                    + "AccountPersistenceAdapter.java");
    private static final Path SERVICE = Path.of(
            "src/main/java/com/szsemicon/hr/identityaccess/application/"
                    + "AccountAccessService.java");
    private static final Path PEOPLE_MAPPER =
            Path.of("src/main/resources/mappers/PeopleMapper.xml");

    @Test
    void roleGrantAndPeoplePublicationCoordinateOnTheSameCompanyRowLock()
            throws Exception {
        String adapter = Files.readString(ADAPTER);
        String peopleMapper = Files.readString(PEOPLE_MAPPER);

        assertThat(adapter)
                .contains("SortedSet<String> targetCompanyIds = new TreeSet<>()")
                .contains("for (String companyId : targetCompanyIds)");
        assertThat(normalizeWhitespace(adapter))
                .contains(
                        "SELECT company_id FROM company "
                                + "WHERE company_id = ? "
                                + "AND status = 'ACTIVE' FOR UPDATE");
        assertThat(normalizeWhitespace(peopleMapper))
                .contains(
                        "<select id=\"lockCompany\" resultType=\"string\"> "
                                + "SELECT company_id FROM company "
                                + "WHERE company_id = #{companyId} "
                                + "FOR UPDATE </select>");
    }

    @Test
    void targetCompaniesAreLockedBeforeActorAssignmentsAndScopeChecks()
            throws Exception {
        String service = Files.readString(SERVICE);

        int targetLock =
                service.indexOf("accountPersistence.lockRoleGrantTargetCompanies(");
        int actorAssignmentLock =
                service.lastIndexOf("accountPersistence.lockCurrentCapabilityAuthority(");
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
