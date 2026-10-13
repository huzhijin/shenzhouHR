## Context

Personnel create and organization create exist (`POST /api/v1/employees`, `POST /api/v1/organizations`) but the employee create body has no organization, so new people have no employment. Organization create requires a unique `code` that roster users do not have. `CompanySelect` disables itself when only one company is authorized; Ant Design omits disabled fields, so submit can drop `companyId`.

People import today is `/api/v1/people-imports` plus a six-step wizard with four templates (`ORGANIZATION` / `EMPLOYEE` / `EMPLOYMENT` / `PRIOR_SERVICE`) keyed by organization code. The HR roster is one sheet of names and department levels. Local `神州花名册.xlsx` is stale versus the September screenshot.

## Goals / Non-Goals

**Goals:**

- Make local create usable: employee with department; department by name/parent.
- Replace the operator-facing opening-import wizard with one roster-format import.
- Downloadable template headers match the roster Excel.
- Precheck then confirm writes departments and people together, including multi-company files.
- Load 神州 `SZST0731`–`SZST0747` (张立强 on 武汉产品服务组) and 聚能 `SZJN0042`–`SZJN0046`.

**Non-Goals:**

- Deleting the old encoding import APIs in this change (hide only).
- Auto-provisioning login accounts for new employees.
- Importing 副岗, prior-service days, or 芯越.
- Rewriting punches, OA, passwords, or published report projections.
- Full historical org-tree rebuild for people already in the system (only rows in the uploaded file / the September hire set).

## Decisions

### 1. Extend employee create instead of a second hop

`CreateEmployee` and `EmployeeCreateRequest` gain `organizationId` (required) and use `effectiveFrom` as employment `startDate`. One transaction: identity + version + employment. Frontend create dialog adds a department tree filtered by selected company.

Alternative: keep create as identity-only and force a follow-up employment call — rejected; that is the current unusable flow.

### 2. Optional organization code, generated when blank

Keep `code` unique per company. Frontend stops marking it required. Backend generates a stable unique code (e.g. `D-` + compact id) when blank. Parent/company mismatch returns `ORGANIZATION_PARENT_COMPANY_MISMATCH` (400), not 403.

Company field: do not disable `CompanySelect` in create forms, or set Form `preserve` so `companyId` is always submitted.

### 3. New roster template type on the existing batch pipeline

Add template type `ROSTER` (or a dedicated `/api/v1/roster-imports` if wiring `TemplateType` is riskier). Reuse batch / upload / precheck / publish / void. Do not reuse the four encoding field maps.

Download workbook: one sheet, header row exactly the ten roster columns. No mapping step; columns match by header text.

Parse rules:

- Skip title rows; first row whose cells equal the ten headers is the header.
- `/`, blank, `-` are empty levels.
- Leaf = last non-empty among 一级→组别.
- Company aliases: `江苏神州` → SZSC, `上海晟州聚能` → SZJN, `上海昇州` → SZSZ; also accept full legal names.
- Strip leading `聚能-` / `神州-` from department cells.
- Alias inside 聚能: `RD1` ≡ `研发一部` (match existing node by alias, then optionally rename display to `研发一部` if the file uses that name).

Publish order: missing organizations from root to leaf, then employees, then employment (close previous open assignment when moving).

### 4. Hide opening import in the shell, keep backend

Menu item that currently goes to `/people/import` stays, label 导入人员. Page implements roster import only. `PEOPLE_IMPORT:*` capabilities can be reused. Old template download endpoints remain but are not linked.

### 5. September hires go through the same publisher

Build a workbook (or in-process rows) for:

- 神州 screenshot `SZST0731`–`SZST0747` with departments from the screenshot; `SZST0743` 张立强 forced to 技术支持中心 / 现场服务部 / 武汉产品服务组, 职位 empty.
- 聚能 five rows from the hire table.

Run through precheck + publish against live, or an equivalent authorized command that uses the same writer. Do not SQL-insert identities bypassing version/employment/audit.

## Risks / Trade-offs

- [Same-name departments at one level] → Match by full path from company root, not by name alone; ambiguous siblings are conflicts.
- [RD1 rename surprises existing screens] → Alias match first; rename display only when the file uses 研发一部 and the operator confirms updates.
- [Create employee API change] → Old clients that omit `organizationId` fail validation; only this frontend uses the local create dialog.
- [Old import still callable] → Acceptable; hide from UI. Removing APIs is a later change.
- [Hire list transcribed from screenshots] → Verify numbers/names against the screenshots in the change folder notes before publish.

## Migration Plan

1. Ship create-form and API fixes so operators can add a single person if import is blocked.
2. Ship roster import (template, parse, precheck, publish) behind the existing `/people/import` route.
3. Publish the September 神州 + 聚能 rows.
4. Keep old people-import APIs deployed but unlinked.

Rollback: revert frontend route/page; new employees/orgs created remain (they are master data, not a toggle). Roster publications follow existing import void/rollback only if still the latest publication.

## Open Questions

None blocking. 张立强职位 left empty; 副岗 omitted from template; 研发一部 aliases RD1.
