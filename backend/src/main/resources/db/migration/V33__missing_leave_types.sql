-- Add leave types that are referenced in OA forms but were absent from the
-- initial seed. The IDs are fixed so FK references in future migrations stay
-- stable.  All rows use the existing leave_type schema (no DDL change).

INSERT INTO leave_type (leave_type_id, leave_code, display_name, active, created_at)
VALUES
    ('27000000-0000-0000-0000-000000000009', 'MARRIAGE_LEAVE',    '婚假',   1, NOW(6)),
    ('27000000-0000-0000-0000-000000000010', 'MATERNITY_LEAVE',   '产假',   1, NOW(6)),
    ('27000000-0000-0000-0000-000000000011', 'PATERNITY_LEAVE',   '陪产假', 1, NOW(6)),
    ('27000000-0000-0000-0000-000000000012', 'WORK_INJURY_LEAVE', '工伤假', 1, NOW(6))
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    active       = VALUES(active);
