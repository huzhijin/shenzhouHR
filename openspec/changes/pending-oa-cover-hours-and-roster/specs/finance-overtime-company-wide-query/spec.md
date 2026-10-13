## ADDED Requirements

### Requirement: Daily overtime SHALL return the whole company without a department filter

每日加班 / 财务加班 for a selected company and date window SHALL return every in-scope employee who has recognized overtime minutes in that window when the department filter is empty (全部部门). The system MUST NOT require the operator to pick a department before any row appears. The company picker remains; each query is still one company. An empty department filter MUST NOT time out into an empty table.

#### Scenario: Jiangsu August overtime loads with all departments
- **WHEN** an authorized user opens 每日加班 for 江苏神州 `2026-08` with department 全部部门
- **THEN** the first page contains overtime people from multiple departments
- **AND** the result is not an empty table solely because no department was selected

#### Scenario: Selecting a department still narrows
- **WHEN** the same user then selects one department including descendants
- **THEN** only that subtree's overtime people appear
