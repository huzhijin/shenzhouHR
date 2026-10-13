-- Persist Deli GPS/out-work address + raw coordinates, and the check type.
-- Map columns stay unused until the coordinate system is confirmed.
ALTER TABLE raw_attendance_fact
    ADD COLUMN verification_method VARCHAR(64) NULL
        AFTER fact_kind,
    ADD COLUMN location_summary VARCHAR(191) NULL
        AFTER interval_end;

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code = 'ATTENDANCE_LOCATION:READ'
WHERE role.role_code IN (
    'HR_ADMIN', 'EXECUTIVE', 'DEPARTMENT_HEAD',
    'MANUFACTURING_CENTER_SUPERVISOR', 'EMPLOYEE_SELF', 'SYSTEM_ADMIN'
)
AND NOT EXISTS (
    SELECT 1
    FROM auth_role_capability existing
    WHERE existing.role_id = role.role_id
      AND existing.capability_id = capability.capability_id
);
