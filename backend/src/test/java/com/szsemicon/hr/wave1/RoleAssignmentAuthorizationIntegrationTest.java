package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    private static final String COMPANY_ONE =
            "30000000-0000-0000-0000-000000000001";
    private static final String COMPANY_TWO =
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
    void crossCompanyGrantIsUnavailableAndDoesNotMutateTarget() throws Exception {
        insertRole(HR_ROLE, "HR_ADMIN", "HR 管理员");

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(
                                HR_ROLE,
                                "COMPANY",
                                COMPANY_TWO,
                                Instant.now().plusSeconds(60),
                                null,
                                0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_AVAILABLE"));

        assertTargetUnchanged();
    }

    @Test
    void accountCreateAndRoleAssignScopesMustIntersectForEveryRequestedCompany()
            throws Exception {
        String createOnlyRole = "12000000-0000-0000-0000-000000000011";
        String assignOnlyRole = "12000000-0000-0000-0000-000000000012";
        String companyTwoScope = "92000000-0000-0000-0000-000000000012";
        String companyTwoAssignment = "a2000000-0000-0000-0000-000000000012";
        insertRole(createOnlyRole, "CREATE_ONLY_TEST", "仅创建账号测试角色");
        insertRole(assignOnlyRole, "ASSIGN_ONLY_TEST", "仅分配角色测试角色");
        insertRole(HR_ROLE, "HR_ADMIN", "HR 管理员");
        jdbc.update(
                """
                INSERT INTO auth_role_capability (role_id, capability_id)
                SELECT ?, capability_id
                FROM auth_capability
                WHERE capability_code = 'ACCOUNT:CREATE'
                """,
                createOnlyRole);
        jdbc.update(
                """
                INSERT INTO auth_role_capability (role_id, capability_id)
                SELECT ?, capability_id
                FROM auth_capability
                WHERE capability_code = 'ROLE:ASSIGN'
                """,
                assignOnlyRole);
        jdbc.update(
                "UPDATE auth_principal_role_assignment SET role_id = ? WHERE principal_id = ?",
                createOnlyRole,
                ADMIN_PRINCIPAL);
        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, company_id, organization_id,
                    include_descendants, valid_from, valid_to
                ) VALUES (?, 'COMPANY', ?, NULL, TRUE, ?, NULL)
                """,
                companyTwoScope,
                COMPANY_TWO,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to, assigned_by, reason, row_version
                ) VALUES (?, ?, ?, ?, ?, NULL, ?, '分离能力范围负例', 0)
                """,
                companyTwoAssignment,
                ADMIN_PRINCIPAL,
                assignOnlyRole,
                companyTwoScope,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                ADMIN_PRINCIPAL);
        String username = uniqueUsername("split_create_assign_scope");

        mockMvc.perform(post("/api/v1/access/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                username,
                                null,
                                HR_ROLE,
                                "COMPANY",
                                COMPANY_TWO)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("ROLE_ASSIGNMENT_SCOPE_DENIED"));

        mockMvc.perform(get("/api/v1/access/grantable-scopes/companies")
                        .queryParam("scopeType", "COMPANY")
                        .queryParam("usage", "ACCOUNT_CREATION")
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_account WHERE normalized_username = ?",
                Long.class,
                username)).isZero();
    }

    @Test
    void accountCreateAuthorityNeedsOnlyCurrentCoverageWhileRoleAssignCoversValidity()
            throws Exception {
        String createOnlyRole = "12000000-0000-0000-0000-000000000021";
        String assignOnlyRole = "12000000-0000-0000-0000-000000000022";
        String createOnlyAssignment = "a2000000-0000-0000-0000-000000000021";
        insertRole(createOnlyRole, "CREATE_CURRENT_TEST", "当前建号测试角色");
        insertRole(assignOnlyRole, "ASSIGN_UNBOUNDED_TEST", "长期授权测试角色");
        insertRole(HR_ROLE, "HR_ADMIN", "HR 管理员");
        jdbc.update(
                """
                INSERT INTO auth_role_capability (role_id, capability_id)
                SELECT ?, capability_id
                FROM auth_capability
                WHERE capability_code = 'ACCOUNT:CREATE'
                """,
                createOnlyRole);
        jdbc.update(
                """
                INSERT INTO auth_role_capability (role_id, capability_id)
                SELECT ?, capability_id
                FROM auth_capability
                WHERE capability_code = 'ROLE:ASSIGN'
                """,
                assignOnlyRole);
        String actorScope = jdbc.queryForObject(
                """
                SELECT data_scope_id
                FROM auth_principal_role_assignment
                WHERE principal_id = ?
                  AND valid_to IS NULL
                """,
                String.class,
                ADMIN_PRINCIPAL);
        jdbc.update(
                "UPDATE auth_principal_role_assignment SET role_id = ? WHERE principal_id = ?",
                assignOnlyRole,
                ADMIN_PRINCIPAL);
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to, assigned_by, reason, row_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, '限时建号能力', 0)
                """,
                createOnlyAssignment,
                ADMIN_PRINCIPAL,
                createOnlyRole,
                actorScope,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)),
                ADMIN_PRINCIPAL);
        String username = uniqueUsername("current_create_unbounded_assignment");

        mockMvc.perform(post("/api/v1/access/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                username,
                                null,
                                HR_ROLE,
                                "COMPANY",
                                COMPANY_ONE)))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_account WHERE normalized_username = ?",
                Long.class,
                username)).isOne();
    }

    @Test
    void legacyScopeLiteralIsRejectedBeforeAnyAuthorizationMutation()
            throws Exception {
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
                                COMPANY_ONE,
                                Instant.now().minusSeconds(1),
                                null,
                                0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

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
                                "COMPANY",
                                COMPANY_ONE,
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
                                "COMPANY",
                                COMPANY_ONE,
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
    void exactOrganizationAuthorityCannotDelegateDescendantsButCanDelegateExactScope()
            throws Exception {
        insertRole(DEPARTMENT_ROLE, "DEPARTMENT_HEAD", "部门负责人");
        bindStandardPrincipalToOrganization(
                "b1000000-0000-0000-0000-000000000041",
                "c1000000-0000-0000-0000-000000000041",
                MANUFACTURING_ORGANIZATION);
        jdbc.update(
                "UPDATE auth_data_scope SET include_descendants = FALSE WHERE scope_id = ?",
                ORGANIZATION_SCOPE);
        jdbc.update(
                "UPDATE auth_principal_role_assignment SET data_scope_id = ? WHERE principal_id = ?",
                ORGANIZATION_SCOPE,
                ADMIN_PRINCIPAL);
        Instant validFrom = Instant.now().minusSeconds(1);

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceOrganizationBody(
                                DEPARTMENT_ROLE,
                                MANUFACTURING_ORGANIZATION,
                                true,
                                validFrom,
                                0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_AVAILABLE"));

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceOrganizationBody(
                                DEPARTMENT_ROLE,
                                MANUFACTURING_ORGANIZATION,
                                false,
                                validFrom,
                                0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].scopeCompanyId")
                        .value(COMPANY_ONE))
                .andExpect(jsonPath("$.roles[0].includeDescendants")
                        .value(false));

        assertThat(jdbc.queryForObject(
                """
                SELECT scope.include_descendants
                FROM auth_principal_role_assignment assignment
                JOIN auth_data_scope scope
                  ON scope.scope_id = assignment.data_scope_id
                WHERE assignment.principal_id = ?
                  AND assignment.valid_to IS NULL
                """,
                Boolean.class,
                STANDARD_PRINCIPAL)).isFalse();
    }

    @Test
    void grantableScopeDirectoryIsCapabilityScopedAndCompanyFirst()
            throws Exception {
        jdbc.update(
                "UPDATE auth_data_scope SET include_descendants = FALSE WHERE scope_id = ?",
                ORGANIZATION_SCOPE);
        jdbc.update(
                "UPDATE auth_principal_role_assignment SET data_scope_id = ? WHERE principal_id = ?",
                ORGANIZATION_SCOPE,
                ADMIN_PRINCIPAL);

        mockMvc.perform(get("/api/v1/access/grantable-scopes/companies")
                        .queryParam("scopeType", "COMPANY")
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/access/grantable-scopes/companies")
                        .queryParam("scopeType", "ORGANIZATION")
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].companyId").value(COMPANY_ONE));

        mockMvc.perform(get(
                        "/api/v1/access/grantable-scopes/companies/{companyId}/organizations",
                        COMPANY_ONE)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].organizationId")
                        .value(MANUFACTURING_ORGANIZATION))
                .andExpect(jsonPath("$[0].companyId").value(COMPANY_ONE))
                .andExpect(jsonPath("$[0].canIncludeDescendants").value(false));

        mockMvc.perform(get(
                        "/api/v1/access/grantable-scopes/companies/{companyId}/organizations",
                        COMPANY_TWO)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void companyOrganizationAuthorityMarksEveryGrantableNodeAsDescendantCapable()
            throws Exception {
        mockMvc.perform(get(
                        "/api/v1/access/grantable-scopes/companies/{companyId}/organizations",
                        COMPANY_ONE)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].canIncludeDescendants").value(true));
    }

    @Test
    void accountCreationOrganizationDirectoryRequiresBothCapabilitiesOnSameNode()
            throws Exception {
        String createOnlyRole = "12000000-0000-0000-0000-000000000051";
        String assignOnlyRole = "12000000-0000-0000-0000-000000000052";
        String createScope = "92000000-0000-0000-0000-000000000051";
        String assignScope = "92000000-0000-0000-0000-000000000052";
        String assignAssignment = "a2000000-0000-0000-0000-000000000052";
        insertRole(createOnlyRole, "CREATE_ORG_A_TEST", "甲部门建号测试角色");
        insertRole(assignOnlyRole, "ASSIGN_ORG_B_TEST", "乙部门授权测试角色");
        jdbc.update(
                """
                INSERT INTO auth_role_capability (role_id, capability_id)
                SELECT ?, capability_id FROM auth_capability
                WHERE capability_code = 'ACCOUNT:CREATE'
                """,
                createOnlyRole);
        jdbc.update(
                """
                INSERT INTO auth_role_capability (role_id, capability_id)
                SELECT ?, capability_id FROM auth_capability
                WHERE capability_code = 'ROLE:ASSIGN'
                """,
                assignOnlyRole);
        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, company_id, organization_id,
                    include_descendants, valid_from, valid_to
                ) VALUES
                  (?, 'ORGANIZATION', NULL, ?, FALSE, ?, NULL),
                  (?, 'ORGANIZATION', NULL, ?, FALSE, ?, NULL)
                """,
                createScope,
                MANUFACTURING_ORGANIZATION,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                assignScope,
                DESCENDANT_ORGANIZATION,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET role_id = ?, data_scope_id = ?
                WHERE principal_id = ?
                """,
                createOnlyRole,
                createScope,
                ADMIN_PRINCIPAL);
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to, assigned_by, reason, row_version
                ) VALUES (?, ?, ?, ?, ?, NULL, ?, '错位组织范围负例', 0)
                """,
                assignAssignment,
                ADMIN_PRINCIPAL,
                assignOnlyRole,
                assignScope,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                ADMIN_PRINCIPAL);

        mockMvc.perform(get("/api/v1/access/grantable-scopes/companies")
                        .queryParam("scopeType", "ORGANIZATION")
                        .queryParam("usage", "ACCOUNT_CREATION")
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get(
                        "/api/v1/access/grantable-scopes/companies/{companyId}/organizations",
                        COMPANY_ONE)
                        .queryParam("usage", "ACCOUNT_CREATION")
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void exactOrganizationAuthorityCannotAccessTargetWithWiderDescendantRoleScope()
            throws Exception {
        String targetScope = "92000000-0000-0000-0000-000000000031";
        String targetAssignment = "a2000000-0000-0000-0000-000000000031";
        insertRole(DEPARTMENT_ROLE, "DEPARTMENT_HEAD", "部门负责人");
        bindStandardPrincipalToOrganization(
                "b1000000-0000-0000-0000-000000000031",
                "c1000000-0000-0000-0000-000000000031",
                MANUFACTURING_ORGANIZATION);
        jdbc.update(
                "UPDATE auth_data_scope SET include_descendants = FALSE WHERE scope_id = ?",
                ORGANIZATION_SCOPE);
        jdbc.update(
                "UPDATE auth_principal_role_assignment SET data_scope_id = ? WHERE principal_id = ?",
                ORGANIZATION_SCOPE,
                ADMIN_PRINCIPAL);
        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, company_id, organization_id,
                    include_descendants, valid_from, valid_to
                ) VALUES (?, 'ORGANIZATION', NULL, ?, TRUE, ?, NULL)
                """,
                targetScope,
                MANUFACTURING_ORGANIZATION,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to, assigned_by, reason, row_version
                ) VALUES (?, ?, ?, ?, ?, NULL, ?, '宽组织目标范围', 0)
                """,
                targetAssignment,
                STANDARD_PRINCIPAL,
                DEPARTMENT_ROLE,
                targetScope,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                ADMIN_PRINCIPAL);

        mockMvc.perform(get("/api/v1/access/accounts/{accountId}", STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_AVAILABLE"));
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
                                "COMPANY",
                                COMPANY_ONE,
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
                                "COMPANY",
                                COMPANY_ONE,
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
                                "COMPANY",
                                COMPANY_ONE,
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
        insertEmployee(employeeId, COMPANY_ONE);
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
        insertEmployee(employeeId, COMPANY_ONE);

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
                                "COMPANY",
                                COMPANY_ONE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("EMPLOYEE_BINDING_NOT_ALLOWED"));
    }

    @Test
    void selfCreationAcrossCompanyRollsBackAccountAndPrincipal() throws Exception {
        insertRole(EMPLOYEE_ROLE, "EMPLOYEE_SELF", "员工本人");
        String employeeId = "b1000000-0000-0000-0000-000000000003";
        insertEmployee(employeeId, COMPANY_TWO);
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
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("ROLE_ASSIGNMENT_SCOPE_DENIED"));

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

    private void insertEmployee(String employeeId, String companyId) {
        jdbc.update(
                """
                INSERT INTO employee (
                    employee_id, company_id, display_name,
                    employment_status, employee_number
                ) VALUES (?, ?, '授权测试员工', 'ACTIVE', ?)
                """,
                employeeId,
                companyId,
                "AUTH-" + employeeId);
    }

    private void bindStandardPrincipalToOrganization(
            String employeeId,
            String assignmentId,
            String organizationId) {
        insertEmployee(employeeId, COMPANY_ONE);
        jdbc.update(
                "UPDATE auth_principal SET employee_id = ? WHERE principal_id = ?",
                employeeId,
                STANDARD_PRINCIPAL);
        jdbc.update(
                """
                INSERT INTO employment_assignment (
                    assignment_id, employee_id, organization_id,
                    effective_from, effective_to, employment_period_id,
                    record_status, version_valid_to
                ) VALUES (?, ?, ?, ?, NULL, ?, 'ACTIVE', NULL)
                """,
                assignmentId,
                employeeId,
                organizationId,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                assignmentId);
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

    private String replaceOrganizationBody(
            String roleId,
            String organizationId,
            boolean includeDescendants,
            Instant validFrom,
            long expectedVersion) {
        return """
               {
                 "assignments":[{
                   "roleId":"%s",
                   "scopeType":"ORGANIZATION",
                   "scopeResourceId":"%s",
                   "includeDescendants":%s,
                   "validFrom":"%s",
                   "validTo":null
                 }],
                 "reason":"组织范围层级验证",
                 "expectedVersion":%d
               }
               """.formatted(
                roleId,
                organizationId,
                includeDescendants,
                validFrom,
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
