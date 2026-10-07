package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OaMysqlProperties.class)
@ConditionalOnProperty(
        prefix = "shenzhouhr.integrations.oa-mysql",
        name = "enabled",
        havingValue = "true")
public class OaMysqlConfiguration {

    @Bean(destroyMethod = "close")
    OaReadOnlyConnectionPool oaReadOnlyConnectionPool(
            OaMysqlProperties properties) {
        return new OaReadOnlyConnectionPool(properties);
    }

    @Bean
    OaMysqlOrgMemberDirectoryAdapter oaMysqlOrgMemberDirectoryAdapter(
            OaReadOnlyConnectionProvider connections,
            OaMysqlProperties properties) {
        return new OaMysqlOrgMemberDirectoryAdapter(
                connections, properties.queryTimeoutSeconds());
    }
}
