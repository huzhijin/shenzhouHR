## ADDED Requirements

### Requirement: Report department columns follow the organization tree
Customer reports that show a department SHALL derive 一级部门, 二级部门, and 三级部门 by walking `organization_current_closure` from the employee's assignment leaf toward the company root, skipping the COMPANY node. 组别 MAY appear as a fourth path segment when the leaf is a TEAM. The values MUST match the roster short names for that employee. The system MUST NOT display organization UUID as the department field.

The single 「部门」 column on attendance-detail, exception, overtime and similar sheets SHALL display a hyphen-joined path of short names **from 二级部门 down to the leaf** (including 组别). 一级部门 is omitted from that concatenation when a deeper level exists, producing strings such as `工程一部-MATCH组-MATCH1组`. An employee hanging only on 一级部门 SHALL show that first-level short name alone.

#### Scenario: Three-level employee splits into three columns
- **WHEN** 熊都 is assigned to 国际销售部 under 服务部 under 销售中心
- **THEN** 一级部门 is `销售中心`
- **AND** 二级部门 is `服务部`
- **AND** 三级部门 is `国际销售部`

#### Scenario: Employee hanging on a first-level node leaves lower columns empty
- **WHEN** 丁书龙 is assigned directly to 销售中心
- **THEN** 一级部门 is `销售中心`
- **AND** 二级部门 and 三级部门 are empty placeholders, not copies of 销售中心

#### Scenario: Company root is not a department level
- **WHEN** the ancestor chain is 江苏神州 → 董事长
- **THEN** 一级部门 is `董事长`
- **AND** 一级部门 is not `江苏神州半导体科技股份有限公司`

#### Scenario: Attendance department column is hyphen-concatenated from 二级 down
- **WHEN** the assignment chain is 服务中心 / 工程一部 / MATCH组 / MATCH1组
- **THEN** the report 部门 column is `工程一部-MATCH组-MATCH1组`
- **AND** 一级部门 remains `服务中心`

#### Scenario: First-level-only employee is not hyphenated
- **WHEN** 丁书龙 is assigned directly to 销售中心
- **THEN** the report 部门 column is `销售中心`

### Requirement: Department filter is an organization tree
The customer-report department filter SHALL be a tree of the active company's current organizations, not a flat list of leaf names. Selecting a node SHALL include that node and all descendant assignments. Duplicate display names under different parents SHALL remain distinct selectable nodes.

#### Scenario: Selecting a parent includes descendants
- **WHEN** the operator selects 销售中心 in the report department tree
- **THEN** the report includes 丁书龙 (hanging on 销售中心) and 熊都 (hanging on 国际销售部)
- **AND** it excludes employees whose assignment is outside that subtree

#### Scenario: Duplicate 测试组 nodes stay distinct
- **WHEN** 制造一部/测试组 and RF部/测试组 both exist
- **THEN** the filter shows two `测试组` entries under their respective parents
- **AND** selecting 制造一部/测试组 MUST NOT include RF部/测试组 employees

### Requirement: Organization and employee directory stay trees with short names
The organization page tree and the employee-page left directory SHALL continue to render a parent/child tree. Node titles SHALL use the roster short name. Expanding a parent SHALL reveal its children; selecting a parent on the employee page SHALL list employees assigned to that node or any descendant.

#### Scenario: Employee directory lists descendants
- **WHEN** the operator selects 工程一部 on the employee directory tree
- **THEN** the list includes people assigned to DC组, MATCH组, MATCH1组, MATCH2组, and RPS组 under that branch
- **AND** node titles do not contain `服务中心-工程一部`

#### Scenario: Organization page remains a tree
- **WHEN** an authorized user opens the organization page for Jiangsu Shenzhou
- **THEN** the page shows a collapsible tree rooted at the company
- **AND** 服务中心 is a parent of 工程一部, not a sibling flat list
