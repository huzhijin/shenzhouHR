package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OaMysqlConfigurationTest {

    @Test
    void disabledByDefaultAndDoesNotAffectMainDataSourceAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class))
                .withUserConfiguration(OaMysqlConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:oa-isolation",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(DataSource.class))
                            .hasSize(1)
                            .containsKey("dataSource");
                    assertThat(context.getBeansOfType(
                            OaReadOnlyConnectionPool.class)).isEmpty();
                    assertThat(context.getBeansOfType(
                            OaMysqlOrgMemberDirectoryAdapter.class))
                            .isEmpty();
                });
    }

    @Test
    void explicitEnablementActivatesBindingAndFailsSafelyOnInvalidConfig() {
        String username = "secret-oa-username";
        String password = "secret-oa-password";

        new ApplicationContextRunner()
                .withUserConfiguration(OaMysqlConfiguration.class)
                .withPropertyValues(
                        "shenzhouhr.integrations.oa-mysql.enabled=true",
                        "shenzhouhr.integrations.oa-mysql.jdbc-url="
                                + "jdbc:mysql://oa.example:3306/oa",
                        "shenzhouhr.integrations.oa-mysql.username="
                                + username,
                        "shenzhouhr.integrations.oa-mysql.password="
                                + password,
                        "shenzhouhr.integrations.oa-mysql.maximum-pool-size=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "OA MySQL pool size must be between 1 and 4");
                    assertThat(context.getStartupFailure().toString())
                            .doesNotContain(username, password);
                });
    }
}
