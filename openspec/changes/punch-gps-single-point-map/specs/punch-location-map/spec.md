## ADDED Requirements

### Requirement: Day detail lists each punch without exposing coordinates

On 考勤日报 and 考勤明细 day detail, the system SHALL list every raw punch in that business day for the employee: clock time and a source label (`手机` for `gps`/`out_work`, `考勤机` otherwise). The list MUST NOT include longitude, latitude, or map points. 「改打卡」remains a separate write action and MUST NOT be the location viewer.

#### Scenario: Daily journal day shows multiple punches
- **WHEN** an HR user opens 考勤日报 for an employee whose day has a GPS on-duty punch and a fingerprint off-duty punch
- **THEN** the day detail lists two punches with times
- **AND** the GPS row is labelled 手机
- **AND** the fingerprint row is labelled 考勤机
- **AND** neither row displays numeric coordinates

#### Scenario: Matrix day list is the same punch list
- **WHEN** the same day is opened from 考勤明细 list view
- **THEN** the punch list matches the daily-journal day detail for that employee and date

### Requirement: View-location is a single-punch controlled map

「查看位置」SHALL appear only on a punch whose method is `gps` or `out_work`. An employee MAY view only their own punch. A manager MUST have the employee in scope, `ATTENDANCE_LOCATION:READ`, and a non-empty view reason before the location view is returned. Each successful or denied view MUST write an audit event. The view SHALL default to authorised location text and MUST NOT show numeric coordinates unless a separate privileged reveal is later specified (this change does not add numeric reveal).

A map point SHALL be drawn only when `coordinate_validation_status=VALID` and conversion produced WGS84 (or the confirmed source system the basemap accepts). If the coordinate system is `UNKNOWN`, conversion failed, or no basemap is configured, the drawer MUST show location text when present and the status「地图暂不可用」, and MUST NOT plot a point.

The system MUST NOT provide realtime tracking, trajectories, or a batch map of many employees.

#### Scenario: Manager views one GPS punch on a map
- **WHEN** a manager with `ATTENDANCE_LOCATION:READ` and the employee in scope clicks 查看位置 on that employee's GPS punch
- **AND** they submit a view reason of at least two characters
- **THEN** the drawer shows that punch's time and location text
- **AND** a single map point is shown only if the stored coordinates are valid for mapping
- **AND** an audit event records actor, punch id, employee id, and reason without raw coordinate digits in ordinary logs

#### Scenario: Unknown system shows text only
- **WHEN** the punch is GPS with location text but `source_coordinate_system=UNKNOWN`
- **THEN** 查看位置 still opens
- **AND** the drawer shows the location text
- **AND** no map marker is rendered
- **AND** the UI states 地图暂不可用

#### Scenario: Employee cannot view another person's punch
- **WHEN** an employee session requests the location view of a coworker punch
- **THEN** the API returns 403
- **AND** no map payload is returned

#### Scenario: Manager without location capability sees no map action
- **WHEN** an HR user can read 考勤日报 but lacks `ATTENDANCE_LOCATION:READ`
- **THEN** GPS punches still list time and 手机
- **AND** 查看位置 is hidden or disabled
- **AND** fetching the location endpoint returns 403

#### Scenario: Dashboards and exports stay free of coordinates
- **WHEN** 签到排行, company attendance screen, or query Excel export for 考勤日报 is produced
- **THEN** the payload contains no longitude, latitude, or map point
- **AND** GPS punches are not expanded into a bulk location sheet


#### Scenario: Report permission cannot widen location scope
- **WHEN** a user has company-wide report access but only SELF-scoped location access
- **THEN** location requests for coworkers return 403 and are audited
- **AND** their day punch list hides the view-location action

#### Scenario: Corrected punch ownership controls location access
- **WHEN** identity replay supersedes a punch event assigned to employee A and activates its replacement for employee B
- **THEN** only the current active association to B is used for self and scope checks
- **AND** a retracted or superseded event cannot grant location access

#### Scenario: Employee opens own punches without report permissions
- **WHEN** a user with only ATTENDANCE_SELF:READ opens a day in 我的考勤
- **THEN** the server resolves the employee from the authenticated principal
- **AND** mobile punches can be viewed without a reason or report-query permission

#### Scenario: Provider or image failure preserves the address
- **WHEN** a valid stored WGS84 map point cannot be converted for the configured provider, the provider times out, or the image cannot load
- **THEN** the location drawer keeps the authorised address and states 地图暂不可用
- **AND** provider keys and URLs are never returned to the browser or written to ordinary logs

#### Scenario: Invalid reason is audited
- **WHEN** an authorised manager submits an empty, one-character, or over-500-character reason
- **THEN** the endpoint returns 400 and records a denied view without exposing location data
- **AND** successful views retain the complete accepted reason and subject employee id in the audit event
