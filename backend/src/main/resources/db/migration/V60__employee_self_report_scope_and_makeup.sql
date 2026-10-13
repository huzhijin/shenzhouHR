-- Ordinary employees keep self attendance/leave only.
-- Company-wide report and query pages stay on HR / department-head roles.

DELETE auth_role_capability
FROM auth_role_capability
JOIN auth_role
  ON auth_role.role_id = auth_role_capability.role_id
JOIN auth_capability
  ON auth_capability.capability_id = auth_role_capability.capability_id
WHERE auth_role.role_code = 'EMPLOYEE_SELF'
  AND auth_capability.capability_code IN (
        'ATTENDANCE_REPORT:READ',
        'ATTENDANCE_REPORT_QUERY:READ'
  );
