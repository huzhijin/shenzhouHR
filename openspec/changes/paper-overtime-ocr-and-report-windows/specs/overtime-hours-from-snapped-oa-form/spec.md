## ADDED Requirements

### Requirement: Paper overtime hours use the same snapped interval minus meals

For an approved paper overtime document, recognized overtime minutes SHALL equal the Asia/Shanghai interval after `OaIntervalGrid` snapping, minus meal minutes under `overtime-meal-window-coverage`, identical to OA overtime. The system MUST NOT use punch pairing to zero paper overtime hours. Daily-fact paid, compensatory, and voluntary minutes SHALL include paper documents of those types split by day the same way as OA.

#### Scenario: Paper evening form without punches still has hours
- **WHEN** an approved paper overtime document snaps to `18:00–21:00` on a summer weekday
- **AND** the employee has no punch pair inside that window
- **THEN** 加班统计 hours for that paper row are `2.5`
- **AND** 月度工时 includes `2.5` under 加班时数 or 加班换调休 according to the paper overtime type
