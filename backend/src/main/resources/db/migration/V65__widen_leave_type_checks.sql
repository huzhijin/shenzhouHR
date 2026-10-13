-- V48 constrained oa_attendance_document.leave_type and
-- attendance_report_daily_fact.leave_type to the then-known LeaveType names.
-- Catalog later added BREASTFEEDING, FAMILY_PLANNING, and OTHER. OA ingest
-- writes those enum names; the old checks abort the whole job with
-- OA_SYNC_FAILED (ck_oa_document_leave_type). Widen both checks to the
-- current LeaveType set so 计生假/其他/哺乳假 can persist.
-- Skip when the live check already lists FAMILY_PLANNING (applied out of band).

DROP PROCEDURE IF EXISTS tmp_widen_leave_type_checks;

DELIMITER $$

CREATE PROCEDURE tmp_widen_leave_type_checks()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.CHECK_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE()
          AND CONSTRAINT_NAME = 'ck_oa_document_leave_type'
          AND CHECK_CLAUSE LIKE '%FAMILY_PLANNING%'
    ) THEN
        ALTER TABLE oa_attendance_document
            DROP CHECK ck_oa_document_leave_type,
            ADD CONSTRAINT ck_oa_document_leave_type CHECK (
                leave_type IS NULL OR leave_type IN (
                    'ANNUAL', 'SICK', 'MARRIAGE', 'MATERNITY', 'PATERNITY',
                    'BEREAVEMENT', 'WORK_INJURY', 'PRENATAL_NURSING',
                    'BREASTFEEDING', 'PERSONAL', 'COMPENSATORY',
                    'FAMILY_PLANNING', 'OTHER'
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.CHECK_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE()
          AND CONSTRAINT_NAME = 'ck_att_report_daily_leave_type'
          AND CHECK_CLAUSE LIKE '%FAMILY_PLANNING%'
    ) THEN
        ALTER TABLE attendance_report_daily_fact
            DROP CHECK ck_att_report_daily_leave_type,
            ADD CONSTRAINT ck_att_report_daily_leave_type CHECK (
                leave_type IS NULL OR leave_type IN (
                    'ANNUAL', 'SICK', 'MARRIAGE', 'MATERNITY', 'PATERNITY',
                    'BEREAVEMENT', 'WORK_INJURY', 'PRENATAL_NURSING',
                    'BREASTFEEDING', 'PERSONAL', 'COMPENSATORY',
                    'FAMILY_PLANNING', 'OTHER'
                )
            );
    END IF;
END$$

DELIMITER ;

CALL tmp_widen_leave_type_checks();
DROP PROCEDURE IF EXISTS tmp_widen_leave_type_checks;
