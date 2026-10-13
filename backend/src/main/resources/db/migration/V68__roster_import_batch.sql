CREATE TABLE roster_import_batch (
    batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_content LONGBLOB NOT NULL,
    precheck_json JSON NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (batch_id),
    KEY ix_roster_import_created (created_at, batch_id),
    CONSTRAINT fk_roster_import_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_roster_import_status
        CHECK (status IN ('PRECHECKED', 'PUBLISHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
