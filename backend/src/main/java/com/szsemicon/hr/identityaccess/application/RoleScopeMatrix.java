package com.szsemicon.hr.identityaccess.application;

import java.util.Set;

/**
 * Closed role-to-data-scope matrix for account administration. New role codes
 * are intentionally non-delegable until they are explicitly classified here.
 */
final class RoleScopeMatrix {

    private RoleScopeMatrix() {
    }

    static boolean permits(String roleCode, String scopeType) {
        return allowedScopeTypes(roleCode).contains(scopeType);
    }

    static Set<String> allowedScopeTypes(String roleCode) {
        return switch (roleCode) {
            case "EMPLOYEE", "EMPLOYEE_SELF" -> Set.of("SELF");
            case "DEPARTMENT_MANAGER",
                    "DEPARTMENT_HEAD",
                    "MANUFACTURING_SUPERVISOR",
                    "MANUFACTURING_CENTER_SUPERVISOR" -> Set.of("ORGANIZATION");
            case "HR_ADMIN", "SYSTEM_ADMIN", "AUDITOR" -> Set.of("COMPANY");
            case "EXECUTIVE" -> Set.of("COMPANY", "ORGANIZATION");
            default -> Set.of();
        };
    }
}
