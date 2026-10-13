## ADDED Requirements

### Requirement: Operator can switch the whole site between day and night
The system SHALL provide a day/night appearance switch that applies to the entire product shell: login, sidebar, top bar, all authorized pages, customer reports, and the attendance big screen. The chosen appearance SHALL persist for that browser until the operator changes it. Default SHALL be day (white canvas) when no preference is stored.

#### Scenario: Toggle from day to night on a report page
- **WHEN** an authorized user on 报表中心 switches to night
- **THEN** the shell, filters, and report chrome use the night canvas and text
- **AND** navigating to 人员 or 组织 keeps night appearance

#### Scenario: Login follows the stored preference
- **WHEN** night appearance is stored and the user opens the login page
- **THEN** the login page renders in night appearance

#### Scenario: Preference survives reload
- **WHEN** the operator selected night and reloads the app
- **THEN** the app starts in night appearance without requiring another toggle

### Requirement: Attendance big screen follows the same appearance
The standalone attendance big screen SHALL use the same day/night preference as the rest of the site. It MUST NOT stay locked to a dark wall when day is selected.

#### Scenario: Big screen in day mode
- **WHEN** day appearance is active and the user opens the attendance big screen
- **THEN** the screen uses the daytime canvas
- **AND** metric and status colors remain readable against that canvas

#### Scenario: Big screen in night mode
- **WHEN** night appearance is active and the user opens the attendance big screen
- **THEN** the screen uses the night canvas

### Requirement: Semantic attendance colors are not restyled per theme
Leave, exception, and overtime legend colors on reports and the big screen SHALL keep the same semantic fills in day and night. Night mode MAY change page chrome, table borders, and default text, but MUST NOT replace 年假 / 迟到 / 加班 and other legend colors with a second palette.

#### Scenario: Annual-leave cell color is stable across themes
- **WHEN** a month-matrix cell is painted with annual-leave color in day mode
- **AND** the operator switches to night
- **THEN** that cell still uses the same annual-leave fill
