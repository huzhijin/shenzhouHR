-- V37: Add FAMILY_PLANNING_LEAVE and OTHER_LEAVE to leave_type catalog
-- These codes are referenced in OaLeaveTypeShowValueCatalog (计生假 / 其他)
-- and must exist as leave_type rows before any OA leave record can be ingested.

INSERT INTO leave_type (leave_type_id, leave_code, display_name, active, created_at)
VALUES
    ('27000000-0000-0000-0000-000000000013', 'FAMILY_PLANNING_LEAVE', '计生假', 1, NOW(6)),
    ('27000000-0000-0000-0000-000000000014', 'OTHER_LEAVE',           '其他假', 1, NOW(6))
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    active       = VALUES(active);
