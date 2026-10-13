package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;

final class OaReadOnlyConnectionPool
        implements OaReadOnlyConnectionProvider, AutoCloseable {

    private final HikariDataSource dataSource;

    OaReadOnlyConnectionPool(OaMysqlProperties properties) {
        this(new HikariDataSource(configuration(properties)));
    }

    OaReadOnlyConnectionPool(HikariDataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException(
                    "OA data source is required");
        }
        this.dataSource = dataSource;
    }

    static HikariConfig configuration(
            OaMysqlProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException(
                    "OA MySQL properties are required");
        }
        properties.validateEnabled();
        HikariConfig config = new HikariConfig();
        config.setPoolName("shenzhouhr-oa-readonly");
        config.setJdbcUrl(properties.getJdbcUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setReadOnly(true);
        config.setAutoCommit(true);
        config.addDataSourceProperty(
                "readOnlyPropagatesToServer", true);
        config.addDataSourceProperty(
                "useLocalSessionState", false);
        config.addDataSourceProperty(
                "allowMultiQueries", false);
        config.setMaximumPoolSize(properties.getMaximumPoolSize());
        config.setMinimumIdle(0);
        long connectionTimeoutMillis =
                properties.getConnectionTimeout().toMillis();
        config.setConnectionTimeout(connectionTimeoutMillis);
        config.setValidationTimeout(Math.min(
                connectionTimeoutMillis, 5_000));
        return config;
    }

    @Override
    public Connection openConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            connection.setReadOnly(true);
            if (!connection.isReadOnly()) {
                throw new SQLException(
                        "OA connection rejected read-only mode");
            }
            return connection;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException
                    | RuntimeException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
