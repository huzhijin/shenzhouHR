-- Read-only operational evidence for Deli E+ synchronization.
-- An absent watermark/last success means "not proven synchronized"; it must
-- never be interpreted as proof that employees had no punches.

WITH job_summary AS (
    SELECT
        job.attendance_source_id,
        MAX(CASE
            WHEN job.status = 'SUCCEEDED' THEN job.finished_at
            ELSE NULL
        END) AS last_fully_successful_sync_at,
        MAX(CASE
            WHEN job.status IN ('SUCCEEDED', 'PARTIALLY_QUARANTINED')
            THEN job.finished_at
            ELSE NULL
        END) AS last_partial_or_full_sync_at,
        MAX(CASE
            WHEN job.quarantined_count > 0 THEN job.finished_at
            ELSE NULL
        END) AS last_quarantined_sync_at,
        SUM(job.quarantined_count) AS historical_quarantined_count,
        MAX(job.finished_at) AS last_job_finished_at
    FROM attendance_sync_job job
    GROUP BY job.attendance_source_id
), ranked_job AS (
    SELECT
        job.*,
        ROW_NUMBER() OVER (
            PARTITION BY job.attendance_source_id
            ORDER BY job.created_at DESC, job.attendance_sync_job_id DESC
        ) AS source_rank
    FROM attendance_sync_job job
)
SELECT
    source.attendance_source_id,
    source.source_code,
    source.display_name,
    source.status,
    watermark.committed_cursor,
    watermark.committed_at AS watermark_committed_at,
    job_summary.last_fully_successful_sync_at,
    job_summary.last_partial_or_full_sync_at,
    job_summary.last_quarantined_sync_at,
    COALESCE(job_summary.historical_quarantined_count, 0)
        AS historical_quarantined_count,
    job_summary.last_job_finished_at,
    latest_job.status AS latest_job_status,
    latest_job.accepted_count AS latest_job_accepted_count,
    latest_job.quarantined_count AS latest_job_quarantined_count,
    latest_job.safe_error_code AS latest_job_safe_error_code
FROM attendance_source source
LEFT JOIN attendance_sync_watermark watermark
  ON watermark.attendance_source_id = source.attendance_source_id
LEFT JOIN job_summary
  ON job_summary.attendance_source_id = source.attendance_source_id
LEFT JOIN ranked_job latest_job
  ON latest_job.attendance_source_id = source.attendance_source_id
 AND latest_job.source_rank = 1
WHERE source.source_type = 'DELI_CLOUD'
ORDER BY source.source_code;

-- Every persisted Deli punch must first have normalization and an explicit
-- match decision. This query must return zero rows; it is separate from the
-- effective-event query so a missing prefix record cannot disappear through
-- an INNER JOIN.
SELECT
    raw.raw_attendance_fact_id,
    raw.source_business_key,
    raw.received_at,
    COUNT(DISTINCT normalized.normalized_attendance_record_id)
        AS normalized_count,
    COUNT(DISTINCT match_decision.employee_match_decision_id)
        AS match_decision_count
FROM raw_attendance_fact raw
JOIN attendance_source source
  ON source.attendance_source_id = raw.attendance_source_id
LEFT JOIN normalized_attendance_record normalized
  ON normalized.raw_attendance_fact_id = raw.raw_attendance_fact_id
LEFT JOIN employee_match_decision match_decision
  ON match_decision.normalized_attendance_record_id
        = normalized.normalized_attendance_record_id
WHERE source.source_type = 'DELI_CLOUD'
  AND raw.fact_kind = 'PUNCH_POINT'
GROUP BY
    raw.raw_attendance_fact_id,
    raw.source_business_key,
    raw.received_at
HAVING normalized_count = 0
    OR normalized_count <> match_decision_count;

-- Every valid, matched Deli punch must reach an effective event and retain the
-- complete raw -> normalized -> match -> link -> lifecycle -> recalculation
-- chain. Starting from the source fact (rather than from the event) also makes
-- a missing evidence link visible. This query must return zero rows.
SELECT
    raw.raw_attendance_fact_id,
    normalized.normalized_attendance_record_id,
    match_decision.employee_match_decision_id,
    match_decision.employee_id,
    normalized.point_instant,
    COUNT(DISTINCT link.evidence_link_id) AS evidence_link_count,
    COUNT(DISTINCT event.effective_attendance_event_id)
        AS effective_event_count,
    COUNT(DISTINCT lifecycle.effective_event_lifecycle_fact_id)
        AS lifecycle_fact_count,
    COUNT(DISTINCT intent.attendance_recalculation_intent_id)
        AS recalculation_intent_count
