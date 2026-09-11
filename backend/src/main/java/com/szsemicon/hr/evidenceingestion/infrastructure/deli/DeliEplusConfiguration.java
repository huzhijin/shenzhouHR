package com.szsemicon.hr.evidenceingestion.infrastructure.deli;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeliEplusProperties.class)
@ConditionalOnProperty(
        prefix = "shenzhouhr.integrations.deli-eplus",
        name = "enabled",
        havingValue = "true")
public class DeliEplusConfiguration {

    @Bean
    DeliEplusSigner deliEplusSigner() {
        return new DeliEplusSigner();
    }

    @Bean
    DeliEplusHttpTransport deliEplusHttpTransport(
            DeliEplusProperties properties) {
        return new JdkDeliEplusHttpTransport(properties);
    }

    @Bean
    @Primary
    DeliEplusClient deliEplusClient(
            DeliEplusProperties properties,
            DeliEplusSigner signer,
            DeliEplusHttpTransport transport,
            ObjectMapper objectMapper,
            Clock clock) {
        return new DeliEplusClient(
                properties,
                signer,
                transport,
                objectMapper,
                clock);
    }
}
