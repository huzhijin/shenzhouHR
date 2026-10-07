## ADDED Requirements

### Requirement: Overnight cut is 06:00 not next shift start

`overnightCut` and workday last-punch selection SHALL treat 06:00 Asia/Shanghai on the next calendar day as the overnight boundary. The previous requirement that `[D+1 00:00, next shift start)` belongs to day D MUST NOT apply to punches at or after 06:00.

#### Scenario: 07:56 is not overnight
- **WHEN** next shift start is 08:30 and a punch occurs at D+1 07:56
- **THEN** that punch is not day D last punch
- **AND** the matrix afternoon cell on D MUST NOT show `次日 07:56`
