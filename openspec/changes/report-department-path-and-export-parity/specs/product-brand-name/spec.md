## ADDED Requirements

### Requirement: User-visible product name is 神州考勤系统
The product name shown to operators SHALL be `神州考勤系统`. This includes the browser document title, login brand text, mobile navigation drawer title, attendance big-screen accessible name, and any other in-app chrome that currently says `神州 HR 管理系统` or `神州HR`.

The company legal name `江苏神州半导体科技股份有限公司` SHALL remain the company label. Repository, package, and deployment project identifiers are out of scope for this requirement.

#### Scenario: Login and document title
- **WHEN** a user opens the login page
- **THEN** the brand text is `神州考勤系统`
- **AND** the document title is `神州考勤系统`

#### Scenario: Mobile drawer uses the product name
- **WHEN** an authorized user opens the mobile navigation drawer
- **THEN** the drawer title is `神州考勤系统`

#### Scenario: Big-screen accessible name
- **WHEN** an authorized user opens the attendance big screen
- **THEN** the screen's accessible name identifies `神州考勤系统` rather than `神州 HR`

#### Scenario: Company legal name is unchanged
- **WHEN** the UI shows the current company
- **THEN** it may still display `江苏神州半导体科技股份有限公司`
- **AND** that string is not replaced by `神州考勤系统`
