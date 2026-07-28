ALTER TABLE auth_principal
    ADD COLUMN row_version BIGINT UNSIGNED NOT NULL DEFAULT 0;

ALTER TABLE auth_principal_role_assignment
    ADD COLUMN row_version BIGINT UNSIGNED NOT NULL DEFAULT 0;

ALTER TABLE audit_event
    ADD COLUMN request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER correlation_id;

UPDATE audit_event
SET request_id = correlation_id
WHERE request_id IS NULL;

ALTER TABLE audit_event
    MODIFY COLUMN request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;

CREATE TABLE local_account (
    account_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    username VARCHAR(128) NOT NULL,
    normalized_username VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    first_password_change_required BOOLEAN NOT NULL DEFAULT TRUE,
    locked_until DATETIME(6) NULL,
    last_login_at DATETIME(6) NULL,
    session_epoch BIGINT UNSIGNED NOT NULL DEFAULT 0,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (account_id),
    UNIQUE KEY uq_local_account_principal (principal_id),
    UNIQUE KEY uq_local_account_normalized_username (normalized_username),
    KEY ix_local_account_status_lock (status, locked_until),
    CONSTRAINT fk_local_account_principal
        FOREIGN KEY (principal_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_local_account_status
        CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE password_credential (
    credential_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    algorithm VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parameter_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (credential_id),
    UNIQUE KEY uq_password_credential_account (account_id),
    CONSTRAINT fk_password_credential_account
        FOREIGN KEY (account_id) REFERENCES local_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE login_failure_window (
    account_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failure_count INT UNSIGNED NOT NULL DEFAULT 0,
    window_started_at DATETIME(6) NULL,
    last_failed_at DATETIME(6) NULL,
    locked_until DATETIME(6) NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id),
    KEY ix_login_failure_lock (locked_until, failure_count),
    CONSTRAINT fk_login_failure_account
        FOREIGN KEY (account_id) REFERENCES local_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE password_reset_grant (
    grant_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    issued_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (grant_id),
    UNIQUE KEY uq_password_reset_digest (token_digest),
    KEY ix_password_reset_account_expiry (account_id, expires_at, used_at),
    CONSTRAINT fk_password_reset_account
        FOREIGN KEY (account_id) REFERENCES local_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_session (
    session_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NULL,
    idle_expires_at DATETIME(6) NOT NULL,
    absolute_expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    revocation_reason VARCHAR(500) NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (session_id),
    UNIQUE KEY uq_user_session_digest (token_digest),
    KEY ix_user_session_active (account_id, status, idle_expires_at, absolute_expires_at),
    CONSTRAINT fk_user_session_account
        FOREIGN KEY (account_id) REFERENCES local_account (account_id),
    CONSTRAINT ck_user_session_status
        CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE session_revocation (
    revocation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    revoked_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revoked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (revocation_id),
    KEY ix_session_revocation_account_time (account_id, revoked_at),
    CONSTRAINT fk_session_revocation_session
        FOREIGN KEY (session_id) REFERENCES user_session (session_id),
    CONSTRAINT fk_session_revocation_account
        FOREIGN KEY (account_id) REFERENCES local_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES
    ('21000000-0000-0000-0000-000000000001', 'ACCOUNT:READ', 'IDENTITY', 'READ'),
    ('21000000-0000-0000-0000-000000000002', 'ACCOUNT:CREATE', 'IDENTITY', 'CREATE'),
    ('21000000-0000-0000-0000-000000000003', 'ACCOUNT:EDIT', 'IDENTITY', 'EDIT'),
    ('21000000-0000-0000-0000-000000000004', 'ACCOUNT:LOCK', 'IDENTITY', 'LOCK'),
    ('21000000-0000-0000-0000-000000000005', 'ACCOUNT:UNLOCK', 'IDENTITY', 'UNLOCK'),
    ('21000000-0000-0000-0000-000000000006', 'ACCOUNT:RESET_PASSWORD', 'IDENTITY', 'RESET_PASSWORD'),
    ('21000000-0000-0000-0000-000000000007', 'ROLE:READ', 'IDENTITY', 'READ'),
    ('21000000-0000-0000-0000-000000000008', 'ROLE:ASSIGN', 'IDENTITY', 'ASSIGN');

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT '10000000-0000-0000-0000-000000000002', capability_id
FROM auth_capability
WHERE capability_id LIKE '21000000-%';

INSERT INTO auth_role_capability (role_id, capability_id)
VALUES (
    '10000000-0000-0000-0000-000000000002',
    '20000000-0000-0000-0000-000000000003'
);
