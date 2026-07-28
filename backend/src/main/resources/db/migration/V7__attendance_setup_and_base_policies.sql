CREATE TABLE location (
    location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (location_id),
    UNIQUE KEY uq_location_code (legal_entity_id, location_code),
    KEY ix_location_scope_code (legal_entity_id, location_code, location_id),
    CONSTRAINT fk_location_legal_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_location_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE location_revision (
    location_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_number INT UNSIGNED NOT NULL,
    location_name VARCHAR(100) NOT NULL,
    time_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_from DATE NOT NULL,
    supersedes_location_revision_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (location_revision_id),
    UNIQUE KEY uq_location_revision_number (location_id, revision_number),
    UNIQUE KEY uq_location_revision_effective (location_id, effective_from),
    UNIQUE KEY uq_location_revision_successor (supersedes_location_revision_id),
    KEY ix_location_revision_resolution
        (location_id, effective_from, revision_number),
    CONSTRAINT fk_location_revision_location
        FOREIGN KEY (location_id) REFERENCES location (location_id),
    CONSTRAINT fk_location_revision_predecessor
        FOREIGN KEY (supersedes_location_revision_id)
        REFERENCES location_revision (location_revision_id),
    CONSTRAINT fk_location_revision_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_location_revision_number CHECK (revision_number > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE shift_template (
    shift_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    template_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (shift_template_id),
    UNIQUE KEY uq_shift_template_code (legal_entity_id, template_code),
    KEY ix_shift_template_scope_code
        (legal_entity_id, location_id, template_code, shift_template_id),
    CONSTRAINT fk_shift_template_legal_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_shift_template_location
        FOREIGN KEY (location_id) REFERENCES location (location_id),
    CONSTRAINT fk_shift_template_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE shift_version (
    shift_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shift_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT UNSIGNED NOT NULL,
    effective_from DATE NOT NULL,
    time_zone_snapshot VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    segments_json JSON NOT NULL,
    supersedes_shift_version_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (shift_version_id),
    UNIQUE KEY uq_shift_version_number (shift_template_id, version_number),
    UNIQUE KEY uq_shift_version_successor (supersedes_shift_version_id),
    KEY ix_shift_version_resolution
        (shift_template_id, effective_from, version_number),
    CONSTRAINT fk_shift_version_template
        FOREIGN KEY (shift_template_id) REFERENCES shift_template (shift_template_id),
    CONSTRAINT fk_shift_version_predecessor
        FOREIGN KEY (supersedes_shift_version_id)
        REFERENCES shift_version (shift_version_id),
    CONSTRAINT fk_shift_version_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_shift_version_number CHECK (version_number > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE work_calendar (
    work_calendar_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    calendar_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (work_calendar_id),
    UNIQUE KEY uq_work_calendar_code (legal_entity_id, calendar_code),
    KEY ix_work_calendar_scope_code
        (legal_entity_id, location_id, calendar_code, work_calendar_id),
    CONSTRAINT fk_work_calendar_legal_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_work_calendar_location
        FOREIGN KEY (location_id) REFERENCES location (location_id),
    CONSTRAINT fk_work_calendar_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE work_calendar_version (
    work_calendar_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    work_calendar_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT UNSIGNED NOT NULL,
    calendar_name VARCHAR(100) NOT NULL,
    calendar_year SMALLINT UNSIGNED NOT NULL,
    time_zone_snapshot VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL,
    supersedes_work_calendar_version_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (work_calendar_version_id),
    UNIQUE KEY uq_work_calendar_version_number (work_calendar_id, version_number),
    UNIQUE KEY uq_work_calendar_version_successor
        (supersedes_work_calendar_version_id),
    KEY ix_work_calendar_version_resolution
        (work_calendar_id, effective_from, effective_to, version_number),
    KEY ix_work_calendar_version_year
        (calendar_year, effective_from, effective_to, work_calendar_id),
    CONSTRAINT fk_work_calendar_version_calendar
        FOREIGN KEY (work_calendar_id) REFERENCES work_calendar (work_calendar_id),
    CONSTRAINT fk_work_calendar_version_predecessor
        FOREIGN KEY (supersedes_work_calendar_version_id)
        REFERENCES work_calendar_version (work_calendar_version_id),
    CONSTRAINT fk_work_calendar_version_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_work_calendar_version_number CHECK (version_number > 0),
    CONSTRAINT ck_work_calendar_version_year CHECK (calendar_year BETWEEN 2000 AND 2100),
    CONSTRAINT ck_work_calendar_version_period
        CHECK (effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE work_calendar_day (
    work_calendar_day_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    work_calendar_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_date DATE NOT NULL,
    day_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shift_version_override_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (work_calendar_day_id),
    UNIQUE KEY uq_work_calendar_day_date (work_calendar_version_id, business_date),
    KEY ix_work_calendar_day_shift_override (shift_version_override_id, business_date),
    CONSTRAINT fk_work_calendar_day_version
        FOREIGN KEY (work_calendar_version_id)
        REFERENCES work_calendar_version (work_calendar_version_id),
    CONSTRAINT fk_work_calendar_day_shift_override
        FOREIGN KEY (shift_version_override_id) REFERENCES shift_version (shift_version_id),
    CONSTRAINT fk_work_calendar_day_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_work_calendar_day_type
        CHECK (day_type IN ('WORKDAY', 'WEEKEND', 'PUBLIC_HOLIDAY', 'SPECIAL_WORKDAY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_group (
    attendance_group_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    group_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_group_id),
    UNIQUE KEY uq_attendance_group_code (legal_entity_id, group_code),
    KEY ix_attendance_group_scope_code
        (legal_entity_id, group_code, attendance_group_id),
    CONSTRAINT fk_attendance_group_legal_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_attendance_group_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_group_revision (
    attendance_group_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_group_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_number INT UNSIGNED NOT NULL,
    group_name VARCHAR(100) NOT NULL,
    location_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    work_calendar_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shift_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_from DATE NOT NULL,
    supersedes_attendance_group_revision_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_group_revision_id),
    UNIQUE KEY uq_attendance_group_revision_number
        (attendance_group_id, revision_number),
    UNIQUE KEY uq_attendance_group_revision_effective
        (attendance_group_id, effective_from),
    UNIQUE KEY uq_attendance_group_revision_successor
        (supersedes_attendance_group_revision_id),
    KEY ix_attendance_group_revision_resolution
        (attendance_group_id, effective_from, revision_number),
    KEY ix_attendance_group_revision_location
        (location_revision_id, effective_from),
    KEY ix_attendance_group_revision_shift
        (shift_template_id, effective_from),
    KEY ix_attendance_group_revision_calendar
        (work_calendar_id, effective_from),
    CONSTRAINT fk_attendance_group_revision_group
        FOREIGN KEY (attendance_group_id) REFERENCES attendance_group (attendance_group_id),
    CONSTRAINT fk_attendance_group_revision_location
        FOREIGN KEY (location_revision_id) REFERENCES location_revision (location_revision_id),
    CONSTRAINT fk_attendance_group_revision_calendar
        FOREIGN KEY (work_calendar_id) REFERENCES work_calendar (work_calendar_id),
    CONSTRAINT fk_attendance_group_revision_shift
        FOREIGN KEY (shift_template_id) REFERENCES shift_template (shift_template_id),
    CONSTRAINT fk_attendance_group_revision_predecessor
        FOREIGN KEY (supersedes_attendance_group_revision_id)
        REFERENCES attendance_group_revision (attendance_group_revision_id),
    CONSTRAINT fk_attendance_group_revision_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_group_revision_number CHECK (revision_number > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_group_assignment (
    attendance_group_assignment_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_group_revision_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_from DATE NOT NULL,
    supersedes_assignment_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_group_assignment_id),
    UNIQUE KEY uq_attendance_assignment_successor (supersedes_assignment_id),
    KEY ix_attendance_assignment_employee_period
        (employee_id, effective_from, attendance_group_revision_id),
    KEY ix_attendance_assignment_group_period
        (attendance_group_revision_id, effective_from, employee_id),
    CONSTRAINT fk_attendance_assignment_group_revision
        FOREIGN KEY (attendance_group_revision_id)
        REFERENCES attendance_group_revision (attendance_group_revision_id),
    CONSTRAINT fk_attendance_assignment_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_attendance_assignment_predecessor
        FOREIGN KEY (supersedes_assignment_id)
        REFERENCES attendance_group_assignment (attendance_group_assignment_id),
    CONSTRAINT fk_attendance_assignment_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE location_timeline (
    location_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_sequence INT UNSIGNED NOT NULL,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_effective_from DATE NOT NULL,
    predecessor_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recorded_at DATETIME(6) NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (location_timeline_id),
    UNIQUE KEY uq_location_timeline_sequence (location_id, event_sequence),
    UNIQUE KEY uq_location_timeline_predecessor (predecessor_timeline_id),
    KEY ix_location_timeline_resolution
        (location_id, business_effective_from, recorded_at, event_sequence),
    CONSTRAINT fk_location_timeline_location
        FOREIGN KEY (location_id) REFERENCES location (location_id),
    CONSTRAINT fk_location_timeline_revision
        FOREIGN KEY (location_revision_id) REFERENCES location_revision (location_revision_id),
    CONSTRAINT fk_location_timeline_predecessor
        FOREIGN KEY (predecessor_timeline_id) REFERENCES location_timeline (location_timeline_id),
    CONSTRAINT fk_location_timeline_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_location_timeline_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_location_timeline_state CHECK (state IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE shift_publication_timeline (
    shift_publication_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shift_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shift_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_sequence INT UNSIGNED NOT NULL,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_effective_from DATE NOT NULL,
    predecessor_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recorded_at DATETIME(6) NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (shift_publication_timeline_id),
    UNIQUE KEY uq_shift_publication_sequence (shift_template_id, event_sequence),
    UNIQUE KEY uq_shift_publication_predecessor (predecessor_timeline_id),
    KEY ix_shift_publication_resolution
        (shift_template_id, business_effective_from, recorded_at, event_sequence),
    CONSTRAINT fk_shift_publication_template
        FOREIGN KEY (shift_template_id) REFERENCES shift_template (shift_template_id),
    CONSTRAINT fk_shift_publication_version
        FOREIGN KEY (shift_version_id) REFERENCES shift_version (shift_version_id),
    CONSTRAINT fk_shift_publication_predecessor
        FOREIGN KEY (predecessor_timeline_id)
        REFERENCES shift_publication_timeline (shift_publication_timeline_id),
    CONSTRAINT fk_shift_publication_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_shift_publication_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_shift_publication_state CHECK (state IN ('PUBLISHED', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE calendar_publication_timeline (
    calendar_publication_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    work_calendar_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    work_calendar_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_sequence INT UNSIGNED NOT NULL,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_effective_from DATE NOT NULL,
    predecessor_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recorded_at DATETIME(6) NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (calendar_publication_timeline_id),
    UNIQUE KEY uq_calendar_publication_sequence (work_calendar_id, event_sequence),
    UNIQUE KEY uq_calendar_publication_predecessor (predecessor_timeline_id),
    KEY ix_calendar_publication_resolution
        (work_calendar_id, business_effective_from, recorded_at, event_sequence),
    CONSTRAINT fk_calendar_publication_calendar
        FOREIGN KEY (work_calendar_id) REFERENCES work_calendar (work_calendar_id),
    CONSTRAINT fk_calendar_publication_version
        FOREIGN KEY (work_calendar_version_id)
        REFERENCES work_calendar_version (work_calendar_version_id),
    CONSTRAINT fk_calendar_publication_predecessor
        FOREIGN KEY (predecessor_timeline_id)
        REFERENCES calendar_publication_timeline (calendar_publication_timeline_id),
    CONSTRAINT fk_calendar_publication_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_calendar_publication_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_calendar_publication_state CHECK (state IN ('PUBLISHED', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_group_timeline (
    attendance_group_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_group_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_group_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_sequence INT UNSIGNED NOT NULL,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_effective_from DATE NOT NULL,
    predecessor_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recorded_at DATETIME(6) NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (attendance_group_timeline_id),
    UNIQUE KEY uq_attendance_group_timeline_sequence
        (attendance_group_id, event_sequence),
    UNIQUE KEY uq_attendance_group_timeline_predecessor (predecessor_timeline_id),
    KEY ix_attendance_group_timeline_resolution
        (attendance_group_id, business_effective_from, recorded_at, event_sequence),
    CONSTRAINT fk_attendance_group_timeline_group
        FOREIGN KEY (attendance_group_id) REFERENCES attendance_group (attendance_group_id),
    CONSTRAINT fk_attendance_group_timeline_revision
        FOREIGN KEY (attendance_group_revision_id)
        REFERENCES attendance_group_revision (attendance_group_revision_id),
    CONSTRAINT fk_attendance_group_timeline_predecessor
        FOREIGN KEY (predecessor_timeline_id)
        REFERENCES attendance_group_timeline (attendance_group_timeline_id),
    CONSTRAINT fk_attendance_group_timeline_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_group_timeline_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_attendance_group_timeline_state CHECK (state IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_assignment_timeline (
    attendance_assignment_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_group_assignment_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_sequence INT UNSIGNED NOT NULL,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_effective_from DATE NOT NULL,
    predecessor_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recorded_at DATETIME(6) NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (attendance_assignment_timeline_id),
    UNIQUE KEY uq_attendance_assignment_timeline_sequence
        (attendance_group_assignment_id, event_sequence),
    UNIQUE KEY uq_attendance_assignment_timeline_predecessor (predecessor_timeline_id),
    KEY ix_attendance_assignment_timeline_resolution
        (employee_id, business_effective_from, recorded_at, event_sequence),
    CONSTRAINT fk_attendance_assignment_timeline_assignment
        FOREIGN KEY (attendance_group_assignment_id)
        REFERENCES attendance_group_assignment (attendance_group_assignment_id),
    CONSTRAINT fk_attendance_assignment_timeline_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_attendance_assignment_timeline_predecessor
        FOREIGN KEY (predecessor_timeline_id)
        REFERENCES attendance_assignment_timeline (attendance_assignment_timeline_id),
    CONSTRAINT fk_attendance_assignment_timeline_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_assignment_timeline_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_attendance_assignment_timeline_state CHECK (state IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_policy_template (
    policy_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    template_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    field_definitions_json JSON NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (policy_template_id),
    UNIQUE KEY uq_attendance_policy_template_code (template_code),
    CONSTRAINT fk_attendance_policy_template_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_policy_template_code
        CHECK (template_code IN (
            'MEAL_DEDUCTION',
            'LATE_GRACE',
            'MONTHLY_LATE_EXEMPTION'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_policy_scope (
    scope_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (scope_id),
    UNIQUE KEY uq_attendance_policy_scope
        (policy_template_id, legal_entity_id),
    KEY ix_attendance_policy_scope_legal_entity
        (legal_entity_id, policy_template_id, scope_id),
    CONSTRAINT fk_attendance_policy_scope_template
        FOREIGN KEY (policy_template_id)
        REFERENCES attendance_policy_template (policy_template_id),
    CONSTRAINT fk_attendance_policy_scope_legal_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_attendance_policy_scope_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_policy_scoped_version (
    scoped_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT UNSIGNED NOT NULL,
    parameters_json JSON NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    validation_json JSON NULL,
    snapshot_json JSON NOT NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rollback_of_scoped_version_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (scoped_version_id),
    UNIQUE KEY uq_attendance_policy_scoped_version
        (scope_id, version_number),
    KEY ix_attendance_policy_scoped_resolution
        (scope_id, effective_from, effective_to, version_number),
    KEY ix_attendance_policy_scoped_rollback
        (rollback_of_scoped_version_id),
    CONSTRAINT fk_attendance_policy_scoped_scope
        FOREIGN KEY (scope_id) REFERENCES attendance_policy_scope (scope_id),
    CONSTRAINT fk_attendance_policy_scoped_rollback
        FOREIGN KEY (rollback_of_scoped_version_id)
        REFERENCES attendance_policy_scoped_version (scoped_version_id),
    CONSTRAINT fk_attendance_policy_scoped_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_policy_scoped_version_number
        CHECK (version_number > 0),
    CONSTRAINT ck_attendance_policy_scoped_period
        CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_policy_lifecycle_event (
    lifecycle_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scoped_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_sequence INT UNSIGNED NOT NULL,
    action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_effective_from DATE NOT NULL,
    predecessor_event_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (lifecycle_event_id),
    UNIQUE KEY uq_attendance_policy_lifecycle_sequence
        (scope_id, event_sequence),
    UNIQUE KEY uq_attendance_policy_lifecycle_predecessor
        (predecessor_event_id),
    KEY ix_attendance_policy_lifecycle_resolution
        (scope_id, business_effective_from, event_sequence, scoped_version_id),
    CONSTRAINT fk_attendance_policy_lifecycle_scope
        FOREIGN KEY (scope_id) REFERENCES attendance_policy_scope (scope_id),
    CONSTRAINT fk_attendance_policy_lifecycle_version
        FOREIGN KEY (scoped_version_id)
        REFERENCES attendance_policy_scoped_version (scoped_version_id),
    CONSTRAINT fk_attendance_policy_lifecycle_predecessor
        FOREIGN KEY (predecessor_event_id)
        REFERENCES attendance_policy_lifecycle_event (lifecycle_event_id),
    CONSTRAINT fk_attendance_policy_lifecycle_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_policy_lifecycle_action
        CHECK (action IN (
            'DRAFT_CREATED', 'VALIDATED', 'PUBLISHED',
            'DEACTIVATE_SCHEDULED', 'ROLLED_BACK'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_policy_binding_family (
    binding_family_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_group_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_kind VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (binding_family_id),
    UNIQUE KEY uq_attendance_policy_binding_family
        (attendance_group_id, policy_kind),
    CONSTRAINT fk_attendance_policy_binding_family_group
        FOREIGN KEY (attendance_group_id) REFERENCES attendance_group (attendance_group_id),
    CONSTRAINT fk_attendance_policy_binding_family_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_policy_binding_family_kind
        CHECK (
            policy_kind IN (
                'MEAL_DEDUCTION',
                'LATE_GRACE',
                'MONTHLY_LATE_EXEMPTION'
            )
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_policy_binding_revision (
    binding_revision_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    binding_family_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_group_revision_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_policy_scoped_version_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_number INT UNSIGNED NOT NULL,
    effective_from DATE NOT NULL,
    supersedes_binding_revision_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (binding_revision_id),
    UNIQUE KEY uq_attendance_policy_binding_revision_number
        (binding_family_id, revision_number),
    UNIQUE KEY uq_attendance_policy_binding_revision_effective
        (binding_family_id, effective_from),
    UNIQUE KEY uq_attendance_policy_binding_revision_successor
        (supersedes_binding_revision_id),
    KEY ix_attendance_policy_scoped_version
        (attendance_policy_scoped_version_id),
    CONSTRAINT fk_attendance_policy_binding_family
        FOREIGN KEY (binding_family_id)
        REFERENCES attendance_policy_binding_family (binding_family_id),
    CONSTRAINT fk_attendance_policy_binding_group_revision
        FOREIGN KEY (attendance_group_revision_id)
        REFERENCES attendance_group_revision (attendance_group_revision_id),
    CONSTRAINT fk_attendance_policy_binding_version
        FOREIGN KEY (attendance_policy_scoped_version_id)
        REFERENCES attendance_policy_scoped_version (scoped_version_id),
    CONSTRAINT fk_attendance_policy_binding_predecessor
        FOREIGN KEY (supersedes_binding_revision_id)
        REFERENCES attendance_policy_binding_revision (binding_revision_id),
    CONSTRAINT fk_attendance_policy_binding_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_policy_binding_revision_number
        CHECK (revision_number > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_setup_idempotency (
    attendance_setup_idempotency_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_status SMALLINT UNSIGNED NULL,
    response_headers_json JSON NULL,
    response_body_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (attendance_setup_idempotency_id),
    UNIQUE KEY uq_attendance_setup_idempotency
        (actor_id, operation_code, resource_type, resource_id, idempotency_key),
    KEY ix_attendance_setup_idempotency_state (state, created_at),
    CONSTRAINT fk_attendance_setup_idempotency_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_setup_idempotency_state
        CHECK (state IN ('STARTED', 'COMPLETED_SUCCESS')),
    CONSTRAINT ck_attendance_setup_idempotency_response
        CHECK (
            (state = 'STARTED'
                AND response_status IS NULL
                AND response_headers_json IS NULL
                AND response_body_json IS NULL
                AND completed_at IS NULL)
            OR (state = 'COMPLETED_SUCCESS'
                AND response_status IS NOT NULL
                AND response_headers_json IS NOT NULL
                AND response_body_json IS NOT NULL
                AND completed_at IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO attendance_policy_template (
    policy_template_id, template_code, name, description,
    field_definitions_json, created_by, created_at
) VALUES
    (
        '25000000-0000-0000-0000-000000000001',
        'MEAL_DEDUCTION',
        '晚餐扣除',
        '所有考勤组默认启用且可版本化配置的晚餐扣除策略',
        JSON_ARRAY(
            JSON_OBJECT('key', 'enabled', 'label', '是否启用',
                'valueType', 'BOOLEAN', 'required', TRUE, 'enumValues', JSON_ARRAY()),
            JSON_OBJECT('key', 'mealWindowStart', 'label', '晚餐窗口开始',
                'valueType', 'LOCAL_TIME', 'required', TRUE, 'enumValues', JSON_ARRAY()),
            JSON_OBJECT('key', 'mealWindowEnd', 'label', '晚餐窗口结束',
                'valueType', 'LOCAL_TIME', 'required', TRUE, 'enumValues', JSON_ARRAY()),
            JSON_OBJECT('key', 'deductionMinutes', 'label', '扣除分钟',
                'valueType', 'INTEGER', 'required', TRUE, 'enumValues', JSON_ARRAY(),
                'minimum', 0, 'maximum', 240),
            JSON_OBJECT('key', 'triggerMinutes', 'label', '触发门槛分钟',
                'valueType', 'INTEGER', 'required', TRUE, 'enumValues', JSON_ARRAY(),
                'minimum', 0, 'maximum', 1440),
            JSON_OBJECT('key', 'applicableDayTypes', 'label', '适用日期类型',
                'valueType', 'ENUM_LIST', 'required', TRUE,
                'enumValues', JSON_ARRAY(
                    'WORKDAY', 'SPECIAL_WORKDAY',
                    'WEEKEND', 'PUBLIC_HOLIDAY'
                ))
        ),
        '20000000-0000-0000-0000-000000000001',
        CURRENT_TIMESTAMP(6)
    ),
    (
        '25000000-0000-0000-0000-000000000002',
        'LATE_GRACE',
        '迟到分钟宽限',
        '按已发布班次和打卡事实推导迟到分钟的受控宽限参数',
        JSON_ARRAY(
            JSON_OBJECT('key', 'enabled', 'label', '是否启用',
                'valueType', 'BOOLEAN', 'required', TRUE, 'enumValues', JSON_ARRAY()),
            JSON_OBJECT('key', 'graceMinutes', 'label', '宽限分钟',
                'valueType', 'INTEGER', 'required', TRUE, 'enumValues', JSON_ARRAY(),
                'minimum', 15, 'maximum', 15)
        ),
        '20000000-0000-0000-0000-000000000001',
        CURRENT_TIMESTAMP(6)
    ),
    (
        '25000000-0000-0000-0000-000000000003',
        'MONTHLY_LATE_EXEMPTION',
        '自然月迟到豁免',
        '同一员工每自然月一次且换组不重置的迟到豁免策略',
        JSON_ARRAY(
            JSON_OBJECT('key', 'enabled', 'label', '是否启用',
                'valueType', 'BOOLEAN', 'required', TRUE, 'enumValues', JSON_ARRAY()),
            JSON_OBJECT('key', 'graceMinutes', 'label', '宽限分钟',
                'valueType', 'INTEGER', 'required', TRUE, 'enumValues', JSON_ARRAY(),
                'minimum', 15, 'maximum', 15),
            JSON_OBJECT('key', 'monthlyUses', 'label', '自然月可用次数',
                'valueType', 'INTEGER', 'required', TRUE, 'enumValues', JSON_ARRAY(),
                'minimum', 1, 'maximum', 1),
            JSON_OBJECT('key', 'resetOnGroupChange', 'label', '换组是否重置',
                'valueType', 'BOOLEAN', 'required', TRUE, 'enumValues', JSON_ARRAY())
        ),
        '20000000-0000-0000-0000-000000000001',
        CURRENT_TIMESTAMP(6)
    );

INSERT INTO attendance_policy_scope (
    scope_id, policy_template_id, legal_entity_id, row_version,
    created_by, created_at
)
SELECT seed.scope_id, seed.template_id, entity.legal_entity_id, 0,
       '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
FROM (
    SELECT
        '25100000-0000-0000-0000-000000000001' AS scope_id,
        '25000000-0000-0000-0000-000000000001' AS template_id
    UNION ALL SELECT
        '25100000-0000-0000-0000-000000000002',
        '25000000-0000-0000-0000-000000000002'
    UNION ALL SELECT
        '25100000-0000-0000-0000-000000000003',
        '25000000-0000-0000-0000-000000000003'
) seed
JOIN legal_entity entity
  ON entity.legal_entity_id = '30000000-0000-0000-0000-000000000001';

INSERT INTO attendance_policy_scoped_version (
    scoped_version_id, scope_id, version_number, parameters_json,
    effective_from, effective_to, validation_json, snapshot_json,
    snapshot_digest, rollback_of_scoped_version_id, row_version,
    change_reason, created_by, created_at
)
SELECT
    seed.scoped_version_id,
    seed.scope_id,
    1,
    seed.parameters_json,
    DATE('1970-01-01'),
    NULL,
    JSON_OBJECT('valid', TRUE, 'issues', JSON_ARRAY()),
    CAST(seed.snapshot_json AS JSON),
    seed.snapshot_digest,
    NULL,
    1,
    'V7 受控考勤基础策略基线',
    '20000000-0000-0000-0000-000000000001',
    CURRENT_TIMESTAMP(6)
FROM (
    SELECT
        '25200000-0000-0000-0000-000000000001' AS scoped_version_id,
        '25100000-0000-0000-0000-000000000001' AS scope_id,
        JSON_OBJECT(
            'applicableDayTypes', JSON_ARRAY('SPECIAL_WORKDAY', 'WORKDAY'),
            'deductionMinutes', 30,
            'enabled', TRUE,
            'mealWindowEnd', '20:00',
            'mealWindowStart', '18:00',
            'triggerMinutes', 240
        ) AS parameters_json,
        '{"effectiveFrom":"1970-01-01","effectiveTo":null,"legalEntityId":"30000000-0000-0000-0000-000000000001","parameters":{"applicableDayTypes":["SPECIAL_WORKDAY","WORKDAY"],"deductionMinutes":30,"enabled":true,"mealWindowEnd":"20:00","mealWindowStart":"18:00","triggerMinutes":240},"policyKind":"MEAL_DEDUCTION","scopeId":"25100000-0000-0000-0000-000000000001","templateId":"25000000-0000-0000-0000-000000000001","versionNumber":1}' AS snapshot_json,
        '4279b3f40121f995d09245f3450e1087b4d5757e237ded4f8d4e0343fb210981' AS snapshot_digest
    UNION ALL
    SELECT
        '25200000-0000-0000-0000-000000000002',
        '25100000-0000-0000-0000-000000000002',
        JSON_OBJECT(
            'enabled', TRUE,
            'graceMinutes', 15
        ),
        '{"effectiveFrom":"1970-01-01","effectiveTo":null,"legalEntityId":"30000000-0000-0000-0000-000000000001","parameters":{"enabled":true,"graceMinutes":15},"policyKind":"LATE_GRACE","scopeId":"25100000-0000-0000-0000-000000000002","templateId":"25000000-0000-0000-0000-000000000002","versionNumber":1}',
        'ea11c63a991838a20b61d6d3e9d0281035263b8529b2fb767fa0b2e631306ce9'
    UNION ALL
    SELECT
        '25200000-0000-0000-0000-000000000003',
        '25100000-0000-0000-0000-000000000003',
        JSON_OBJECT(
            'enabled', TRUE,
            'graceMinutes', 15,
            'monthlyUses', 1,
            'resetOnGroupChange', FALSE
        ),
        '{"effectiveFrom":"1970-01-01","effectiveTo":null,"legalEntityId":"30000000-0000-0000-0000-000000000001","parameters":{"enabled":true,"graceMinutes":15,"monthlyUses":1,"resetOnGroupChange":false},"policyKind":"MONTHLY_LATE_EXEMPTION","scopeId":"25100000-0000-0000-0000-000000000003","templateId":"25000000-0000-0000-0000-000000000003","versionNumber":1}',
        'b0a533500852c464c7065812fd56519f0c571ebbf453834a8b189388e29f1a51'
) seed
JOIN attendance_policy_scope scope ON scope.scope_id = seed.scope_id;

INSERT INTO attendance_policy_lifecycle_event (
    lifecycle_event_id, scope_id, scoped_version_id, event_sequence,
    predecessor_event_id, action, business_effective_from, reason,
    actor_id, request_id, recorded_at
)
SELECT
    CONCAT('25300000-0000-0000-0000-', RIGHT(scope.scope_id, 12)),
    scope.scope_id,
    version.scoped_version_id,
    1,
    NULL,
    'PUBLISHED',
    DATE('1970-01-01'),
    'V7 受控考勤基础策略基线',
    '20000000-0000-0000-0000-000000000001',
    CONCAT('V7-', scope.scope_id),
    CURRENT_TIMESTAMP(6)
FROM attendance_policy_scope scope
JOIN attendance_policy_scoped_version version
  ON version.scope_id = scope.scope_id
 AND version.version_number = 1;

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES
    ('24000000-0000-0000-0000-000000000001', 'ATTENDANCE_SETUP:READ', 'ATTENDANCE', 'READ'),
    ('24000000-0000-0000-0000-000000000002', 'ATTENDANCE_SETUP:MANAGE_GROUP', 'ATTENDANCE', 'EDIT'),
    ('24000000-0000-0000-0000-000000000003', 'ATTENDANCE_SETUP:ASSIGN', 'ATTENDANCE', 'ASSIGN'),
    ('24000000-0000-0000-0000-000000000004', 'ATTENDANCE_SETUP:MANAGE_SHIFT', 'ATTENDANCE', 'EDIT'),
    ('24000000-0000-0000-0000-000000000005', 'ATTENDANCE_SETUP:MANAGE_CALENDAR', 'ATTENDANCE', 'EDIT'),
    ('24000000-0000-0000-0000-000000000006', 'ATTENDANCE_SETUP:MANAGE_POLICY', 'ATTENDANCE', 'EDIT');

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
CROSS JOIN auth_capability capability
WHERE role.role_code IN ('HR_ADMIN', 'SYSTEM_ADMIN')
  AND capability.capability_id LIKE '24000000-%';

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code = 'ATTENDANCE_SETUP:READ'
WHERE role.role_code = 'AUDITOR';
