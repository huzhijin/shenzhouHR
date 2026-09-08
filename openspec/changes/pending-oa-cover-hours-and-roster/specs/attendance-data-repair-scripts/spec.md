## ADDED Requirements

### Requirement: Deploy package SHALL include diagnose-and-repair scripts for live data gaps

The release SHALL ship Python scripts that operators can run on the BaoTa host against the live HR database (and, where needed, the same HR APIs used by the UI). Default mode is preview: print who is wrong and what would change. `APPLY=1` writes repairs. Scripts MUST NOT rewind the OA watermark, MUST NOT fake-approve OA, and MUST NOT delete 张衡 `SZSZ0003` outing HR rows that have no covering OA.

Diagnose SHALL at least report:

- people-days with OA outing/trip/leave covering the day whose matrix/exceptions still show 漏刷
- pending OA overtime that is missing from 财务加班 / 加班统计
- overtime hour cells that are not a multiple of 0.5
- in-scope employees missing from 月度工时 (including `SZSZ0000` 叶剑 on 昇州)
- person-days where HR pre-add and OA of the same kind both exist

Repair with `APPLY=1` SHALL at least:

- move 叶剑 `SZSZ0000` current employment to 昇州 if it is still Jiangsu Shenzhou
- apply the HR-yields-to-OA rule without touching no-OA HR rows
- trigger recalculation only for affected people and date windows, not an unsolicited full-company month click

#### Scenario: Preview lists Ju Jun outing still as missed punch
- **WHEN** an operator runs the diagnose script without `APPLY=1` on a database where 居军 `SZST0017` has an OA outing on 8/19–8/20 but the pin still paints 漏刷
- **THEN** the script prints that person-day, document key, OA status, and that the matrix would show 外出 after recalc
- **AND** the database is unchanged

#### Scenario: Apply moves Ye Jian and keeps Zhang Heng
- **WHEN** `APPLY=1` runs while 叶剑 is still on Jiangsu Shenzhou 总经办
- **AND** 张衡 `SZSZ0003` has outing HR on 8/11 and 8/26 with no OA outing
- **THEN** 叶剑 current assignment is 昇州
- **AND** 张衡 those two HR outing rows remain
- **AND** OA watermark is unchanged

#### Scenario: Apply does not double overtime after pending OA is effective
- **WHEN** `APPLY=1` finds HR overtime pre-add on a day that now has OA overtime
- **THEN** subsequent recalculation uses OA hours only for that kind
- **AND** 财务加班 does not sum HR hours plus OA hours
