package com.szsemicon.hr.identityaccess.infrastructure.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * One-shot production administrator initializer.
 *
 * <p>This class is intentionally a command-line entry point and is not wired
 * into the web application. The Baota release installer extracts the fat jar
 * and invokes it only after Flyway has completed successfully.
 */
public final class InitialProductionAdminCommand {

    private static final String SYSTEM_ADMIN_ROLE_ID =
            "10000000-0000-0000-0000-000000000002";
    private static final String HR_ADMIN_ROLE_ID =
            "10000000-0000-0000-0000-000000000001";
    private static final String PEOPLE_IMPORT_SYSTEM_PRINCIPAL_ID =
            "20000000-0000-0000-0000-000000000001";
    private static final List<CompanyCatalogEntry> EXPECTED_COMPANIES = List.of(
            new CompanyCatalogEntry("SZSZ", "上海昇州半导体科技有限公司"),
            new CompanyCatalogEntry("SZJN", "上海晟州聚能半导体科技有限公司"),
            new CompanyCatalogEntry("SZSC", "江苏神州半导体科技股份有限公司"),
            new CompanyCatalogEntry("SZXY", "江苏芯越半导体科技有限公司"));
    private static final int EXPECTED_COMPANY_COUNT = 4;
    private static final int EXPECTED_SCOPE_COUNT = 4;
    private static final int EXPECTED_ROLE_ASSIGNMENT_COUNT = 8;

    private InitialProductionAdminCommand() {}

    public static void main(String[] args) throws Exception {
        String jdbcUrl = required("SHENZHOUHR_DB_URL");
        String dbUsername = required("SHENZHOUHR_DB_USERNAME");
        String dbPassword = required("SHENZHOUHR_DB_PASSWORD");
        String username = required("SHENZHOUHR_INIT_ADMIN_USERNAME").trim();
        String displayName = required("SHENZHOUHR_INIT_ADMIN_DISPLAY_NAME").trim();
        String adminPassword = required("SHENZHOUHR_INIT_ADMIN_PASSWORD");
        Path companyCatalogPath =
                Path.of(required("SHENZHOUHR_INIT_COMPANY_CATALOG").trim());

        validateUsername(username);
        validateLength(displayName, 1, 100, "SHENZHOUHR_INIT_ADMIN_DISPLAY_NAME");
        validatePassword(adminPassword);
        List<CompanyCatalogEntry> companies = loadCompanyCatalog(companyCatalogPath);

        Class.forName("com.mysql.cj.jdbc.Driver");
        Timestamp timestamp = Timestamp.from(Instant.now());
        String normalizedUsername = Normalizer.normalize(username, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, dbUsername, dbPassword)) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                assertFreshMigratedDatabase(connection);
                assertRequiredRoles(connection);
                ensureUsernameIsAvailable(connection, normalizedUsername);

                String principalId = UUID.randomUUID().toString();
                String accountId = UUID.randomUUID().toString();
                String credentialId = UUID.randomUUID().toString();
                String passwordHash = passwordEncoder.encode(adminPassword);

                insertPrincipal(connection, principalId, timestamp);
                for (CompanyCatalogEntry company : companies) {
                    String companyId = UUID.randomUUID().toString();
                    insertCompany(connection, companyId, company, timestamp);
                    String scopeId = UUID.randomUUID().toString();
                    insertCompanyScope(connection, scopeId, companyId, timestamp);
                    insertRoleAssignment(
                            connection,
                            UUID.randomUUID().toString(),
                            principalId,
                            SYSTEM_ADMIN_ROLE_ID,
                            scopeId,
                            principalId,
                            timestamp,
                            "INITIAL_PRODUCTION_ADMIN_SYSTEM_" + company.code());
                    insertRoleAssignment(
                            connection,
                            UUID.randomUUID().toString(),
                            principalId,
                            HR_ADMIN_ROLE_ID,
                            scopeId,
                            principalId,
                            timestamp,
                            "INITIAL_PRODUCTION_ADMIN_HR_" + company.code());
                }

                insertAccount(
                        connection,
                        accountId,
                        principalId,
                        username,
                        normalizedUsername,
                        displayName,
                        principalId,
                        timestamp);
                insertCredential(connection, credentialId, accountId, passwordHash, timestamp);
                insertFailureWindow(connection, accountId);
                assertCreatedState(connection, principalId);

