ALTER TABLE oa_runtime_table_contract
    ADD COLUMN cursor_commit_order VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'NOT_VERIFIED'
        AFTER stable_cursor_type,
    ADD CONSTRAINT ck_oa_contract_cursor_commit_order CHECK (
        cursor_commit_order IN ('NOT_VERIFIED', 'STRICTLY_MONOTONIC')
    );
