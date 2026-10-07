## MODIFIED Requirements

### Requirement: Overtime report hours SHALL come from the snapped OA interval minus scheduled work and meals

For an approved, activated overtime document, recognized overtime minutes SHALL equal the Asia/Shanghai snapped interval after these deductions, and MUST NOT use punch pairing or punch ∩ form.

On a **weekday or adjusted workday**:
1. Subtract overlap with that day's published WORK segments.
2. Subtract the lunch gap `12:00–13:00` from remaining fragments (lunch is not overtime).
3. If any remaining fragment intersects the dinner window, subtract a **fixed 30 minutes**. Summer dinner is `18:00–18:30`; winter dinner is `[shiftOff, shiftOff+30min]`. A form that starts at `18:30` after dinner MUST NOT lose another 30 minutes.

On a **Saturday, Sunday, or public holiday**: do not subtract WORK. Deduct lunch 60 minutes only when the snapped interval fully covers `12:00–13:00`, and dinner 30 minutes only when it fully covers that day's dinner window.

Overnight documents SHALL split by calendar day; each weekday slice applies the weekday rule; a next-day fragment before that day's WORK is overtime.

Paper overtime uses the same function. Hours MUST be a multiple of 0.5. Start/end on 加班单据明细 remain snapped clocks.

#### Scenario: Weekday form that includes shift start is 2.5 hours
- **WHEN** an approved weekday overtime document snaps to `08:30–21:00` Asia/Shanghai in summer
- **AND** published WORK is `08:30–12:00` and `13:00–18:00`
- **THEN** recognized hours are `2.5`
- **AND** 加班单据明细 开始/结束 remain `08:30` / `21:00`

#### Scenario: Weekday evening form starting after dinner stays 2.5 hours
- **WHEN** an approved weekday overtime document snaps to `18:30–21:00` in summer
- **THEN** hours are `2.5`
- **AND** dinner is not deducted again

#### Scenario: Saturday daytime form is not zeroed by punches or WORK
- **WHEN** Saturday is a rest day
- **AND** an approved overtime document snaps to `08:30–17:00`
- **THEN** hours are `7.5` (8.5 minus weekend lunch 1.0)

#### Scenario: Overnight weekday deducts that day's WORK
- **WHEN** an approved overtime document snaps to `09:00` through next calendar `00:30` on a weekday with published WORK `08:30–12:00` and `13:00–18:00`
- **THEN** the first calendar day remaining overtime starts at `18:30`
- **AND** the `00:00–00:30` tail is overtime on the next date
