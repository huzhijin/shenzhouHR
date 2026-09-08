package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EvidenceEmployeeResolverSqlContractTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/EvidenceEmployeeResolverMapper.xml");

    @Test
    void resolverIsExactScopedTemporalAndFailClosedOnCardinality()
            throws Exception {
        String sql = Files.readString(MAPPER);

        assertThat(sql)
                .doesNotContain("employee.company_id = #{companyId}")
                .contains("employee.employee_id,\n        employee.company_id,")
                .contains(
                        "employee_version.employee_number ="
                                + " #{employeeNumber}")
                .contains("employee_version.status = 'ACTIVE'")
                .contains("employment.record_status = 'ACTIVE'")
                .contains("employment.version_valid_to IS NULL")
                .contains("organization.identity_status = 'ACTIVE'")
                .contains("binding.binding_status = 'CONFIRMED'")
                .contains("binding.confirmation_ref IS NOT NULL")
                .contains("#{bindingKind} IN ('DELI_EXT_ID', 'DELI_USER_ID')")
                .contains("binding.deli_user_id = #{externalPersonRef}")
                .contains("binding.deli_ext_id = #{externalPersonRef}")
                .contains("employee_version.display_name = #{displayName}")
                .contains("LIMIT 2");
        assertThat(sql)
                .doesNotContain(
                        "binding.deli_employee_num ="
                                + " #{externalPersonRef}");
        assertThat(sql.toUpperCase(java.util.Locale.ROOT))
                .doesNotContain("LOWER(")
                .doesNotContain("TRIM(")
                .doesNotContain("CAST(#{EMPLOYEENUMBER}");
    }

    @Test
    void resolverRowConstructorKeepsNullableBindingIdBeforePrimitiveVersions()
            throws Exception {
        String sql = Files.readString(MAPPER);
        int identityThenBinding = sql.indexOf(
                "<include refid=\"resolverIdentityProjection\"/>,\n"
                        + "            NULL AS binding_id,\n"
                        + "            <include refid=\"resolverVersionProjection\"/>");
        int identityThenConfirmedBinding = sql.indexOf(
                "<include refid=\"resolverIdentityProjection\"/>,\n"
                        + "            binding.binding_id,\n"
                        + "            <include refid=\"resolverVersionProjection\"/>");

        assertThat(identityThenBinding).isGreaterThan(0);
        assertThat(identityThenConfirmedBinding).isGreaterThan(0);
        assertThat(sql).doesNotContain("resultMap=");
    }
}
