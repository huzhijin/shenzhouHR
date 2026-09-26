## ADDED Requirements

### Requirement: OA attendance documents sync hourly on an incremental watermark

Scheduled OA attendance document sync SHALL run once every hour in `Asia/Shanghai` by default (`0 0 * * * ?`). Each run MUST resume from the source's committed OA watermark and MUST NOT restart from the epoch. The enable switch and cron MUST remain configurable. Manual sync-job runs MUST stay available and MUST NOT change the watermark contract.

#### Scenario: Top of the hour pulls only new OA rows
- **WHEN** a scheduled OA job starts at 09:00 Asia/Shanghai and the source watermark is already past yesterday's last form
- **THEN** the job fetches pages after that cursor and does not re-ingest earlier approved documents as new identities

#### Scenario: Cron default is hourly
- **WHEN** `shenzhouhr.oa.auto-sync-cron` is unset and auto-sync is enabled
- **THEN** the job is scheduled with cron `0 0 * * * ?` in Asia/Shanghai

### Requirement: Deli punches sync at 08:00, 12:00, 18:00, and 00:00 incrementally

Scheduled Deli punch sync SHALL run at 08:00, 12:00, 18:00, and 00:00 Asia/Shanghai by default (`0 0 0,8,12,18 * * ?`). Each run MUST resume from the source's committed Deli / kq watermark (`next_id` / committed cursor) and MUST NOT treat the scheduler clock as the ingest window. Manual sync and replay MUST stay available.

#### Scenario: 18:00 job continues the kq cursor
- **WHEN** a scheduled Deli job starts at 18:00 and the kq committed cursor is `12852026233`
- **THEN** the fetch payload uses that cursor as `next_id` and does not reset to `0`

#### Scenario: Cron default is four times a day
- **WHEN** `shenzhouhr.deli.auto-sync-cron` is unset and auto-sync is enabled
- **THEN** the job is scheduled with cron `0 0 0,8,12,18 * * ?` in Asia/Shanghai
