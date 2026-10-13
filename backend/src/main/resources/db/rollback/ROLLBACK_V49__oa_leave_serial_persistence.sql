-- Rollback V49 leave serial columns. Only run if V49 was applied and must
-- be reversed before replaying a corrected migration.

ALTER TABLE oa_attendance_document
    DROP INDEX ix_oa_document_original_leave_serial,
    DROP INDEX ix_oa_document_leave_serial,
    DROP COLUMN original_leave_serial,
    DROP COLUMN leave_serial;
