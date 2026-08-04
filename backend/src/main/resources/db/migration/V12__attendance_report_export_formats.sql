-- Persist the requested representation before asynchronous report generation.
-- Existing XLSX jobs retain their prior behavior through the default.
ALTER TABLE attendance_report_export_job
    ADD COLUMN export_format VARCHAR(8)
        CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'XLSX'
        AFTER row_count,
    ADD CONSTRAINT ck_att_report_export_format
        CHECK (export_format IN ('CSV', 'XLS', 'XLSX'));
