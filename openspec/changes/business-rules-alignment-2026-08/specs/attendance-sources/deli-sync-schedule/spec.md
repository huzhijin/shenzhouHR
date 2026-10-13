# Deli Sync Schedule Specification Delta

## MODIFIED Requirements

### Requirement: Deli sync SHALL run hourly

The system SHALL synchronize attendance punch records from Deli E+ system once per hour.

Cron schedule: `0 0 * * * ?` (at the top of every hour: 00:00, 01:00, 02:00, ..., 23:00)

**Simplification**: No complex alert thresholds, no red/yellow/green status lights. Only basic sync status recording.

#### Scenario: Hourly sync schedule
- **WHEN** sync job is configured
- **THEN** job SHALL run at 00:00, 01:00, 02:00, ..., 23:00 every day
- **AND** there SHALL be 24 sync runs per day

#### Scenario: Sync runs automatically
- **WHEN** system time reaches the top of the hour
- **THEN** Deli sync job SHALL execute automatically
- **AND** no manual trigger is required

### Requirement: Sync status SHALL display last sync time and record count

The system SHALL display the following information about the most recent Deli sync:
- Last sync time (datetime)
- Number of records synchronized
- Sync status (success or failure)
- Error message (if failure)

This is informational display only, with no alert logic.

#### Scenario: Successful sync shows time and count
- **WHEN** Deli sync completes successfully at 2026-08-16 14:00:00
- **AND** sync retrieved 156 punch records
- **THEN** system SHALL display:
  - "上次同步时间: 2026-08-16 14:00:00"
  - "同步记录数: 156"
  - "同步状态: 成功"

#### Scenario: Failed sync shows error message
- **WHEN** Deli sync fails at 2026-08-16 15:00:00
- **AND** failure reason is "connection timeout"
- **THEN** system SHALL display:
  - "上次同步时间: 2026-08-16 15:00:00"
  - "同步记录数: 0"
  - "同步状态: 失败"
  - "错误信息: connection timeout"

### Requirement: Sync SHALL NOT have alert thresholds

The system SHALL NOT implement alert thresholds, warning colors (yellow/red), or automatic blocking of month-end close based on sync status.

HR users view sync status as informational context, but sync delays do not automatically block business operations.

#### Scenario: No yellow/red alert logic
- **WHEN** last successful sync was 3 hours ago
- **THEN** system SHALL NOT display yellow warning
- **AND** system SHALL NOT display red critical alert
- **AND** status display remains neutral

#### Scenario: Sync delay does not block month close
- **WHEN** HR initiates month-end close process
- **AND** last Deli sync was 6 hours ago
- **THEN** system SHALL NOT automatically block the close
- **AND** HR can proceed with close if they judge data is sufficient

### Requirement: Sync log SHALL retain recent sync history

The system SHALL retain a log of recent sync executions (at minimum, last 30 days) for troubleshooting purposes.

Each log entry includes:
- Sync execution time
- Success or failure status
- Record count retrieved
- Error message (if any)

#### Scenario: Log shows last 30 days of syncs
- **WHEN** user views Deli sync history
- **THEN** system SHALL show log entries for last 30 days
- **AND** each entry shows time, status, count, and any errors

#### Scenario: Log entries are retained for troubleshooting
- **WHEN** sync fails repeatedly
- **THEN** HR can view the log to diagnose the pattern
- **AND** error messages help identify the root cause

### Requirement: Sync SHALL handle API authentication

The system SHALL authenticate with Deli E+ API using configured credentials (API key or username/password) before retrieving punch records.

Authentication credentials are configured in system settings, not hard-coded.

#### Scenario: Sync uses configured credentials
- **WHEN** sync job runs
- **THEN** system SHALL read Deli API credentials from configuration
- **AND** system SHALL authenticate with Deli API before requesting records

#### Scenario: Authentication failure is logged
- **WHEN** Deli API rejects authentication
- **THEN** sync status SHALL be "失败"
- **AND** error message SHALL indicate authentication failure

### Requirement: Sync SHALL continue from each source committed cursor

Each Deli source SHALL use its own provider `next_id` cursor in
`attendance_sync_watermark` as the only incremental ingestion position.
`deli_sync_log` timestamps are operational display data and SHALL NOT filter
punch records or control another source's starting position.

#### Scenario: Incremental sync uses the source cursor
- **WHEN** source A has committed provider cursor `next_id=1200`
- **AND** source B has no committed cursor
- **THEN** source A SHALL request its next page from `next_id=1200`
- **AND** source B SHALL start from the provider-defined initial cursor
- **AND** neither source SHALL inherit a timestamp or cursor from the other

#### Scenario: Late-arriving punch is retained
- **WHEN** Deli returns a new page after the committed cursor
- **AND** a record on that page has a punch timestamp earlier than the last successful job time
- **THEN** the system SHALL still ingest and resolve that record
- **AND** it SHALL NOT discard the record using a job-time predicate

### Requirement: Sync failures SHALL NOT lose data

When a page fails before its atomic commit, the system SHALL NOT advance that
source beyond the last successfully committed provider cursor. Pages committed
earlier in the same job remain valid, and the next attempt SHALL resume from
the last committed cursor rather than restarting the whole job.

#### Scenario: Failed page does not advance source cursor
- **WHEN** source A starts from `next_id=1200`
- **AND** its next page cannot be committed safely
- **THEN** source A's committed cursor SHALL remain `next_id=1200`
- **AND** the next run SHALL request the same page again

#### Scenario: Earlier committed pages remain after a later page fails
- **WHEN** source A atomically commits a page and advances to `next_id=1250`
- **AND** the following page fails before commit
- **THEN** source A's committed cursor SHALL remain `next_id=1250`
- **AND** the next run SHALL resume from `next_id=1250`

#### Scenario: Other source progress is independent
- **WHEN** source A fails and source B commits its page successfully
- **THEN** source A's cursor SHALL remain unchanged
- **AND** source B's cursor SHALL advance to its returned `next_id`
- **AND** neither result SHALL be derived from the global sync-log time
