## ADDED Requirements

### Requirement: Paper overtime is absent from the login menu

The session menu returned at login MUST NOT contain the paper-overtime item, even when the principal has `PAPER_OVERTIME:MANAGE`. The route `/attendance/paper-overtime` MAY remain authorized for a direct URL.

#### Scenario: Menu after login
- **WHEN** a principal with `PAPER_OVERTIME:MANAGE` loads the current session
- **THEN** `menu` has no item whose path is `/attendance/paper-overtime`
- **AND** `authorizedMenu` does not show 「纸质加班单」