                connection.commit();
                System.out.println("INITIAL_ADMIN_CREATED=PASS");
                System.out.println("INITIAL_ADMIN_USERNAME=" + username);
                System.out.println("INITIAL_ADMIN_COMPANY_CODES=" + companyCodes(companies));
                System.out.println("INITIAL_ADMIN_COMPANY_COUNT=" + EXPECTED_COMPANY_COUNT);
                System.out.println("INITIAL_ADMIN_SCOPE_COUNT=" + EXPECTED_SCOPE_COUNT);
                System.out.println(
                        "INITIAL_ADMIN_ROLE_ASSIGNMENT_COUNT="
                                + EXPECTED_ROLE_ASSIGNMENT_COUNT);
                System.out.println("INITIAL_ADMIN_FIRST_PASSWORD_CHANGE_REQUIRED=true");
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    static List<CompanyCatalogEntry> loadCompanyCatalog(Path catalogPath) throws IOException {
        if (!Files.isRegularFile(catalogPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(catalogPath)
                || !Files.isReadable(catalogPath)) {
            throw new IllegalArgumentException(
                    "SHENZHOUHR_INIT_COMPANY_CATALOG must be a readable regular file, not a symlink");
        }
        byte[] expected = expectedCatalogText().getBytes(StandardCharsets.UTF_8);
        if (Files.size(catalogPath) != expected.length) {
            throw new IllegalArgumentException(
                    "Company catalog differs from the signed four-company release contract");
        }
        byte[] actual = Files.readAllBytes(catalogPath);
        if (!Arrays.equals(actual, expected)) {
            throw new IllegalArgumentException(
                    "Company catalog differs from the signed four-company release contract");
        }
        return EXPECTED_COMPANIES;
    }

    private static String expectedCatalogText() {
        StringBuilder catalog = new StringBuilder();
        for (CompanyCatalogEntry company : EXPECTED_COMPANIES) {
            catalog.append(company.code())
                    .append('\t')
                    .append(company.name())
                    .append('\n');
        }
        return catalog.toString();
    }

    private static String companyCodes(List<CompanyCatalogEntry> companies) {
        return String.join(",", companies.stream().map(CompanyCatalogEntry::code).toList());
    }

    private static void assertFreshMigratedDatabase(Connection connection) throws SQLException {
        assertTableIsEmpty(connection, "company");
        assertTableIsEmpty(connection, "local_account");
        assertTableIsEmpty(connection, "password_credential");
        assertTableIsEmpty(connection, "login_failure_window");
        assertTableIsEmpty(connection, "auth_data_scope");
        assertTableIsEmpty(connection, "auth_principal_role_assignment");

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*), COALESCE(SUM(CASE WHEN principal_id = ? "
                        + "AND employee_id IS NULL AND status = 'ACTIVE' AND row_version = 0 "
                        + "THEN 1 ELSE 0 END), 0) FROM auth_principal")) {
            statement.setString(1, PEOPLE_IMPORT_SYSTEM_PRINCIPAL_ID);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) != 1 || result.getLong(2) != 1) {
                    throw new IllegalStateException(
                            "Database is not a fresh migrated target: unexpected principal state");
                }
            }
        }
    }

    private static void assertTableIsEmpty(Connection connection, String tableName)
            throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement("SELECT COUNT(*) FROM " + tableName);
                ResultSet result = statement.executeQuery()) {
            if (!result.next() || result.getLong(1) != 0) {
                throw new IllegalStateException(
                        "Database is not a fresh migrated target: " + tableName + " is not empty");
            }
        }
    }

    private static void assertRequiredRoles(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM auth_role WHERE "
                        + "(role_id = ? AND role_code = 'SYSTEM_ADMIN') OR "
                        + "(role_id = ? AND role_code = 'HR_ADMIN')")) {
            statement.setString(1, SYSTEM_ADMIN_ROLE_ID);
            statement.setString(2, HR_ADMIN_ROLE_ID);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) != 2) {
                    throw new IllegalStateException(
                            "Required SYSTEM_ADMIN and HR_ADMIN role catalog is missing or changed");
                }
            }
        }
    }

    private static void assertCreatedState(Connection connection, String principalId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT "
                        + "(SELECT COUNT(*) FROM company), "
                        + "(SELECT COUNT(*) FROM auth_data_scope), "
                        + "(SELECT COUNT(*) FROM auth_principal_role_assignment "
                        + " WHERE principal_id = ?), "
                        + "(SELECT COUNT(*) FROM local_account WHERE principal_id = ?)")) {
            statement.setString(1, principalId);
            statement.setString(2, principalId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || result.getLong(1) != EXPECTED_COMPANY_COUNT
                        || result.getLong(2) != EXPECTED_SCOPE_COUNT
                        || result.getLong(3) != EXPECTED_ROLE_ASSIGNMENT_COUNT
                        || result.getLong(4) != 1) {
                    throw new IllegalStateException(
                            "Four-company administrator initialization produced an unexpected state");
                }
            }
        }
    }

    private static void insertCompany(
            Connection connection,
            String companyId,
            CompanyCatalogEntry company,
            Timestamp timestamp)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO company (company_id, code, name, status, created_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', ?)")) {
            statement.setString(1, companyId);
            statement.setString(2, company.code());
            statement.setString(3, company.name());
            statement.setTimestamp(4, timestamp);
            requireSingleInsert(statement, "company " + company.code());
        }
    }

    private static void ensureUsernameIsAvailable(Connection connection, String normalizedUsername)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM local_account WHERE normalized_username = ? LIMIT 1")) {
            statement.setString(1, normalizedUsername);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new IllegalStateException(
                            "Username already exists; refusing to replace credentials: "
                                    + normalizedUsername);
                }
            }
        }
    }

    private static void insertPrincipal(Connection connection, String principalId, Timestamp timestamp)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO auth_principal "
                        + "(principal_id, employee_id, status, created_at, row_version) "
                        + "VALUES (?, NULL, 'ACTIVE', ?, 0)")) {
            statement.setString(1, principalId);
            statement.setTimestamp(2, timestamp);
            requireSingleInsert(statement, "administrator principal");
        }
    }

    private static void insertAccount(
            Connection connection,
            String accountId,
            String principalId,
            String username,
            String normalizedUsername,
            String displayName,
            String actorId,
            Timestamp timestamp)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO local_account "
                        + "(account_id, principal_id, username, normalized_username, display_name, "
                        + "status, first_password_change_required, session_epoch, row_version, "
                        + "created_by, created_at, updated_by, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', TRUE, 0, 0, ?, ?, ?, ?)")) {
            statement.setString(1, accountId);
            statement.setString(2, principalId);
            statement.setString(3, username);
            statement.setString(4, normalizedUsername);
            statement.setString(5, displayName);
            statement.setString(6, actorId);
            statement.setTimestamp(7, timestamp);
            statement.setString(8, actorId);
            statement.setTimestamp(9, timestamp);
            requireSingleInsert(statement, "administrator account");
        }
    }

    private static void insertCredential(
            Connection connection,
            String credentialId,
            String accountId,
            String passwordHash,
            Timestamp timestamp)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO password_credential "
                        + "(credential_id, account_id, password_hash, algorithm, "
                        + "parameter_version, changed_at, row_version) "
                        + "VALUES (?, ?, ?, 'BCRYPT', '2B_COST_12', ?, 0)")) {
            statement.setString(1, credentialId);
            statement.setString(2, accountId);
            statement.setString(3, passwordHash);
            statement.setTimestamp(4, timestamp);
            requireSingleInsert(statement, "administrator credential");
        }
    }

    private static void insertFailureWindow(Connection connection, String accountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO login_failure_window (account_id, failure_count, row_version) "
                        + "VALUES (?, 0, 0)")) {
            statement.setString(1, accountId);
            requireSingleInsert(statement, "administrator login failure window");
        }
    }

    private static void insertCompanyScope(
            Connection connection,
            String scopeId,
            String companyId,
            Timestamp timestamp)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO auth_data_scope "
                        + "(scope_id, scope_type, company_id, organization_id, "
                        + "include_descendants, valid_from, valid_to) "
                        + "VALUES (?, 'COMPANY', ?, NULL, TRUE, ?, NULL)")) {
            statement.setString(1, scopeId);
            statement.setString(2, companyId);
            statement.setTimestamp(3, timestamp);
            requireSingleInsert(statement, "company data scope");
        }
    }

    private static void insertRoleAssignment(
            Connection connection,
            String assignmentId,
            String principalId,
            String roleId,
            String scopeId,
            String assignedBy,
            Timestamp timestamp,
            String reason)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO auth_principal_role_assignment "
                        + "(assignment_id, principal_id, role_id, data_scope_id, valid_from, "
                        + "valid_to, assigned_by, reason, row_version) "
                        + "VALUES (?, ?, ?, ?, ?, NULL, ?, ?, 0)")) {
            statement.setString(1, assignmentId);
            statement.setString(2, principalId);
            statement.setString(3, roleId);
            statement.setString(4, scopeId);
            statement.setTimestamp(5, timestamp);
            statement.setString(6, assignedBy);
            statement.setString(7, reason);
            requireSingleInsert(statement, "administrator role assignment");
        }
    }

    private static void requireSingleInsert(PreparedStatement statement, String description)
            throws SQLException {
        int updatedRows = statement.executeUpdate();
        if (updatedRows != 1) {
            throw new IllegalStateException(
                    "Expected one inserted row for " + description + "; got " + updatedRows);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static void validateUsername(String username) {
        if (!username.matches("[A-Za-z0-9._-]{3,64}")) {
            throw new IllegalArgumentException(
                    "SHENZHOUHR_INIT_ADMIN_USERNAME must use 3-64 letters, digits, dot, underscore or hyphen");
        }
    }

    private static void validateLength(String value, int minimum, int maximum, String name) {
        if (value.length() < minimum || value.length() > maximum) {
            throw new IllegalArgumentException(name + " length is outside the allowed range");
        }
    }

    private static void validatePassword(String password) {
        if (password.length() < 12 || password.length() > 256) {
            throw new IllegalArgumentException("Initial administrator password length is invalid");
        }
        boolean uppercase = false;
        boolean lowercase = false;
        boolean digit = false;
        boolean symbol = false;
        for (char value : password.toCharArray()) {
            uppercase |= Character.isUpperCase(value);
            lowercase |= Character.isLowerCase(value);
            digit |= Character.isDigit(value);
            symbol |= !Character.isLetterOrDigit(value);
        }
        if (!(uppercase && lowercase && digit && symbol)) {
            throw new IllegalArgumentException(
                    "Initial administrator password must contain upper/lowercase letters, a digit and a symbol");
        }
    }

    record CompanyCatalogEntry(String code, String name) {}
}
