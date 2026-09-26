-- V36: HR annual leave management capabilities
-- ANNUAL_LEAVE:READ   → HR_ADMIN, HR_MANAGER
-- ANNUAL_LEAVE:ADJUST → HR_ADMIN

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES (
    '36000000-0000-0000-0000-000000000001',
    'ANNUAL_LEAVE:READ', 'LEAVE', 'READ'
), (
    '36000000-0000-0000-0000-000000000002',
    'ANNUAL_LEAVE:ADJUST', 'LEAVE', 'ADJUST'
);

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code IN ('ANNUAL_LEAVE:READ', 'ANNUAL_LEAVE:ADJUST')
WHERE role.role_code = 'HR_ADMIN';

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code = 'ANNUAL_LEAVE:READ'
WHERE role.role_code IN ('HR_MANAGER', 'AUDITOR');
