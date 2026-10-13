package com.szsemicon.hr.leavetimeaccount.infrastructure.scheduler;

import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndSettings;
import java.lang.management.ManagementFactory;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TimeOffYearEndProperties.class)
class TimeOffYearEndConfiguration {

    @Bean
    TimeOffYearEndSettings timeOffYearEndSettings(
            TimeOffYearEndProperties properties) {
        String configuredNodeId = properties.getNodeId();
        String owner = configuredNodeId == null || configuredNodeId.isBlank()
                ? generatedNodeId()
                : configuredNodeId.trim();
        return new TimeOffYearEndSettings(
                properties.getLockLease(), properties.getZone(), owner);
    }

    private static String generatedNodeId() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        String value = "hr-year-end@" + runtimeName + ":"
                + UUID.randomUUID().toString().substring(0, 8);
        return value.length() <= 128 ? value : value.substring(0, 128);
    }
}
