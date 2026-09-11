-- findEffectiveOaDocuments / findReportableOaDocuments rank every row with
-- created_at <= dataAsOf partitioned by (attendance_source_id, source_business_key)
-- BEFORE filtering APPROVED/MODIFIED/SUPPLEMENTED/UNKNOWN. A status-leading
-- index (source_status, document_type, created_at, id) does not prune that
-- window and MUST NOT be added for this query.
--
-- Existing ix_oa_document_current is (source, business_key, knowledge_rank)
-- without created_at. This index matches the cutoff plus partition/order keys.
-- Apply in a low-traffic window. MySQL 8 InnoDB ADD INDEX is INPLACE, LOCK=NONE.

ALTER TABLE oa_attendance_document
    ADD INDEX ix_oa_document_source_key_created (
        attendance_source_id,
        source_business_key,
        created_at,
        knowledge_rank,
        oa_attendance_document_id
    ),
    ALGORITHM=INPLACE,
    LOCK=NONE;