FROM raw_attendance_fact raw
JOIN attendance_source source
  ON source.attendance_source_id = raw.attendance_source_id
JOIN normalized_attendance_record normalized
  ON normalized.raw_attendance_fact_id = raw.raw_attendance_fact_id
JOIN employee_match_decision match_decision
  ON match_decision.normalized_attendance_record_id
        = normalized.normalized_attendance_record_id
LEFT JOIN evidence_link link
  ON link.raw_attendance_fact_id = raw.raw_attendance_fact_id
 AND link.normalized_attendance_record_id
        = normalized.normalized_attendance_record_id
 AND link.employee_match_decision_id
        = match_decision.employee_match_decision_id
LEFT JOIN effective_attendance_event event
  ON event.effective_attendance_event_id
        = link.effective_attendance_event_id
LEFT JOIN effective_event_lifecycle_fact lifecycle
  ON lifecycle.effective_attendance_event_id
        = event.effective_attendance_event_id
LEFT JOIN attendance_recalculation_intent intent
  ON intent.effective_attendance_event_id
        = event.effective_attendance_event_id
WHERE source.source_type = 'DELI_CLOUD'
  AND raw.fact_kind = 'PUNCH_POINT'
  AND normalized.record_kind = 'PUNCH_POINT'
  AND normalized.validation_status = 'VALID'
  AND match_decision.match_status = 'MATCHED'
GROUP BY
    raw.raw_attendance_fact_id,
    normalized.normalized_attendance_record_id,
    match_decision.employee_match_decision_id,
    match_decision.employee_id,
    normalized.point_instant
HAVING COUNT(DISTINCT link.evidence_link_id) = 0
    OR COUNT(DISTINCT event.effective_attendance_event_id) = 0
    OR COUNT(DISTINCT lifecycle.effective_event_lifecycle_fact_id) = 0
    OR COUNT(DISTINCT intent.attendance_recalculation_intent_id) = 0;

-- Quarantined and unmatched data is evidence of an incomplete business date,
-- not evidence that the employee had no punch. PARTIALLY_QUARANTINED must not
-- be treated as a fully successful watermark while this result is non-empty.
SELECT
    raw.raw_attendance_fact_id,
    raw.source_business_key,
    normalized.normalized_attendance_record_id,
    normalized.validation_status,
    normalized.issue_code,
    match_decision.match_status,
    match_decision.match_reason,
    raw.received_at
FROM raw_attendance_fact raw
JOIN attendance_source source
  ON source.attendance_source_id = raw.attendance_source_id
JOIN normalized_attendance_record normalized
  ON normalized.raw_attendance_fact_id = raw.raw_attendance_fact_id
LEFT JOIN employee_match_decision match_decision
  ON match_decision.normalized_attendance_record_id
        = normalized.normalized_attendance_record_id
WHERE source.source_type = 'DELI_CLOUD'
  AND raw.fact_kind = 'PUNCH_POINT'
  AND (
      normalized.validation_status = 'QUARANTINED'
      OR (
          normalized.validation_status = 'VALID'
          AND (
              match_decision.employee_match_decision_id IS NULL
              OR match_decision.match_status <> 'MATCHED'
          )
      )
  )
ORDER BY raw.received_at, raw.raw_attendance_fact_id;

-- A page-level quarantine remains operationally visible even when the record
-- was rejected before a raw fact could be persisted, or a later job succeeds.
-- Until an operator proves and records remediation outside this immutable
-- ingestion history, every row means the affected source range is incomplete.
SELECT
    source.attendance_source_id,
    source.source_code,
    job.attendance_sync_job_id,
    job.status AS job_status,
    page.page_number,
    page.input_cursor,
    page.next_cursor,
    page.record_count,
    page.accepted_count,
    page.quarantined_count,
    page.request_id,
    page.committed_at
FROM attendance_sync_job_page page
JOIN attendance_sync_job job
  ON job.attendance_sync_job_id = page.attendance_sync_job_id
JOIN attendance_source source
  ON source.attendance_source_id = job.attendance_source_id
WHERE source.source_type = 'DELI_CLOUD'
  AND page.quarantined_count > 0
ORDER BY page.committed_at, job.attendance_sync_job_id, page.page_number;

-- Pages advance the source watermark only when their accepted/quarantined
-- counts reconcile. This query must return zero rows.
SELECT
    page.attendance_sync_job_id,
    page.page_number,
    page.record_count,
    page.accepted_count,
    page.quarantined_count
FROM attendance_sync_job_page page
WHERE page.record_count <> page.accepted_count + page.quarantined_count;
