## ADDED Requirements

### Requirement: Confirmed Deli person id wins over device empno
When a punch record carries a Deli snowflake person reference (`ext_id` or a digit `user_id` of length ≥ 16) and exactly one confirmed binding exists for that source and person id, the system MUST attach the punch to the bound employee. The empno printed on the device or in `check_data.employee_num` MUST NOT override that binding, even if it matches a different active employee.

#### Scenario: 彭伟 device emits 赵艺娴 empno
- **WHEN** a Deli punch has snowflake person id bound to 彭伟 `SZST0335` and empno `SZST0289`
- **THEN** the effective punch belongs to 彭伟
- **AND** 赵艺娴 MUST NOT receive that source record as her punch

#### Scenario: 陆玉蕾 device emits 汪立青 empno
- **WHEN** a Deli punch has snowflake person id bound to 陆玉蕾 `SZST0284` and empno `SZST0285`
- **THEN** the effective punch belongs to 陆玉蕾

#### Scenario: Unbound snowflake falls back to unique empno
- **WHEN** no confirmed binding exists for the snowflake person id
- **AND** empno matches exactly one active employee
- **THEN** the punch MAY match by employee number
- **AND** the match reason MUST be `EMPLOYEE_NUMBER`

### Requirement: Short CHECKIN user_id MUST NOT overwrite empno
A CHECKIN `user_id` shorter than 16 digits MUST NOT be used as a Deli directory id to replace empno. Directory id 387 is 彭伟; CHECKIN user_id 387 is 周步新.

#### Scenario: Short user_id 387 keeps punch empno
- **WHEN** a CHECKIN record has `user_id` `387` and empno of the person who punched
- **THEN** matching MUST use empno (or a confirmed snowflake binding if present)
- **AND** MUST NOT treat 387 as 彭伟's directory id

### Requirement: Confirmed bindings are source-scoped and unique
A confirmed Deli person-id binding MUST be unique per attendance source. Ambiguous bindings MUST quarantine the punch, not pick a winner.

#### Scenario: Two employees bound to the same snowflake
- **WHEN** two confirmed bindings exist for the same source and snowflake person id
- **THEN** the punch MUST be quarantined as `CONFIRMED_BINDING_MULTIPLE`
- **AND** MUST NOT become an effective event for either employee
