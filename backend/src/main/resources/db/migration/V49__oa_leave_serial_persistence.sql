-- V49: Persist OA leave / original-leave serials for 销假 matching.
--
-- Customer confirmation Q10: match 销假 formmain_0370.field0099 to
-- 请假 formmain_0170.field0097, then fall back to the same employee number.
-- Serials were already read from OA but dropped before local insert.
--
-- This migration is after V48. The customer library is still at V35 until
-- operators forward-migrate V36–V49; do not treat V48/V49 as already applied.

ALTER TABLE oa_attendance_document
    ADD COLUMN leave_serial VARCHAR(64) NULL
        COMMENT '请假单流水号 formmain_0170.field0097'
        AFTER leave_type,
    ADD COLUMN original_leave_serial VARCHAR(64) NULL
        COMMENT '销假对应原请假流水号 formmain_0370.field0099'
        AFTER leave_serial,
    ADD INDEX ix_oa_document_leave_serial (leave_serial),
    ADD INDEX ix_oa_document_original_leave_serial (original_leave_serial);
