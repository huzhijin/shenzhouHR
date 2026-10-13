package com.szsemicon.hr.reporting.application;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base class for MySQL isolation integration tests.
 *
 * The Spring context uses the "mysql-isolation-test" profile which points to
 * the isolated mysqld at 127.0.0.1:13307. If that instance is not running,
 * Spring context startup fails with a DataSource connection error — that is
 * intentional and the desired outcome. There is no assumeTrue / skip path;
 * a missing database instance is a hard test failure.
 *
 * The @BeforeAll below adds an explicit connection check so that the failure
 * message immediately names the cause rather than surfacing as an opaque
 * Spring context load error.
 *
 * Test classes extending this base must end with "IT" so that the default
 * Surefire configuration skips them. The parent session runs them explicitly
 * via -Dtest=MysqlIsolationRepairIT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql-isolation-test")
@TestPropertySource(properties = {"spring.flyway.enabled=true"})
public abstract class MysqlIsolationTestBase {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TransactionTemplate txTemplate;

    @Autowired
    protected OaOvertimeRecognitionWriteService overtimeRecognitionWriteService;

    @Autowired
    protected OaEvidenceExclusionWriteService evidenceExclusionWriteService;

    @Autowired
    protected PunchExemptionRepairService punchExemptionRepairService;

    /**
     * Verifies that the isolation MySQL instance is reachable before any test
     * runs. Throws AssertionError on failure — not a skip — so that a missing
     * instance is an explicit red build, not a silent green.
     */
    @BeforeAll
    static void verifyDatabaseAvailable(@Autowired DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            // Connection obtained: instance is up and schema initialized.
        } catch (Exception e) {
            throw new AssertionError(
                    "MySQL isolation test instance not available at 127.0.0.1:13307. "
                    + "Initialize and start the instance before running IT tests. "
                    + "Cause: " + e.getMessage(),
                    e);
        }
    }
}
