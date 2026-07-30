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
                .contains("employee.company_id = #{companyId}")
                .contains(
                        "employee_version.employee_number ="
                                + " #{employeeNumber}")
                .contains("employee_version.status = 'ACTIVE'")
                .contains("employment.record_status = 'ACTIVE'")
                .contains("employment.version_valid_to IS NULL")
                .contains("organization.identity_status = 'ACTIVE'")
                .contains("binding.binding_status = 'CONFIRMED'")
                .contains("binding.confirmation_ref IS NOT NULL")
                .contains("#{bindingKind} = 'DELI_EXT_ID'")
                .contains("#{bindingKind} = 'DELI_USER_ID'")
                .contains("binding.deli_user_id = #{externalPersonRef}")
                .contains("binding.deli_ext_id = #{externalPersonRef}")
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
}
