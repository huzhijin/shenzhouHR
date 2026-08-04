-- Post-V30 local/deployment finalization.
--
-- This file intentionally remains outside Flyway until the authoritative V29
-- and V30 source files are restored to the repository. Run it only after the
-- target database has successfully applied V30. It is repeat-safe.

-- The role delete is protected by the assignment foreign key. All changes run
-- in one transaction, so any existing assignment makes the delete fail and
-- rolls the preceding changes back instead of silently remapping privileges.
START TRANSACTION;

UPDATE company
SET status = 'INACTIVE'
WHERE company_id = '30000000-0000-0000-0000-000000000001'
  AND code = 'W3_BASELINE_LEGAL_ENTITY'
  AND name = 'W3 verification baseline legal entity'
  AND status = 'ACTIVE';

DELETE role_capability
FROM auth_role_capability role_capability
JOIN auth_role role ON role.role_id = role_capability.role_id
WHERE role.role_code IN (
    'MANUFACTURING_SUPERVISOR',
    'MANUFACTURING_CENTER_SUPERVISOR'
);

DELETE FROM auth_role
WHERE role_code IN (
    'MANUFACTURING_SUPERVISOR',
    'MANUFACTURING_CENTER_SUPERVISOR'
);

COMMIT;
