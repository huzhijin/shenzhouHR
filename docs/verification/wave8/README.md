# W8 payroll backend reservation verification

## Current independent phase

- Baseline: `0c09fc375b1d2973915234e50a9aac10900446ca`
- Feature flag: `SHENZHOUHR_PAYROLL_RESERVATION_ENABLED`
- Checked-in/default value: `false`
- Public REST/OpenAPI/frontend surface: none
- PAYROLL role/account grants: none
- W5 frozen attendance snapshot adapter: none
- Database migration: none

The server checks the feature flag before authorization, W5 adapter lookup, or
snapshot access. If disabled it returns a resource-unavailable denial. If
explicitly enabled, it still requires the internal
`PAYROLL:RESERVATION_READ` capability; ordinary capability/menu/account/role
responses filter the entire `PAYROLL:*` namespace. An enabled and authorized
call still fails closed until a real W5 FINAL adapter is registered.

Denial audits use only a stable action, resource type, hashed opaque period
reference, result, and allowlisted reason. Commands, capability lists,
snapshot bodies, exception text, secrets, and payroll-sensitive values are not
accepted by the audit port.

## Scope exclusions

This phase does not add frontend navigation or copy, public or ordinary-user
APIs, payroll calculations or values, payslips, statutory deductions,
exports, payments, bank files, or persistence. It does not create a
development/test fallback snapshot.

## Verification

Run the independent W8 checks:

```bash
cd backend
./mvnw -Dtest='PayrollReservation*Test,FrozenAttendanceSnapshotPortContractTest,AuditServicePayrollReservationAuditTest,CurrentCapabilityServiceTest' test
./mvnw test

cd ..
PYTHONDONTWRITEBYTECODE=1 python3 -m py_compile \
  scripts/qa/verify_wave8_payroll_reservation.py \
  scripts/qa/test_verify_wave8_payroll_reservation.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v \
  scripts/qa/test_verify_wave8_payroll_reservation.py
python3 scripts/qa/verify_wave8_payroll_reservation.py self-test
python3 scripts/qa/verify_wave8_payroll_reservation.py verify
```

Operational rollback is to leave or restore
`SHENZHOUHR_PAYROLL_RESERVATION_ENABLED=false`; this phase has no data
migration to reverse.

## Latest independent result

Executed on 2026-07-28 in the W8 worktree:

- Focused backend boundary/security/audit tests: 22 passed.
- Payroll and shared layer architecture tests: 5 passed.
- Full backend Maven suite: 200 passed, 0 failed, 0 errors, 0 skipped.
- Frontend production build: passed.
- Frontend demo build: passed.
- Verifier unit suite: 6 passed.
- Unicode zero-discoverability: passed across 79 product source files,
  104 production/demo build files, and 30 public REST/OpenAPI contract files.
- Baseline diff: no frontend, OpenAPI, migration, payroll controller, or
  payroll DTO change.

## Blocked by W5 FINAL

The following work deliberately remains incomplete:

1. Reconcile W5’s final close-snapshot identifier, close version,
   reopen/supersession semantics, and integrity digest.
2. Add the single real read-only W5 adapter and an integration test in a
   separate commit.

No adapter, fallback, integration PASS, production-readiness claim, or W9
dependency is implied by the independent W8 checks.
