-- Location views retain a subject employee id and the full bounded view reason.
-- Other audit actions retain their existing application-side 64-character limit.
ALTER TABLE audit_event MODIFY COLUMN reason_code VARCHAR(1000) NULL;
