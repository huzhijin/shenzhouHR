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

### Requirement: Sync SHALL retrieve records since last successful sync

Each sync run SHALL retrieve punch records with timestamps after the last successful sync time.

This prevents re-processing the same records and keeps incremental sync efficient.

#### Scenario: Incremental sync based on last sync time
- **WHEN** last successful sync was at 2026-08-16 14:00:00
- **AND** current sync runs at 2026-08-16 15:00:00
- **THEN** system SHALL request records with timestamp > 2026-08-16 14:00:00
- **AND** system SHALL NOT re-retrieve records from before 14:00:00

#### Scenario: First sync retrieves all recent records
- **WHEN** no previous successful sync exists
- **THEN** system SHALL retrieve records from a default lookback period (e.g., last 7 days)
- **AND** this establishes the baseline for future incremental syncs

### Requirement: Sync failures SHALL NOT lose data

When a sync fails, the system SHALL NOT update the "last successful sync time" marker.

The next sync attempt SHALL retry from the previous successful sync time, ensuring no punch records are skipped.

#### Scenario: Failed sync does not advance marker
- **WHEN** last successful sync was at 14:00:00
- **AND** sync at 15:00:00 fails
- **THEN** "last successful sync time" SHALL remain 14:00:00
- **AND** next sync at 16:00:00 SHALL request records from > 14:00:00

#### Scenario: Successful retry retrieves missed records
- **WHEN** sync fails at 15:00:00 and succeeds at 16:00:00
- **THEN** 16:00:00 sync SHALL retrieve all records from 14:00:00 to 16:00:00
- **AND** no records are lost due to the 15:00:00 failure
