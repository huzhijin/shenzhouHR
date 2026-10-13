package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.evidenceingestion.domain.EvidenceResolutionPolicy;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.Resolution;
import com.szsemicon.hr.evidenceingestion.port.OaOrgMemberDirectoryPort.OrgMemberRecord;
import java.math.BigInteger;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OaOrgMemberSignedIdLookupTest {

    private static final BigInteger HUANG_MEMBER = new BigInteger("-7874135810509684136");
    private static final Instant AT = Instant.parse("2026-09-20T10:30:00Z");
    private static final Resolution HUANG = new Resolution(
            "2e810468-1f1d-49c2-b778-21b1687bc16f", "active-since-2026-09-14",
            "resolver-proof", "company-3");

    @Test
    void negativeLookupUsesItsExactSignedIdAndStoredNameForTheExistingFallback() throws Exception {
        var member = lookup(HUANG_MEMBER, "SZST0759", "黄波涛");

        assertThat(member.id()).isEqualTo(HUANG_MEMBER);
        assertThat(member.code()).isEqualTo("SZST0759");
        assertThat(member.name()).isEqualTo("黄波涛");
        var decision = resolve(member, List.of(HUANG));
        assertThat(decision.status()).isEqualTo(EvidenceResolutionPolicy.MatchStatus.MATCHED);
        assertThat(decision.reason()).isEqualTo("DISPLAY_NAME");
        assertThat(decision.employeeId()).isEqualTo(HUANG.employeeId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1", "-9223372036854775808", "9223372036854775807"})
    void realPreparedLookupPreservesTheFullSignedBigintRange(String text) throws Exception {
        BigInteger id = new BigInteger(text);
        var member = lookup(id, "00Ab7", "边界员工");
        assertThat(member.id()).isEqualTo(id);
        assertThat(member.code()).isEqualTo("00Ab7");
        assertThat(member.name()).isEqualTo("边界员工");
    }

    @Test
    void negativeIdDoesNotAuthorizeChoosingBetweenDuplicateExactNames() throws Exception {
        var member = lookup(HUANG_MEMBER, "SZST0759", "黄波涛");
        var decision = resolve(member, List.of(HUANG,
                new Resolution("other-employee", "other-employment", "other-proof", "company-3")));
        assertThat(decision.status()).isEqualTo(EvidenceResolutionPolicy.MatchStatus.UNMATCHED);
        assertThat(decision.reason()).isEqualTo("NO_AUTHORITATIVE_MATCH");
        assertThat(decision.employeeId()).isNull();
    }

    @Test
    void lookupDoesNotTrimTheNameToCreateAnAuthoritativeMatch() throws Exception {
        var member = lookup(HUANG_MEMBER, "SZST0759", "黄波涛 ");
        assertThat(member.name()).isEqualTo("黄波涛 ");
        var decision = resolve(member, List.of(HUANG));
        assertThat(decision.status()).isEqualTo(EvidenceResolutionPolicy.MatchStatus.UNMATCHED);
        assertThat(decision.employeeId()).isNull();
    }

    private static EvidenceResolutionPolicy.MatchDecision resolve(
            OrgMemberRecord member, List<Resolution> exactNameMatches) {
        var resolver = mock(EmployeeEmploymentResolverPort.class);
        when(resolver.resolveByEmployeeNumber(anyString(), anyString(), any())).thenReturn(List.of());
        when(resolver.resolveByDisplayName("company-3", "黄波涛", AT)).thenReturn(exactNameMatches);
        return EvidenceResolutionPolicy.resolve(resolver, "source-oa", "company-3", member.code(),
                null, null, member.id().toString(), null, member.name(), AT);
    }

    private static OrgMemberRecord lookup(BigInteger id, String code, String name) throws Exception {
        try (var real = DriverManager.getConnection("jdbc:h2:mem:;MODE=MySQL")) {
            try (var schema = real.createStatement()) {
                schema.execute("CREATE TABLE org_member(id BIGINT PRIMARY KEY, code VARCHAR(64), name VARCHAR(64))");
            }
            try (var insert = real.prepareStatement("INSERT INTO org_member(id,code,name) VALUES(?,?,?)")) {
                insert.setLong(1, id.longValueExact());
                insert.setString(2, code);
                insert.setString(3, name);
                insert.executeUpdate();
                // A sign-stripping bug must select a different person, never pass unnoticed.
                if (id.equals(HUANG_MEMBER)) {
                    insert.setLong(1, id.negate().longValueExact());
                    insert.setString(2, "UNRELATED");
                    insert.setString(3, "另一人");
                    insert.executeUpdate();
                }
            }
            // H2 does not implement the JDBC read-only flag. Only acknowledge that
            // flag here; production SQL, parameter binding and ResultSet stay real.
            var jdbc = spy(real);
            doReturn(true).when(jdbc).isReadOnly();
            var rows = new OaMysqlOrgMemberDirectoryAdapter(() -> jdbc, 3).findById(id);
            assertThat(rows).hasSize(1);
            return rows.getFirst();
        }
    }
}
