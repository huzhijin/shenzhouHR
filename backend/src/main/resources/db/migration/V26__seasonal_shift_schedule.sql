-- One logical shift template owns a yearly winter/summer/winter schedule.
-- Saving a new future schedule appends a revision; published shift versions
-- and group references remain immutable.

CREATE TABLE shift_seasonal_schedule (
    shift_seasonal_schedule_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shift_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schedule_year SMALLINT UNSIGNED NOT NULL,
    summer_effective_from DATE NOT NULL,
    winter_effective_from DATE NOT NULL,
    winter_h1_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    summer_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    winter_h2_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schedule_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_number INT UNSIGNED NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (shift_seasonal_schedule_id),
    UNIQUE KEY uq_shift_seasonal_schedule_year
        (shift_template_id, schedule_year),
    CONSTRAINT fk_shift_seasonal_schedule_template
        FOREIGN KEY (shift_template_id)
        REFERENCES shift_template (shift_template_id),
    CONSTRAINT fk_shift_seasonal_schedule_winter_h1
        FOREIGN KEY (winter_h1_version_id)
        REFERENCES shift_version (shift_version_id),
    CONSTRAINT fk_shift_seasonal_schedule_summer
        FOREIGN KEY (summer_version_id)
        REFERENCES shift_version (shift_version_id),
    CONSTRAINT fk_shift_seasonal_schedule_winter_h2
        FOREIGN KEY (winter_h2_version_id)
        REFERENCES shift_version (shift_version_id),
    CONSTRAINT fk_shift_seasonal_schedule_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_shift_seasonal_schedule_updated_by
        FOREIGN KEY (updated_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_shift_seasonal_schedule_year CHECK (
        schedule_year BETWEEN 2000 AND 2100
    ),
    CONSTRAINT ck_shift_seasonal_schedule_boundaries CHECK (
        summer_effective_from < winter_effective_from
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE shift_seasonal_schedule_revision (
    shift_seasonal_schedule_revision_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shift_seasonal_schedule_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_number INT UNSIGNED NOT NULL,
    winter_h1_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    summer_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    winter_h2_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schedule_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (shift_seasonal_schedule_revision_id),
    UNIQUE KEY uq_shift_seasonal_schedule_revision
        (shift_seasonal_schedule_id, revision_number),
    CONSTRAINT fk_shift_seasonal_revision_schedule
        FOREIGN KEY (shift_seasonal_schedule_id)
        REFERENCES shift_seasonal_schedule (shift_seasonal_schedule_id),
    CONSTRAINT fk_shift_seasonal_revision_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_company_default_provisioning (
    provisioning_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provisioning_year SMALLINT UNSIGNED NOT NULL,
    location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shift_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    calendar_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_group_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    configuration_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (provisioning_id),
    UNIQUE KEY uq_attendance_company_default_provisioning
        (company_id, provisioning_year),
    CONSTRAINT fk_attendance_default_provision_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT fk_attendance_default_provision_location
        FOREIGN KEY (location_id) REFERENCES location (location_id),
    CONSTRAINT fk_attendance_default_provision_shift
        FOREIGN KEY (shift_template_id)
        REFERENCES shift_template (shift_template_id),
    CONSTRAINT fk_attendance_default_provision_calendar
        FOREIGN KEY (calendar_id)
        REFERENCES work_calendar (work_calendar_id),
    CONSTRAINT fk_attendance_default_provision_group
        FOREIGN KEY (attendance_group_id)
        REFERENCES attendance_group (attendance_group_id),
    CONSTRAINT fk_attendance_default_provision_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_default_provision_status CHECK (
        status IN ('READY')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
