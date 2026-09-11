-- Controlled local/deployment convergence for the shared physical-location model.
-- Flyway V31 owns all DDL.  This script refuses to run until V31 is registered,
-- then validates the complete table shape before any DML is allowed to run.
-- The caller must select the target schema (for example with the mysql database
-- argument).  Keeping this script schema-neutral is required for fresh rehearsal.
DROP PROCEDURE IF EXISTS guard_shared_location_target;
DELIMITER $$
CREATE PROCEDURE guard_shared_location_target()
BEGIN
    IF DATABASE() IS NULL
       OR (SELECT COUNT(*)
           FROM information_schema.tables
           WHERE table_schema = DATABASE()
             AND table_name IN (
                 'flyway_schema_history', 'company', 'location',
                 'location_revision', 'location_timeline', 'auth_principal',
                 'shared_location', 'shared_location_revision',
                 'company_location_availability'
             )) <> 9
       OR NOT EXISTS (
           SELECT 1
           FROM flyway_schema_history
           WHERE version = '31' AND success = 1
       ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'target must be a selected ShenzhouHR schema at successful V31';
    END IF;
END$$
DELIMITER ;
CALL guard_shared_location_target();
DROP PROCEDURE guard_shared_location_target;

DROP PROCEDURE IF EXISTS verify_shared_location_schema;
DELIMITER $$
CREATE PROCEDURE verify_shared_location_schema()
BEGIN
    IF (SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'shared_location'
          AND column_name IN (
              'shared_location_id', 'location_code', 'row_version',
              'created_by', 'created_at'
          )) <> 5 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared_location has an incomplete column shape';
    END IF;

    IF (SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'shared_location_revision'
          AND column_name IN (
              'shared_location_revision_id', 'shared_location_id',
              'revision_number', 'location_name', 'time_zone', 'status',
              'effective_from', 'effective_to',
              'supersedes_shared_location_revision_id', 'snapshot_digest',
              'change_reason', 'created_by', 'created_at'
          )) <> 13 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared_location_revision has an incomplete column shape';
    END IF;

    IF (SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'company_location_availability'
          AND column_name IN (
              'company_location_availability_id', 'shared_location_id',
              'company_id', 'location_id', 'location_code', 'status',
              'effective_from', 'effective_to', 'created_by', 'created_at'
          )) <> 10 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company_location_availability has an incomplete column shape';
    END IF;

    IF (SELECT COUNT(*)
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'shared_location'
          AND index_name = 'uq_shared_location_code'
          AND non_unique = 0) <> 1
       OR (SELECT COUNT(*)
           FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 'shared_location'
             AND index_name = 'uq_shared_location_identity_code'
             AND non_unique = 0) <> 2
       OR (SELECT COUNT(*)
           FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 'company_location_availability'
             AND index_name = 'uq_company_location_shared'
             AND non_unique = 0) <> 2
       OR (SELECT COUNT(*)
           FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 'company_location_availability'
             AND index_name = 'uq_company_location_projection'
             AND non_unique = 0) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared location unique indexes are incomplete';
    END IF;

    IF (SELECT COUNT(*)
        FROM information_schema.referential_constraints
        WHERE constraint_schema = DATABASE()
          AND constraint_name IN (
              'fk_shared_location_created_by',
              'fk_shared_location_revision_location',
              'fk_shared_location_revision_predecessor',
              'fk_shared_location_revision_created_by',
              'fk_company_location_shared_code',
              'fk_company_location_company',
              'fk_company_location_projection_company',
              'fk_company_location_created_by'
          )) <> 8 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared location foreign keys are incomplete';
    END IF;

    IF (SELECT COUNT(*)
           FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 'location'
             AND index_name = 'uq_location_company_projection'
             AND non_unique = 0) <> 2
       OR (SELECT COUNT(*)
           FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 'location'
             AND index_name = 'uq_location_company_projection_code'
             AND non_unique = 0) <> 3
       OR (SELECT GROUP_CONCAT(
                    column_name ORDER BY ordinal_position SEPARATOR ',')
           FROM information_schema.key_column_usage
           WHERE constraint_schema = DATABASE()
             AND table_name = 'company_location_availability'
             AND constraint_name =
                    'fk_company_location_projection_company')
            <> 'company_id,location_id,location_code'
       OR (SELECT GROUP_CONCAT(
                    column_name ORDER BY ordinal_position SEPARATOR ',')
           FROM information_schema.key_column_usage
           WHERE constraint_schema = DATABASE()
             AND table_name = 'company_location_availability'
             AND constraint_name = 'fk_company_location_shared_code')
            <> 'shared_location_id,location_code' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company/location projection constraint is incomplete';
    END IF;

    IF (SELECT COUNT(*)
        FROM information_schema.check_constraints
        WHERE constraint_schema = DATABASE()
          AND constraint_name IN (
              'ck_shared_location_revision_number',
              'ck_shared_location_revision_status',
              'ck_shared_location_revision_period',
              'ck_company_location_status',
              'ck_company_location_period'
          )) <> 5 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared location check constraints are incomplete';
    END IF;
END$$
DELIMITER ;
CALL verify_shared_location_schema();
DROP PROCEDURE verify_shared_location_schema;

DROP PROCEDURE IF EXISTS converge_shared_locations;
DELIMITER $$
CREATE PROCEDURE converge_shared_locations()
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    IF (SELECT COUNT(*)
        FROM company
        WHERE code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
          AND status = 'ACTIVE') <> 4
       OR (SELECT COUNT(*)
           FROM location projection
           JOIN company business_company
             ON business_company.company_id = projection.company_id
            AND business_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
            AND business_company.status = 'ACTIVE') <> 28
       OR (SELECT COUNT(DISTINCT projection.location_code)
           FROM location projection
           JOIN company business_company
             ON business_company.company_id = projection.company_id
            AND business_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
            AND business_company.status = 'ACTIVE') <> 7 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'confirmed four-company projection shape must be 4 companies, 28 rows and 7 codes';
    END IF;

    IF EXISTS (
        SELECT projection.location_code
        FROM location projection
        JOIN company business_company
          ON business_company.company_id = projection.company_id
         AND business_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
         AND business_company.status = 'ACTIVE'
        JOIN location_revision revision
          ON revision.location_id = projection.location_id
         AND revision.revision_number = (
             SELECT MAX(latest.revision_number)
             FROM location_revision latest
             WHERE latest.location_id = projection.location_id
         )
        JOIN location_timeline timeline
          ON timeline.location_revision_id = revision.location_revision_id
         AND timeline.event_sequence = (
             SELECT MIN(origin.event_sequence)
             FROM location_timeline origin
             WHERE origin.location_revision_id = revision.location_revision_id
         )
        GROUP BY projection.location_code
        HAVING COUNT(*) <> 4
            OR COUNT(DISTINCT CONCAT(
                revision.location_name, '|', revision.time_zone, '|',
                timeline.state, '|', revision.effective_from, '|',
                COALESCE(
                    (
                        SELECT MIN(boundary.business_effective_from)
                        FROM location_timeline boundary
                        WHERE boundary.location_id = projection.location_id
                          AND boundary.event_sequence > timeline.event_sequence
                          AND boundary.business_effective_from >
                                timeline.business_effective_from
                          AND (
                              boundary.location_revision_id <>
                                    timeline.location_revision_id
                              OR boundary.state = 'INACTIVE'
                          )
                    ),
                    'NULL'
                )
            )) <> 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company projections disagree on shared name, timezone, status or effective date';
    END IF;

    INSERT INTO shared_location (
        shared_location_id, location_code, row_version, created_by, created_at
    )
    SELECT canonical.location_id, canonical.location_code, 0,
           canonical.created_by, canonical.created_at
    FROM location canonical
    JOIN company canonical_company
      ON canonical_company.company_id = canonical.company_id
     AND canonical_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
    WHERE canonical.location_id = (
        SELECT MIN(candidate.location_id)
        FROM location candidate
        JOIN company candidate_company
          ON candidate_company.company_id = candidate.company_id
         AND candidate_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
        WHERE candidate.location_code = canonical.location_code
    )
      AND NOT EXISTS (
          SELECT 1
          FROM shared_location shared
          WHERE shared.location_code = canonical.location_code
      );

    INSERT INTO shared_location_revision (
        shared_location_revision_id, shared_location_id, revision_number,
        location_name, time_zone, status, effective_from, effective_to,
        supersedes_shared_location_revision_id, snapshot_digest,
        change_reason, created_by, created_at
    )
    SELECT canonical_revision.location_revision_id,
           shared.shared_location_id,
           1,
           canonical_revision.location_name,
           canonical_revision.time_zone,
           canonical_timeline.state,
           canonical_revision.effective_from,
           (
               SELECT MIN(boundary.business_effective_from)
               FROM location_timeline boundary
               WHERE boundary.location_id = canonical.location_id
                 AND boundary.event_sequence >
                        canonical_timeline.event_sequence
                 AND boundary.business_effective_from >
                        canonical_timeline.business_effective_from
                 AND (
                     boundary.location_revision_id <>
                            canonical_timeline.location_revision_id
                     OR boundary.state = 'INACTIVE'
                 )
           ),
           NULL,
           SHA2(CONCAT(
               shared.location_code, '|', canonical_revision.location_name, '|',
               canonical_revision.time_zone, '|', canonical_timeline.state, '|',
               canonical_revision.effective_from, '|',
               COALESCE(
                   (
                       SELECT MIN(boundary.business_effective_from)
                       FROM location_timeline boundary
                       WHERE boundary.location_id = canonical.location_id
                         AND boundary.event_sequence >
                                canonical_timeline.event_sequence
                         AND boundary.business_effective_from >
                                canonical_timeline.business_effective_from
                         AND (
                             boundary.location_revision_id <>
                                    canonical_timeline.location_revision_id
                             OR boundary.state = 'INACTIVE'
                         )
                   ),
                   'NULL'
               )
           ), 256),
           '共享物理地点目录收口（保留公司兼容投影历史）',
           canonical_revision.created_by,
           canonical_revision.created_at
    FROM shared_location shared
    JOIN location canonical
      ON canonical.location_id = (
          SELECT MIN(candidate.location_id)
          FROM location candidate
          JOIN company candidate_company
            ON candidate_company.company_id = candidate.company_id
           AND candidate_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
          WHERE candidate.location_code = shared.location_code
      )
    JOIN location_revision canonical_revision
      ON canonical_revision.location_id = canonical.location_id
     AND canonical_revision.revision_number = (
         SELECT MAX(latest_revision.revision_number)
         FROM location_revision latest_revision
         WHERE latest_revision.location_id = canonical.location_id
     )
    JOIN location_timeline canonical_timeline
      ON canonical_timeline.location_revision_id =
            canonical_revision.location_revision_id
     AND canonical_timeline.event_sequence = (
         SELECT MIN(origin.event_sequence)
         FROM location_timeline origin
         WHERE origin.location_revision_id =
                canonical_revision.location_revision_id
     )
    WHERE NOT EXISTS (
        SELECT 1
        FROM shared_location_revision existing
        WHERE existing.shared_location_id = shared.shared_location_id
    );

    INSERT INTO company_location_availability (
        company_location_availability_id, shared_location_id,
        company_id, location_id, location_code,
        status, effective_from, effective_to,
        created_by, created_at
    )
    SELECT LOWER(CONCAT(
               SUBSTRING(SHA2(CONCAT('COMPANY_LOCATION|', location.location_id), 256), 1, 8), '-',
               SUBSTRING(SHA2(CONCAT('COMPANY_LOCATION|', location.location_id), 256), 9, 4), '-',
               SUBSTRING(SHA2(CONCAT('COMPANY_LOCATION|', location.location_id), 256), 13, 4), '-',
               SUBSTRING(SHA2(CONCAT('COMPANY_LOCATION|', location.location_id), 256), 17, 4), '-',
               SUBSTRING(SHA2(CONCAT('COMPANY_LOCATION|', location.location_id), 256), 21, 12)
           )),
           shared.shared_location_id,
           location.company_id,
           location.location_id,
           location.location_code,
           'ACTIVE',
           revision.effective_from,
           NULL,
           location.created_by,
           location.created_at
    FROM location
    JOIN company
      ON company.company_id = location.company_id
     AND company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
    JOIN shared_location shared
      ON shared.location_code = location.location_code
    JOIN location_revision revision
      ON revision.location_id = location.location_id
     AND revision.revision_number = 1
    WHERE NOT EXISTS (
        SELECT 1
        FROM company_location_availability existing
        WHERE existing.location_id = location.location_id
    );

    IF EXISTS (
        SELECT 1
        FROM location projection
        JOIN company projection_company
          ON projection_company.company_id = projection.company_id
         AND projection_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
        LEFT JOIN company_location_availability availability
          ON availability.location_id = projection.location_id
        WHERE availability.location_id IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'one or more location projections lack shared availability';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM company_location_availability availability
        JOIN location projection
          ON projection.location_id = availability.location_id
        JOIN shared_location shared
          ON shared.shared_location_id = availability.shared_location_id
        WHERE projection.company_id <> availability.company_id
           OR projection.location_code <> availability.location_code
           OR availability.location_code <> shared.location_code
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared availability does not match its compatibility projection';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM company_location_availability availability
        JOIN location projection
          ON projection.location_id = availability.location_id
         AND projection.company_id = availability.company_id
        JOIN company business_company
          ON business_company.company_id = availability.company_id
         AND business_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
        WHERE availability.status <> 'ACTIVE'
           OR availability.effective_to IS NOT NULL
           OR availability.effective_from <> (
               SELECT MIN(revision.effective_from)
               FROM location_revision revision
               WHERE revision.location_id = projection.location_id
           )
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'target company location availability period drifted';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM shared_location shared
        JOIN shared_location_revision shared_revision
          ON shared_revision.shared_location_id = shared.shared_location_id
         AND shared_revision.revision_number = (
             SELECT MAX(latest.revision_number)
             FROM shared_location_revision latest
             WHERE latest.shared_location_id = shared.shared_location_id
         )
        JOIN location canonical
          ON canonical.location_id = (
              SELECT MIN(candidate.location_id)
              FROM location candidate
              JOIN company candidate_company
                ON candidate_company.company_id = candidate.company_id
               AND candidate_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
              WHERE candidate.location_code = shared.location_code
          )
        JOIN location_revision canonical_revision
          ON canonical_revision.location_id = canonical.location_id
         AND canonical_revision.revision_number = (
             SELECT MAX(latest_revision.revision_number)
             FROM location_revision latest_revision
             WHERE latest_revision.location_id = canonical.location_id
         )
        JOIN location_timeline canonical_timeline
          ON canonical_timeline.location_revision_id =
                canonical_revision.location_revision_id
         AND canonical_timeline.event_sequence = (
             SELECT MIN(origin.event_sequence)
             FROM location_timeline origin
             WHERE origin.location_revision_id =
                    canonical_revision.location_revision_id
         )
        WHERE shared.row_version + 1 <> shared_revision.revision_number
           OR shared_revision.location_name <>
                canonical_revision.location_name
           OR shared_revision.time_zone <> canonical_revision.time_zone
           OR shared_revision.status <> canonical_timeline.state
           OR shared_revision.effective_from <>
                canonical_revision.effective_from
           OR NOT (
               shared_revision.effective_to <=> (
                   SELECT MIN(boundary.business_effective_from)
                   FROM location_timeline boundary
                   WHERE boundary.location_id = canonical.location_id
                     AND boundary.event_sequence >
                            canonical_timeline.event_sequence
                     AND boundary.business_effective_from >
                            canonical_timeline.business_effective_from
                     AND (
                         boundary.location_revision_id <>
                                canonical_timeline.location_revision_id
                         OR boundary.state = 'INACTIVE'
                     )
               )
           )
           OR shared_revision.snapshot_digest <> SHA2(CONCAT(
               shared.location_code, '|', canonical_revision.location_name, '|',
               canonical_revision.time_zone, '|', canonical_timeline.state, '|',
               canonical_revision.effective_from, '|',
               COALESCE(
                   (
                       SELECT MIN(boundary.business_effective_from)
                       FROM location_timeline boundary
                       WHERE boundary.location_id = canonical.location_id
                         AND boundary.event_sequence >
                                canonical_timeline.event_sequence
                         AND boundary.business_effective_from >
                                canonical_timeline.business_effective_from
                         AND (
                             boundary.location_revision_id <>
                                    canonical_timeline.location_revision_id
                             OR boundary.state = 'INACTIVE'
                         )
                   ),
                   'NULL'
               )
           ), 256)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared latest revision drifted from company projections';
    END IF;

    IF (SELECT COUNT(*)
        FROM shared_location_revision revision
        WHERE revision.revision_number = (
            SELECT MAX(latest.revision_number)
            FROM shared_location_revision latest
            WHERE latest.shared_location_id = revision.shared_location_id
        )) <> (SELECT COUNT(*) FROM shared_location) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'each shared location must resolve one latest revision';
    END IF;

    IF (SELECT COUNT(*)
        FROM company
        WHERE code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')) = 4 THEN
        IF (SELECT COUNT(*) FROM shared_location) <> 7
           OR (SELECT COUNT(DISTINCT availability.shared_location_id)
            FROM company_location_availability availability
            JOIN company ON company.company_id = availability.company_id
            WHERE company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
              AND availability.status = 'ACTIVE') <> 7
           OR (SELECT COUNT(*)
               FROM company_location_availability availability
               JOIN company ON company.company_id = availability.company_id
               WHERE company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
                 AND availability.status = 'ACTIVE') <> 28 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'four-company location target must be 7 shared and 28 availability rows';
        END IF;
    END IF;

    COMMIT;
END$$
DELIMITER ;
CALL converge_shared_locations();
DROP PROCEDURE converge_shared_locations;
