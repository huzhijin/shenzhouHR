## ADDED Requirements

### Requirement: Existing organization identities are renamed not replaced
From `2026-01-01` the system SHALL publish new `organization_version` rows on the **existing** Jiangsu Shenzhou, Shanghai Shengzhou Juneng, and Shanghai Shengzhou `organization_id`s so display names and parents match the roster short-name tree. It MUST NOT insert a parallel tree of new identities for a path that already maps one-to-one. 江苏芯越 organization identities and versions MUST remain unchanged. Historical organization versions with `effective_to` on or before `2026-01-01` MUST NOT be rewritten. Punch events and OA documents MUST NOT be updated.

#### Scenario: MATCH1组 keeps its organization id
- **WHEN** the live node `工程一部-MATCH组-MATCH1组` maps uniquely to roster `服务中心 / 工程一部 / MATCH组 / MATCH1组`
- **THEN** the same `organization_id` receives a new version named `MATCH1组` from `2026-01-01`
- **AND** no second identity is created for that path

#### Scenario: Xinyue is untouched
- **WHEN** the roster sync publishes
- **THEN** Jiangsu Xinyue organization versions, closures, and employments SHALL be identical to the pre-sync current versions

### Requirement: Employees keep their id; assignments change only when the leaf changes
For each roster row whose resolved employee number already exists, the system SHALL keep that `employee_id`. If the mapped roster leaf is the same organization identity as the current assignment, the system MUST NOT insert a replacement assignment. If the leaf differs, it SHALL close the current assignment at `2026-01-01` and open a new one to the roster leaf. New roster people without an employee SHALL be created. Historical punch rows MUST stay on the original `employee_id`.

#### Scenario: Rename-only employee is not reassigned
- **WHEN** 熊都 `SZST0003` is already assigned to the organization identity that maps to `国际销售部`
- **THEN** that assignment remains
- **AND** the employee appears under short name `国际销售部` because the organization version name changed

#### Scenario: Ma Yu moves company leaf
- **WHEN** live `SZST0019` 马雨 is assigned under Shengzhou Juneng 上海销售部
- **AND** the roster Shenzhou row is 销售中心 / 新品部
- **THEN** from `2026-01-01` the current assignment is Jiangsu Shenzhou 新品部
- **AND** the same `employee_id` is used

### Requirement: Accounts stay bound and their employee projection is updated
Each existing `local_account` whose principal is bound to an employee SHALL keep `account_id`, `principal_id`, `employee_id`, password hash, and `session_epoch`. After sync, account `display_name` SHALL equal the current employee display name. The employee number, current organization short name, and employment status shown on account and employee screens SHALL match the roster outcome for that `employee_id`. The system MUST NOT create a second account for a merged person and MUST NOT provision accounts for newly created employees in this cutover.

#### Scenario: Zhang Meiju can still log in
- **WHEN** `SZST0007` 张梅菊 has already completed first password change
- **THEN** username remains `SZST0007`
- **AND** the password hash is unchanged
- **AND** after sync the account still binds to the same `employee_id` whose current department is roster 运营中心

#### Scenario: Account list follows the new employee department
- **WHEN** an operator opens account management after sync
- **THEN** 马雨's account still uses username `SZST0019`
- **AND** the bound employee department shown is 销售中心 / 新品部, not 上海销售部

### Requirement: Confirmed identity corrections keep the live person and account
The system SHALL apply these mappings on the **live** employee and account, not by inserting a second person:

- live `SZT0687` 吕加军 → employee number `SZST0687`; same `employee_id` and account
- live `SZT0709` 杨玲 → employee number `SZST0709`; same `employee_id` and account
- roster `SZST0567` 叶剑 → live `SZSZ0000`; do not create `SZST0567` or another account
- roster Shengzhou `SZJN0014` 丁昊 → live `SZST0524`; do not create
- 赵子奇 → create employee `SZSZ0002`; 张衡 → create `SZSZ0003`; do not auto-create accounts

If a corrected account has `first_password_change_required = true` and has never logged in, the system SHALL also set `local_account.username` to the new employee number. If the account has already completed first password change, username MUST stay.

叶剑 SHALL be assigned under Jiangsu Shenzhou 总经办; username remains `SZSZ0000`.

#### Scenario: Lü Jiajun is one person one account
- **WHEN** live employee and username are `SZT0687` and first password change is still required
- **THEN** after sync the employee number is `SZST0687`
- **AND** the same account username becomes `SZST0687`
- **AND** the password hash is unchanged
- **AND** no second employee or account exists for 吕加军

#### Scenario: Ye Jian account stays SZSZ0000
- **WHEN** live 叶剑 logs in as `SZSZ0000`
- **THEN** after sync the username is still `SZSZ0000`
- **AND** the bound employee current organization is Jiangsu Shenzhou 总经办

### Requirement: Dual-company roster rows keep only the Shenzhou department
马雨 `SZST0019`、尹华凌 `SZST0450`、丁昊 `SZST0524` SHALL have exactly one current assignment from `2026-01-01` under Jiangsu Shenzhou (新品部 / 总经办 / 总经办). Their existing accounts MUST remain those three usernames.

#### Scenario: Ding Hao keeps the Shenzhou account
- **WHEN** live 丁昊 is `SZST0524` under Jiangsu Shenzhou 总经办
- **AND** the roster also has Shengzhou `SZJN0014` 丁昊
- **THEN** after sync the only current assignment is Jiangsu Shenzhou 总经办
- **AND** username remains `SZST0524`
- **AND** no employee or account `SZJN0014` is created

### Requirement: Named absences end employment but keep accounts
The system SHALL end current employment for these people on `2026-01-01` and MUST NOT delete `employee_id`, historical punches, or their local accounts, and MUST NOT disable or reset those accounts in this cutover:

`SZST0598` 范康搏, `SZST0638` 刘梓轩, `SZST0641` 陈柏宇, `SZST0652` 江梦圆, `SZST0662` 李恩琪, `SZST0674` 徐利民, `SZJN0026` 陈惠, `SZJN0031` 徐赛杰, `SZJNSX05` 周坤.

江苏芯越 employees, `SZWX0003` 孙静, and admin `szsc_admin_faa41d5bd802` SHALL remain as they are.

#### Scenario: Fan Kangbo employment ends, login still exists
- **WHEN** the sync publishes
- **THEN** `SZST0598` employment is terminated from `2026-01-01`
- **AND** account `SZST0598` remains ACTIVE with the same password
- **AND** punch history remains

### Requirement: Historical attendance facts are not rewritten
The cutover MUST NOT update punch evidence rows, OA document rows, password credentials, or published `attendance_report_projection` rows. Recalculating a 2026 LIVE report MAY show the new department names; stored projections stay as published.

#### Scenario: A stored punch time is unchanged
- **WHEN** employee `SZST0520` has a punch at `08:18` on the cutover day
- **THEN** that punch event row is byte-identical after sync
- **AND** only organization/employee current versions and account display fields may differ
