-- V20 is reserved by the OA cursor commit-order reliability change.
-- Retained pending documents must remain visible in the immutable formal
-- projection while recognizing zero minutes.
ALTER TABLE attendance_report_oa_fact
    DROP CHECK ck_att_report_oa_status,
    ADD CONSTRAINT ck_att_report_oa_status
        CHECK (
            source_status IN (
                'APPROVED', 'PENDING', 'DRAFT', 'REJECTED', 'UNKNOWN',
                'MODIFIED', 'SUPPLEMENTED', 'REVOKED'
            )
        );
