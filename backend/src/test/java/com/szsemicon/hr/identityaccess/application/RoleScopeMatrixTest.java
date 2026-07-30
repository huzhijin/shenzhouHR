package com.szsemicon.hr.identityaccess.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RoleScopeMatrixTest {

    @ParameterizedTest
    @MethodSource("roleScopes")
    void roleCodesHaveOnlyTheirExplicitlyApprovedScopes(
            String roleCode,
            Set<String> expected) {
        assertThat(RoleScopeMatrix.allowedScopeTypes(roleCode))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    static Arguments[] roleScopes() {
        return new Arguments[] {
            Arguments.of("EMPLOYEE", Set.of("SELF")),
            Arguments.of("EMPLOYEE_SELF", Set.of("SELF")),
            Arguments.of("DEPARTMENT_MANAGER", Set.of("ORGANIZATION")),
            Arguments.of("DEPARTMENT_HEAD", Set.of("ORGANIZATION")),
            Arguments.of("MANUFACTURING_SUPERVISOR", Set.of("ORGANIZATION")),
            Arguments.of(
                    "MANUFACTURING_CENTER_SUPERVISOR",
                    Set.of("ORGANIZATION")),
            Arguments.of("HR_ADMIN", Set.of("COMPANY")),
            Arguments.of("SYSTEM_ADMIN", Set.of("COMPANY")),
            Arguments.of("AUDITOR", Set.of("COMPANY")),
            Arguments.of(
                    "EXECUTIVE",
                    Set.of("COMPANY", "ORGANIZATION")),
            Arguments.of("UNSIGNED_FUTURE_ROLE", Set.of())
        };
    }
}
