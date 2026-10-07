-- Optional Deli punch coordinates are sensitive attendance evidence. Retain
-- the source values only after bounded parsing, persist one explicit source
-- system, and expose only converted WGS84 values to authorized map reads.
-- DELI_RAW_FACT_V2 includes this coordinate decision in canonical bytes.
-- Pre-V22 V1 rows remain immutable: replaying the same source identity is
-- deliberately rejected as a collision instead of silently replacing V1.
ALTER TABLE raw_attendance_fact
    ADD COLUMN longitude_raw VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER interval_end,
    ADD COLUMN latitude_raw VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER longitude_raw,
    ADD COLUMN source_coordinate_system VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNKNOWN'
        AFTER latitude_raw,
    ADD COLUMN coordinate_validation_status VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MISSING'
        AFTER source_coordinate_system,
    ADD COLUMN coordinate_conversion_status VARCHAR(24)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NOT_APPLICABLE'
        AFTER coordinate_validation_status,
    ADD COLUMN map_longitude DECIMAL(11,8) NULL
        AFTER coordinate_conversion_status,
    ADD COLUMN map_latitude DECIMAL(10,8) NULL
        AFTER map_longitude,
    ADD COLUMN map_coordinate_system VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER map_latitude,
    ADD CONSTRAINT ck_raw_fact_coordinate_shape CHECK (
        source_coordinate_system IN ('WGS84', 'GCJ02', 'BD09', 'UNKNOWN')
        AND coordinate_validation_status IN (
            'VALID', 'MISSING', 'INCOMPLETE', 'UNKNOWN_SYSTEM',
            'INVALID_FORMAT', 'OUT_OF_BOUNDS'
        )
        AND coordinate_conversion_status IN (
            'IDENTITY', 'CONVERTED', 'NOT_APPLICABLE', 'FAILED'
        )
        AND (
            (
                coordinate_validation_status = 'VALID'
                AND coordinate_conversion_status IN ('IDENTITY', 'CONVERTED')
                AND source_coordinate_system IN ('WGS84', 'GCJ02', 'BD09')
                AND longitude_raw IS NOT NULL
                AND latitude_raw IS NOT NULL
                AND map_longitude BETWEEN -180 AND 180
                AND map_latitude BETWEEN -90 AND 90
                AND map_coordinate_system = 'WGS84'
            )
            OR (
                coordinate_validation_status = 'VALID'
                AND coordinate_conversion_status = 'FAILED'
                AND source_coordinate_system IN ('WGS84', 'GCJ02', 'BD09')
                AND longitude_raw IS NOT NULL
                AND latitude_raw IS NOT NULL
                AND map_longitude IS NULL
                AND map_latitude IS NULL
                AND map_coordinate_system IS NULL
            )
            OR (
                coordinate_validation_status <> 'VALID'
                AND coordinate_conversion_status = 'NOT_APPLICABLE'
                AND map_longitude IS NULL
                AND map_latitude IS NULL
                AND map_coordinate_system IS NULL
            )
        )
    );

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES (
    '2e000000-0000-0000-0000-000000000001',
    'ATTENDANCE_LOCATION:READ',
    'ATTENDANCE_LOCATION',
    'READ'
);

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code = 'ATTENDANCE_LOCATION:READ'
WHERE role.role_code IN (
    'HR_ADMIN', 'EXECUTIVE', 'DEPARTMENT_HEAD',
    'MANUFACTURING_CENTER_SUPERVISOR', 'EMPLOYEE_SELF'
);

-- Basemap status: EXTERNAL_CONFIGURATION_REQUIRED. A provider and its key
-- remain external deployment configuration.
-- TODO(MAP-PROVIDER-CONFIG): select the provider and provision its key before
-- enabling a third-party basemap; the application never embeds one.
