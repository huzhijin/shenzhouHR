package com.szsemicon.hr.identityaccess.infrastructure.bootstrap;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.text.Normalizer;
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

    private InitialProductionAdminCommand() {}

    public static void main(String[] args) throws Exception {
        String jdbcUrl = required("SHENZHOUHR_DB_URL");
        String dbUsername = required("SHENZHOUHR_DB_USERNAME");
        String dbPassword = required("SHENZHOUHR_DB_PASSWORD");
        String username = required("SHENZHOUHR_INIT_ADMIN_USERNAME").trim();
        String displayName = required("SHENZHOUHR_INIT_ADMIN_DISPLAY_NAME").trim();
        String companyCode = required("SHENZHOUHR_INIT_COMPANY_CODE").trim();
        String companyName = required("SHENZHOUHR_INIT_COMPANY_NAME").trim();
        String adminPassword = required("SHENZHOUHR_INIT_ADMIN_PASSWORD");

        validateUsername(username);
        validateLength(displayName, 1, 100, "SHENZHOUHR_INIT_ADMIN_DISPLAY_NAME");
        validateLength(companyCode, 1, 64, "SHENZHOUHR_INIT_COMPANY_CODE");
        validateLength(companyName, 1, 200, "SHENZHOUHR_INIT_COMPANY_NAME");
        validatePassword(adminPassword);

        Class.forName("com.mysql.cj.jdbc.Driver");
        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);
        String normalizedUsername = Normalizer.normalize(username, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, dbUsername, dbPassword)) {
            connection.setAutoCommit(false);
            try {
                String companyId = findOrCreateCompany(
                        connection, companyCode, companyName, timestamp);
                ensureUsernameIsAvailable(connection, normalizedUsername);

                String principalId = UUID.randomUUID().toString();
                String accountId = UUID.randomUUID().toString();
                String credentialId = UUID.randomUUID().toString();
                String scopeId = UUID.randomUUID().toString();
                String systemAssignmentId = UUID.randomUUID().toString();
                String hrAssignmentId = UUID.randomUUID().toString();
                String passwordHash = passwordEncoder.encode(adminPassword);

                insertPrincipal(connection, principalId, timestamp);
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
                insertCompanyScope(connection, scopeId, companyId, timestamp);
                insertRoleAssignment(
                        connection,
                        systemAssignmentId,
                        principalId,
                        SYSTEM_ADMIN_ROLE_ID,
                        scopeId,
                        principalId,
                        timestamp,
                        "INITIAL_PRODUCTION_ADMIN_SYSTEM");
                insertRoleAssignment(
                        connection,
                        hrAssignmentId,
                        principalId,
                        HR_ADMIN_ROLE_ID,
                        scopeId,
                        principalId,
                        timestamp,
                        "INITIAL_PRODUCTION_ADMIN_HR");

                connection.commit();
                System.out.println("INITIAL_ADMIN_CREATED=PASS");
                System.out.println("INITIAL_ADMIN_USERNAME=" + username);
                System.out.println("INITIAL_ADMIN_COMPANY_CODE=" + companyCode);
                System.out.println("INITIAL_ADMIN_FIRST_PASSWORD_CHANGE_REQUIRED=true");
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static String findOrCreateCompany(
            Connection connection,
            String companyCode,
            String companyName,
            Timestamp timestamp)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT company_id, status FROM company WHERE code = ? FOR UPDATE")) {
            statement.setString(1, companyCode);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    if (!"ACTIVE".equals(result.getString("status"))) {
                        throw new IllegalStateException(
                                "Existing company is not ACTIVE: " + companyCode);
                    }
                    return result.getString("company_id");
                }
            }
        }

        String companyId = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO company (company_id, code, name, status, created_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', ?)")) {
            statement.setString(1, companyId);
            statement.setString(2, companyCode);
            statement.setString(3, companyName);
            statement.setTimestamp(4, timestamp);
            statement.executeUpdate();
        }
        return companyId;
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
            statement.executeUpdate();
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
            statement.executeUpdate();
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
            statement.executeUpdate();
        }
    }

    private static void insertFailureWindow(Connection connection, String accountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO login_failure_window (account_id, failure_count, row_version) "
                        + "VALUES (?, 0, 0)")) {
            statement.setString(1, accountId);
            statement.executeUpdate();
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
            statement.executeUpdate();
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
            statement.executeUpdate();
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
        if (!username.matches("[A-Za-z0-9._-]{3,128}")) {
            throw new IllegalArgumentException(
                    "SHENZHOUHR_INIT_ADMIN_USERNAME must use letters, digits, dot, underscore or hyphen");
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
}
