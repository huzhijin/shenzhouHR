## Purpose

定义考勤报表直接基于最新已提交到本地的得力打卡、OA 单据、人员组织、班次、考勤组和日历进行快速计算与查询的行为，不以人工发布或持久化报表投影作为查看前置条件，也不在报表请求线程直连得力或 OA 外部网络。

## ADDED Requirements

### Requirement: Report query calculates without publication
The system SHALL calculate an attendance report when an authorized report query is received and SHALL NOT require a published report projection, manual publication, approval, reopen, or republish operation before returning the report.

#### Scenario: First query for a company month
- **WHEN** an authorized user queries a company and month for which no report projection exists
- **THEN** the system reads the current committed calculation inputs, calculates the requested report, and returns the result without asking the user to publish it first

#### Scenario: Existing legacy projection
- **WHEN** an authorized user queries a month that has an older persisted report projection
- **THEN** the system calculates from current committed source inputs and does not return migration-default zeroes from that legacy projection

### Requirement: Calculation uses the authoritative attendance inputs
The system SHALL calculate reports from the latest locally committed Deli punch evidence, OA attendance documents, employee identity and employment assignments, organization versions, attendance-group assignments, shift definitions, calendars, punch corrections, exemption roles, and signed attendance policies that are effective for the requested business dates. A report query SHALL NOT call the Deli or OA external network directly.

#### Scenario: Deli punches and OA documents overlap a scheduled shift
- **WHEN** a scheduled employee has committed Deli punches and an approved OA document overlapping the same business date
- **THEN** the system applies the confirmed attendance business rules to those inputs and returns one deterministic employee-day result

#### Scenario: Source data has not been committed
- **WHEN** an external source page is still in progress, failed, or quarantined and has not advanced its committed watermark
- **THEN** the report excludes that uncommitted page and exposes the latest committed source cutoff instead of treating the missing page as zero attendance

#### Scenario: Newly committed evidence becomes visible
- **WHEN** Deli or OA evidence is committed after a company-month result entered the active cache bucket
- **THEN** the new evidence is reflected after at most the current 30-second bucket, without requiring publication or approval

### Requirement: Report authorization is enforced before calculation
The system MUST require the report capability and MUST restrict company, organization, descendant organization, employee, and self visibility using the current principal's effective data scopes before report rows or aggregates are returned.

#### Scenario: Requested company is outside the user's scope
- **WHEN** a user queries a company that is not included in any current effective data scope
- **THEN** the system denies the request without calculating rows or revealing whether that company has source data

#### Scenario: Organization filter narrows an authorized company
- **WHEN** a user with an authorized organization scope requests a department or employee filter
- **THEN** the system calculates and returns only employees in the intersection of the current scope and requested filter

### Requirement: Query results are consistent within one input snapshot
The system SHALL bind report pages and drill-down reads to one snapshot token derived from the committed Deli/OA watermarks and a deterministic digest of the complete calculated output. Until an independent version query covers every personnel and configuration input, the system SHALL NOT describe the token as a complete configuration-version catalog. Production export is outside the completed realtime snapshot contract and SHALL NOT be exposed as if it used that token.

#### Scenario: Source watermark changes between pages
- **WHEN** the Deli or OA committed watermark changes after the first page is returned
- **THEN** a request carrying the earlier snapshot token either returns the cached result for that exact token or responds with a retryable snapshot-changed error, and never mixes rows from both snapshots

#### Scenario: Same inputs are queried repeatedly
- **WHEN** an equivalent authorized query is repeated while its committed source watermarks and deterministic calculated output are unchanged
- **THEN** the system may return the cached calculated result and the business values and snapshot token remain identical

### Requirement: Realtime report queries meet the fast response budget
For a company-month containing no more than 5,000 active employees, the system SHALL avoid per-employee and per-day database queries, SHALL use bounded batch reads, and SHALL meet a warm-query p95 response time of 1 second and a cold-query p95 response time of 5 seconds on the customer acceptance environment.

#### Scenario: Warm repeated report query
- **WHEN** the same authorized company-month and unchanged input snapshot are queried after one successful calculation
- **THEN** the complete report response is returned within the warm-query performance budget without repeating full source reads or daily calculations

#### Scenario: Cold company-month query
- **WHEN** no cached result exists for an authorized company-month
- **THEN** the system loads each input category in bounded batch operations and returns the calculated report within the cold-query performance budget

### Requirement: Unsafe or ambiguous inputs fail closed
The system SHALL NOT fabricate zero attendance or silently ignore an active source document when an employee identity, attendance group, shift, calendar, policy, OA status, document relationship, or source schema cannot be resolved uniquely and safely.

#### Scenario: Leave revocation cannot be linked safely
- **WHEN** an effective OA leave-revocation document cannot be linked to a complete current set of original leave intervals
- **THEN** the affected report calculation fails with a safe actionable error and does not count the original leave unchanged

#### Scenario: Required source is stale or unavailable
- **WHEN** a signed freshness contract has configured an age threshold and the latest committed Deli or OA source cutoff does not satisfy it
- **THEN** the response is marked unavailable with the source cutoff and safe error code rather than returning an apparently normal zero result

#### Scenario: No signed source-age threshold exists
- **WHEN** no signed freshness age threshold has been configured
- **THEN** the system exposes the actual Deli/OA cutoff and does not invent an age-based stale classification

#### Scenario: Required source has no committed watermark
- **WHEN** a required Deli or OA source is missing or has not completed its first committed sync
- **THEN** the calculation fails closed with a safe source-status error instead of returning a zero-valued report

### Requirement: Existing report clients remain compatible
The existing attendance report GET routes SHALL remain available, SHALL return the established report row and column contract, and SHALL use the realtime input snapshot token wherever the contract previously exposed a projection version.

#### Scenario: Existing report page loads after upgrade
- **WHEN** the current frontend calls the established attendance report endpoint with its normal report type, period, company, organization, employee, status, and paging filters
- **THEN** the endpoint returns realtime calculated rows using the existing response shape without requiring a separate frontend publication action

### Requirement: Incomplete dependent consumers are not presented as realtime
The system SHALL keep the legacy publication operation as compatibility-only and SHALL NOT present production export or dashboard values as part of the realtime report snapshot until those consumers are explicitly bound to the same snapshot and authorization contract.

#### Scenario: Realtime report page is opened before export cutover
- **WHEN** the production report page uses the realtime report service but export has not been bound to its snapshot token
- **THEN** the page does not offer the legacy projection export as a realtime export

#### Scenario: Dashboard has not been cut over
- **WHEN** dashboard summary or drill-down still depends on a legacy projection
- **THEN** it is not identified as a consumer of the current realtime report snapshot
