package com.szsemicon.hr.evidenceingestion.infrastructure.deli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class DeliEplusPropertiesTest {

    @Test
    void defaultsToDisabledAndNeverPrintsCredentials() {
        DeliEplusProperties properties = new DeliEplusProperties();
        properties.setAppKey("example-app-key");
        properties.setAppSecret("example-app-secret");

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getBaseUrl())
                .isEqualTo(URI.create("https://v2-api.delicloud.com"));
        assertThat(properties.toString())
                .contains("enabled=false", "appKey=<redacted>", "appSecret=<redacted>")
                .doesNotContain("example-app-key", "example-app-secret");
        assertThatThrownBy(properties::validateForEnabledClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Deli E+ integration is disabled");
    }

    @Test
    void rejectsNonOfficialEndpointsAndOutOfRangeDefaultPageSizes() {
        DeliEplusProperties properties = validProperties();
        properties.setBaseUrl(URI.create("http://v2-api.delicloud.com"));

        assertThatThrownBy(properties::validateForEnabledClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("official HTTPS endpoint");

        properties.setBaseUrl(DeliEplusProperties.OFFICIAL_BASE_URL);
        properties.setPageSize(501);
        assertThatThrownBy(properties::validateForEnabledClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("between 1 and 500");
    }

    @Test
    void rejectsUnsafeCredentialReferenceNames() {
        DeliEplusProperties properties = validProperties();
        properties.setCredentialReferenceName(
                "contains-a-secret-value");

        assertThatThrownBy(properties::validateForEnabledClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "credential reference name is invalid");
    }

    @Test
    void springDoesNotCreateARealClientUntilExplicitlyEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(DeliEplusConfiguration.class)
                .run(context -> assertThat(
                        context.getBeansOfType(DeliEplusClient.class)).isEmpty());
    }

    @Test
    void explicitExternalConfigurationCreatesClientWithoutMakingARequest() {
        new ApplicationContextRunner()
                .withUserConfiguration(DeliEplusConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues(
                        "shenzhouhr.integrations.deli-eplus.enabled=true",
                        "shenzhouhr.integrations.deli-eplus.app-key=example-app-key",
                        "shenzhouhr.integrations.deli-eplus.app-secret=example-app-secret")
                .run(context -> assertThat(
                        context.getBeansOfType(DeliEplusClient.class)).hasSize(1));
    }

    private static DeliEplusProperties validProperties() {
        DeliEplusProperties properties = new DeliEplusProperties();
        properties.setEnabled(true);
        properties.setAppKey("example-app-key");
        properties.setAppSecret("example-app-secret");
        return properties;
    }
}
