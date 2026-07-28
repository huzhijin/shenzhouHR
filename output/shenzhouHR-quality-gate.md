# Quality gate — FAILED

Total score: **74 / 100** (weighted 72.2)

Quality gate FAILED with score 74/100; 0 critical issue(s).

## Checks

| Check | Category | Status | Score | Details |
|---|---|---|---|---|
| Research content | artifact | n/a | 0 | N/A — a lean build skips the research + three-doc phase. (File is empty or missing) |
| Discovery section | quality | n/a | 60 | N/A — a lean build skips the research + three-doc phase. (Missing Discovery section — design direction may be inconsistent) |
| PRD content | artifact | n/a | 0 | N/A — a lean build skips the research + three-doc phase. (File is empty or missing) |
| Acceptance criteria depth | quality | warning | 0 | 0 acceptance criteria found (target: ≥8) |
| Architecture content | artifact | n/a | 80 | N/A — a lean build skips the research + three-doc phase. (1 issue(s): Missing ## Data model section) |
| UI/UX content | artifact | n/a | 60 | N/A — a lean build skips the research + three-doc phase. (2 issue(s): Missing CSS color tokens; Missing typography tokens) |
| Execution plan | artifact | n/a | 60 | N/A — a lean build skips the research + three-doc phase. (9 lines, needs structured sections) |
| Real source code present | artifact | passed | 100 | 304 real source file(s) present |
| API audit log | evidence | warning | 60 | no rows yet at /Users/huzhijin/Downloads/shenzhouHR/.umadev/audit/frontend-api-calls.jsonl |
| Tool-call audit log | evidence | passed | 100 | 2119 rows recorded in /Users/huzhijin/Downloads/shenzhouHR/.umadev/audit/tool-calls.jsonl |
| Emoji block events | code_rule | passed | 100 | 0 block event(s) recorded in this run |
| Hardcoded color block events | code_rule | failed | 30 | 5 block event(s) recorded in this run |
| Anti-AI-slop check | quality | passed | 100 | No AI template patterns detected in output artifacts |
| Design quality (code) | quality | warning | 85 | 0 hard + 3 soft design tell(s): design-tokens.css: [cream-band] AI cream/beige surface `#fff4e5` · design-tokens.css: [cream-band] AI cream/beige surface `#fff4e5` · PRD_V1.7.html: [em-dash-overuse] 7 em-dashes |
| Typography contract conformance | code-rule | passed | 100 | UIUX contract declares no fonts (skipped) |
| API URL consistency | code-rule | warning | 55 | 3/3 API paths not found in frontend: /api/v1/me/capabilities, /api/v1/organization-units, /api/v1/employees |
| PRD↔Architecture alignment | quality | warning | 50 | Cannot cross-validate — one or both documents empty |
| Dark mode support | quality | warning | 70 | No dark mode / prefers-color-scheme tokens found — consider adding for accessibility |
| Design system completeness | quality | failed | 30 | UIUX document completeness: 30/100 |
| OpenAPI contract | contract | passed | 100 | 8 endpoints in contract |
| Frontend↔contract conformance | contract | passed | 100 | All frontend calls match the contract |
| PRD routes↔contract coverage | contract | passed | 100 | PRD routes covered by contract |
| Input validation coverage | contract | passed | 100 | 2/2 mutation endpoints have request schemas |
| Auth coverage | contract | passed | 100 | 3 protected endpoint(s) all declare auth |
| Pagination strategy | contract | passed | 100 | Pagination documented for 4 list endpoints |
| Error handling convention | contract | warning | 60 | No HTTP error convention — add 400/404/500 response table |
| Ops artifacts present | delivery | passed | 100 | All 4 ops artifacts present with valid content |
| No leaked secrets | compliance | failed | 0 | 19 file(s) leak secrets: .env.example, deploy/mysql/lib/mysql-safety.sh, deploy/mysql/wave2-local-mysql.sh, deploy/mysql/wave3-local-mysql.sh, docker-compose.yml |

## Recommendations

- Address `Acceptance criteria depth`: 0 acceptance criteria found (target: ≥8)
- Address `API audit log`: no rows yet at /Users/huzhijin/Downloads/shenzhouHR/.umadev/audit/frontend-api-calls.jsonl
- Address `Hardcoded color block events`: 5 block event(s) recorded in this run
- Address `Design quality (code)`: 0 hard + 3 soft design tell(s): design-tokens.css: [cream-band] AI cream/beige surface `#fff4e5` · design-tokens.css: [cream-band] AI cream/beige surface `#fff4e5` · PRD_V1.7.html: [em-dash-overuse] 7 em-dashes
- Address `API URL consistency`: 3/3 API paths not found in frontend: /api/v1/me/capabilities, /api/v1/organization-units, /api/v1/employees
- Address `PRD↔Architecture alignment`: Cannot cross-validate — one or both documents empty
- Address `Dark mode support`: No dark mode / prefers-color-scheme tokens found — consider adding for accessibility
- Address `Design system completeness`: UIUX document completeness: 30/100
- Address `Error handling convention`: No HTTP error convention — add 400/404/500 response table
- Address `No leaked secrets`: 19 file(s) leak secrets: .env.example, deploy/mysql/lib/mysql-safety.sh, deploy/mysql/wave2-local-mysql.sh, deploy/mysql/wave3-local-mysql.sh, docker-compose.yml
