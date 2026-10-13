ALTER TABLE location
    ADD UNIQUE KEY uq_location_company_projection (company_id, location_id),
    ADD UNIQUE KEY uq_location_company_projection_code
        (company_id, location_id, location_code);

CREATE TABLE shared_location (
    shared_location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (shared_location_id),
    UNIQUE KEY uq_shared_location_code (location_code),
    UNIQUE KEY uq_shared_location_identity_code
        (shared_location_id, location_code),
    CONSTRAINT fk_shared_location_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE shared_location_revision (
    shared_location_revision_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shared_location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_number INT UNSIGNED NOT NULL,
    location_name VARCHAR(100) NOT NULL,
    time_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    supersedes_shared_location_revision_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (shared_location_revision_id),
    UNIQUE KEY uq_shared_location_revision_number
        (shared_location_id, revision_number),
    UNIQUE KEY uq_shared_location_revision_effective
        (shared_location_id, effective_from),
    UNIQUE KEY uq_shared_location_revision_successor
        (supersedes_shared_location_revision_id),
    KEY ix_shared_location_revision_resolution
        (shared_location_id, effective_from, revision_number),
    CONSTRAINT fk_shared_location_revision_location
        FOREIGN KEY (shared_location_id)
        REFERENCES shared_location (shared_location_id),
    CONSTRAINT fk_shared_location_revision_predecessor
        FOREIGN KEY (supersedes_shared_location_revision_id)
        REFERENCES shared_location_revision (shared_location_revision_id),
    CONSTRAINT fk_shared_location_revision_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_shared_location_revision_number CHECK (revision_number > 0),
    CONSTRAINT ck_shared_location_revision_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_shared_location_revision_period
        CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE company_location_availability (
    company_location_availability_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shared_location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (company_location_availability_id),
    UNIQUE KEY uq_company_location_shared (company_id, shared_location_id),
    UNIQUE KEY uq_company_location_projection (location_id),
    KEY ix_company_location_shared_company
        (shared_location_id, company_id, status),
    CONSTRAINT fk_company_location_shared_code
        FOREIGN KEY (shared_location_id, location_code)
        REFERENCES shared_location (shared_location_id, location_code),
    CONSTRAINT fk_company_location_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT fk_company_location_projection_company
        FOREIGN KEY (company_id, location_id, location_code)
        REFERENCES location (company_id, location_id, location_code),
    CONSTRAINT fk_company_location_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_company_location_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_company_location_period
        CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Deliberately no data backfill here.  Flyway V31 may run before a deployment's
-- four-company finalizer, when archived/bootstrap companies are still active.
-- The post-finalization convergence script seeds only confirmed business
-- companies and validates the exact seven-master/twenty-eight-availability shape.
