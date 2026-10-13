## ADDED Requirements

### Requirement: Official month-matrix warm read is a paged pin query
When a complete pin exists, GET `/api/v1/attendance-reports/month-matrix` SHALL authorize once, then select the requested employee page from pinned daily facts (plus OA and exceptions for those employee ids only). Per-row correlated `reportFactVisibility` and the recursive organization subtree MUST NOT run when `organizationId` is absent. The first-page p95 on the customer environment for 江苏神州 SHALL be 5 seconds or less.

#### Scenario: Company-wide matrix does not walk the org tree per fact
- **WHEN** month-matrix is queried with a company id and a null organization id
- **THEN** the daily-fact SQL does not evaluate the recursive organization subtree
- **AND** visibility is the already-resolved company scope

#### Scenario: Pin exists so GET does not wait on calculation
- **WHEN** projection version ARP1 (or successor) is already published for the company-month
- **THEN** the GET does not wait on `FIRST_WAIT` / shared month calculation
- **AND** returns the paged pin within the first-page budget
