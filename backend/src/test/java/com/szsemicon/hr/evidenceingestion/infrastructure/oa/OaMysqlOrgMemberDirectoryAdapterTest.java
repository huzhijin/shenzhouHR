package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class OaMysqlOrgMemberDirectoryAdapterTest {

    @Test
    void usesOneFixedParameterizedReadOnlyLookupAndPreservesCode()
            throws Exception {
        var connections = mock(OaReadOnlyConnectionProvider.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var resultSet = mock(ResultSet.class);
        when(connections.openConnection()).thenReturn(connection);
        when(connection.prepareStatement(
                        OaMysqlOrgMemberDirectoryAdapter.FIND_MEMBER_SQL))
                .thenReturn(statement);
        when(connection.isReadOnly()).thenReturn(true);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("id")).thenReturn("123");
        when(resultSet.getString("code")).thenReturn("00Ab7");
        var adapter = new OaMysqlOrgMemberDirectoryAdapter(
                connections, 3);

        var result = adapter.findById(new BigInteger("123"));

        assertThat(result).singleElement().satisfies(record -> {
            assertThat(record.id()).isEqualTo(new BigInteger("123"));
            assertThat(record.code()).isEqualTo("00Ab7");
        });
        verify(connection).setReadOnly(true);
        verify(statement).setQueryTimeout(3);
        verify(statement).setMaxRows(2);
        verify(statement).setBigDecimal(1, new BigDecimal("123"));
        verify(resultSet).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void returnsBothRowsSoTheDomainCanFailClosedOnAmbiguity()
            throws Exception {
        var connections = mock(OaReadOnlyConnectionProvider.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var resultSet = mock(ResultSet.class);
        when(connections.openConnection()).thenReturn(connection);
        when(connection.prepareStatement(
                        OaMysqlOrgMemberDirectoryAdapter.FIND_MEMBER_SQL))
                .thenReturn(statement);
        when(connection.isReadOnly()).thenReturn(true);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("id")).thenReturn("123");
        when(resultSet.getString("code"))
                .thenReturn("0007", "0007");
        var adapter = new OaMysqlOrgMemberDirectoryAdapter(
                connections, 3);

        assertThat(adapter.findById(new BigInteger("123")))
                .hasSize(2);
    }

    @Test
    void databaseFailuresExposeOnlyASafeCodeAndMessage()
            throws Exception {
        var connections = mock(OaReadOnlyConnectionProvider.class);
        when(connections.openConnection()).thenThrow(
                new SQLException(
                        "password=do-not-leak; host=secret.internal"));
        var adapter = new OaMysqlOrgMemberDirectoryAdapter(
                connections, 3);

        assertThatThrownBy(() ->
                adapter.findById(new BigInteger("123")))
                .isInstanceOf(OaReadOnlyQueryException.class)
                .satisfies(exception -> assertThat(
                        ((OaReadOnlyQueryException) exception)
                                .safeCode())
                        .isEqualTo("OA_ORG_MEMBER_QUERY_FAILED"))
                .hasMessage("OA member lookup could not be completed")
                .hasMessageNotContaining("do-not-leak")
                .hasMessageNotContaining("secret.internal");
    }

    @Test
    void runtimeDriverFailuresAlsoExposeOnlyTheSafeBoundary()
            throws Exception {
        var connections = mock(OaReadOnlyConnectionProvider.class);
        when(connections.openConnection()).thenThrow(
                new IllegalStateException(
                        "username=do-not-leak; jdbc:mysql://secret"));
        var adapter = new OaMysqlOrgMemberDirectoryAdapter(
                connections, 3);

        assertThatThrownBy(() ->
                adapter.findById(new BigInteger("123")))
                .isInstanceOf(OaReadOnlyQueryException.class)
                .hasMessage("OA member lookup could not be completed")
                .hasMessageNotContaining("do-not-leak")
                .hasMessageNotContaining("jdbc:mysql")
                .hasCause(null);
    }

    @Test
    void failsClosedWhenTheConnectionDoesNotEnterReadOnlyMode()
            throws Exception {
        var connections = mock(OaReadOnlyConnectionProvider.class);
        var connection = mock(Connection.class);
        when(connections.openConnection()).thenReturn(connection);
        when(connection.isReadOnly()).thenReturn(false);
        var adapter = new OaMysqlOrgMemberDirectoryAdapter(
                connections, 3);

        assertThatThrownBy(() ->
                adapter.findById(new BigInteger("123")))
                .isInstanceOf(OaReadOnlyQueryException.class)
                .hasMessage("OA member lookup could not be completed")
                .hasCause(null);
        verify(connection).close();
    }

    @Test
    void malformedDatabaseIdsFailWithOnlyTheSafeException()
            throws Exception {
        var connections = mock(OaReadOnlyConnectionProvider.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var resultSet = mock(ResultSet.class);
        when(connections.openConnection()).thenReturn(connection);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.prepareStatement(
                        OaMysqlOrgMemberDirectoryAdapter.FIND_MEMBER_SQL))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id"))
                .thenReturn("not-an-id password=do-not-leak");
        var adapter = new OaMysqlOrgMemberDirectoryAdapter(
                connections, 3);

        assertThatThrownBy(() ->
                adapter.findById(new BigInteger("123")))
                .isInstanceOf(OaReadOnlyQueryException.class)
                .hasMessage("OA member lookup could not be completed")
                .hasMessageNotContaining("not-an-id")
                .hasMessageNotContaining("do-not-leak")
                .hasCause(null);
    }

    @Test
    void rejectsNonPositiveIdsBeforeOpeningAConnection() {
        var connections = mock(OaReadOnlyConnectionProvider.class);
        var adapter = new OaMysqlOrgMemberDirectoryAdapter(
                connections, 3);

        assertThatThrownBy(() -> adapter.findById(BigInteger.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(connections);
    }
}
