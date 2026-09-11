-- Four-company finalization for the confirmed 2026-08-05 local data shape.
--
-- Safety contract:
--   * caller must explicitly select a controlled ShenzhouHR target schema;
--   * run on MySQL 8.0.45 after successful Flyway V31 (V31 is DDL-only);
--   * stop application writes and take a verified backup first;
--   * the three mixed Excel imports and the original configuration remain on
--     an INACTIVE archive company; no historical import/audit row is rewritten;
--   * all four companies use Yangzhou as the default location/time. Only
--     Jiangsu Shenzhou Dalian and Chengdu have independent fixed shifts;
--     its Shanghai group copies the Yangzhou seasonal shift. Other cities are
--     selectable location records only and do not create implicit assignments;
--   * every new identity is fixed (literal or deterministic UUID-shaped MD5);
--   * no INSERT IGNORE / REPLACE / disabled foreign-key checks;
--   * a partial, changed or future non-empty business shape raises SIGNAL and
--     rolls back. A fully finalized shape is verified and becomes a no-op.

DROP PROCEDURE IF EXISTS finalize_four_companies_post_v30;

DELIMITER $$

CREATE PROCEDURE finalize_four_companies_post_v30()
main: BEGIN
    DECLARE lock_acquired INT DEFAULT 0;
    DECLARE target_company_rows INT DEFAULT 0;
    DECLARE migration_needed BOOLEAN DEFAULT FALSE;
    DECLARE original_group_concat_max_len BIGINT UNSIGNED DEFAULT NULL;
    DECLARE source_location_id VARCHAR(36);
    DECLARE source_location_revision_id VARCHAR(36);
    DECLARE source_shift_template_id VARCHAR(36);
    DECLARE source_calendar_id VARCHAR(36);
    DECLARE source_group_id VARCHAR(36);
    DECLARE source_group_revision_id VARCHAR(36);
    DECLARE admin_principal_id VARCHAR(36);
    DECLARE admin_account_id VARCHAR(36);
    DECLARE admin_session_epoch BIGINT UNSIGNED;
    DECLARE hr_role_id VARCHAR(36);
    DECLARE system_role_id VARCHAR(36);
    DECLARE old_scope_id VARCHAR(36);

    DECLARE archive_company_id VARCHAR(36)
        DEFAULT '4dcb5ee1-b186-58d3-b538-6661a3dd435d';
    DECLARE baseline_company_id VARCHAR(36)
        DEFAULT '30000000-0000-0000-0000-000000000001';
    DECLARE bootstrap_actor_id VARCHAR(36)
        DEFAULT '20000000-0000-0000-0000-000000000001';
    DECLARE cutover_date DATE DEFAULT '2026-08-05';
    DECLARE cutover_at DATETIME(6) DEFAULT '2026-08-05 00:00:00.000000';

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        IF original_group_concat_max_len IS NOT NULL THEN
            SET SESSION group_concat_max_len = original_group_concat_max_len;
        END IF;
        DO RELEASE_LOCK('shenzhouhr:four-company-finalization:v1');
        RESIGNAL;
    END;

    SET original_group_concat_max_len = @@SESSION.group_concat_max_len;
    SELECT GET_LOCK('shenzhouhr:four-company-finalization:v1', 0)
      INTO lock_acquired;
    IF lock_acquired <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'four-company finalization lock is already held';
    END IF;

    SET SESSION group_concat_max_len = 4000000;
    START TRANSACTION;

    IF DATABASE() IS NULL
       OR SUBSTRING_INDEX(VERSION(), '-', 1) <> '8.0.45' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'requires an explicitly selected schema on MySQL 8.0.45';
    END IF;
    IF (SELECT COUNT(*) FROM flyway_schema_history
        WHERE version = '31' AND success = 1) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'successful Flyway V31 is required before finalization';
    END IF;
    IF (SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN (
              'company', 'location', 'location_revision', 'location_timeline',
              'shared_location', 'shared_location_revision',
              'company_location_availability', 'employee',
              'organization_identity', 'auth_principal'
          )) <> 10 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'selected schema is not a complete V31 ShenzhouHR target';
    END IF;
    IF (SELECT COUNT(*) FROM company
        WHERE company_id = baseline_company_id
          AND code = 'W3_BASELINE_LEGAL_ENTITY'
          AND status = 'INACTIVE') <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'W3 baseline company shape changed';
    END IF;

    DROP TEMPORARY TABLE IF EXISTS fc_company_map;
    DROP TEMPORARY TABLE IF EXISTS fc_org_map;
    DROP TEMPORARY TABLE IF EXISTS fc_org_map_ancestor;
    DROP TEMPORARY TABLE IF EXISTS fc_employee_map;
    DROP TEMPORARY TABLE IF EXISTS fc_shift_version_map;
    DROP TEMPORARY TABLE IF EXISTS fc_shift_version_predecessor_map;
    DROP TEMPORARY TABLE IF EXISTS fc_shift_version_summer_map;
    DROP TEMPORARY TABLE IF EXISTS fc_shift_version_winter_h2_map;
    DROP TEMPORARY TABLE IF EXISTS fc_shift_version_stage;
    DROP TEMPORARY TABLE IF EXISTS fc_shift_timeline_stage;
    DROP TEMPORARY TABLE IF EXISTS fc_calendar_version_map;
    DROP TEMPORARY TABLE IF EXISTS fc_calendar_version_predecessor_map;
    DROP TEMPORARY TABLE IF EXISTS fc_calendar_version_stage;
    DROP TEMPORARY TABLE IF EXISTS fc_policy_map;
    DROP TEMPORARY TABLE IF EXISTS fc_binding_map;
    DROP TEMPORARY TABLE IF EXISTS fc_assignment_map;
    DROP TEMPORARY TABLE IF EXISTS fc_city_map;
    DROP TEMPORARY TABLE IF EXISTS fc_special_map;
    DROP TEMPORARY TABLE IF EXISTS fc_named_employee_expectation;
    DROP TEMPORARY TABLE IF EXISTS fc_special_employee_map;
    DROP TEMPORARY TABLE IF EXISTS fc_special_shift_raw;
    DROP TEMPORARY TABLE IF EXISTS fc_special_shift_raw_copy;
    DROP TEMPORARY TABLE IF EXISTS fc_special_shift_stage;
    DROP TEMPORARY TABLE IF EXISTS fc_special_binding_map;

    CREATE TEMPORARY TABLE fc_company_map (
        company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
        company_code VARCHAR(64) NOT NULL,
        company_name VARCHAR(200) NOT NULL,
        short_name VARCHAR(32) NOT NULL,
        root_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        expected_orgs INT NOT NULL,
        expected_employees INT NOT NULL,
        root_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        location_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        location_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        shift_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        seasonal_schedule_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        seasonal_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        calendar_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        group_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        group_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        group_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        provisioning_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        annual_policy_version_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        annual_lifecycle_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        auth_scope_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE
    ) ENGINE=InnoDB;

    INSERT INTO fc_company_map VALUES
      ('41000000-0000-0000-0000-000000000001', 'SZSZ',
       '上海昇州半导体科技有限公司', '上海昇州',
       'b0d252af-5e5f-567c-a58e-f625e84fd530', 2, 1,
       '41001000-0000-0000-0000-000000000001',
       '41010000-0000-0000-0000-000000000001',
       '41011000-0000-0000-0000-000000000001',
       '41012000-0000-0000-0000-000000000001',
       '41020000-0000-0000-0000-000000000001',
       '41022000-0000-0000-0000-000000000001',
       '41023000-0000-0000-0000-000000000001',
       '41030000-0000-0000-0000-000000000001',
       '41040000-0000-0000-0000-000000000001',
       '41041000-0000-0000-0000-000000000001',
       '41042000-0000-0000-0000-000000000001',
       '41050000-0000-0000-0000-000000000001',
       '41060000-0000-0000-0000-000000000001',
       '41061000-0000-0000-0000-000000000001',
       '41090000-0000-0000-0000-000000000001'),
      ('41000000-0000-0000-0000-000000000002', 'SZJN',
       '上海晟州聚能半导体科技有限公司', '上海晟州聚能',
       '277137ee-0e50-5dcc-a6b5-504168fbab29', 17, 35,
       '41001000-0000-0000-0000-000000000002',
       '41010000-0000-0000-0000-000000000002',
       '41011000-0000-0000-0000-000000000002',
       '41012000-0000-0000-0000-000000000002',
       '41020000-0000-0000-0000-000000000002',
       '41022000-0000-0000-0000-000000000002',
       '41023000-0000-0000-0000-000000000002',
       '41030000-0000-0000-0000-000000000002',
       '41040000-0000-0000-0000-000000000002',
       '41041000-0000-0000-0000-000000000002',
       '41042000-0000-0000-0000-000000000002',
       '41050000-0000-0000-0000-000000000002',
       '41060000-0000-0000-0000-000000000002',
       '41061000-0000-0000-0000-000000000002',
       '41090000-0000-0000-0000-000000000002'),
      ('41000000-0000-0000-0000-000000000003', 'SZSC',
       '江苏神州半导体科技股份有限公司', '江苏神州',
       '00fd5358-f068-5b13-b0cc-1a41390e201c', 130, 571,
       '41001000-0000-0000-0000-000000000003',
       '41010000-0000-0000-0000-000000000003',
       '41011000-0000-0000-0000-000000000003',
       '41012000-0000-0000-0000-000000000003',
       '41020000-0000-0000-0000-000000000003',
       '41022000-0000-0000-0000-000000000003',
       '41023000-0000-0000-0000-000000000003',
       '41030000-0000-0000-0000-000000000003',
       '41040000-0000-0000-0000-000000000003',
       '41041000-0000-0000-0000-000000000003',
       '41042000-0000-0000-0000-000000000003',
       '41050000-0000-0000-0000-000000000003',
       '41060000-0000-0000-0000-000000000003',
       '41061000-0000-0000-0000-000000000003',
       '41090000-0000-0000-0000-000000000003'),
      ('41000000-0000-0000-0000-000000000004', 'SZXY',
       '江苏芯越半导体科技有限公司', '江苏芯越',
       'b1570eff-0f9a-527e-b7c4-fa201c022c73', 7, 8,
       '41001000-0000-0000-0000-000000000004',
       '41010000-0000-0000-0000-000000000004',
       '41011000-0000-0000-0000-000000000004',
       '41012000-0000-0000-0000-000000000004',
       '41020000-0000-0000-0000-000000000004',
       '41022000-0000-0000-0000-000000000004',
       '41023000-0000-0000-0000-000000000004',
       '41030000-0000-0000-0000-000000000004',
       '41040000-0000-0000-0000-000000000004',
       '41041000-0000-0000-0000-000000000004',
       '41042000-0000-0000-0000-000000000004',
       '41050000-0000-0000-0000-000000000004',
       '41060000-0000-0000-0000-000000000004',
       '41061000-0000-0000-0000-000000000004',
       '41090000-0000-0000-0000-000000000004');

    CREATE TEMPORARY TABLE fc_city_map (
        company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        city_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        city_name VARCHAR(100) NOT NULL,
        location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        location_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        location_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        PRIMARY KEY(company_id,city_code),
        UNIQUE KEY(location_id),UNIQUE KEY(location_revision_id),
        UNIQUE KEY(location_timeline_id)
    ) ENGINE=InnoDB;
    INSERT INTO fc_city_map
    SELECT company.company_id,city.city_code,city.city_name,
           LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(location_hash,1,8),'-',SUBSTR(location_hash,9,4),'-',
                        SUBSTR(location_hash,13,4),'-',SUBSTR(location_hash,17,4),'-',
                        SUBSTR(location_hash,21,12))),
           LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(revision_hash,1,8),'-',SUBSTR(revision_hash,9,4),'-',
                        SUBSTR(revision_hash,13,4),'-',SUBSTR(revision_hash,17,4),'-',
                        SUBSTR(revision_hash,21,12))),
           LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(timeline_hash,1,8),'-',SUBSTR(timeline_hash,9,4),'-',
                        SUBSTR(timeline_hash,13,4),'-',SUBSTR(timeline_hash,17,4),'-',
                        SUBSTR(timeline_hash,21,12)))
    FROM fc_company_map company
    CROSS JOIN (
        SELECT 'CHENGDU' city_code,'成都' city_name UNION ALL
        SELECT 'DALIAN','大连' UNION ALL SELECT 'SHANGHAI','上海' UNION ALL
        SELECT 'HEFEI','合肥' UNION ALL SELECT 'WUHAN','武汉' UNION ALL
        SELECT 'SHENZHEN','深圳'
    ) city
    CROSS JOIN LATERAL (SELECT
        MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|city-location|',
                   company.company_id,'|',
                   city.city_code)) location_hash,
        MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|city-location-revision|',
                   company.company_id,'|',
                   city.city_code)) revision_hash,
        MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|city-location-timeline|',
                   company.company_id,'|',
                   city.city_code)) timeline_hash
    ) seed;

    CREATE TEMPORARY TABLE fc_special_map (
        special_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
        shift_code VARCHAR(64) NOT NULL,
        group_code VARCHAR(64) NOT NULL,
        group_name VARCHAR(100) NOT NULL,
        expected_employees INT NOT NULL,
        shift_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        group_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        group_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        group_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        seasonal_schedule_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
        seasonal_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE
    ) ENGINE=InnoDB;
    INSERT INTO fc_special_map VALUES
      ('DALIAN','DALIAN_FIXED','DALIAN_ATTENDANCE','大连考勤组',13,
       '42020000-0000-0000-0000-000000000001',
       '42040000-0000-0000-0000-000000000001',
       '42041000-0000-0000-0000-000000000001',
       '42042000-0000-0000-0000-000000000001',NULL,NULL),
      ('CHENGDU','CHENGDU_FIXED','CHENGDU_ATTENDANCE','成都考勤组',4,
       '42020000-0000-0000-0000-000000000002',
       '42040000-0000-0000-0000-000000000002',
       '42041000-0000-0000-0000-000000000002',
       '42042000-0000-0000-0000-000000000002',NULL,NULL),
      ('SHANGHAI','SHANGHAI_SEASONAL','SHANGHAI_ATTENDANCE','上海考勤组',1,
       '42020000-0000-0000-0000-000000000003',
       '42040000-0000-0000-0000-000000000003',
       '42041000-0000-0000-0000-000000000003',
       '42042000-0000-0000-0000-000000000003',
       '42022000-0000-0000-0000-000000000003',
       '42023000-0000-0000-0000-000000000003');

    SELECT COUNT(*) INTO target_company_rows
    FROM company c JOIN fc_company_map m ON m.company_id = c.company_id;
    IF target_company_rows NOT IN (0, 4) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial target company set detected';
    END IF;
    SET migration_needed = target_company_rows = 0;

    IF migration_needed THEN
        IF (SELECT COUNT(*) FROM company
            WHERE company_id = archive_company_id
              AND code = 'SZSC'
              AND name = '江苏神州半导体科技股份有限公司'
              AND status = 'ACTIVE') <> 1 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'source company is not the approved initial shape';
        END IF;
    ELSEIF (SELECT COUNT(*) FROM company c JOIN fc_company_map m
            ON m.company_id = c.company_id
            WHERE c.code = m.company_code AND c.name = m.company_name
              AND c.status = 'ACTIVE') <> 4
       OR (SELECT COUNT(*) FROM company
           WHERE company_id = archive_company_id
             AND code = 'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
             AND name = '历史导入归档（原四公司混合数据）'
             AND status = 'INACTIVE') <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'target company set exists with a changed shape';
    END IF;

    -- Lock authoritative source rows before deriving any mapping.
    SELECT company_id FROM company
    WHERE company_id IN (archive_company_id, baseline_company_id)
       OR company_id IN (SELECT company_id FROM fc_company_map)
    ORDER BY company_id FOR UPDATE;

    IF (SELECT COUNT(*) FROM auth_principal
        WHERE principal_id = bootstrap_actor_id AND status = 'ACTIVE') <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'bootstrap actor is unavailable';
    END IF;
    SELECT p.principal_id, a.account_id, a.session_epoch
      INTO admin_principal_id, admin_account_id, admin_session_epoch
    FROM auth_principal p JOIN local_account a ON a.principal_id = p.principal_id
    WHERE a.username = 'szsc_admin_faa41d5bd802'
      AND a.normalized_username = 'szsc_admin_faa41d5bd802'
      AND a.status = 'ACTIVE' AND p.status = 'ACTIVE'
    FOR UPDATE;
    IF admin_principal_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'active initial administrator is unavailable';
    END IF;
    SELECT role_id INTO hr_role_id FROM auth_role
      WHERE role_code = 'HR_ADMIN' FOR UPDATE;
    SELECT role_id INTO system_role_id FROM auth_role
      WHERE role_code = 'SYSTEM_ADMIN' FOR UPDATE;
    IF hr_role_id IS NULL OR system_role_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'required administrator roles are unavailable';
    END IF;

    SELECT scope_id INTO old_scope_id FROM auth_data_scope
    WHERE scope_type = 'COMPANY' AND company_id = archive_company_id
      AND organization_id IS NULL
    ORDER BY valid_from DESC LIMIT 1 FOR UPDATE;
    IF old_scope_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'source administrator company scope is unavailable';
    END IF;
    IF migration_needed AND (
        SELECT COUNT(*)
        FROM auth_principal_role_assignment a
        WHERE a.principal_id = admin_principal_id
          AND a.data_scope_id = old_scope_id
          AND a.role_id IN (hr_role_id, system_role_id)
          AND a.valid_from <= cutover_at
          AND (a.valid_to IS NULL OR a.valid_to > cutover_at)
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'source administrator archive grants changed';
    END IF;

    -- The four fixed roots must still be disjoint current trees.
    CREATE TEMPORARY TABLE fc_org_map (
        organization_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
        root_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        depth INT NOT NULL
    ) ENGINE=InnoDB;
    INSERT INTO fc_org_map
    WITH RECURSIVE tree AS (
        SELECT m.root_id AS organization_id, m.root_id, m.company_id, 0 depth
        FROM fc_company_map m
        UNION ALL
        SELECT child.organization_id, tree.root_id, tree.company_id, tree.depth + 1
        FROM tree
        JOIN organization_version child
          ON child.parent_organization_id = tree.organization_id
         AND child.current_marker = 1
         AND child.status = 'ACTIVE'
    )
    SELECT organization_id, root_id, company_id, depth FROM tree;

    CREATE TEMPORARY TABLE fc_org_map_ancestor LIKE fc_org_map;
    INSERT INTO fc_org_map_ancestor SELECT * FROM fc_org_map;

    IF (SELECT COUNT(*) FROM fc_org_map) <> 156 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'four root organization trees changed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        LEFT JOIN fc_org_map om ON om.root_id = m.root_id
        GROUP BY m.company_id, m.expected_orgs
        HAVING COUNT(om.organization_id) <> m.expected_orgs
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'four root organization trees changed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        JOIN organization_current_projection p ON p.organization_id = m.root_id
        JOIN organization_version v ON v.organization_version_id = p.current_version_id
        WHERE v.parent_organization_id IS NOT NULL OR v.status <> 'ACTIVE'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'four root organization trees changed';
    END IF;
    IF (SELECT COUNT(*) FROM fc_company_map m
        JOIN organization_current_projection p ON p.organization_id = m.root_id
        JOIN organization_version v ON v.organization_version_id = p.current_version_id
        WHERE v.name = m.company_name) <> 4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'four root organization trees changed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM organization_current_closure cl
        JOIN fc_org_map_ancestor a
          ON a.organization_id = cl.ancestor_organization_id
        JOIN fc_org_map d ON d.organization_id = cl.descendant_organization_id
        WHERE a.root_id <> d.root_id
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'cross-root organization closure detected';
    END IF;

    CREATE TEMPORARY TABLE fc_employee_map (
        employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
        company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        employment_assignment_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE
    ) ENGINE=InnoDB;
    INSERT INTO fc_employee_map
    SELECT a.employee_id, om.company_id, a.assignment_id
    FROM employment_assignment a
    JOIN fc_org_map om ON om.organization_id = a.organization_id
    WHERE a.record_status = 'ACTIVE' AND a.current_version_marker = 1;

    IF (SELECT COUNT(*) FROM fc_employee_map) <> 615 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'employee/current-employment root mapping changed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        LEFT JOIN fc_employee_map em ON em.company_id = m.company_id
        GROUP BY m.company_id, m.expected_employees
        HAVING COUNT(em.employee_id) <> m.expected_employees
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'employee/current-employment root mapping changed';
    END IF;
    IF (SELECT COUNT(*) FROM employee) <> 615 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'employee/current-employment root mapping changed';
    END IF;
    IF (SELECT COUNT(*) FROM employment_assignment
        WHERE record_status = 'ACTIVE' AND current_version_marker = 1) <> 615 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'employee/current-employment root mapping changed';
    END IF;
    IF EXISTS (
        SELECT employee_id FROM employment_assignment
        WHERE record_status = 'ACTIVE' AND current_version_marker = 1
        GROUP BY employee_id HAVING COUNT(*) <> 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'employee/current-employment root mapping changed';
    END IF;

    CREATE TEMPORARY TABLE fc_named_employee_expectation (
        employee_number VARCHAR(128) PRIMARY KEY,
        display_name VARCHAR(100) NOT NULL,
        expected_company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        special_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
        expected_organization_name VARCHAR(200) NULL
    ) ENGINE=InnoDB;
    INSERT INTO fc_named_employee_expectation VALUES
      ('SZST0074','路昊','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0121','高攀','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0163','李政','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0207','王小龙','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0302','姜长波','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0388','王杰S','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0414','李春江','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0424','王松','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0445','霍岩','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0645','张泽','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0646','温慧杰','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0669','张清雅','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0708','戢昱','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0511','周文武','41000000-0000-0000-0000-000000000003','CHENGDU',NULL),
      ('SZST0512','周彦沛','41000000-0000-0000-0000-000000000003','CHENGDU',NULL),
      ('SZST0618','彭帆','41000000-0000-0000-0000-000000000003','CHENGDU',NULL),
      ('SZST0537','唐浩','41000000-0000-0000-0000-000000000003','CHENGDU',NULL),
      ('SZST0442','张静','41000000-0000-0000-0000-000000000003','SHANGHAI',NULL),
      ('SZJN0012','赵俊君','41000000-0000-0000-0000-000000000002',NULL,NULL),
      ('SZJN0021','时晨','41000000-0000-0000-0000-000000000002',NULL,NULL),
      ('SZJN0030','王颂雅','41000000-0000-0000-0000-000000000002',NULL,NULL);

    IF EXISTS (
        SELECT 1
        FROM fc_named_employee_expectation expected
        LEFT JOIN employee e
          ON e.employee_number=expected.employee_number
         AND e.display_name=expected.display_name
        LEFT JOIN fc_employee_map em
          ON em.employee_id=e.employee_id
         AND em.company_id=expected.expected_company_id
        GROUP BY expected.employee_number,expected.display_name,
                 expected.expected_company_id
        HAVING COUNT(em.employee_id)<>1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'named special/default employee mapping changed';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM fc_named_employee_expectation expected
        LEFT JOIN employee e
          ON e.employee_number=expected.employee_number
         AND e.display_name=expected.display_name
        LEFT JOIN employment_assignment assignment
          ON assignment.employee_id=e.employee_id
         AND assignment.current_version_marker=1
         AND assignment.record_status='ACTIVE'
        LEFT JOIN organization_current_projection projection
          ON projection.organization_id=assignment.organization_id
        LEFT JOIN organization_version version
          ON version.organization_version_id=projection.current_version_id
         AND version.name=expected.expected_organization_name
        WHERE expected.special_code='DALIAN'
        GROUP BY expected.employee_number,expected.display_name,
                 expected.expected_organization_name
        HAVING COUNT(version.organization_version_id)<>1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'named Dalian employee organization mapping changed';
    END IF;

    CREATE TEMPORARY TABLE fc_special_employee_map (
        employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
        special_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    ) ENGINE=InnoDB;
    INSERT INTO fc_special_employee_map
    SELECT em.employee_id,expected.special_code
    FROM fc_named_employee_expectation expected
    JOIN employee e
      ON e.employee_number=expected.employee_number
     AND e.display_name=expected.display_name
    JOIN fc_employee_map em
      ON em.employee_id=e.employee_id
     AND em.company_id=expected.expected_company_id
    WHERE expected.special_code IS NOT NULL;

    IF (SELECT COUNT(*) FROM fc_special_employee_map)<>18 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special attendance employee mapping changed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_special_map special
        LEFT JOIN fc_special_employee_map employee_map
          ON employee_map.special_code=special.special_code
        GROUP BY special.special_code,special.expected_employees
        HAVING COUNT(employee_map.employee_id)<>special.expected_employees
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special attendance employee counts changed';
    END IF;

    -- No populated composite company graph can be safely re-keyed under the
    -- current NO ACTION foreign keys. This approved database has none.
    IF EXISTS (SELECT 1 FROM employment_period_identity)
       OR EXISTS (SELECT 1 FROM annual_leave_entitlement_projection)
       OR EXISTS (SELECT 1 FROM leave_request)
       OR EXISTS (SELECT 1 FROM time_account)
       OR EXISTS (SELECT 1 FROM attendance_evidence_subject_lock)
       OR EXISTS (SELECT 1 FROM attendance_feedback)
       OR EXISTS (SELECT 1 FROM attendance_source)
       OR EXISTS (SELECT 1 FROM raw_attendance_fact)
       OR EXISTS (SELECT 1 FROM effective_attendance_event)
       OR EXISTS (SELECT 1 FROM evidence_interval_slice)
       OR EXISTS (SELECT 1 FROM duplicate_review_group)
       OR EXISTS (SELECT 1 FROM attendance_recalculation_intent)
       OR EXISTS (SELECT 1 FROM punch_import_batch)
       OR EXISTS (SELECT 1 FROM attendance_report_projection)
       OR EXISTS (SELECT 1 FROM attendance_close_snapshot) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company-dependent business facts exist; dedicated graph migration required';
    END IF;

    IF (SELECT COUNT(*) FROM people_import_batch
        WHERE company_id = archive_company_id AND status = 'PUBLISHED') <> 3
       OR (SELECT COUNT(*) FROM people_import_publication
           WHERE company_id = archive_company_id) <> 3
       OR (SELECT COUNT(*) FROM people_import_file) <> 3
       OR (SELECT COUNT(*) FROM people_import_diff) <> 1386
       OR (SELECT COUNT(*) FROM people_import_issue) <> 33 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'mixed historical import archive shape changed';
    END IF;

    SELECT l.location_id, lr.location_revision_id
      INTO source_location_id, source_location_revision_id
    FROM location l JOIN location_revision lr ON lr.location_id = l.location_id
    WHERE l.company_id = archive_company_id
      AND l.location_code = 'DEFAULT_SHANGHAI'
    ORDER BY lr.revision_number DESC LIMIT 1 FOR UPDATE;
    SELECT shift_template_id INTO source_shift_template_id
    FROM shift_template WHERE company_id = archive_company_id
      AND template_code = 'STANDARD_SEASONAL' FOR UPDATE;
    SELECT work_calendar_id INTO source_calendar_id
    FROM work_calendar WHERE company_id = archive_company_id
      AND calendar_code = 'STANDARD_2026' FOR UPDATE;
    SELECT g.attendance_group_id, r.attendance_group_revision_id
      INTO source_group_id, source_group_revision_id
    FROM attendance_group g JOIN attendance_group_revision r
      ON r.attendance_group_id = g.attendance_group_id
    WHERE g.company_id = archive_company_id
      AND g.group_code = 'DEFAULT_ATTENDANCE'
    ORDER BY r.revision_number DESC LIMIT 1 FOR UPDATE;

    IF source_location_id IS NULL OR source_shift_template_id IS NULL
       OR source_calendar_id IS NULL OR source_group_id IS NULL
       OR (SELECT COUNT(*) FROM location_revision
           WHERE location_id = source_location_id) <> 1
       OR (SELECT COUNT(*) FROM location_timeline
           WHERE location_id = source_location_id
             AND event_sequence = 1 AND state = 'ACTIVE') <> 1
       OR (SELECT COUNT(*) FROM shift_version
           WHERE shift_template_id = source_shift_template_id) <> 3
       OR (SELECT COUNT(*) FROM shift_publication_timeline
           WHERE shift_template_id = source_shift_template_id
             AND state = 'PUBLISHED') <> 3
       OR (SELECT COUNT(*) FROM shift_seasonal_schedule
           WHERE shift_template_id = source_shift_template_id
             AND schedule_year = 2026) <> 1
       OR (SELECT COUNT(*) FROM shift_seasonal_schedule_revision revision
           JOIN shift_seasonal_schedule schedule
             ON schedule.shift_seasonal_schedule_id =
                revision.shift_seasonal_schedule_id
           WHERE schedule.shift_template_id = source_shift_template_id) <> 1
       OR (SELECT COUNT(*) FROM work_calendar_version
           WHERE work_calendar_id = source_calendar_id) <> 2
       OR (SELECT COUNT(*) FROM work_calendar_day d
           JOIN work_calendar_version v
             ON v.work_calendar_version_id = d.work_calendar_version_id
           WHERE v.work_calendar_id = source_calendar_id) <> 365
       OR EXISTS (
           SELECT 1 FROM work_calendar_day d
           JOIN work_calendar_version v
             ON v.work_calendar_version_id = d.work_calendar_version_id
           WHERE v.work_calendar_id = source_calendar_id
             AND d.shift_version_override_id IS NOT NULL
       )
       OR (SELECT COUNT(*) FROM calendar_publication_timeline
           WHERE work_calendar_id = source_calendar_id
             AND state = 'PUBLISHED') <> 1
       OR (SELECT COUNT(*) FROM attendance_group_revision
           WHERE attendance_group_id = source_group_id) <> 1
       OR (SELECT COUNT(*) FROM attendance_group_timeline
           WHERE attendance_group_id = source_group_id
             AND event_sequence = 1 AND state = 'ACTIVE') <> 1
       OR (SELECT COUNT(*) FROM attendance_company_default_provisioning
           WHERE company_id = archive_company_id
             AND provisioning_year = 2026 AND status = 'READY') <> 1
       OR (SELECT COUNT(*) FROM attendance_policy_scope
           WHERE company_id = archive_company_id) <> 8
       OR (SELECT COUNT(*) FROM attendance_policy_scoped_version v
           JOIN attendance_policy_scope s ON s.scope_id = v.scope_id
           WHERE s.company_id = archive_company_id) <> 8
       OR (SELECT COUNT(*) FROM attendance_policy_lifecycle_event e
           JOIN attendance_policy_scope s ON s.scope_id = e.scope_id
           WHERE s.company_id = archive_company_id
             AND e.action = 'PUBLISHED') <> 8
       OR EXISTS (
           SELECT 1 FROM attendance_policy_scoped_version v
           JOIN attendance_policy_scope s ON s.scope_id = v.scope_id
           WHERE s.company_id = archive_company_id
             AND v.rollback_of_scoped_version_id IS NOT NULL
       )
       OR (SELECT COUNT(*) FROM attendance_policy_binding_family
           WHERE attendance_group_id = source_group_id) <> 3
       OR (SELECT COUNT(*) FROM attendance_policy_binding_revision r
           JOIN attendance_policy_binding_family f
             ON f.binding_family_id = r.binding_family_id
           WHERE f.attendance_group_id = source_group_id) <> 3
       OR EXISTS (
           SELECT 1 FROM attendance_policy_binding_revision r
           JOIN attendance_policy_binding_family f
             ON f.binding_family_id = r.binding_family_id
           WHERE f.attendance_group_id = source_group_id
             AND r.supersedes_binding_revision_id IS NOT NULL
       )
       OR (SELECT COUNT(*) FROM annual_leave_policy_version
           WHERE company_id = archive_company_id
             AND scope_type = 'COMPANY' AND status = 'PUBLISHED') <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'source attendance/leave baseline changed';
    END IF;
    IF migration_needed AND (
        (SELECT COUNT(*)
         FROM attendance_group_assignment a
         WHERE a.attendance_group_revision_id = source_group_revision_id
           AND a.effective_from = '2026-08-04'
           AND a.supersedes_assignment_id IS NULL) <> 615
        OR (SELECT COUNT(*)
            FROM attendance_assignment_timeline t
            JOIN attendance_group_assignment a
              ON a.attendance_group_assignment_id =
                 t.attendance_group_assignment_id
            WHERE a.attendance_group_revision_id = source_group_revision_id
              AND t.event_sequence = 1 AND t.state = 'ACTIVE'
              AND t.business_effective_from = '2026-08-04') <> 615
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'source employee attendance assignments changed';
    END IF;

    IF migration_needed THEN
        -- Create four fresh business boundaries; retain all mixed imports and
        -- old defaults on a clearly named inactive archive boundary.
        UPDATE company
        SET code = 'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE',
            name = '历史导入归档（原四公司混合数据）',
            status = 'INACTIVE'
        WHERE company_id = archive_company_id;

        INSERT INTO company (company_id, code, name, status, created_at)
        SELECT company_id, company_code, company_name, 'ACTIVE', cutover_at
        FROM fc_company_map ORDER BY company_id;

        -- Version the four root records instead of rewriting imported history.
        UPDATE organization_version v
        JOIN fc_company_map m ON m.root_id = v.organization_id
        SET v.effective_to = cutover_at,
            v.row_version = v.row_version + 1,
            v.change_reason = '2026-08-05 四公司维度切换：关闭导入根版本'
        WHERE v.current_marker = 1;

        INSERT INTO organization_version (
            organization_version_id, organization_id, parent_organization_id,
            code, name, org_type, effective_from, effective_to,
            formal_sync_batch_id, row_version, status, source_authority,
            source_import_batch_id, change_reason, created_by, created_at
        )
        SELECT m.root_version_id, old.organization_id, NULL,
               old.code, old.name, 'COMPANY', cutover_at, NULL,
               old.formal_sync_batch_id, old.row_version + 1, 'ACTIVE', 'LOCAL',
               NULL, '2026-08-05 四公司维度切换：独立公司根',
               bootstrap_actor_id, cutover_at
        FROM fc_company_map m
        JOIN organization_version old ON old.organization_id = m.root_id
         AND old.effective_to = cutover_at;

        UPDATE organization_current_projection p
        JOIN fc_company_map m ON m.root_id = p.organization_id
        SET p.current_version_id = m.root_version_id,
            p.projection_batch_id = '41002000-0000-0000-0000-000000000001',
            p.projected_at = cutover_at;

        UPDATE organization_identity oi
        JOIN fc_org_map om ON om.organization_id = oi.organization_id
        SET oi.company_id = om.company_id;

        UPDATE employee e JOIN fc_employee_map em ON em.employee_id = e.employee_id
        SET e.company_id = em.company_id,
            e.row_version = e.row_version + 1,
            e.updated_at = cutover_at;

        -- Human-readable company-specific locations.
        INSERT INTO location (
            location_id, company_id, location_code, row_version, created_by, created_at
        )
        SELECT location_id, company_id, 'DEFAULT_LOCATION', 0,
               bootstrap_actor_id, cutover_at
        FROM fc_company_map;

        INSERT INTO location_revision (
            location_revision_id, location_id, revision_number, location_name,
            time_zone, effective_from, supersedes_location_revision_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT m.location_revision_id, m.location_id, 1,
               '扬州', 'Asia/Shanghai', '2026-01-01', NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.company_id,
                           '|DEFAULT_LOCATION|扬州|Asia/Shanghai|2026-01-01|NULL'), 256),
               '2026-08-05 四公司默认地点初始化', bootstrap_actor_id, cutover_at
        FROM fc_company_map m;

        INSERT INTO location_timeline (
            location_timeline_id, location_id, location_revision_id,
            event_sequence, state, business_effective_from,
            predecessor_timeline_id, recorded_at, actor_id, request_id
        )
        SELECT location_timeline_id, location_id, location_revision_id,
               1, 'ACTIVE', '2026-01-01', NULL, cutover_at,
               bootstrap_actor_id, CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-LOCATION-', company_id)
        FROM fc_company_map;

        INSERT INTO location (
            location_id,company_id,location_code,row_version,created_by,created_at
        )
        SELECT location_id,company_id,city_code,0,bootstrap_actor_id,cutover_at
        FROM fc_city_map;

        INSERT INTO location_revision (
            location_revision_id,location_id,revision_number,location_name,
            time_zone,effective_from,supersedes_location_revision_id,
            snapshot_digest,change_reason,created_by,created_at
        )
        SELECT location_revision_id,location_id,1,city_name,'Asia/Shanghai',
               '2026-01-01',NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,company_id,'|',city_code,'|',city_name,
                           '|Asia/Shanghai|2026-01-01|NULL'),256),
               '2026-08-05 城市地点预置',bootstrap_actor_id,cutover_at
        FROM fc_city_map;

        INSERT INTO location_timeline (
            location_timeline_id,location_id,location_revision_id,
            event_sequence,state,business_effective_from,
            predecessor_timeline_id,recorded_at,actor_id,request_id
        )
        SELECT location_timeline_id,location_id,location_revision_id,
               1,'ACTIVE','2026-01-01',NULL,cutover_at,bootstrap_actor_id,
               CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-CITY-',SUBSTR(MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,company_id,'|',city_code)),1,32))
        FROM fc_city_map;

        INSERT INTO shift_template (
            shift_template_id, company_id, location_id, template_code,
            created_by, created_at
        )
        SELECT shift_template_id, company_id, location_id, 'STANDARD_SEASONAL',
               bootstrap_actor_id, cutover_at
        FROM fc_company_map;

        CREATE TEMPORARY TABLE fc_shift_version_map (
            company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            source_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            target_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            PRIMARY KEY (company_id, source_version_id),
            UNIQUE KEY (target_version_id)
        ) ENGINE=InnoDB;
        INSERT INTO fc_shift_version_map
        SELECT m.company_id, sv.shift_version_id,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h,1,8),'-',SUBSTR(h,9,4),'-',SUBSTR(h,13,4),
                            '-',SUBSTR(h,17,4),'-',SUBSTR(h,21,12)))
        FROM fc_company_map m
        JOIN shift_version sv ON sv.shift_template_id = source_shift_template_id
        CROSS JOIN LATERAL (
            SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|shift-version|',m.company_id,
                              '|',sv.shift_version_id)) h
        ) digest_seed;

        CREATE TEMPORARY TABLE fc_shift_version_predecessor_map
            LIKE fc_shift_version_map;
        INSERT INTO fc_shift_version_predecessor_map
        SELECT * FROM fc_shift_version_map;

        CREATE TEMPORARY TABLE fc_shift_version_summer_map
            LIKE fc_shift_version_map;
        INSERT INTO fc_shift_version_summer_map
        SELECT * FROM fc_shift_version_map;

        CREATE TEMPORARY TABLE fc_shift_version_winter_h2_map
            LIKE fc_shift_version_map;
        INSERT INTO fc_shift_version_winter_h2_map
        SELECT * FROM fc_shift_version_map;

        CREATE TEMPORARY TABLE fc_shift_version_stage ENGINE=InnoDB AS
        SELECT vm.target_version_id AS shift_version_id,
               m.shift_template_id AS shift_template_id, sv.version_number,
               sv.effective_from, sv.time_zone_snapshot, sv.segments_json,
               predecessor.target_version_id AS supersedes_shift_version_id,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                   'S:17:shift-snapshot-v1',
                   'S:36:',m.shift_template_id,
                   'S:36:',vm.target_version_id,
                   'S:',OCTET_LENGTH(sv.version_number),':',sv.version_number,
                   'S:10:',sv.effective_from,
                   CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'S:10:',JSON_UNQUOTE(JSON_EXTRACT(sv.segments_json,'$.effectiveTo'))),
                   'S:',OCTET_LENGTH(sv.time_zone_snapshot),':',sv.time_zone_snapshot,
                   'S:',OCTET_LENGTH(seg.segment_count),':',seg.segment_count,
                   seg.segment_body), 256) AS snapshot_digest,
               '2026-08-05 四公司标准冬夏令班次初始化' AS change_reason,
               bootstrap_actor_id AS created_by, cutover_at AS created_at
        FROM fc_company_map m
        JOIN fc_shift_version_map vm ON vm.company_id = m.company_id
        JOIN shift_version sv ON sv.shift_version_id = vm.source_version_id
        LEFT JOIN fc_shift_version_predecessor_map predecessor
          ON predecessor.company_id = m.company_id
         AND predecessor.source_version_id = sv.supersedes_shift_version_id
        JOIN (
            SELECT source.shift_version_id, COUNT(*) segment_count,
                   GROUP_CONCAT(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                       'S:',OCTET_LENGTH(j.segment_type),':',j.segment_type,
                       'S:',OCTET_LENGTH(TIME_FORMAT(TIME(j.start_time),'%H:%i')),
                           ':',TIME_FORMAT(TIME(j.start_time),'%H:%i'),
                       'S:',OCTET_LENGTH(j.start_offset),':',j.start_offset,
                       'S:',OCTET_LENGTH(TIME_FORMAT(TIME(j.end_time),'%H:%i')),
                           ':',TIME_FORMAT(TIME(j.end_time),'%H:%i'),
                       'S:',OCTET_LENGTH(j.end_offset),':',j.end_offset)
                       ORDER BY j.ord SEPARATOR '') segment_body
            FROM shift_version source
            JOIN JSON_TABLE(source.segments_json, '$.segments[*]' COLUMNS(
                ord FOR ORDINALITY,
                segment_type VARCHAR(32) PATH '$.segmentType',
                start_time VARCHAR(16) PATH '$.startLocalTime',
                start_offset INT PATH '$.startDayOffset',
                end_time VARCHAR(16) PATH '$.endLocalTime',
                end_offset INT PATH '$.endDayOffset'
            )) AS j ON TRUE
            WHERE source.shift_template_id = source_shift_template_id
            GROUP BY source.shift_version_id
        ) seg ON seg.shift_version_id = sv.shift_version_id;

        INSERT INTO shift_version (
            shift_version_id, shift_template_id, version_number, effective_from,
            time_zone_snapshot, segments_json, supersedes_shift_version_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT shift_version_id, shift_template_id, version_number, effective_from,
               time_zone_snapshot, segments_json, supersedes_shift_version_id,
               snapshot_digest, change_reason, created_by, created_at
        FROM fc_shift_version_stage
        WHERE supersedes_shift_version_id IS NULL;

        INSERT INTO shift_version (
            shift_version_id, shift_template_id, version_number, effective_from,
            time_zone_snapshot, segments_json, supersedes_shift_version_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT staged.shift_version_id, staged.shift_template_id,
               staged.version_number, staged.effective_from,
               staged.time_zone_snapshot, staged.segments_json,
               staged.supersedes_shift_version_id, staged.snapshot_digest,
               staged.change_reason, staged.created_by, staged.created_at
        FROM fc_shift_version_stage staged
        JOIN shift_version predecessor
          ON predecessor.shift_version_id=staged.supersedes_shift_version_id
        LEFT JOIN shift_version existing
          ON existing.shift_version_id=staged.shift_version_id
        WHERE existing.shift_version_id IS NULL;

        INSERT INTO shift_version (
            shift_version_id, shift_template_id, version_number, effective_from,
            time_zone_snapshot, segments_json, supersedes_shift_version_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT staged.shift_version_id, staged.shift_template_id,
               staged.version_number, staged.effective_from,
               staged.time_zone_snapshot, staged.segments_json,
               staged.supersedes_shift_version_id, staged.snapshot_digest,
               staged.change_reason, staged.created_by, staged.created_at
        FROM fc_shift_version_stage staged
        JOIN shift_version predecessor
          ON predecessor.shift_version_id=staged.supersedes_shift_version_id
        LEFT JOIN shift_version existing
          ON existing.shift_version_id=staged.shift_version_id
        WHERE existing.shift_version_id IS NULL;

        CREATE TEMPORARY TABLE fc_shift_timeline_stage ENGINE=InnoDB AS
        SELECT LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h,1,8),'-',SUBSTR(h,9,4),'-',SUBSTR(h,13,4),
                            '-',SUBSTR(h,17,4),'-',SUBSTR(h,21,12)))
                   AS shift_publication_timeline_id,
               m.shift_template_id AS shift_template_id,
               vm.target_version_id AS shift_version_id,
               source.event_sequence,
               source.state, source.business_effective_from,
               CASE WHEN source.predecessor_timeline_id IS NULL THEN NULL ELSE
                    LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(ph,1,8),'-',SUBSTR(ph,9,4),'-',SUBSTR(ph,13,4),
                                 '-',SUBSTR(ph,17,4),'-',SUBSTR(ph,21,12))) END
                   AS predecessor_timeline_id,
               cutover_at AS recorded_at, bootstrap_actor_id AS actor_id,
               CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-SHIFT-',m.company_id,'-',source.event_sequence) AS request_id
        FROM fc_company_map m
        JOIN shift_publication_timeline source
          ON source.shift_template_id = source_shift_template_id
        JOIN fc_shift_version_map vm ON vm.company_id=m.company_id
         AND vm.source_version_id=source.shift_version_id
        CROSS JOIN LATERAL (SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|shift-timeline|',
            m.company_id,'|',source.shift_publication_timeline_id)) h) a
        CROSS JOIN LATERAL (SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|shift-timeline|',
            m.company_id,'|',source.predecessor_timeline_id)) ph) b;

        INSERT INTO shift_publication_timeline (
            shift_publication_timeline_id, shift_template_id, shift_version_id,
            event_sequence, state, business_effective_from,
            predecessor_timeline_id, recorded_at, actor_id, request_id
        )
        SELECT shift_publication_timeline_id, shift_template_id, shift_version_id,
               event_sequence, state, business_effective_from,
               predecessor_timeline_id, recorded_at, actor_id, request_id
        FROM fc_shift_timeline_stage
        WHERE predecessor_timeline_id IS NULL;

        INSERT INTO shift_publication_timeline (
            shift_publication_timeline_id, shift_template_id, shift_version_id,
            event_sequence, state, business_effective_from,
            predecessor_timeline_id, recorded_at, actor_id, request_id
        )
        SELECT staged.shift_publication_timeline_id, staged.shift_template_id,
               staged.shift_version_id, staged.event_sequence, staged.state,
               staged.business_effective_from, staged.predecessor_timeline_id,
               staged.recorded_at, staged.actor_id, staged.request_id
        FROM fc_shift_timeline_stage staged
        JOIN shift_publication_timeline predecessor
          ON predecessor.shift_publication_timeline_id=staged.predecessor_timeline_id
        LEFT JOIN shift_publication_timeline existing
          ON existing.shift_publication_timeline_id=staged.shift_publication_timeline_id
        WHERE existing.shift_publication_timeline_id IS NULL;

        INSERT INTO shift_publication_timeline (
            shift_publication_timeline_id, shift_template_id, shift_version_id,
            event_sequence, state, business_effective_from,
            predecessor_timeline_id, recorded_at, actor_id, request_id
        )
        SELECT staged.shift_publication_timeline_id, staged.shift_template_id,
               staged.shift_version_id, staged.event_sequence, staged.state,
               staged.business_effective_from, staged.predecessor_timeline_id,
               staged.recorded_at, staged.actor_id, staged.request_id
        FROM fc_shift_timeline_stage staged
        JOIN shift_publication_timeline predecessor
          ON predecessor.shift_publication_timeline_id=staged.predecessor_timeline_id
        LEFT JOIN shift_publication_timeline existing
          ON existing.shift_publication_timeline_id=staged.shift_publication_timeline_id
        WHERE existing.shift_publication_timeline_id IS NULL;

        INSERT INTO shift_seasonal_schedule (
            shift_seasonal_schedule_id, shift_template_id, schedule_year,
            summer_effective_from, winter_effective_from,
            winter_h1_version_id, summer_version_id, winter_h2_version_id,
            schedule_digest, revision_number, row_version, change_reason,
            created_by, created_at, updated_by, updated_at
        )
        SELECT m.seasonal_schedule_id, m.shift_template_id, source.schedule_year,
               source.summer_effective_from, source.winter_effective_from,
               v1.target_version_id, v2.target_version_id, v3.target_version_id,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.shift_template_id,'|',source.schedule_year,'|',
                           source.summer_effective_from,'|',source.winter_effective_from,
                           '|',v1.target_version_id,'|',v2.target_version_id,'|',
                           v3.target_version_id),256),
               1, 0, '2026-08-05 四公司标准冬夏令计划初始化',
               bootstrap_actor_id, cutover_at, bootstrap_actor_id, cutover_at
        FROM fc_company_map m
        JOIN shift_seasonal_schedule source
          ON source.shift_template_id = source_shift_template_id
        JOIN fc_shift_version_map v1 ON v1.company_id=m.company_id
         AND v1.source_version_id=source.winter_h1_version_id
        JOIN fc_shift_version_summer_map v2 ON v2.company_id=m.company_id
         AND v2.source_version_id=source.summer_version_id
        JOIN fc_shift_version_winter_h2_map v3 ON v3.company_id=m.company_id
         AND v3.source_version_id=source.winter_h2_version_id;

        INSERT INTO shift_seasonal_schedule_revision (
            shift_seasonal_schedule_revision_id, shift_seasonal_schedule_id,
            revision_number, winter_h1_version_id, summer_version_id,
            winter_h2_version_id, schedule_digest, change_reason, actor_id, recorded_at
        )
        SELECT m.seasonal_revision_id, s.shift_seasonal_schedule_id, 1,
               s.winter_h1_version_id, s.summer_version_id, s.winter_h2_version_id,
               s.schedule_digest, s.change_reason, bootstrap_actor_id, cutover_at
        FROM fc_company_map m JOIN shift_seasonal_schedule s
          ON s.shift_seasonal_schedule_id=m.seasonal_schedule_id;

        INSERT INTO work_calendar (
            work_calendar_id, company_id, location_id, calendar_code,
            created_by, created_at
        )
        SELECT calendar_id, company_id, location_id, 'STANDARD_2026',
               bootstrap_actor_id, cutover_at FROM fc_company_map;

        CREATE TEMPORARY TABLE fc_calendar_version_map (
            company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            source_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            target_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            PRIMARY KEY (company_id, source_version_id), UNIQUE KEY(target_version_id)
        ) ENGINE=InnoDB;
        INSERT INTO fc_calendar_version_map
        SELECT m.company_id, v.work_calendar_version_id,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h,1,8),'-',SUBSTR(h,9,4),'-',SUBSTR(h,13,4),
                            '-',SUBSTR(h,17,4),'-',SUBSTR(h,21,12)))
        FROM fc_company_map m
        JOIN work_calendar_version v ON v.work_calendar_id=source_calendar_id
        CROSS JOIN LATERAL (SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|calendar-version|',
            m.company_id,'|',v.work_calendar_version_id)) h) d;

        CREATE TEMPORARY TABLE fc_calendar_version_predecessor_map
            LIKE fc_calendar_version_map;
        INSERT INTO fc_calendar_version_predecessor_map
        SELECT * FROM fc_calendar_version_map;

        CREATE TEMPORARY TABLE fc_calendar_version_stage ENGINE=InnoDB AS
        SELECT vm.target_version_id AS work_calendar_version_id,
               m.calendar_id AS work_calendar_id, source.version_number,
               CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.short_name,'2026工作日历') AS calendar_name,
               source.calendar_year,
               source.time_zone_snapshot, source.effective_from, source.effective_to,
               predecessor.target_version_id AS supersedes_work_calendar_version_id,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                  'S:',OCTET_LENGTH('work-calendar-snapshot-v1'),':work-calendar-snapshot-v1',
                  'S:36:',m.company_id,'S:36:',m.location_id,
                  'S:36:',m.calendar_id,'S:36:',vm.target_version_id,
                  'S:',OCTET_LENGTH(source.version_number),':',source.version_number,
                  'S:13:STANDARD_2026',
                  'S:',OCTET_LENGTH(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.short_name,'2026工作日历')),':',
                      CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.short_name,'2026工作日历'),
                  'S:',OCTET_LENGTH(source.calendar_year),':',source.calendar_year,
                  'S:',OCTET_LENGTH(source.time_zone_snapshot),':',source.time_zone_snapshot,
                  'S:10:',source.effective_from,'S:10:',source.effective_to,
                  'S:',OCTET_LENGTH(days.day_count),':',days.day_count,days.day_body),256)
                   AS snapshot_digest,
               '2026-08-05 四公司2026工作日历初始化' AS change_reason,
               bootstrap_actor_id AS created_by, cutover_at AS created_at
        FROM fc_company_map m
        JOIN fc_calendar_version_map vm ON vm.company_id=m.company_id
        JOIN work_calendar_version source
          ON source.work_calendar_version_id=vm.source_version_id
        LEFT JOIN fc_calendar_version_predecessor_map predecessor
          ON predecessor.company_id=m.company_id
         AND predecessor.source_version_id=source.supersedes_work_calendar_version_id
        JOIN (
            SELECT v.work_calendar_version_id,
                   COUNT(d.work_calendar_day_id) day_count,
                   COALESCE(GROUP_CONCAT(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                     'S:10:',d.business_date,
                     'S:',OCTET_LENGTH(d.day_type),':',d.day_type,
                     'N:0:') ORDER BY d.business_date,d.day_type SEPARATOR ''),'') day_body
            FROM work_calendar_version v
            LEFT JOIN work_calendar_day d
              ON d.work_calendar_version_id=v.work_calendar_version_id
            WHERE v.work_calendar_id=source_calendar_id
            GROUP BY v.work_calendar_version_id
        ) days ON days.work_calendar_version_id=source.work_calendar_version_id;

        INSERT INTO work_calendar_version (
            work_calendar_version_id, work_calendar_id, version_number,
            calendar_name, calendar_year, time_zone_snapshot,
            effective_from, effective_to, supersedes_work_calendar_version_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT work_calendar_version_id, work_calendar_id, version_number,
               calendar_name, calendar_year, time_zone_snapshot,
               effective_from, effective_to, supersedes_work_calendar_version_id,
               snapshot_digest, change_reason, created_by, created_at
        FROM fc_calendar_version_stage
        WHERE supersedes_work_calendar_version_id IS NULL;

        INSERT INTO work_calendar_version (
            work_calendar_version_id, work_calendar_id, version_number,
            calendar_name, calendar_year, time_zone_snapshot,
            effective_from, effective_to, supersedes_work_calendar_version_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT staged.work_calendar_version_id, staged.work_calendar_id,
               staged.version_number, staged.calendar_name, staged.calendar_year,
               staged.time_zone_snapshot, staged.effective_from, staged.effective_to,
               staged.supersedes_work_calendar_version_id, staged.snapshot_digest,
               staged.change_reason, staged.created_by, staged.created_at
        FROM fc_calendar_version_stage staged
        JOIN work_calendar_version predecessor
          ON predecessor.work_calendar_version_id=
             staged.supersedes_work_calendar_version_id
        LEFT JOIN work_calendar_version existing
          ON existing.work_calendar_version_id=staged.work_calendar_version_id
        WHERE existing.work_calendar_version_id IS NULL;

        INSERT INTO work_calendar_day (
            work_calendar_day_id, work_calendar_version_id, business_date,
            day_type, shift_version_override_id, snapshot_digest, created_by, created_at
        )
        SELECT LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h,1,8),'-',SUBSTR(h,9,4),'-',SUBSTR(h,13,4),
                            '-',SUBSTR(h,17,4),'-',SUBSTR(h,21,12))),
               vm.target_version_id, d.business_date, d.day_type, NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,vm.target_version_id,'|',d.business_date,'|',d.day_type,'|NULL'),256),
               bootstrap_actor_id, cutover_at
        FROM fc_calendar_version_map vm
        JOIN work_calendar_day d ON d.work_calendar_version_id=vm.source_version_id
        CROSS JOIN LATERAL (SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|calendar-day|',
            vm.company_id,'|',d.work_calendar_day_id)) h) seed;

        INSERT INTO calendar_publication_timeline (
            calendar_publication_timeline_id, work_calendar_id,
            work_calendar_version_id, event_sequence, state,
            business_effective_from, predecessor_timeline_id,
            recorded_at, actor_id, request_id
        )
        SELECT LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h,1,8),'-',SUBSTR(h,9,4),'-',SUBSTR(h,13,4),
                            '-',SUBSTR(h,17,4),'-',SUBSTR(h,21,12))),
               m.calendar_id, vm.target_version_id, source.event_sequence,
               source.state, source.business_effective_from, NULL,
               cutover_at, bootstrap_actor_id, CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-CALENDAR-',m.company_id)
        FROM fc_company_map m
        JOIN calendar_publication_timeline source
          ON source.work_calendar_id=source_calendar_id
        JOIN fc_calendar_version_map vm ON vm.company_id=m.company_id
         AND vm.source_version_id=source.work_calendar_version_id
        CROSS JOIN LATERAL (SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|calendar-timeline|',
            m.company_id,'|',source.calendar_publication_timeline_id)) h) seed;

        INSERT INTO shift_template (
            shift_template_id,company_id,location_id,template_code,created_by,created_at
        )
        SELECT special.shift_template_id,
               '41000000-0000-0000-0000-000000000003',city.location_id,
               special.shift_code,bootstrap_actor_id,cutover_at
        FROM fc_special_map special
        JOIN fc_city_map city
          ON city.company_id='41000000-0000-0000-0000-000000000003'
         AND city.city_code=special.special_code;

        CREATE TEMPORARY TABLE fc_special_shift_raw (
            shift_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
            special_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            shift_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            version_number INT NOT NULL,
            effective_from DATE NOT NULL,
            segments_json JSON NOT NULL
        ) ENGINE=InnoDB;
        INSERT INTO fc_special_shift_raw VALUES
          ('42021000-0000-0000-0000-000000000001','DALIAN',
           '42020000-0000-0000-0000-000000000001',1,'2026-01-01',
           CAST('{"effectiveTo":"2027-01-01","segments":[{"segmentType":"WORK","startLocalTime":"07:30:00","startDayOffset":0,"endLocalTime":"12:00:00","endDayOffset":0},{"segmentType":"BREAK","startLocalTime":"12:00:00","startDayOffset":0,"endLocalTime":"13:00:00","endDayOffset":0},{"segmentType":"WORK","startLocalTime":"13:00:00","startDayOffset":0,"endLocalTime":"16:30:00","endDayOffset":0}]}' AS JSON)),
          ('42021000-0000-0000-0000-000000000002','CHENGDU',
           '42020000-0000-0000-0000-000000000002',1,'2026-01-01',
           CAST('{"effectiveTo":"2027-01-01","segments":[{"segmentType":"WORK","startLocalTime":"09:00:00","startDayOffset":0,"endLocalTime":"12:00:00","endDayOffset":0},{"segmentType":"BREAK","startLocalTime":"12:00:00","startDayOffset":0,"endLocalTime":"13:00:00","endDayOffset":0},{"segmentType":"WORK","startLocalTime":"13:00:00","startDayOffset":0,"endLocalTime":"18:00:00","endDayOffset":0}]}' AS JSON));
        INSERT INTO fc_special_shift_raw
        SELECT CASE source.version_number
                   WHEN 1 THEN '42021000-0000-0000-0000-000000000003'
                   WHEN 2 THEN '42021000-0000-0000-0000-000000000004'
                   WHEN 3 THEN '42021000-0000-0000-0000-000000000005'
               END,
               'SHANGHAI','42020000-0000-0000-0000-000000000003',
               source.version_number,source.effective_from,source.segments_json
        FROM shift_version source
        WHERE source.shift_template_id='41020000-0000-0000-0000-000000000003';

        CREATE TEMPORARY TABLE fc_special_shift_raw_copy LIKE fc_special_shift_raw;
        INSERT INTO fc_special_shift_raw_copy SELECT * FROM fc_special_shift_raw;

        CREATE TEMPORARY TABLE fc_special_shift_stage ENGINE=InnoDB AS
        SELECT raw.shift_version_id,raw.shift_template_id,raw.version_number,
               raw.effective_from,'Asia/Shanghai' AS time_zone_snapshot,
               raw.segments_json,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                   'S:17:shift-snapshot-v1','S:36:',raw.shift_template_id,
                   'S:36:',raw.shift_version_id,
                   'S:',OCTET_LENGTH(raw.version_number),':',raw.version_number,
                   'S:10:',raw.effective_from,
                   'S:10:',JSON_UNQUOTE(JSON_EXTRACT(raw.segments_json,'$.effectiveTo')),
                   'S:13:Asia/Shanghai',
                   'S:',OCTET_LENGTH(segments.segment_count),':',segments.segment_count,
                   segments.segment_body),256) AS snapshot_digest
        FROM fc_special_shift_raw raw
        JOIN (
            SELECT source.shift_version_id,COUNT(*) segment_count,
                   GROUP_CONCAT(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                       'S:',OCTET_LENGTH(segment.segment_type),':',segment.segment_type,
                       'S:5:',TIME_FORMAT(TIME(segment.start_time),'%H:%i'),
                       'S:',OCTET_LENGTH(segment.start_offset),':',segment.start_offset,
                       'S:5:',TIME_FORMAT(TIME(segment.end_time),'%H:%i'),
                       'S:',OCTET_LENGTH(segment.end_offset),':',segment.end_offset)
                       ORDER BY segment.ord SEPARATOR '') segment_body
            FROM fc_special_shift_raw_copy source
            JOIN JSON_TABLE(source.segments_json,'$.segments[*]' COLUMNS(
                ord FOR ORDINALITY,
                segment_type VARCHAR(32) PATH '$.segmentType',
                start_time VARCHAR(16) PATH '$.startLocalTime',
                start_offset INT PATH '$.startDayOffset',
                end_time VARCHAR(16) PATH '$.endLocalTime',
                end_offset INT PATH '$.endDayOffset'
            )) segment ON TRUE
            GROUP BY source.shift_version_id
        ) segments ON segments.shift_version_id=raw.shift_version_id;

        INSERT INTO shift_version (
            shift_version_id,shift_template_id,version_number,effective_from,
            time_zone_snapshot,segments_json,supersedes_shift_version_id,
            snapshot_digest,change_reason,created_by,created_at
        )
        SELECT shift_version_id,shift_template_id,version_number,effective_from,
               time_zone_snapshot,segments_json,NULL,snapshot_digest,
               '2026-08-05 城市班次初始化',bootstrap_actor_id,cutover_at
        FROM fc_special_shift_stage;

        INSERT INTO shift_publication_timeline (
            shift_publication_timeline_id,shift_template_id,shift_version_id,
            event_sequence,state,business_effective_from,predecessor_timeline_id,
            recorded_at,actor_id,request_id
        ) VALUES
          ('42024000-0000-0000-0000-000000000001',
           '42020000-0000-0000-0000-000000000001',
           '42021000-0000-0000-0000-000000000001',1,'PUBLISHED','2026-01-01',NULL,
           cutover_at,bootstrap_actor_id,'FCF-SHIFT-DALIAN-1'),
          ('42024000-0000-0000-0000-000000000002',
           '42020000-0000-0000-0000-000000000002',
           '42021000-0000-0000-0000-000000000002',1,'PUBLISHED','2026-01-01',NULL,
           cutover_at,bootstrap_actor_id,'FCF-SHIFT-CHENGDU-1'),
          ('42024000-0000-0000-0000-000000000003',
           '42020000-0000-0000-0000-000000000003',
           '42021000-0000-0000-0000-000000000003',1,'PUBLISHED','2026-01-01',NULL,
           cutover_at,bootstrap_actor_id,'FCF-SHIFT-SHANGHAI-1');
        INSERT INTO shift_publication_timeline (
            shift_publication_timeline_id,shift_template_id,shift_version_id,
            event_sequence,state,business_effective_from,predecessor_timeline_id,
            recorded_at,actor_id,request_id
        ) VALUES
          ('42024000-0000-0000-0000-000000000004',
           '42020000-0000-0000-0000-000000000003',
           '42021000-0000-0000-0000-000000000004',2,'PUBLISHED','2026-05-01',
           '42024000-0000-0000-0000-000000000003',cutover_at,bootstrap_actor_id,
           'FCF-SHIFT-SHANGHAI-2');
        INSERT INTO shift_publication_timeline (
            shift_publication_timeline_id,shift_template_id,shift_version_id,
            event_sequence,state,business_effective_from,predecessor_timeline_id,
            recorded_at,actor_id,request_id
        ) VALUES
          ('42024000-0000-0000-0000-000000000005',
           '42020000-0000-0000-0000-000000000003',
           '42021000-0000-0000-0000-000000000005',3,'PUBLISHED','2026-10-01',
           '42024000-0000-0000-0000-000000000004',cutover_at,bootstrap_actor_id,
           'FCF-SHIFT-SHANGHAI-3');

        INSERT INTO shift_seasonal_schedule (
            shift_seasonal_schedule_id,shift_template_id,schedule_year,
            summer_effective_from,winter_effective_from,winter_h1_version_id,
            summer_version_id,winter_h2_version_id,schedule_digest,
            revision_number,row_version,change_reason,created_by,created_at,
            updated_by,updated_at
        ) VALUES (
            '42022000-0000-0000-0000-000000000003',
            '42020000-0000-0000-0000-000000000003',2026,'2026-05-01','2026-10-01',
            '42021000-0000-0000-0000-000000000003',
            '42021000-0000-0000-0000-000000000004',
            '42021000-0000-0000-0000-000000000005',
            SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'42020000-0000-0000-0000-000000000003|2026|2026-05-01|2026-10-01|',
                        '42021000-0000-0000-0000-000000000003|',
                        '42021000-0000-0000-0000-000000000004|',
                        '42021000-0000-0000-0000-000000000005'),256),
            1,0,'2026-08-05 上海复制扬州冬夏令班次',bootstrap_actor_id,cutover_at,
            bootstrap_actor_id,cutover_at);
        INSERT INTO shift_seasonal_schedule_revision (
            shift_seasonal_schedule_revision_id,shift_seasonal_schedule_id,
            revision_number,winter_h1_version_id,summer_version_id,
            winter_h2_version_id,schedule_digest,change_reason,actor_id,recorded_at
        )
        SELECT '42023000-0000-0000-0000-000000000003',
               shift_seasonal_schedule_id,1,winter_h1_version_id,summer_version_id,
               winter_h2_version_id,schedule_digest,change_reason,
               bootstrap_actor_id,cutover_at
        FROM shift_seasonal_schedule
        WHERE shift_seasonal_schedule_id='42022000-0000-0000-0000-000000000003';

        INSERT INTO attendance_group (
            attendance_group_id, company_id, group_code, created_by, created_at
        )
        SELECT group_id,company_id,'DEFAULT_ATTENDANCE',bootstrap_actor_id,cutover_at
        FROM fc_company_map;

        INSERT INTO attendance_group_revision (
            attendance_group_revision_id, attendance_group_id, revision_number,
            group_name, location_revision_id, work_calendar_id, shift_template_id,
            effective_from, supersedes_attendance_group_revision_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT group_revision_id,group_id,1,CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,short_name,'默认考勤组'),
               location_revision_id,calendar_id,shift_template_id,'2026-01-01',NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,company_id,'|DEFAULT_ATTENDANCE|',short_name,
                    '默认考勤组|',location_revision_id,'|Asia/Shanghai|',calendar_id,
                    '|',shift_template_id,'|2026-01-01|NULL'),256),
               '2026-08-05 四公司默认考勤组初始化',bootstrap_actor_id,cutover_at
        FROM fc_company_map;

        INSERT INTO attendance_group_timeline (
            attendance_group_timeline_id,attendance_group_id,
            attendance_group_revision_id,event_sequence,state,
            business_effective_from,predecessor_timeline_id,
            recorded_at,actor_id,request_id
        )
        SELECT group_timeline_id,group_id,group_revision_id,1,'ACTIVE','2026-01-01',
               NULL,cutover_at,bootstrap_actor_id,CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-GROUP-',company_id)
        FROM fc_company_map;

        INSERT INTO attendance_group (
            attendance_group_id,company_id,group_code,created_by,created_at
        )
        SELECT group_id,'41000000-0000-0000-0000-000000000003',group_code,
               bootstrap_actor_id,cutover_at
        FROM fc_special_map;

        INSERT INTO attendance_group_revision (
            attendance_group_revision_id,attendance_group_id,revision_number,
            group_name,location_revision_id,work_calendar_id,shift_template_id,
            effective_from,supersedes_attendance_group_revision_id,snapshot_digest,
            change_reason,created_by,created_at
        )
        SELECT special.group_revision_id,special.group_id,1,special.group_name,
               city.location_revision_id,
               '41030000-0000-0000-0000-000000000003',special.shift_template_id,
               '2026-01-01',NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'41000000-0000-0000-0000-000000000003|',
                           special.group_code,'|',special.group_name,'|',
                           city.location_revision_id,'|Asia/Shanghai|',
                           '41030000-0000-0000-0000-000000000003|',
                           special.shift_template_id,'|2026-01-01|NULL'),256),
               '2026-08-05 城市考勤组初始化',bootstrap_actor_id,cutover_at
        FROM fc_special_map special
        JOIN fc_city_map city
          ON city.company_id='41000000-0000-0000-0000-000000000003'
         AND city.city_code=special.special_code;

        INSERT INTO attendance_group_timeline (
            attendance_group_timeline_id,attendance_group_id,
            attendance_group_revision_id,event_sequence,state,
            business_effective_from,predecessor_timeline_id,
            recorded_at,actor_id,request_id
        )
        SELECT group_timeline_id,group_id,group_revision_id,1,'ACTIVE','2026-01-01',
               NULL,cutover_at,bootstrap_actor_id,CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-GROUP-',special_code)
        FROM fc_special_map;

        CREATE TEMPORARY TABLE fc_policy_map (
            company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            template_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            source_scope_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            target_scope_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            source_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            target_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            target_lifecycle_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            PRIMARY KEY(company_id,template_code),
            UNIQUE KEY(target_scope_id), UNIQUE KEY(target_version_id),
            UNIQUE KEY(target_lifecycle_id)
        ) ENGINE=InnoDB;
        INSERT INTO fc_policy_map
        SELECT m.company_id,t.template_code,s.scope_id,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h1,1,8),'-',SUBSTR(h1,9,4),'-',SUBSTR(h1,13,4),
                            '-',SUBSTR(h1,17,4),'-',SUBSTR(h1,21,12))),
               v.scoped_version_id,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h2,1,8),'-',SUBSTR(h2,9,4),'-',SUBSTR(h2,13,4),
                            '-',SUBSTR(h2,17,4),'-',SUBSTR(h2,21,12))),
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h3,1,8),'-',SUBSTR(h3,9,4),'-',SUBSTR(h3,13,4),
                            '-',SUBSTR(h3,17,4),'-',SUBSTR(h3,21,12)))
        FROM fc_company_map m
        JOIN attendance_policy_scope s ON s.company_id=archive_company_id
        JOIN attendance_policy_template t ON t.policy_template_id=s.policy_template_id
        JOIN attendance_policy_scoped_version v ON v.scope_id=s.scope_id
        CROSS JOIN LATERAL (SELECT
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|policy-scope|',m.company_id,'|',t.template_code)) h1,
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|policy-version|',m.company_id,'|',t.template_code)) h2,
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|policy-lifecycle|',m.company_id,'|',t.template_code)) h3
        ) seed;

        INSERT INTO attendance_policy_scope (
            scope_id,policy_template_id,company_id,row_version,created_by,created_at
        )
        SELECT pm.target_scope_id,s.policy_template_id,pm.company_id,0,
               bootstrap_actor_id,cutover_at
        FROM fc_policy_map pm JOIN attendance_policy_scope s
          ON s.scope_id=pm.source_scope_id;

        INSERT INTO attendance_policy_scoped_version (
            scoped_version_id,scope_id,version_number,parameters_json,
            effective_from,effective_to,validation_json,snapshot_json,snapshot_digest,
            rollback_of_scoped_version_id,row_version,change_reason,created_by,created_at
        )
        SELECT pm.target_version_id,pm.target_scope_id,v.version_number,v.parameters_json,
               v.effective_from,v.effective_to,v.validation_json,
               canonical.snapshot_json,
               SHA2(canonical.snapshot_json,256),NULL,0,
               '2026-08-05 四公司考勤规则默认值初始化',
               bootstrap_actor_id,cutover_at
        FROM fc_policy_map pm
        JOIN attendance_policy_scoped_version v
          ON v.scoped_version_id=pm.source_version_id
        JOIN attendance_policy_scope source_scope
          ON source_scope.scope_id=pm.source_scope_id
        JOIN (
            SELECT source.scoped_version_id,
                   CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'{',GROUP_CONCAT(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                       JSON_QUOTE(parameter_key.parameter_key),':',
                       REPLACE(CAST(JSON_EXTRACT(
                           source.parameters_json,
                           CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'$.',parameter_key.parameter_key)
                       ) AS CHAR CHARACTER SET utf8mb4),', ',','))
                       ORDER BY CONVERT(parameter_key.parameter_key USING utf8mb4)
                                COLLATE utf8mb4_bin SEPARATOR ','),'}')
                       AS canonical_parameters
            FROM attendance_policy_scoped_version source
            JOIN JSON_TABLE(
                JSON_KEYS(source.parameters_json),
                '$[*]' COLUMNS(parameter_key VARCHAR(128) PATH '$')
            ) parameter_key ON TRUE
            GROUP BY source.scoped_version_id
        ) parameters ON parameters.scoped_version_id=v.scoped_version_id
        CROSS JOIN LATERAL (
            SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                '{"companyId":',JSON_QUOTE(pm.company_id),
                ',"effectiveFrom":',JSON_QUOTE(DATE_FORMAT(v.effective_from,'%Y-%m-%d')),
                ',"effectiveTo":',IF(v.effective_to IS NULL,'null',
                    JSON_QUOTE(DATE_FORMAT(v.effective_to,'%Y-%m-%d'))),
                ',"parameters":',parameters.canonical_parameters,
                ',"policyKind":',JSON_QUOTE(pm.template_code),
                ',"scopeId":',JSON_QUOTE(pm.target_scope_id),
                ',"templateId":',JSON_QUOTE(source_scope.policy_template_id),
                ',"versionNumber":',v.version_number,'}'
            ) AS snapshot_json
        ) canonical;

        INSERT INTO attendance_policy_lifecycle_event (
            lifecycle_event_id,scope_id,scoped_version_id,event_sequence,action,
            business_effective_from,predecessor_event_id,reason,actor_id,request_id,recorded_at
        )
        SELECT pm.target_lifecycle_id,pm.target_scope_id,pm.target_version_id,1,
               'PUBLISHED',source.business_effective_from,NULL,
               '2026-08-05 四公司考勤规则默认值发布',bootstrap_actor_id,
               CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-P-',SUBSTR(MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,pm.company_id,'|',pm.template_code)),1,32)),
               cutover_at
        FROM fc_policy_map pm JOIN attendance_policy_lifecycle_event source
          ON source.scope_id=pm.source_scope_id AND source.action='PUBLISHED';

        CREATE TEMPORARY TABLE fc_binding_map (
            company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            policy_kind VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            family_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            PRIMARY KEY(company_id,policy_kind), UNIQUE KEY(family_id), UNIQUE KEY(revision_id)
        ) ENGINE=InnoDB;
        INSERT INTO fc_binding_map
        SELECT m.company_id,f.policy_kind,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h1,1,8),'-',SUBSTR(h1,9,4),'-',SUBSTR(h1,13,4),
                            '-',SUBSTR(h1,17,4),'-',SUBSTR(h1,21,12))),
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h2,1,8),'-',SUBSTR(h2,9,4),'-',SUBSTR(h2,13,4),
                            '-',SUBSTR(h2,17,4),'-',SUBSTR(h2,21,12)))
        FROM fc_company_map m
        JOIN attendance_policy_binding_family f
          ON f.attendance_group_id=source_group_id
        CROSS JOIN LATERAL (SELECT
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|binding-family|',m.company_id,'|',f.policy_kind)) h1,
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|binding-revision|',m.company_id,'|',f.policy_kind)) h2
        ) seed;

        INSERT INTO attendance_policy_binding_family (
            binding_family_id,attendance_group_id,policy_kind,created_by,created_at
        )
        SELECT b.family_id,m.group_id,b.policy_kind,bootstrap_actor_id,cutover_at
        FROM fc_binding_map b JOIN fc_company_map m ON m.company_id=b.company_id;

        INSERT INTO attendance_policy_binding_revision (
            binding_revision_id,binding_family_id,attendance_group_revision_id,
            attendance_policy_scoped_version_id,revision_number,effective_from,
            supersedes_binding_revision_id,snapshot_digest,change_reason,created_by,created_at
        )
        SELECT b.revision_id,b.family_id,m.group_revision_id,pm.target_version_id,
               1,source.effective_from,NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.group_revision_id,'|',pm.target_version_id,'|',
                           v.snapshot_digest,'|',source.effective_from,'|NULL'),256),
               '2026-08-05 四公司默认考勤规则绑定',bootstrap_actor_id,cutover_at
        FROM fc_binding_map b
        JOIN fc_company_map m ON m.company_id=b.company_id
        JOIN fc_policy_map pm ON pm.company_id=b.company_id
         AND pm.template_code=b.policy_kind
        JOIN attendance_policy_scoped_version v
          ON v.scoped_version_id=pm.target_version_id
        JOIN attendance_policy_binding_family sf
          ON sf.attendance_group_id=source_group_id AND sf.policy_kind=b.policy_kind
        JOIN attendance_policy_binding_revision source
          ON source.binding_family_id=sf.binding_family_id;

        CREATE TEMPORARY TABLE fc_special_binding_map (
            special_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            policy_kind VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            family_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            PRIMARY KEY(special_code,policy_kind),UNIQUE KEY(family_id),
            UNIQUE KEY(revision_id)
        ) ENGINE=InnoDB;
        INSERT INTO fc_special_binding_map
        SELECT special.special_code,source_family.policy_kind,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(family_hash,1,8),'-',SUBSTR(family_hash,9,4),'-',
                            SUBSTR(family_hash,13,4),'-',SUBSTR(family_hash,17,4),'-',
                            SUBSTR(family_hash,21,12))),
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(revision_hash,1,8),'-',SUBSTR(revision_hash,9,4),'-',
                            SUBSTR(revision_hash,13,4),'-',SUBSTR(revision_hash,17,4),'-',
                            SUBSTR(revision_hash,21,12)))
        FROM fc_special_map special
        JOIN attendance_policy_binding_family source_family
          ON source_family.attendance_group_id=source_group_id
        CROSS JOIN LATERAL (SELECT
            MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|special-binding-family|',
                       special.special_code,'|',source_family.policy_kind)) family_hash,
            MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|special-binding-revision|',
                       special.special_code,'|',source_family.policy_kind)) revision_hash
        ) seed;

        INSERT INTO attendance_policy_binding_family (
            binding_family_id,attendance_group_id,policy_kind,created_by,created_at
        )
        SELECT binding.family_id,special.group_id,binding.policy_kind,
               bootstrap_actor_id,cutover_at
        FROM fc_special_binding_map binding
        JOIN fc_special_map special
          ON special.special_code=binding.special_code;

        INSERT INTO attendance_policy_binding_revision (
            binding_revision_id,binding_family_id,attendance_group_revision_id,
            attendance_policy_scoped_version_id,revision_number,effective_from,
            supersedes_binding_revision_id,snapshot_digest,change_reason,created_by,created_at
        )
        SELECT binding.revision_id,binding.family_id,special.group_revision_id,
               policy.target_version_id,1,source_revision.effective_from,NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,special.group_revision_id,'|',policy.target_version_id,'|',
                           target_version.snapshot_digest,'|',
                           source_revision.effective_from,'|NULL'),256),
               '2026-08-05 城市考勤组规则绑定',bootstrap_actor_id,cutover_at
        FROM fc_special_binding_map binding
        JOIN fc_special_map special
          ON special.special_code=binding.special_code
        JOIN fc_policy_map policy
          ON policy.company_id='41000000-0000-0000-0000-000000000003'
         AND policy.template_code=binding.policy_kind
        JOIN attendance_policy_scoped_version target_version
          ON target_version.scoped_version_id=policy.target_version_id
        JOIN attendance_policy_binding_family source_family
          ON source_family.attendance_group_id=source_group_id
         AND source_family.policy_kind=binding.policy_kind
        JOIN attendance_policy_binding_revision source_revision
          ON source_revision.binding_family_id=source_family.binding_family_id;

        INSERT INTO annual_leave_policy_version (
            annual_leave_policy_version_id,version_number,scope_type,company_id,
            based_on_version_id,effective_from,effective_to,qualification_required,
            qualification_months,leap_day_rule,tiers_json,status,snapshot_digest,
            row_version,change_reason,created_by,created_at,published_at
        )
        SELECT m.annual_policy_version_id,v.version_number,'COMPANY',m.company_id,
               v.based_on_version_id,v.effective_from,v.effective_to,
               v.qualification_required,v.qualification_months,v.leap_day_rule,
               v.tiers_json,'PUBLISHED',v.snapshot_digest,0,
               '2026-08-05 四公司年假默认策略初始化',bootstrap_actor_id,cutover_at,cutover_at
        FROM fc_company_map m JOIN annual_leave_policy_version v
          ON v.company_id=archive_company_id AND v.scope_type='COMPANY'
         AND v.status='PUBLISHED';

        INSERT INTO annual_leave_policy_lifecycle_event (
            annual_leave_policy_lifecycle_event_id,annual_leave_policy_version_id,
            event_sequence,action,business_effective_from,reason,actor_id,recorded_at
        )
        SELECT m.annual_lifecycle_id,m.annual_policy_version_id,1,'PUBLISHED',
               source.business_effective_from,'2026-08-05 四公司年假默认策略发布',
               bootstrap_actor_id,cutover_at
        FROM fc_company_map m
        JOIN annual_leave_policy_version old ON old.company_id=archive_company_id
         AND old.scope_type='COMPANY' AND old.status='PUBLISHED'
        JOIN annual_leave_policy_lifecycle_event source
          ON source.annual_leave_policy_version_id=old.annual_leave_policy_version_id
         AND source.action='PUBLISHED';

        INSERT INTO attendance_company_default_provisioning (
            provisioning_id,company_id,provisioning_year,location_id,
            shift_template_id,calendar_id,attendance_group_id,status,
            configuration_digest,change_reason,created_by,created_at
        )
        SELECT provisioning_id,company_id,2026,location_id,shift_template_id,
               calendar_id,group_id,'READY',
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,company_id,'|2026|',location_id,'|',shift_template_id,
                           '|',calendar_id,'|',group_id),256),
               '2026-08-05 四公司完整默认考勤基线',bootstrap_actor_id,cutover_at
        FROM fc_company_map;

        -- Versioned cross-company reassignment. The old imported assignments
        -- remain archive history; successors begin strictly later on cutover.
        IF (SELECT COUNT(*) FROM attendance_group_assignment) <> 615 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'source attendance assignments changed';
        END IF;
        IF EXISTS (SELECT 1 FROM attendance_group_assignment
                   WHERE supersedes_assignment_id IS NOT NULL) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'source attendance assignments changed';
        END IF;
        IF EXISTS (
            SELECT 1 FROM fc_employee_map em
            LEFT JOIN attendance_group_assignment olda
              ON olda.employee_id=em.employee_id
             AND olda.supersedes_assignment_id IS NULL
            GROUP BY em.employee_id
            HAVING COUNT(olda.attendance_group_assignment_id)<>1
        ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'source attendance assignments changed';
        END IF;

        CREATE TEMPORARY TABLE fc_assignment_map (
            employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
            company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            target_group_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            old_assignment_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
            old_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
            new_assignment_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
            old_inactive_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
            new_active_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE
        ) ENGINE=InnoDB;
        INSERT INTO fc_assignment_map
        SELECT em.employee_id,em.company_id,
               COALESCE(special.group_revision_id,company.group_revision_id),
               olda.attendance_group_assignment_id,
               t.attendance_assignment_timeline_id,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h1,1,8),'-',SUBSTR(h1,9,4),'-',SUBSTR(h1,13,4),
                            '-',SUBSTR(h1,17,4),'-',SUBSTR(h1,21,12))),
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h2,1,8),'-',SUBSTR(h2,9,4),'-',SUBSTR(h2,13,4),
                            '-',SUBSTR(h2,17,4),'-',SUBSTR(h2,21,12))),
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h3,1,8),'-',SUBSTR(h3,9,4),'-',SUBSTR(h3,13,4),
                            '-',SUBSTR(h3,17,4),'-',SUBSTR(h3,21,12)))
        FROM fc_employee_map em
        JOIN fc_company_map company ON company.company_id=em.company_id
        LEFT JOIN fc_special_employee_map special_employee
          ON special_employee.employee_id=em.employee_id
        LEFT JOIN fc_special_map special
          ON special.special_code=special_employee.special_code
        JOIN attendance_group_assignment olda
          ON olda.employee_id=em.employee_id
         AND olda.supersedes_assignment_id IS NULL
        JOIN attendance_assignment_timeline t
          ON t.attendance_group_assignment_id=
             olda.attendance_group_assignment_id
         AND t.event_sequence=1 AND t.state='ACTIVE'
        CROSS JOIN LATERAL (SELECT
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|assignment|',em.employee_id)) h1,
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|assignment-old-inactive|',em.employee_id)) h2,
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|assignment-new-active|',em.employee_id)) h3
        ) seed;
        IF (SELECT COUNT(*) FROM fc_assignment_map) <> 615 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'source attendance assignments changed';
        END IF;

        INSERT INTO attendance_assignment_timeline (
            attendance_assignment_timeline_id,attendance_group_assignment_id,
            employee_id,event_sequence,state,business_effective_from,
            predecessor_timeline_id,recorded_at,actor_id,request_id
        )
        SELECT old_inactive_timeline_id,old_assignment_id,employee_id,2,'INACTIVE',
               cutover_date,old_timeline_id,cutover_at,bootstrap_actor_id,
               CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-REASSIGN-',employee_id)
        FROM fc_assignment_map;

        INSERT INTO attendance_group_assignment (
            attendance_group_assignment_id,employee_id,attendance_group_revision_id,
            effective_from,supersedes_assignment_id,snapshot_digest,
            change_reason,created_by,created_at
        )
        SELECT a.new_assignment_id,a.employee_id,a.target_group_revision_id,cutover_date,
               a.old_assignment_id,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,a.target_group_revision_id,'|',a.employee_id,
                           '|',cutover_date,'|NULL'),256),
               '2026-08-05 四公司维度切换：考勤组重分配',
               bootstrap_actor_id,cutover_at
        FROM fc_assignment_map a;

        INSERT INTO attendance_assignment_timeline (
            attendance_assignment_timeline_id,attendance_group_assignment_id,
            employee_id,event_sequence,state,business_effective_from,
            predecessor_timeline_id,recorded_at,actor_id,request_id
        )
        SELECT new_active_timeline_id,new_assignment_id,employee_id,1,'ACTIVE',
               cutover_date,NULL,cutover_at,bootstrap_actor_id,
               CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-REASSIGN-',employee_id)
        FROM fc_assignment_map;

        INSERT INTO auth_data_scope (
            scope_id,scope_type,company_id,organization_id,
            include_descendants,valid_from,valid_to
        )
        SELECT auth_scope_id,'COMPANY',company_id,NULL,TRUE,cutover_at,NULL
        FROM fc_company_map;

        INSERT INTO auth_principal_role_assignment (
            assignment_id,principal_id,role_id,data_scope_id,
            valid_from,valid_to,assigned_by,reason
        )
        SELECT LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h,1,8),'-',SUBSTR(h,9,4),'-',SUBSTR(h,13,4),
                            '-',SUBSTR(h,17,4),'-',SUBSTR(h,21,12))),
               admin_principal_id,r.role_id,m.auth_scope_id,cutover_at,NULL,
               bootstrap_actor_id,'2026-08-05 四公司初始管理员授权'
        FROM fc_company_map m
        JOIN auth_role r ON r.role_id IN (hr_role_id,system_role_id)
        CROSS JOIN LATERAL (SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|admin-role|',
            m.company_id,'|',r.role_id)) h) seed;

        UPDATE auth_principal_role_assignment a
        SET a.valid_to=cutover_at,
            a.reason='2026-08-05 原混合公司归档，授权结束'
        WHERE a.principal_id=admin_principal_id
          AND a.data_scope_id=old_scope_id
          AND a.role_id IN (hr_role_id,system_role_id)
          AND a.valid_to IS NULL;
        UPDATE auth_data_scope SET valid_to=cutover_at
        WHERE scope_id=old_scope_id AND valid_to IS NULL;
    END IF;

    -- Strong final shape checks. Any drift rolls back the first run and also
    -- prevents a later run from silently accepting a changed final state.
    IF (SELECT COUNT(*) FROM company WHERE status='ACTIVE') <> 4
       OR (SELECT COUNT(*) FROM company c JOIN fc_company_map m
           ON m.company_id=c.company_id
           WHERE c.code=m.company_code AND c.name=m.company_name
             AND c.status='ACTIVE') <> 4
       OR (SELECT COUNT(*) FROM company
           WHERE company_id=archive_company_id
             AND code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
             AND name='历史导入归档（原四公司混合数据）'
             AND status='INACTIVE') <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final company directory verification failed';
    END IF;

    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        LEFT JOIN organization_identity oi ON oi.company_id=m.company_id
        GROUP BY m.company_id,m.expected_orgs
        HAVING COUNT(oi.organization_id)<>m.expected_orgs
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final organization/employee verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        LEFT JOIN employee e ON e.company_id=m.company_id
        GROUP BY m.company_id,m.expected_employees
        HAVING COUNT(e.employee_id)<>m.expected_employees
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final organization/employee verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM employment_assignment a
        JOIN employee e ON e.employee_id=a.employee_id
        JOIN organization_identity oi ON oi.organization_id=a.organization_id
        WHERE a.record_status='ACTIVE' AND a.current_version_marker=1
          AND e.company_id<>oi.company_id
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final organization/employee verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        JOIN organization_current_projection p ON p.organization_id=m.root_id
        JOIN organization_version v ON v.organization_version_id=p.current_version_id
        WHERE p.current_version_id<>m.root_version_id OR v.org_type<>'COMPANY'
          OR v.parent_organization_id IS NOT NULL OR v.current_marker<>1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final organization/employee verification failed';
    END IF;

    IF (SELECT COUNT(*) FROM location l JOIN fc_company_map m
        ON m.location_id=l.location_id
        WHERE l.company_id=m.company_id AND l.location_code='DEFAULT_LOCATION')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM shift_template s JOIN fc_company_map m
        ON m.shift_template_id=s.shift_template_id
        WHERE s.company_id=m.company_id AND s.template_code='STANDARD_SEASONAL')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM shift_version v JOIN fc_company_map m
        ON m.shift_template_id=v.shift_template_id)<>12 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM shift_seasonal_schedule s JOIN fc_company_map m
        ON m.seasonal_schedule_id=s.shift_seasonal_schedule_id)<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM work_calendar c JOIN fc_company_map m
        ON m.calendar_id=c.work_calendar_id
        WHERE c.company_id=m.company_id AND c.calendar_code='STANDARD_2026')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM work_calendar_version v JOIN fc_company_map m
        ON m.calendar_id=v.work_calendar_id)<>8 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM work_calendar_day d
        JOIN work_calendar_version v ON v.work_calendar_version_id=d.work_calendar_version_id
        JOIN fc_company_map m ON m.calendar_id=v.work_calendar_id)<>1460 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_group g JOIN fc_company_map m
        ON m.group_id=g.attendance_group_id
        WHERE g.company_id=m.company_id AND g.group_code='DEFAULT_ATTENDANCE')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_scope s JOIN fc_company_map m
        ON m.company_id=s.company_id)<>32 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_scoped_version v
        JOIN attendance_policy_scope s ON s.scope_id=v.scope_id
        JOIN fc_company_map m ON m.company_id=s.company_id)<>32 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_lifecycle_event e
        JOIN attendance_policy_scope s ON s.scope_id=e.scope_id
        JOIN fc_company_map m ON m.company_id=s.company_id
        WHERE e.action='PUBLISHED')<>32 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM attendance_policy_scoped_version v
        JOIN attendance_policy_scope s ON s.scope_id=v.scope_id
        JOIN attendance_policy_template t
          ON t.policy_template_id=s.policy_template_id
        JOIN fc_company_map m ON m.company_id=s.company_id
        JOIN (
            SELECT source.scoped_version_id,
                   CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'{',GROUP_CONCAT(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                       JSON_QUOTE(parameter_key.parameter_key),':',
                       REPLACE(CAST(JSON_EXTRACT(
                           source.parameters_json,
                           CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'$.',parameter_key.parameter_key)
                       ) AS CHAR CHARACTER SET utf8mb4),', ',','))
                       ORDER BY CONVERT(parameter_key.parameter_key USING utf8mb4)
                                COLLATE utf8mb4_bin SEPARATOR ','),'}')
                       AS canonical_parameters
            FROM attendance_policy_scoped_version source
            JOIN JSON_TABLE(
                JSON_KEYS(source.parameters_json),
                '$[*]' COLUMNS(parameter_key VARCHAR(128) PATH '$')
            ) parameter_key ON TRUE
            GROUP BY source.scoped_version_id
        ) parameters ON parameters.scoped_version_id=v.scoped_version_id
        CROSS JOIN LATERAL (
            SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                '{"companyId":',JSON_QUOTE(s.company_id),
                ',"effectiveFrom":',JSON_QUOTE(DATE_FORMAT(v.effective_from,'%Y-%m-%d')),
                ',"effectiveTo":',IF(v.effective_to IS NULL,'null',
                    JSON_QUOTE(DATE_FORMAT(v.effective_to,'%Y-%m-%d'))),
                ',"parameters":',parameters.canonical_parameters,
                ',"policyKind":',JSON_QUOTE(t.template_code),
                ',"scopeId":',JSON_QUOTE(s.scope_id),
                ',"templateId":',JSON_QUOTE(s.policy_template_id),
                ',"versionNumber":',v.version_number,'}'
            ) AS snapshot_json
        ) canonical
        WHERE COALESCE(JSON_LENGTH(v.snapshot_json),-1)<>8
           OR NOT (JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.companyId'))
                   <=> s.company_id)
           OR NOT (JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.effectiveFrom'))
                   <=> DATE_FORMAT(v.effective_from,'%Y-%m-%d'))
           OR (v.effective_to IS NULL AND
               COALESCE(JSON_TYPE(JSON_EXTRACT(
                   v.snapshot_json,'$.effectiveTo')),'MISSING')<>'NULL')
           OR (v.effective_to IS NOT NULL AND NOT (
               JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.effectiveTo'))
               <=> DATE_FORMAT(v.effective_to,'%Y-%m-%d')))
           OR COALESCE(JSON_CONTAINS(
                  JSON_EXTRACT(v.snapshot_json,'$.parameters'),v.parameters_json),0)=0
           OR COALESCE(JSON_CONTAINS(
                  v.parameters_json,
                  JSON_EXTRACT(v.snapshot_json,'$.parameters')),0)=0
           OR NOT (JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.policyKind'))
                   <=> t.template_code)
           OR NOT (JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.scopeId'))
                   <=> s.scope_id)
           OR NOT (JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.templateId'))
                   <=> s.policy_template_id)
           OR NOT (JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.versionNumber'))
                   <=> CAST(v.version_number AS CHAR))
           OR v.snapshot_digest<>SHA2(canonical.snapshot_json,256)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'attendance policy canonical snapshot verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_binding_family f
        JOIN fc_company_map m ON m.group_id=f.attendance_group_id)<>12 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_binding_revision r
        JOIN attendance_policy_binding_family f ON f.binding_family_id=r.binding_family_id
        JOIN fc_company_map m ON m.group_id=f.attendance_group_id)<>12 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM annual_leave_policy_version v
        JOIN fc_company_map m ON m.company_id=v.company_id
        WHERE v.scope_type='COMPANY' AND v.status='PUBLISHED')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM annual_leave_policy_lifecycle_event e
        JOIN fc_company_map m ON m.annual_policy_version_id=e.annual_leave_policy_version_id
        WHERE e.action='PUBLISHED')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_company_default_provisioning p
        JOIN fc_company_map m ON m.provisioning_id=p.provisioning_id
        WHERE p.company_id=m.company_id AND p.provisioning_year=2026
          AND p.status='READY')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM location location
        JOIN fc_city_map city ON city.location_id=location.location_id
        JOIN location_revision revision
          ON revision.location_revision_id=city.location_revision_id
        JOIN location_timeline timeline
          ON timeline.location_timeline_id=city.location_timeline_id
        WHERE location.company_id=city.company_id
          AND location.location_code=city.city_code
          AND revision.location_name=city.city_name
          AND revision.time_zone='Asia/Shanghai'
          AND timeline.state='ACTIVE' AND timeline.event_sequence=1)<>24 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'city location provisioning verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM shift_template shift_template
        JOIN fc_special_map special
          ON special.shift_template_id=shift_template.shift_template_id
        WHERE shift_template.company_id='41000000-0000-0000-0000-000000000003'
          AND shift_template.template_code=special.shift_code)<>3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special shift/group verification failed';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM shift_template shift_template
        JOIN location location ON location.location_id=shift_template.location_id
        WHERE location.location_code IN (
                  'CHENGDU','DALIAN','SHANGHAI','HEFEI','WUHAN','SHENZHEN')
          AND NOT (
              shift_template.company_id='41000000-0000-0000-0000-000000000003'
              AND location.location_code IN ('CHENGDU','DALIAN','SHANGHAI')
              AND shift_template.template_code IN (
                  'CHENGDU_FIXED','DALIAN_FIXED','SHANGHAI_SEASONAL'))
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'unapproved city-specific shift detected';
    END IF;
    IF (SELECT COUNT(*) FROM shift_version version
        JOIN fc_special_map special
          ON special.shift_template_id=version.shift_template_id)<>5 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special shift/group verification failed';
    END IF;
    IF (SELECT COUNT(*)
        FROM shift_version version
        WHERE version.version_number=1
          AND version.effective_from='2026-01-01'
          AND version.time_zone_snapshot='Asia/Shanghai'
          AND ((
              version.shift_version_id='42021000-0000-0000-0000-000000000001'
              AND version.segments_json=CAST(
                  '{"effectiveTo":"2027-01-01","segments":[{"segmentType":"WORK","startLocalTime":"07:30:00","startDayOffset":0,"endLocalTime":"12:00:00","endDayOffset":0},{"segmentType":"BREAK","startLocalTime":"12:00:00","startDayOffset":0,"endLocalTime":"13:00:00","endDayOffset":0},{"segmentType":"WORK","startLocalTime":"13:00:00","startDayOffset":0,"endLocalTime":"16:30:00","endDayOffset":0}]}'
                  AS JSON)
          ) OR (
              version.shift_version_id='42021000-0000-0000-0000-000000000002'
              AND version.segments_json=CAST(
                  '{"effectiveTo":"2027-01-01","segments":[{"segmentType":"WORK","startLocalTime":"09:00:00","startDayOffset":0,"endLocalTime":"12:00:00","endDayOffset":0},{"segmentType":"BREAK","startLocalTime":"12:00:00","startDayOffset":0,"endLocalTime":"13:00:00","endDayOffset":0},{"segmentType":"WORK","startLocalTime":"13:00:00","startDayOffset":0,"endLocalTime":"18:00:00","endDayOffset":0}]}'
                  AS JSON)
          )))<>2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'fixed city shift times verification failed';
    END IF;
    IF (SELECT COUNT(*)
        FROM shift_version special_version
        JOIN shift_version default_version
          ON default_version.shift_template_id=
             '41020000-0000-0000-0000-000000000003'
         AND default_version.version_number=special_version.version_number
        WHERE special_version.shift_template_id=
              '42020000-0000-0000-0000-000000000003'
          AND special_version.effective_from=default_version.effective_from
          AND special_version.time_zone_snapshot=default_version.time_zone_snapshot
          AND special_version.segments_json=default_version.segments_json)<>3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Shanghai shift does not match Yangzhou shift';
    END IF;
    IF (SELECT COUNT(*) FROM shift_publication_timeline timeline
        JOIN fc_special_map special
          ON special.shift_template_id=timeline.shift_template_id
        WHERE timeline.state='PUBLISHED')<>5 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special shift/group verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM shift_seasonal_schedule schedule
        WHERE schedule.shift_seasonal_schedule_id=
              '42022000-0000-0000-0000-000000000003')<>1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special shift/group verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_group attendance_group
        JOIN fc_special_map special
          ON special.group_id=attendance_group.attendance_group_id
        JOIN attendance_group_revision revision
          ON revision.attendance_group_revision_id=special.group_revision_id
        JOIN attendance_group_timeline timeline
          ON timeline.attendance_group_timeline_id=special.group_timeline_id
        WHERE attendance_group.company_id=
              '41000000-0000-0000-0000-000000000003'
          AND attendance_group.group_code=special.group_code
          AND revision.group_name=special.group_name
          AND timeline.state='ACTIVE' AND timeline.event_sequence=1)<>3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special shift/group verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_binding_family family
        JOIN fc_special_map special
          ON special.group_id=family.attendance_group_id)<>9 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special policy binding verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_binding_revision revision
        JOIN attendance_policy_binding_family family
          ON family.binding_family_id=revision.binding_family_id
        JOIN fc_special_map special
          ON special.group_id=family.attendance_group_id)<>9 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special policy binding verification failed';
    END IF;

    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        JOIN location_revision r ON r.location_revision_id=m.location_revision_id
        WHERE r.location_name<>'扬州'
          OR r.time_zone<>'Asia/Shanghai'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company-specific display names verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        JOIN work_calendar_version v ON v.work_calendar_id=m.calendar_id
        WHERE v.calendar_name<>CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.short_name,'2026工作日历')
          OR v.time_zone_snapshot<>'Asia/Shanghai'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company-specific display names verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        JOIN attendance_group_revision r ON r.attendance_group_id=m.group_id
        WHERE r.group_name<>CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.short_name,'默认考勤组')
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company-specific display names verification failed';
    END IF;

    IF (SELECT COUNT(*) FROM attendance_group_assignment a
        JOIN fc_company_map m ON m.group_revision_id=a.attendance_group_revision_id
        JOIN attendance_assignment_timeline t
          ON t.attendance_group_assignment_id=a.attendance_group_assignment_id
         AND t.event_sequence=1 AND t.state='ACTIVE'
        WHERE a.effective_from=cutover_date)<>597 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'current attendance assignment verification failed';
    END IF;
    IF (SELECT COUNT(DISTINCT a.employee_id)
        FROM attendance_group_assignment a
        WHERE a.effective_from=cutover_date
          AND a.supersedes_assignment_id IS NOT NULL)<>615 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'current attendance assignment verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_group_assignment assignment
        JOIN fc_special_map special
          ON special.group_revision_id=assignment.attendance_group_revision_id
        JOIN attendance_assignment_timeline timeline
          ON timeline.attendance_group_assignment_id=
             assignment.attendance_group_assignment_id
         AND timeline.event_sequence=1 AND timeline.state='ACTIVE'
        WHERE assignment.effective_from=cutover_date)<>18 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special attendance assignment verification failed';
    END IF;
    IF (SELECT COUNT(*)
        FROM fc_special_employee_map expected
        JOIN employee employee ON employee.employee_id=expected.employee_id
        JOIN attendance_group_assignment assignment
          ON assignment.employee_id=employee.employee_id
         AND assignment.supersedes_assignment_id IS NOT NULL
         AND assignment.effective_from=cutover_date
        JOIN attendance_group_revision revision
          ON revision.attendance_group_revision_id=
             assignment.attendance_group_revision_id
        JOIN attendance_group attendance_group
          ON attendance_group.attendance_group_id=revision.attendance_group_id
         AND attendance_group.group_code=CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,expected.special_code,'_ATTENDANCE')
        JOIN attendance_assignment_timeline timeline
          ON timeline.attendance_group_assignment_id=
             assignment.attendance_group_assignment_id
         AND timeline.event_sequence=1 AND timeline.state='ACTIVE')<>18 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special employee-to-group mapping verification failed';
    END IF;
    IF (SELECT COUNT(*)
        FROM fc_named_employee_expectation expected
        JOIN employee employee
          ON employee.employee_number=expected.employee_number
         AND employee.display_name=expected.display_name
         AND employee.company_id=expected.expected_company_id
        JOIN attendance_group_assignment assignment
          ON assignment.employee_id=employee.employee_id
         AND assignment.supersedes_assignment_id IS NOT NULL
         AND assignment.effective_from=cutover_date
        JOIN attendance_group_revision revision
          ON revision.attendance_group_revision_id=
             assignment.attendance_group_revision_id
        JOIN attendance_group attendance_group
          ON attendance_group.attendance_group_id=revision.attendance_group_id
         AND attendance_group.company_id=expected.expected_company_id
         AND attendance_group.group_code='DEFAULT_ATTENDANCE'
        JOIN attendance_assignment_timeline timeline
          ON timeline.attendance_group_assignment_id=
             assignment.attendance_group_assignment_id
         AND timeline.event_sequence=1 AND timeline.state='ACTIVE'
        WHERE expected.special_code IS NULL)<>3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZJN named Yangzhou default mapping verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_special_map special
        LEFT JOIN attendance_group_assignment assignment
          ON assignment.attendance_group_revision_id=special.group_revision_id
         AND assignment.effective_from=cutover_date
         AND assignment.supersedes_assignment_id IS NOT NULL
        GROUP BY special.special_code,special.expected_employees
        HAVING COUNT(assignment.attendance_group_assignment_id)<>
               special.expected_employees
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special attendance assignment verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_group_assignment
        WHERE supersedes_assignment_id IS NOT NULL)<>615 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'current attendance assignment verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_assignment_timeline t
        JOIN attendance_group_assignment successor
          ON successor.supersedes_assignment_id=
             t.attendance_group_assignment_id
        JOIN fc_employee_map em ON em.employee_id=successor.employee_id
        WHERE t.event_sequence=2 AND t.state='INACTIVE'
          AND t.business_effective_from=cutover_date)<>615 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'current attendance assignment verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM attendance_group_assignment a
        JOIN attendance_group_revision r
          ON r.attendance_group_revision_id=a.attendance_group_revision_id
        JOIN attendance_group g ON g.attendance_group_id=r.attendance_group_id
        JOIN employee e ON e.employee_id=a.employee_id
        JOIN attendance_assignment_timeline t
          ON t.attendance_group_assignment_id=a.attendance_group_assignment_id
         AND t.event_sequence=1 AND t.state='ACTIVE'
        WHERE a.supersedes_assignment_id IS NOT NULL
          AND e.company_id<>g.company_id
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'current attendance assignment verification failed';
    END IF;

    IF (SELECT COUNT(*) FROM people_import_batch
        WHERE company_id=archive_company_id AND status='PUBLISHED')<>3
       OR EXISTS (SELECT 1 FROM people_import_batch b
                  JOIN fc_company_map m ON m.company_id=b.company_id)
       OR EXISTS (SELECT 1 FROM organization_identity
                  WHERE company_id=archive_company_id)
       OR EXISTS (SELECT 1 FROM employee WHERE company_id=archive_company_id) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'historical import archive isolation verification failed';
    END IF;

    IF (SELECT COUNT(*) FROM auth_principal_role_assignment a
        JOIN auth_data_scope s ON s.scope_id=a.data_scope_id
        JOIN fc_company_map m ON m.auth_scope_id=s.scope_id
        WHERE a.principal_id=admin_principal_id
          AND a.role_id IN (hr_role_id,system_role_id)
          AND a.valid_from<=cutover_at AND a.valid_to IS NULL
          AND s.scope_type='COMPANY' AND s.company_id=m.company_id
          AND s.valid_to IS NULL)<>8
       OR EXISTS (
           SELECT 1 FROM auth_principal_role_assignment a
           JOIN auth_data_scope s ON s.scope_id=a.data_scope_id
           WHERE a.principal_id=admin_principal_id
             AND s.company_id=archive_company_id
             AND a.valid_from<=cutover_at
             AND (a.valid_to IS NULL OR a.valid_to>cutover_at)
       )
       OR (SELECT session_epoch FROM local_account
           WHERE account_id=admin_account_id)<>admin_session_epoch THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'administrator four-company authorization verification failed';
    END IF;

    SET SESSION group_concat_max_len = original_group_concat_max_len;
    COMMIT;
    DO RELEASE_LOCK('shenzhouhr:four-company-finalization:v1');
END$$

DELIMITER ;

CALL finalize_four_companies_post_v30();
DROP PROCEDURE finalize_four_companies_post_v30;
