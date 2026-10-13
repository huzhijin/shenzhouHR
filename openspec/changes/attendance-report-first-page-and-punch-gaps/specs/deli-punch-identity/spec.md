## ADDED Requirements

### Requirement: Roster employee number is the unique binding key
Confirmed Deli identity bindings SHALL use the HR roster employee number as the unique key. The system MUST NOT create a second active employee for the same roster empno. Device empno that equals exactly one roster empno SHALL bind to that employee even when the Deli display name differs; the Deli name is then rematched per the name-conflict rule below.

#### Scenario: Excel name on an HR empno is wrong
- **WHEN** Deli/Excel lists `SZST0663` as 黄凯 and the roster lists `SZST0663` as 张晨阳
- **THEN** punches whose Deli person is 张晨阳 attach to `SZST0663`
- **AND** punches whose Deli person is 黄凯 attach to the roster employee numbered `SZST0667` (黄凯)
- **AND** 张晨阳 MUST NOT keep 黄凯's punches

#### Scenario: 张海兰 roster empno wins
- **WHEN** Excel lists 张海兰 under `SZST0302` and the roster lists 张海兰 as `SZST0303` and 姜长波 as `SZST0302`
- **THEN** 张海兰's punches attach to `SZST0303`
- **AND** `SZST0302` remains 姜长波

### Requirement: Unlisted Deli empno with a unique roster name attaches to that person
When the Deli/device empno is **not** present on the active HR roster for the authorized companies, and the Deli person name matches **exactly one** active roster employee, the system SHALL attach those punches to that roster employee (rule B). If the name matches zero or more than one roster employee, the punch MUST NOT be attached by name.

This rule MUST NOT steal an empno that already exists on the roster; existing roster empnos stay under the previous requirement.

#### Scenario: Intern device empno maps by unique name
- **WHEN** Deli empno `SZSTSX61` is not on the roster
- **AND** the Deli name is 张国庆
- **AND** the roster has exactly one 张国庆 (`SZST0677`)
- **THEN** those punches attach to `SZST0677`
- **AND** the same applies to 赵建浩 `SZSTSX71`→`SZST0680`, 陈希雨 `SZSTSX85`→`SZST0701`, 汪婷婷 `SZSTSX80`→`SZST0699`, 程兆俊 `SZSTSX58`→`SZST0554`, 葛倩荣 `SZSTSX50`→`SZST0498`, 蔡小钰 `SZSTSX67`→`SZST0654`

#### Scenario: Duplicate roster name does not guess
- **WHEN** Deli empno is not on the roster
- **AND** two active roster employees share that name
- **THEN** the punch is not attached by name

### Requirement: Excel-absent days are not treated as ingestion bugs
When both complementary Deli workbooks have no clock time for an employee-day, the system MUST NOT treat an empty matrix cell as a missed-ingestion defect. When either workbook has a clock time and the roster employee has no first/last punch on a scheduled weekday, the system SHALL treat that as a punch gap to fix.

#### Scenario: 季佳男 clocks exist in both workbooks
- **WHEN** `SZST0105` 季佳男 has weekday clock times in both Deli workbooks and the matrix weekday cells have no punches
- **THEN** that is a punch-gap defect
- **AND** the system MUST attach the Deli evidence to `SZST0105`

### Requirement: Standing punch exemption still receives punches
When an employee is punch-exempt via the standing list or executive role, and has no effective OA outing or OA exempt-punch document for the date, the system SHALL still attach Deli punches to that employee. The exemption rule itself MUST NOT be changed in this change.

#### Scenario: 王鲲鹏 exemption unchanged
- **WHEN** 王鲲鹏 `SZST0031` is system punch-exempt and has Deli clock times
- **THEN** the matrix shows those punch times
- **AND** the standing/executive exemption flag stays as it was
