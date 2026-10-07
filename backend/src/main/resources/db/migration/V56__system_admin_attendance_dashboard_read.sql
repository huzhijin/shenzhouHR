INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code = 'ATTENDANCE_DASHBOARD:READ'
WHERE role.role_code = 'SYSTEM_ADMIN'
  AND NOT EXISTS (
        SELECT 1
        FROM auth_role_capability existing
        WHERE existing.role_id = role.role_id
          AND existing.capability_id = capability.capability_id
  );
