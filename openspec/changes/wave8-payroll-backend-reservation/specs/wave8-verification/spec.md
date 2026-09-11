## ADDED Requirements

### Requirement: Security negatives prove ordered fail-closed behavior
Automated tests SHALL cover feature-off, missing PAYROLL capability, missing W5 adapter, invalid snapshot state/version/digest/period, and accidental PAYROLL capability serialization. Each test SHALL assert both the public failure semantics and that no later dependency in the ordered gate was invoked.

#### Scenario: Run W8 security negative tests
- **WHEN** the W8 backend security suite executes
- **THEN** every negative path passes with feature → capability → adapter → snapshot ordering and no permissive fallback

### Requirement: Audit negatives prove minimal denial records
Automated tests SHALL verify that each guarded rejection writes exactly one stable payroll denial event and SHALL reject audit fields containing command payloads, snapshot contents, capability lists, exception text, secrets, or payroll-sensitive values.

#### Scenario: Run W8 audit negative tests
- **WHEN** all guarded rejection cases are exercised with a recording audit port
- **THEN** each case produces one allowlisted safe event and no non-allowlisted field

### Requirement: Unicode-normalized frontend discoverability remains zero
A dependency-free verifier SHALL scan production and demo frontend source and, when present, their built assets after Unicode NFKC normalization and full case-folding. It SHALL detect compatibility-width, case, whitespace, dot, underscore, slash, and hyphen variants of payroll/payslip plus Chinese payroll terms, and SHALL report a zero-hit PASS only when every required product scan root exists and is clean.

#### Scenario: Compatibility variants are hidden in a product file
- **WHEN** a scanned product file contains a compatibility-width or separator-obfuscated payroll/payslip token
- **THEN** the verifier fails and reports only the repository-relative path and line number

#### Scenario: Production and demo product surfaces are clean
- **WHEN** the verifier scans the checked-in frontend product source and available fresh production/demo build outputs
- **THEN** it emits the W8 zero-discoverability PASS marker with zero normalized hits

### Requirement: Backend reservation has no public route or expanded payroll behavior
The verifier SHALL fail if a payroll REST controller/DTO, a public OpenAPI payroll path, a frontend production payroll surface, or implementation behavior for payslips, statutory deductions, exports, payments, or bank files is introduced by W8. Test-only negative vectors and OpenSpec documentation SHALL be outside product discoverability scope but SHALL remain inside scope-control tests.

#### Scenario: Scan W8 scope boundaries
- **WHEN** W8 source and its public-contract diff are inspected
- **THEN** only the isolated domain/application/configuration/audit reservation boundary is present

### Requirement: Architecture tests enforce optional-branch isolation
Architecture tests SHALL enforce that payroll domain code has no outer-layer dependency, payroll application code has no adapter/interface dependency, no non-payroll business package depends on payroll, and no frozen-attendance-snapshot port implementation is present before W5 FINAL.

#### Scenario: Run architecture isolation tests before W5 FINAL
- **WHEN** the W8 architecture suite scans compiled application classes
- **THEN** all layer and optional-branch rules pass and the adapter implementation count is zero

### Requirement: W5 dependency remains explicit and W9 remains unblocked
OpenSpec progress and delivery reporting SHALL keep real W5 close-snapshot adapter/integration tasks incomplete until W5 FINAL is synchronized. W8’s incomplete optional integration SHALL NOT change, block, or claim completion of W9 attendance P0-A gates.

#### Scenario: Report this independent W8 phase
- **WHEN** the baseline lacks W5 FINAL
- **THEN** independently implemented W8 tasks are marked complete, W5 integration tasks remain unchecked with the blocking dependency named, and no W9 task or gate is modified
