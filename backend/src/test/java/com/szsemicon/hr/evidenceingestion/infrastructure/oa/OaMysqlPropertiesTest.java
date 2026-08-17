package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class OaMysqlPropertiesTest {

    @Test
    void defaultsToDisabledAndFailsWithoutRenderingConfiguration() {
        var properties = new OaMysqlProperties();
        properties.setJdbcUrl(
                "jdbc:mysql://secret.internal:3306/oa");
        properties.setUsername("secret-user");
        properties.setPassword("secret-password");

        assertThat(properties.isEnabled()).isFalse();
        assertThatThrownBy(properties::validateEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA MySQL integration is disabled");
        assertThat(properties.toString())
                .doesNotContain(
                        "secret.internal",
                        "secret-user",
                        "secret-password");
    }

    @Test
    void acceptsAConfiguredMysqlSourceWithoutRenderingSecrets() {
        var properties = configured();

        properties.validateEnabled();

        assertThat(properties.getSourceTimeZone())
                .isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(properties.toString())
                .doesNotContain(
                        properties.getJdbcUrl(),
                        properties.getUsername(),
                        properties.getPassword())
                .contains(
                        "jdbcUrl=<redacted>",
                        "password=<redacted>");
    }

    @Test
    void rejectsAZoneThatWouldShiftOaDatetimeEvidence() {
        var properties = configured();
        properties.setSourceTimeZone(ZoneId.of("UTC"));

        assertThatThrownBy(properties::validateEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA MySQL source time zone must be Asia/Shanghai");
    }

    @Test
    void rejectsNonMysqlAndControlCharacterConfiguration() {
        var properties = configured();
        properties.setJdbcUrl("jdbc:postgresql://oa.example/oa");

        assertThatThrownBy(properties::validateEnabled)
                .isInstanceOf(IllegalStateException.class);

        properties = configured();
        properties.setUsername("readonly\ninjected");
        assertThatThrownBy(properties::validateEnabled)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsCredentialsAndReadOnlyOverridesInsideTheJdbcUrl() {
        var properties = configured();
        properties.setJdbcUrl(
                "jdbc:mysql://readonly:secret@oa.example:3306/oa");
        assertThatThrownBy(properties::validateEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA MySQL JDBC URL is invalid");

        properties = configured();
        properties.setJdbcUrl(
                "jdbc:mysql://oa.example:3306/oa"
                        + "?password=secret"
                        + "&readOnlyPropagatesToServer=false");
        assertThatThrownBy(properties::validateEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA MySQL JDBC URL is invalid");

        properties = configured();
        properties.setJdbcUrl(
                "jdbc:mysql://oa.example:3306/oa"
                        + "?%70assword=secret");
        assertThatThrownBy(properties::validateEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA MySQL JDBC URL is invalid");
    }

    @Test
    void validatesPoolTimeoutBoundsAndRoundsQueryTimeoutUp() {
        var properties = configured();
        properties.setConnectionTimeout(Duration.ofMillis(249));
        assertThatThrownBy(properties::validateEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA MySQL timeouts are invalid");

        properties = configured();
        properties.setQueryTimeout(Duration.ofMillis(999));
        assertThatThrownBy(properties::validateEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA MySQL timeouts are invalid");

        properties = configured();
        properties.setQueryTimeout(Duration.ofMillis(1_001));
        properties.validateEnabled();
        assertThat(properties.queryTimeoutSeconds()).isEqualTo(2);
    }

    private static OaMysqlProperties configured() {
        var properties = new OaMysqlProperties();
        properties.setEnabled(true);
        properties.setJdbcUrl(
                "jdbc:mysql://oa.example:3306/oa"
                        + "?useSSL=true&requireSSL=true");
        properties.setUsername("oa_readonly");
        properties.setPassword("not-a-real-secret");
        return properties;
    }
}
