ALTER TABLE punch_import_normalization_attempt
    MODIFY mapping_profile_version_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL;

CREATE TABLE punch_stored_object (
    stored_object_ref VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_type VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_size_bytes BIGINT UNSIGNED NOT NULL,
    content LONGBLOB NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (stored_object_ref),
    KEY ix_punch_stored_object_hash (content_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE identity_effective_from_cutover_run (
    run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    cutoff_date DATE NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_count INT UNSIGNED NOT NULL,
    refused_count INT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (run_id),
    UNIQUE KEY uq_identity_cutover_request (actor_id, request_id),
    KEY ix_identity_cutover_created (created_at, run_id),
    CONSTRAINT fk_identity_cutover_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_identity_cutover_status CHECK (
        status IN ('COMMITTED', 'ALREADY_APPLIED', 'REFUSED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE identity_effective_from_cutover_item (
    item_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    old_effective_from DATETIME(6) NOT NULL,
    new_effective_from DATETIME(6) NOT NULL,
    PRIMARY KEY (item_id),
    KEY ix_identity_cutover_item_run (run_id, object_type, object_id),
    CONSTRAINT fk_identity_cutover_item_run
        FOREIGN KEY (run_id)
        REFERENCES identity_effective_from_cutover_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES (
    '50000000-0000-0000-0000-000000000001',
    'IDENTITY_EFFECTIVE_FROM:CUTOVER',
    'IDENTITY_EFFECTIVE_FROM',
    'CUTOVER'
);

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code = 'IDENTITY_EFFECTIVE_FROM:CUTOVER'
WHERE role.role_code = 'HR_ADMIN';
