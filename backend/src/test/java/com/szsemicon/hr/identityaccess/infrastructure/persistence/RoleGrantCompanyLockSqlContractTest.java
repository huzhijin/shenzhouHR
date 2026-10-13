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
        // Role-grant now locks company_people_write_lock (same as PeopleMapper),
        // not the company ACTIVE row, so DAY_CONTEXT_SQL FOR SHARE is unblocked.
        assertThat(normalizeWhitespace(adapter))
                .contains(
                        "INSERT IGNORE INTO company_people_write_lock (company_id) VALUES (?)")
                .contains(
                        "SELECT company_id FROM company_people_write_lock "
                                + "WHERE company_id = ? FOR UPDATE");
        // ACTIVE existence is still verified, but without a FOR UPDATE on company.
        assertThat(normalizeWhitespace(adapter))
                .contains(
                        "SELECT company_id FROM company "
                                + "WHERE company_id = ? "
                                + "AND status = 'ACTIVE'");
        assertThat(normalizeWhitespace(peopleMapper))
                .contains(
                        "<select id=\"lockCompany\" resultType=\"string\"> "
                                + "SELECT company_id FROM company_people_write_lock "
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
                service.indexOf(
                        "accountPersistence.lockCurrentCapabilityAuthority(",
                        targetLock);
        int scopeResolution = service.indexOf(
                "accountPersistence.resolveAuthorizedRoleAssignmentScopes(");

        assertThat(targetLock).isGreaterThanOrEqualTo(0);
        assertThat(actorAssignmentLock).isGreaterThan(targetLock);
        assertThat(scopeResolution).isGreaterThan(actorAssignmentLock);
    }

    @Test
    void roleReplacementDoesNotLockTargetPrincipalOrEmployeeBeforeCompany()
            throws Exception {
        String adapter = Files.readString(ADAPTER);
        String service = Files.readString(SERVICE);
        String replacement = between(
                service,
                "public AccountDetail replaceRoleAssignments(",
                "public List<RoleRecord> listRoles()");
        String localAccountLock = between(
                adapter,
                "public Optional<AccountRecord> lockProvisionedAccount(",
                "public String createAccount(");

        assertThat(replacement)
                .containsSubsequence(
                        "requireLockedLocalAccount(accountId)",
                        "lockRoleAssignmentAuthorization(",
                        "lockTargetRoleAssignments(")
                .doesNotContain("requireLockedAccount(accountId)");
        assertThat(service).contains(
                "@Transactional(isolation = Isolation.READ_COMMITTED)\n"
                        + "    public AccountDetail replaceRoleAssignments(");
        assertThat(localAccountLock)
                .contains("FROM local_account account", "FOR UPDATE")
                .doesNotContain("JOIN auth_principal")
                .doesNotContain("JOIN employee");
    }

    private static String between(String value, String start, String end) {
        int from = value.indexOf(start);
        int to = value.indexOf(end, from + start.length());
        assertThat(from).isGreaterThanOrEqualTo(0);
        assertThat(to).isGreaterThan(from);
        return value.substring(from, to);
    }

    private static String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
