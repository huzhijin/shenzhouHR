-- Replace the company-row X-lock taken by report projection publishing with a
-- dedicated per-(company, period) lock row so that szsc DAY_CONTEXT_SQL's
-- FOR SHARE on the company row is no longer blocked during publishing.
CREATE TABLE attendance_report_publication_lock (
    company_id   VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_start DATE                                              NOT NULL,
    PRIMARY KEY (company_id, period_start),
    CONSTRAINT fk_arpl_company
        FOREIGN KEY (company_id) REFERENCES company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin;

-- Pre-seed one row per existing (company, month) combination so that
-- INSERT ... ON DUPLICATE KEY UPDATE always takes an X record-lock, never a
-- gap lock that could deadlock when two publishers race on the same key.
INSERT INTO attendance_report_publication_lock (company_id, period_start)
SELECT DISTINCT company_id, period_start
FROM   attendance_report_projection;

-- Dedicated lock table for people/HR write operations (employee create, update,
-- roster import, group assignment). This replaces the company-row X-lock in
-- PeopleMapper.lockCompany so that szsc DAY_CONTEXT_SQL's FOR SHARE on company
-- is no longer blocked by HR writes.
CREATE TABLE company_people_write_lock (
    company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (company_id),
    CONSTRAINT fk_cpwl_company
        FOREIGN KEY (company_id) REFERENCES company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin;

-- Pre-seed one row per active company so the lock row always exists.
INSERT IGNORE INTO company_people_write_lock (company_id)
SELECT company_id FROM company;
