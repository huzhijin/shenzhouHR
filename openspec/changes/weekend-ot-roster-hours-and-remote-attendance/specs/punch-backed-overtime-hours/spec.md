## ADDED Requirements

### Requirement: Punch-required overtime hours follow the last punch, not the form end

For employees who must punch (not 大连/武汉 no-clock sites and not standing punch-exempt), recognized overtime minutes SHALL be computed from the snapped OA interval **capped to the last punch at or after the form start**, with that punch floored onto the same 30-minute grid, then minus meals and weekday WORK. The form end MUST NOT be used when it is later than the last covering punch.

If there is no punch and no approved makeup/补签 in the overtime interval:

- For business dates in **2026-08** only: recognized overtime minutes SHALL still use the uncapped form (treat as if the employee punched the form end) so the month can close. The day SHALL still show 漏签 and the overtime-beyond-punch exception SHALL remain, so operators can still reconcile against Deli.
- For business dates **2026-09 and later**: recognized overtime minutes SHALL be 0, the day SHALL show 漏签, and the exception SHALL remain pending until makeup.

After a makeup punch is saved, the exception SHALL clear and hours SHALL use that punch.

Standing punch-exempt employees keep form hours without a punch cap in every month. 大连/武汉 skip the punch cap **only on 2026-08** dates; from 2026-09 they are punch-required.

#### Scenario: Li Xin Wednesday 20:02 is 1.5 not 2.5
- **WHEN** 李鑫 `SZST0369` has an overtime form snapping to `18:00–21:00` on 2026-08-26
- **AND** last punch is `20:02`
- **THEN** the punch floors to `20:00`
- **AND** recognized hours are `1.5` (18:00–20:00 minus dinner 18:00–18:30)
- **AND** 加班统计 / 日报 / 月度工时 overtime for that day are `1.5`, not `2.5`

#### Scenario: August form without off-duty punch still pays form hours
- **WHEN** a punch-required employee on `2026-08-26` files `18:00–21:00` with only an on-duty punch
- **THEN** overtime hours still use the form (`2.5` in summer)
- **AND** an overtime-beyond-punch exception is open
- **AND** the off-duty slot is 漏签 so Deli comparison still sees the gap

#### Scenario: September form without covering punch is unpaid until makeup
- **WHEN** a punch-required employee on `2026-09-02` files `18:00–21:00` with only an on-duty punch
- **THEN** overtime hours are `0`
- **AND** an overtime-beyond-punch exception is open
- **AND** the off-duty slot is 漏签
- **AND** after a makeup punch at `20:02` hours become `1.5` and the exception is gone

#### Scenario: Dalian form without punches still has hours
- **WHEN** 霍岩 has an overtime form and no punches
- **THEN** hours still come from the form minus meals
- **AND** the day is not 漏签 solely for missing OT punches
