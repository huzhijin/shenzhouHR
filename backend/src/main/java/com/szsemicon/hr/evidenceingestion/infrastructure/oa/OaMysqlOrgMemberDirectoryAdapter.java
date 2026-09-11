package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import com.szsemicon.hr.evidenceingestion.port.OaOrgMemberDirectoryPort;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class OaMysqlOrgMemberDirectoryAdapter
        implements OaOrgMemberDirectoryPort {

    static final String FIND_MEMBER_SQL =
            "SELECT id, code, name FROM org_member WHERE id = ?";

    private final OaReadOnlyConnectionProvider connections;
    private final int queryTimeoutSeconds;

    OaMysqlOrgMemberDirectoryAdapter(
            OaReadOnlyConnectionProvider connections,
            int queryTimeoutSeconds) {
        if (connections == null
                || queryTimeoutSeconds < 1
                || queryTimeoutSeconds > 180) {
            throw new IllegalArgumentException(
                    "OA member directory configuration is invalid");
        }
        this.connections = connections;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public List<OrgMemberRecord> findById(BigInteger orgMemberId) {
        if (orgMemberId == null || orgMemberId.signum() <= 0) {
            throw new IllegalArgumentException(
                    "OA org_member.id must be a positive integer");
        }
        try (var connection = connections.openConnection()) {
            connection.setReadOnly(true);
            if (!connection.isReadOnly()) {
                throw new SQLException(
                        "OA connection rejected read-only mode");
            }
            try (var statement =
                    connection.prepareStatement(FIND_MEMBER_SQL)) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                statement.setMaxRows(2);
                statement.setBigDecimal(1, new BigDecimal(orgMemberId));
                try (var resultSet = statement.executeQuery()) {
                    List<OrgMemberRecord> matches =
                            new ArrayList<>(2);
                    while (resultSet.next()) {
                        String rawId = resultSet.getString("id");
                        String code = resultSet.getString("code");
                        String name = resultSet.getString("name");
                        matches.add(new OrgMemberRecord(
                                parseMemberId(rawId), code, name));
                    }
                    return List.copyOf(matches);
                }
            }
        } catch (SQLException | RuntimeException exception) {
            throw new OaReadOnlyQueryException(
                    "OA_ORG_MEMBER_QUERY_FAILED",
                    "OA member lookup could not be completed");
        }
    }

    private static BigInteger parseMemberId(String rawId) {
        if (rawId == null) {
            throw new NumberFormatException(
                    "OA org_member.id was null");
        }
        return new BigInteger(rawId);
    }
}
