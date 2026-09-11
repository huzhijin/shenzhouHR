package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class OaReadOnlyConnectionPoolTest {

    @Test
    void createsAnIsolatedStrictReadOnlyPoolConfiguration() {
        var config = OaReadOnlyConnectionPool.configuration(
                configuredProperties());

        assertThat(config.getPoolName())
                .isEqualTo("shenzhouhr-oa-readonly");
        assertThat(config.isReadOnly()).isTrue();
        assertThat(config.isAutoCommit()).isTrue();
        assertThat(config.getMaximumPoolSize()).isEqualTo(2);
        assertThat(config.getMinimumIdle()).isZero();
        assertThat(config.getDataSourceProperties())
                .containsEntry(
                        "readOnlyPropagatesToServer", true)
                .containsEntry("useLocalSessionState", false)
                .containsEntry("allowMultiQueries", false);
        assertThat(DataSource.class.isAssignableFrom(
                OaReadOnlyConnectionPool.class)).isFalse();
    }

    @Test
    void returnsAConnectionOnlyAfterReadOnlyWasConfirmed()
            throws Exception {
        var dataSource = mock(HikariDataSource.class);
        var connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isReadOnly()).thenReturn(true);
        var pool = new OaReadOnlyConnectionPool(dataSource);

        assertThat(pool.openConnection()).isSameAs(connection);
        verify(connection).setReadOnly(true);
        verify(connection, never()).close();
    }

    @Test
    void closesBorrowedConnectionWhenReadOnlyCannotBeEnforced()
            throws Exception {
        var dataSource = mock(HikariDataSource.class);
        var connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isReadOnly()).thenReturn(false);
        var pool = new OaReadOnlyConnectionPool(dataSource);

        assertThatThrownBy(pool::openConnection)
                .isInstanceOf(SQLException.class)
                .hasMessage("OA connection rejected read-only mode");
        verify(connection).close();
    }

    private static OaMysqlProperties configuredProperties() {
        var properties = new OaMysqlProperties();
        properties.setEnabled(true);
        properties.setJdbcUrl(
                "jdbc:mysql://oa.example:3306/oa"
                        + "?sslMode=VERIFY_IDENTITY");
        properties.setUsername("oa_readonly");
        properties.setPassword("not-a-real-secret");
        return properties;
    }
}
