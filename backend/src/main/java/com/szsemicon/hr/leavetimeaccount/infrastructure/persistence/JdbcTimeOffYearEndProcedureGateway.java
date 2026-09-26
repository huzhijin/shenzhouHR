package com.szsemicon.hr.leavetimeaccount.infrastructure.persistence;

import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.ExpiryResult;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndProcedureGateway;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndSettings;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTimeOffYearEndProcedureGateway
        implements TimeOffYearEndProcedureGateway {

    private static final List<String> RESULT_COLUMNS = List.of(
            "source_request_id",
            "operation_status",
            "account_type",
            "account_year",
            "affected_hours",
            "balance_hours",
            "reserved_hours",
            "available_hours");

    private final JdbcTemplate jdbc;
    private final int queryTimeoutSeconds;

    public JdbcTimeOffYearEndProcedureGateway(
            JdbcTemplate jdbc,
            TimeOffYearEndSettings settings) {
        this.jdbc = jdbc;
        this.queryTimeoutSeconds = Math.toIntExact(Math.max(
                1L,
                settings.lockLease().minusSeconds(5).toSeconds()));
    }

    @Override
    public ExpiryResult expire(
            String employeeNumber,
            int accountYear,
            String eventId,
            String payloadDigest) {
        return jdbc.execute((ConnectionCallback<ExpiryResult>) connection ->
                executeCall(
                        connection,
                        employeeNumber,
                        accountYear,
                        eventId,
                        payloadDigest));
    }

    private ExpiryResult executeCall(
            Connection connection,
            String employeeNumber,
            int accountYear,
            String eventId,
            String payloadDigest) throws SQLException {
        try (CallableStatement statement = connection.prepareCall(
                "{CALL szsc_oa_time_off_expire(?, ?, ?, ?)}")) {
            // Bound a single call below the lease. The service renews the
            // database-clock lease immediately before and after the call.
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setString(1, employeeNumber);
            statement.setInt(2, accountYear);
            statement.setString(3, eventId);
            statement.setString(4, payloadDigest);

            boolean resultAvailable = statement.execute();
            ExpiryResult result = null;
            while (true) {
                if (resultAvailable) {
                    if (result != null) {
                        throw contractFailure("multiple result sets");
                    }
                    try (ResultSet resultSet = statement.getResultSet()) {
                        result = readSingleResult(resultSet);
                    }
                } else if (statement.getUpdateCount() == -1) {
                    break;
                }
                resultAvailable = statement.getMoreResults(
                        Statement.CLOSE_CURRENT_RESULT);
            }
            if (result == null) {
                throw contractFailure("missing result set");
            }
            return result;
        }
    }

    private ExpiryResult readSingleResult(ResultSet resultSet)
            throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        if (metadata.getColumnCount() != RESULT_COLUMNS.size()) {
            throw contractFailure("expected eight columns");
        }
        for (int index = 0; index < RESULT_COLUMNS.size(); index++) {
            String actual = metadata.getColumnLabel(index + 1);
            if (!RESULT_COLUMNS.get(index).equals(actual)) {
                throw contractFailure("unexpected column " + actual);
            }
        }
        if (!resultSet.next()) {
            throw contractFailure("missing result row");
        }
        ExpiryResult result = new ExpiryResult(
                resultSet.getString("source_request_id"),
                resultSet.getString("operation_status"),
                resultSet.getString("account_type"),
                requiredYear(resultSet),
                resultSet.getBigDecimal("affected_hours"),
                resultSet.getBigDecimal("balance_hours"),
                resultSet.getBigDecimal("reserved_hours"),
                resultSet.getBigDecimal("available_hours"));
        if (resultSet.next()) {
            throw contractFailure("multiple result rows");
        }
        return result;
    }

    private static int requiredYear(ResultSet resultSet) throws SQLException {
        int year = resultSet.getInt("account_year");
        if (resultSet.wasNull()) {
            throw contractFailure("account_year is null");
        }
        return year;
    }

    private static SQLException contractFailure(String detail) {
        return new SQLException(
                "SZSC_EXPIRY_RESULT_CONTRACT_INVALID: " + detail,
                "45000");
    }
}
