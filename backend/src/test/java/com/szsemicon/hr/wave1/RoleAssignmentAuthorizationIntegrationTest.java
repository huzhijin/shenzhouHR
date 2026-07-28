package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class RoleAssignmentAuthorizationIntegrationTest
        extends Wave1IntegrationTestSupport {

    private static final String SYSTEM_ADMIN_ROLE =
            "10000000-0000-0000-0000-000000000002";
    private static final String HR_ROLE =
            "12000000-0000-0000-0000-000000000001";
    private static final String DEPARTMENT_ROLE =
            "12000000-0000-0000-0000-000000000002";
    private static final String EMPLOYEE_ROLE =
            "12000000-0000-0000-0000-000000000003";
    private static final String LEGAL_ENTITY_ONE =
            "30000000-0000-0000-0000-000000000001";
    private static final String LEGAL_ENTITY_TWO =
            "30000000-0000-0000-0000-000000000002";
    private static final String MANUFACTURING_ORGANIZATION =
            "40000000-0000-0000-0000-000000000002";
    private static final String DESCENDANT_ORGANIZATION =
            "40000000-0000-0000-0000-000000000003";
    private static final String ROOT_ORGANIZATION =
            "40000000-0000-0000-0000-000000000001";
    private static final String ORGANIZATION_SCOPE =
            "90000000-0000-0000-0000-000000000002";

    @Test
    void crossLegalEntityGrantIsUnavailableAndDoesNotMutateTarget() throws Exception {
        insertRole(HR_ROLE, "HR_ADMIN", "HR 管理员");

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(
                                HR_ROLE,
                                "LEGAL_ENTITY",
                                LEGAL_ENTITY_TWO,
                                Instant.now().plusSeconds(60),
                                null,
                                0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_AVAILABLE"));

        assertTargetUnchanged();
    }

    @Test
    void selfTargetRoleReplacementIsAlwaysDenied() throws Exception {
        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        ADMIN_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(
                                SYSTEM_ADMIN_ROLE,
                                "LEGAL_ENTITY",
                                LEGAL_ENTITY_ONE,
                                Instant.now().plusSeconds(60),
                                null,
                                0)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("ROLE_ASSIGNMENT_SELF_SERVICE_DENIED"));
    }

    @Test
    void roleScopeMatrixAndUnknownRolesFailClosed() throws Exception {
        insertRole(HR_ROLE, "HR_ADMIN", "HR 管理员");

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(
                                HR_ROLE,
                                "ORGANIZATION",
                                MANUFACTURING_ORGANIZATION,
                                Instant.now().plusSeconds(60),
                                null,
                                0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_SCOPE_NOT_ALLOWED"));

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(
                                READER_ROLE,
                                "LEGAL_ENTITY",
                                LEGAL_ENTITY_ONE,
                                Instant.now().plusSeconds(60),
                                null,
                                0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_SCOPE_NOT_ALLOWED"));

        assertTargetUnchanged();
    }

    @Test
    void organizationAuthorityCanDelegateOnlyItsCurrentClosure() throws Exception {
        insertRole(DEPARTMENT_ROLE, "DEPARTMENT_HEAD", "部门负责人");
        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET data_scope_id = ?
                WHERE principal_id = ?
                """,
                ORGANIZATION_SCOPE,
                ADMIN_PRINCIPAL);

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(
                                DEPARTMENT_ROLE,
                                "ORGANIZATION",
                                DESCENDANT_ORGANIZATION,
                                Instant.now().minusSeconds(1),
                                null,
                                0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].scopeResourceId")
                        .value(DESCENDANT_ORGANIZATION));
    }

    @Test
    void organizationAuthorityCannotDelegateAnAncestorOutsideItsClosure() throws Exception {
        insertRole(DEPARTMENT_ROLE, "DEPARTMENT_HEAD", "部门负责人");
        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET data_scope_id = ?
                WHERE principal_id = ?
                """,
                ORGANIZATION_SCOPE,
                ADMIN_PRINCIPAL);

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(
                                DEPARTMENT_ROLE,
                                "ORGANIZATION",
                                ROOT_ORGANIZATION,
                                Instant.now().plusSeconds(60),
                                null,
                                0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_AVAILABLE"));

        assertTargetUnchanged();
    }

    @Test
    void expiringActorGrantCannotCreateAnUnboundedGrant() throws Exception {
        Instant now = Instant.now();
        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET valid_to = ?
                WHERE principal_id = ?
                """,
                Timestamp.from(now.plus(1, ChronoUnit.DAYS)),
                ADMIN_PRINCIPAL);

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(
                                SYSTEM_ADMIN_ROLE,
                                "LEGAL_ENTITY",
                                LEGAL_ENTITY_ONE,
                                now.plusSeconds(60),
                                null,
                                0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_AVAILABLE"));

        assertTargetUnchanged();
    }

    @Test
    void actorCannotBackdateBeforeTheStartOfItsOwnGrant() throws Exception {
        Instant now = Instant.now();
        Instant actorStarts = now.minus(1, ChronoUnit.DAYS);
        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET valid_from = ?
                WHERE principal_id = ?
                """,
                Timestamp.from(actorStarts),
                ADMIN_PRINCIPAL);

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(
                                SYSTEM_ADMIN_ROLE,
                                "LEGAL_ENTITY",
                                LEGAL_ENTITY_ONE,
                                actorStarts.minusSeconds(1),
                                now.plus(1, ChronoUnit.DAYS),
                                0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_AVAILABLE"));

        assertTargetUnchanged();
    }

    @Test
    void finiteGrantInsideOneActorAssignmentAndScopeIsAllowed() throws Exception {
        Instant now = Instant.now();
        Instant actorEnds = now.plus(1, ChronoUnit.DAYS);
        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET valid_to = ?
                WHERE principal_id = ?
                """,
                Timestamp.from(actorEnds),
                ADMIN_PRINCIPAL);

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(
                                SYSTEM_ADMIN_ROLE,
                                "LEGAL_ENTITY",
                                LEGAL_ENTITY_ONE,
                                now.minusSeconds(1),
                                actorEnds.minusSeconds(60),
                                0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].roleId")
                        .value(SYSTEM_ADMIN_ROLE));
    }

    @Test
    void selfAccountCreationRequiresAndPersistsCoveredEmployeeBinding() throws Exception {
        insertRole(EMPLOYEE_ROLE, "EMPLOYEE_SELF", "员工本人");
        String employeeId = "b1000000-0000-0000-0000-000000000001";
        insertEmployee(employeeId, LEGAL_ENTITY_ONE);
        String username = uniqueUsername("covered_self");

        mockMvc.perform(post("/api/v1/access/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                username,
                                employeeId,
                                EMPLOYEE_ROLE,
                                "SELF",
                                null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles[0].scopeType").value("SELF"));

        assertThat(jdbc.queryForObject(
                """
                SELECT principal.employee_id
                FROM auth_principal principal
                JOIN local_account account
                  ON account.principal_id = principal.principal_id
                WHERE account.normalized_username = ?
                """,
                String.class,
                username)).isEqualTo(employeeId);
    }

    @Test
    void selfCreationWithoutEmployeeAndNonSelfEmployeeSmugglingAreRejected() throws Exception {
        insertRole(EMPLOYEE_ROLE, "EMPLOYEE_SELF", "员工本人");
        String employeeId = "b1000000-0000-0000-0000-000000000002";
        insertEmployee(employeeId, LEGAL_ENTITY_ONE);

        mockMvc.perform(post("/api/v1/access/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                uniqueUsername("missing_employee"),
                                null,
                                EMPLOYEE_ROLE,
                                "SELF",
                                null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("EMPLOYEE_BINDING_REQUIRED"));

        mockMvc.perform(post("/api/v1/access/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                uniqueUsername("smuggled_employee"),
                                employeeId,
                                SYSTEM_ADMIN_ROLE,
                                "LEGAL_ENTITY",
                                LEGAL_ENTITY_ONE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("EMPLOYEE_BINDING_NOT_ALLOWED"));
    }

    @Test
    void selfCreationAcrossLegalEntityRollsBackAccountAndPrincipal() throws Exception {
        insertRole(EMPLOYEE_ROLE, "EMPLOYEE_SELF", "员工本人");
        String employeeId = "b1000000-0000-0000-0000-000000000003";
        insertEmployee(employeeId, LEGAL_ENTITY_TWO);
        String username = uniqueUsername("cross_legal_self");

        mockMvc.perform(post("/api/v1/access/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                username,
                                employeeId,
                                EMPLOYEE_ROLE,
                                "SELF",
                                null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_AVAILABLE"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_account WHERE normalized_username = ?",
                Long.class,
                username)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_principal WHERE employee_id = ?",
                Long.class,
                employeeId)).isZero();
    }

    @Test
    void employeeCanHaveOnlyOneLocalPrincipal() throws Exception {
        insertRole(EMPLOYEE_ROLE, "EMPLOYEE_SELF", "员工本人");

        mockMvc.perform(post("/api/v1/access/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                uniqueUsername("duplicate_employee"),
                                "b0000000-0000-0000-0000-000000000002",
                                EMPLOYEE_ROLE,
                                "SELF",
                                null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_ACCOUNT_CONFLICT"));
    }

    @Test
    void sameRoleCanKeepMultipleAuthorizedOrganizationRows() throws Exception {
        insertRole(DEPARTMENT_ROLE, "DEPARTMENT_HEAD", "部门负责人");
        Instant validFrom = Instant.now().minusSeconds(1);

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceTwoOrganizationBody(
                                DEPARTMENT_ROLE,
                                MANUFACTURING_ORGANIZATION,
                                DESCENDANT_ORGANIZATION,
                                validFrom,
                                0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2));

        assertThat(jdbc.queryForList(
                        """
                        SELECT scope.organization_id
                        FROM auth_principal_role_assignment assignment
                        JOIN auth_data_scope scope
                          ON scope.scope_id = assignment.data_scope_id
                        WHERE assignment.principal_id = ?
                          AND assignment.role_id = ?
                          AND assignment.valid_to IS NULL
                        ORDER BY scope.organization_id
                        """,
                        String.class,
                        STANDARD_PRINCIPAL,
                        DEPARTMENT_ROLE))
                .containsExactly(
                        MANUFACTURING_ORGANIZATION,
                        DESCENDANT_ORGANIZATION);
    }

    @Test
    void exactDuplicateAssignmentRowsAreRejectedBeforeMutation() throws Exception {
        insertRole(DEPARTMENT_ROLE, "DEPARTMENT_HEAD", "部门负责人");
        Instant validFrom = Instant.now().minusSeconds(1);

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceTwoOrganizationBody(
                                DEPARTMENT_ROLE,
                                MANUFACTURING_ORGANIZATION,
                                MANUFACTURING_ORGANIZATION,
                                validFrom,
                                0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertTargetUnchanged();
    }

    private void insertRole(String roleId, String roleCode, String roleName) {
        jdbc.update(
                """
                INSERT INTO auth_role (
                    role_id, role_code, role_name, permission_domain
                ) VALUES (?, ?, ?, 'IDENTITY')
                """,
                roleId,
                roleCode,
                roleName);
    }

    private void insertEmployee(String employeeId, String legalEntityId) {
        jdbc.update(
                """
                INSERT INTO employee (
                    employee_id, legal_entity_id, display_name,
                    employment_status, employee_number
                ) VALUES (?, ?, '授权测试员工', 'ACTIVE', ?)
                """,
                employeeId,
                legalEntityId,
                "AUTH-" + employeeId);
    }

    private void assertTargetUnchanged() {
        assertThat(jdbc.queryForObject(
                "SELECT row_version FROM local_account WHERE account_id = ?",
                Long.class,
                STANDARD_ACCOUNT)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth_principal_role_assignment
                WHERE principal_id = ?
                """,
                Long.class,
                STANDARD_PRINCIPAL)).isZero();
    }

    private String replaceBody(
            String roleId,
            String scopeType,
            String scopeResourceId,
            Instant validFrom,
            Instant validTo,
            long expectedVersion) {
        return """
               {
                 "assignments":[{
                   "roleId":"%s",
                   "scopeType":"%s",
                   "scopeResourceId":%s,
                   "validFrom":"%s",
                   "validTo":%s
                 }],
                 "reason":"权限委派自动化验证",
                 "expectedVersion":%d
               }
               """.formatted(
                roleId,
                scopeType,
                jsonString(scopeResourceId),
                validFrom,
                jsonString(validTo == null ? null : validTo.toString()),
                expectedVersion);
    }

    private String createBody(
            String username,
            String employeeId,
            String roleId,
            String scopeType,
            String scopeResourceId) {
        return """
               {
                 "username":"%s",
                 "displayName":"授权测试账号",
                 "temporaryPassword":"%s",
                 "employeeId":%s,
                 "roleAssignments":[{
                   "roleId":"%s",
                   "scopeType":"%s",
                   "scopeResourceId":%s,
                   "validFrom":"%s",
                   "validTo":null
                 }]
               }
               """.formatted(
                username,
                newTestSecret(),
                jsonString(employeeId),
                roleId,
                scopeType,
                jsonString(scopeResourceId),
                Instant.now().minusSeconds(1));
    }

    private String replaceTwoOrganizationBody(
            String roleId,
            String firstOrganizationId,
            String secondOrganizationId,
            Instant validFrom,
            long expectedVersion) {
        return """
               {
                 "assignments":[
                   {
                     "roleId":"%s",
                     "scopeType":"ORGANIZATION",
                     "scopeResourceId":"%s",
                     "validFrom":"%s",
                     "validTo":null
                   },
                   {
                     "roleId":"%s",
                     "scopeType":"ORGANIZATION",
                     "scopeResourceId":"%s",
                     "validFrom":"%s",
                     "validTo":null
                   }
                 ],
                 "reason":"同角色多范围验证",
                 "expectedVersion":%d
               }
               """.formatted(
                roleId,
                firstOrganizationId,
                validFrom,
                roleId,
                secondOrganizationId,
                validFrom,
                expectedVersion);
    }

    private String uniqueUsername(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String jsonString(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
