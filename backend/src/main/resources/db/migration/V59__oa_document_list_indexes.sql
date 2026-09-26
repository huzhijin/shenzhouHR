CREATE INDEX ix_oa_document_source_rank_created
    ON oa_attendance_document (
        attendance_source_id,
        knowledge_rank,
        created_at,
        oa_attendance_document_id);
