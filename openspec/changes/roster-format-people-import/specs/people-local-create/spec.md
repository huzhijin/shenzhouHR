## ADDED Requirements

### Requirement: Creating an employee assigns a department
The system SHALL require a current organization when creating a local employee, and SHALL create the employee identity, current version, and employment assignment in one request. The employee MUST appear under that department in the employee directory after success.

#### Scenario: Create employee with department
- **WHEN** an authorized operator submits 公司、工号、姓名、任职部门、入职日期
- **THEN** the system creates an ACTIVE employee and an open-ended employment on that organization starting on the hire date

#### Scenario: Department is required
- **WHEN** the operator submits create without a department
- **THEN** the system rejects the request and does not create an employee identity

#### Scenario: Duplicate employee number
- **WHEN** the operator submits a number that already exists in that company
- **THEN** the system returns a conflict that names the existing employee number and does not create a second identity

### Requirement: Create employee form collects department
The employee list page create dialog SHALL include 任职部门 chosen from the current organization tree of the selected company, and SHALL submit department with the create request. Change-reason length SHALL match the API (2 to 500 characters). A single authorized company SHALL still submit `companyId`.

#### Scenario: Operator picks department from the tree
- **WHEN** the operator opens 新建员工 and selects a department
- **THEN** confirm sends company, number, name, organization id, and hire date together

#### Scenario: One-company account can still create
- **WHEN** the operator is authorized for exactly one company
- **THEN** that company is used on submit and create does not fail because the company field is disabled

### Requirement: Creating an organization uses name and parent
The system SHALL create a local organization from company, parent, name, and type. Organization code SHALL be optional; when omitted the system MUST generate a unique code inside the company. Parent and company MUST belong to the same company; a mismatch SHALL return a readable validation error, not an access-denied empty failure.

#### Scenario: Create department without typing a code
- **WHEN** the operator submits a department name and parent and leaves code empty
- **THEN** the system creates the node under that parent with a generated unique code

#### Scenario: Parent belongs to another company
- **WHEN** the selected parent organization is not in the selected company
- **THEN** the system rejects with a message that company and parent do not match

#### Scenario: Duplicate code still conflicts
- **WHEN** the operator supplies a code that already exists in that company
- **THEN** the system returns `ORGANIZATION_CODE_CONFLICT` and does not create a node
